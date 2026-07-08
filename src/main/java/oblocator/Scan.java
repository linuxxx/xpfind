package oblocator;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import oblocator.api.Rule;

/** 扫描调度（§14）。串起 进程门控 → 缓存 → dex 初筛 → 反射匹配。只定位，不 Hook（Hook 由调用方用 Hit.method 自行实现）。 */
public final class Scan {

    private Scan() {}

    private static final Set<String> RAN =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public static ScanResult run(OL cfg) {
        // 进程门控（§28）
        if (!cfg.anyProc) {
            String target = cfg.proc != null ? cfg.proc : cfg.pkg;
            if (target != null && !Proc.isMain(target) && !Proc.is(target)) {
                Log.i("skip: not target process " + Proc.name());
                return empty();
            }
        }
        // pkg 门控（§27）
        if (cfg.pkg == null && !cfg.allowAll) {
            throw new IllegalStateException("pkg required; call .allowAll() to scan without a package prefix");
        }
        // 模式门控
        if (cfg.mode == Mode.DEEP || cfg.mode == Mode.TRACE) {
            throw new UnsupportedOperationException(cfg.mode + " 未在第一版实现（见方案 §30/§31/§32）");
        }

        long t0 = System.currentTimeMillis();
        ScanResult.Stats st = new ScanResult.Stats();
        List<Hit> all = new ArrayList<>();

        Cache cache = new Cache(cfg.ctx, cfg.cl, cfg.pkg, cfg.mode, !cfg.memOnly);

        for (Rule rule : cfg.rules) {
            try {
                all.addAll(scanRule(cfg, rule, st, cache));
            } catch (Throwable t) {
                Log.e("scanRule threw", t);
            }
        }

        st.elapsedMs = System.currentTimeMillis() - t0;
        st.hitCount = all.size();
        Log.i("scan done: " + st);
        return new ScanResult(all, st);
    }

    private static List<Hit> scanRule(OL cfg, Rule rule, ScanResult.Stats st, Cache cache) {
        String hash = rule.hash();
        String ranKey = cfg.pkg + '|' + cfg.mode + '|' + hash;

        if (cfg.once && RAN.contains(ranKey)) {
            Log.i("once: rule already ran " + hash);
            return new ArrayList<>();
        }

        // 缓存快路径
        if (cfg.useCache) {
            List<Hit> cached = cache.get(hash);
            if (cached != null) {
                st.fromCache = true;
                bindRule(cached, rule);
                List<Hit> out = applyLimit(cfg, rule, cached);
                RAN.add(ranKey);
                return out;
            }
        }

        // 全量扫描
        List<Hit> full = freshScan(cfg, rule, st);
        // 只缓存完整结果；预算截断的不写（§10.6 / §14.1）
        if (cfg.useCache && !st.budgetTripped) {
            cache.put(hash, full);
        }
        List<Hit> out = applyLimit(cfg, rule, full);
        RAN.add(ranKey);
        return out;
    }

    private static List<Hit> freshScan(final OL cfg, final Rule rule, final ScanResult.Stats st) {
        final Mode mode = cfg.mode;
        final ClassLoader cl = cfg.cl;

        // 阶段 1：dex 初筛 → 候选类名（去重、保序）
        final LinkedHashSet<String> cand = new LinkedHashSet<>();
        final long deadline = cfg.budgetMs > 0
                ? System.currentTimeMillis() + cfg.budgetMs : Long.MAX_VALUE;
        final int[] counter = {0};
        final boolean[] tripped = {false};

        final String prefix = cfg.allowAll ? null : cfg.pkg;
        // retSuper 需要全 dex 的 类→父类 索引（先扫一遍收集）；不用 retSuper 时不建，零开销。
        final Map<String, String> superIndex = rule.retSuper != null ? buildSuperIndex(cfg) : null;
        Dex.ClassVisitor visitor = new Dex.ClassVisitor() {
            @Override
            public boolean visit(Dex.DexClass c) {
                counter[0]++;
                if (System.currentTimeMillis() > deadline
                        || (cfg.maxClasses > 0 && counter[0] > cfg.maxClasses)) {
                    tripped[0] = true;
                    return false; // 中止扫描
                }
                for (Dex.DexMethod m : c.methods) {
                    if (Match.dexPass(rule, mode, c, m, superIndex)) {
                        cand.add(c.name);
                        break; // 该类已入候选，反射阶段再逐方法确认
                    }
                }
                return true;
            }
        };

        scanSources(cfg, prefix, visitor);
        st.classesScanned += counter[0];
        if (tripped[0]) {
            st.budgetTripped = true;
            Log.w("budget tripped after " + counter[0] + " classes");
        }
        st.candidates += cand.size();

        // 阶段 2：只加载候选类，反射做最终匹配（NORMAL 走弱匹配）
        List<Hit> hits = new ArrayList<>();
        for (String cn : cand) {
            try {
                Class<?> c = Class.forName(cn, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (Match.reflectPass(rule, mode, c, m, cl)) {
                        hits.add(makeHit(c, m, rule));
                    }
                }
            } catch (Throwable t) {
                Log.w("load candidate fail " + cn, t);
            }
        }
        return hits;
    }

    /**
     * 按 cfg 的来源设置把类喂给 visitor：优先自定义来源（.dex/.asset/.dexBytes），
     * 否则扫默认宿主 APK。.asset() 需要 cfg.ctx，其余不需要。
     */
    static void scanSources(OL cfg, String prefix, Dex.ClassVisitor visitor) {
        boolean custom = !cfg.dexFiles.isEmpty() || !cfg.assetNames.isEmpty()
                || !cfg.dexBytes.isEmpty();
        if (custom) {
            if (!cfg.dexFiles.isEmpty()) {
                Dex.scanFiles(cfg.dexFiles.toArray(new String[0]), prefix, visitor);
            }
            for (String name : cfg.assetNames) {
                byte[] data = readAsset(cfg.ctx, name);
                if (data != null) Dex.scanBytes(data, prefix, visitor);
            }
            for (byte[] data : cfg.dexBytes) {
                Dex.scanBytes(data, prefix, visitor);
            }
        } else {
            Dex.scan(Dex.apkPaths(cfg.ctx), prefix, visitor);
        }
    }

    /**
     * 只跑 dex 初筛层（{@link Dex} 解析 + {@link Match#dexPass}）：不加载类、不反射、不 Hook。
     * 用于本地/离线验证规则是否合理。FAST 下即最终结果；NORMAL 下类型条件延后到反射，
     * 这里返回的是候选（按 名字/参数数/修饰符/结构 过滤，偏多）。
     */
    static List<ProbeHit> probe(OL cfg, Rule rule) {
        final Mode mode = cfg.mode;
        final String prefix = cfg.allowAll ? null : cfg.pkg;
        final List<ProbeHit> out = new ArrayList<>();
        final Map<String, String> superIndex = rule.retSuper != null ? buildSuperIndex(cfg) : null;
        Dex.ClassVisitor visitor = new Dex.ClassVisitor() {
            @Override
            public boolean visit(Dex.DexClass c) {
                for (Dex.DexMethod m : c.methods) {
                    if (Match.dexPass(rule, mode, c, m, superIndex)) {
                        out.add(new ProbeHit(c.name, m.name, m.ret, m.params, m.accessFlags));
                    }
                }
                return true;
            }
        };
        try {
            scanSources(cfg, prefix, visitor);
        } catch (Throwable t) {
            Log.e("probe threw", t);
        }
        return out;
    }

    /** 扫全 dex 收集 类名→父类名 索引（retSuper 的父类链判定用）。prefix=null 覆盖整包。 */
    private static Map<String, String> buildSuperIndex(OL cfg) {
        final Map<String, String> idx = new HashMap<>();
        Dex.ClassVisitor v = new Dex.ClassVisitor() {
            @Override
            public boolean visit(Dex.DexClass c) {
                idx.put(c.name, c.superName);
                return true;
            }
        };
        try {
            scanSources(cfg, null, v);
        } catch (Throwable t) {
            Log.w("build super index fail", t);
        }
        return idx;
    }

    private static Hit makeHit(Class<?> c, Method m, Rule rule) {
        Hit h = new Hit();
        h.cls = c.getName();
        h.name = m.getName();
        h.ret = m.getReturnType().getName();
        Class<?>[] ps = m.getParameterTypes();
        h.args = new String[ps.length];
        for (int i = 0; i < ps.length; i++) h.args[i] = ps[i].getName();
        h.mod = m.getModifiers();
        h.method = m;
        h.rule = rule;
        return h;
    }

    /** 缓存命中的 Hit 需要补回当前规则（回调 / 日志配置来自 rule）。 */
    private static void bindRule(List<Hit> hits, Rule rule) {
        for (Hit h : hits) h.rule = rule;
    }

    /** first/limit 只在读出后作用于返回结果；受 OL.limit 上限约束（§27）。 */
    private static List<Hit> applyLimit(OL cfg, Rule rule, List<Hit> full) {
        int cap = rule.first ? 1 : Math.min(rule.limit, cfg.limit);
        if (full.size() <= cap) return new ArrayList<>(full);
        Log.w("hits " + full.size() + " > limit " + cap + ", truncating");
        return new ArrayList<>(full.subList(0, cap));
    }

    private static ScanResult empty() {
        return new ScanResult(new ArrayList<Hit>(), new ScanResult.Stats());
    }

    /** 从宿主 assets 读取字节（明文 dex/jar）。失败返回 null。 */
    private static byte[] readAsset(Context ctx, String name) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            byte[] tmp = new byte[16 * 1024];
            int r;
            while ((r = in.read(tmp)) != -1) bos.write(tmp, 0, r);
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w("asset read fail " + name, t);
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
        }
    }
}

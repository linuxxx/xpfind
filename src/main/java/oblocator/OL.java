package oblocator;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import oblocator.api.Rule;

/**
 * 主入口。链式构建 + 终止执行。
 *
 * <pre>
 * List&lt;Hit&gt; hits = OL.with(ctx, cl).pkg("com.target.app").fast()
 *     .find(Ri.m().ret(String.class).args(Context.class, String.class).static_());
 * // 本库只定位，Hook 由调用方用 hit.method（java.lang.reflect.Method）自行实现。
 * </pre>
 */
public final class OL {

    public static final String VERSION = "1.0.0";

    Context ctx;
    ClassLoader cl;
    String pkg;
    Mode mode = Mode.NORMAL;
    boolean useCache = true;
    boolean memOnly = false;
    int limit = 20;
    int budgetMs = 0;      // 0 = 不限
    int maxClasses = 0;    // 0 = 不限
    boolean allowAll = false;
    String proc;
    boolean anyProc = false;
    boolean dyn = false;
    boolean once = false;
    final List<Rule> rules = new ArrayList<>();
    final List<String> dexFiles = new ArrayList<>();   // 自定义 dex/apk/jar 文件路径
    final List<String> assetNames = new ArrayList<>(); // 从宿主 assets 读取的 dex/jar 名
    final List<byte[]> dexBytes = new ArrayList<>();   // 内存中的 dex/zip 字节（解密后）

    private OL() {}

    public static OL with(Context ctx, ClassLoader cl) {
        OL ol = new OL();
        ol.ctx = ctx;
        ol.cl = cl;
        return ol;
    }

    public OL pkg(String pkg) { this.pkg = pkg; return this; }

    public OL mode(Mode mode) { this.mode = mode; return this; }

    public OL fast() { this.mode = Mode.FAST; return this; }
    public OL normal() { this.mode = Mode.NORMAL; return this; }
    public OL deep() { this.mode = Mode.DEEP; return this; }
    public OL trace() { this.mode = Mode.TRACE; return this; }

    public OL cache(boolean enable) { this.useCache = enable; return this; }
    public OL memOnly() { this.memOnly = true; return this; }
    public OL disk() { this.memOnly = false; return this; }

    public OL limit(int limit) { this.limit = limit; return this; }

    /** 时间预算（毫秒），超时降级并打警告；<=0 表示不限。 */
    public OL budget(int ms) { this.budgetMs = ms; return this; }

    /** 类数预算；<=0 表示不限。 */
    public OL maxClasses(int n) { this.maxClasses = n; return this; }

    /** 允许无 pkg 全量扫描（危险，默认禁止）。 */
    public OL allowAll() { this.allowAll = true; return this; }

    public OL proc(String proc) { this.proc = proc; return this; }
    public OL anyProc() { this.anyProc = true; return this; }

    /** 动态 dex（第一版不实现，仅占位）。 */
    public OL dyn() { this.dyn = true; return this; }

    /**
     * 指定要扫描的 dex 文件路径（可传多个）：raw .dex，或 apk/jar/zip 容器。
     * 用于动态加载 / assets 解压出来的 dex。设置后 <b>只扫这些</b>，不再扫默认宿主 APK。
     */
    public OL dex(String... paths) {
        for (String p : paths) if (p != null) dexFiles.add(p);
        return this;
    }

    /**
     * 从宿主 assets 读取 dex/jar（明文时可用）。名字是 assets 下的相对路径，如 "foo.dex"。
     * 设置后 <b>只扫这些</b>，不再扫默认宿主 APK。加密的 assets 读不了，需先拿到解密后的文件/字节。
     */
    public OL asset(String... names) {
        for (String n : names) if (n != null) assetNames.add(n);
        return this;
    }

    /**
     * 直接扫描内存中的 dex/zip 字节（raw dex 或 jar/apk 字节）。
     * 适合 assets 被加密、只能在运行时 Hook 拿到解密后字节的场景。设置后 <b>只扫这些</b>。
     */
    public OL dexBytes(byte[] data) {
        if (data != null) dexBytes.add(data);
        return this;
    }

    /** 同一进程内该规则只执行一次。 */
    public OL once() { this.once = true; return this; }

    public OL log() { Log.enabled = true; return this; }

    public OL rule(Rule rule) { this.rules.add(rule); return this; }

    public OL rules(Rule... rs) {
        for (Rule r : rs) this.rules.add(r);
        return this;
    }

    // ---- 终止方法 ----

    public List<Hit> run() {
        return Scan.run(this).hits;
    }

    public ScanResult runWithStats() {
        return Scan.run(this);
    }

    /** 加这条规则并执行，返回命中（含 {@link Hit#method}）。本库只定位，Hook 由调用方自行实现。 */
    public List<Hit> find(Rule rule) {
        this.rules.add(rule);
        return run();
    }

    /**
     * 只跑 dex 初筛层匹配（不加载类、不反射、不 Hook），用于本地/离线快速验证规则是否合理。
     * 来源用 {@code .dex()}（文件路径）/ {@code .dexBytes()}（内存字节）指定 —— 这两者不需要 ctx/cl，
     * 可在桌面 JVM 单元测试里直接跑；{@code .asset()} 需要 ctx。
     *
     * <p>FAST 模式：ret/args 在 dex 层强匹配，返回即最终命中。
     * <br>NORMAL 模式：类型条件延后到反射阶段，这里只按 名字/参数数/修饰符/结构 过滤，返回的是候选（偏多）。
     */
    public List<ProbeHit> probe(Rule rule) {
        return Scan.probe(this, rule);
    }

    /** 后台扫描，完成回调。回调在工作线程执行。 */
    public void async(final OnScan cb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                ScanResult r;
                try {
                    r = Scan.run(OL.this);
                } catch (Throwable e) {
                    Log.e("async scan threw", e);
                    r = new ScanResult(new ArrayList<Hit>(), new ScanResult.Stats());
                }
                if (cb != null) {
                    try { cb.on(r); } catch (Throwable e) { Log.e("async cb threw", e); }
                }
            }
        }, "ol-scan");
        t.setDaemon(true);
        t.start();
    }

    public OL clear() {
        Cache.clear(ctx, pkg);
        return this;
    }
}

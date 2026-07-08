package oblocator;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 三层缓存：
 *   L1 内存：进程内 ruleHits / classList。
 *   L2/L3 文件：App 版本级 meta + 每规则命中集，JSON 落地。
 * 只缓存完整命中集；预算被截断的扫描不写缓存（Scan 侧判断）。
 * 命中缓存读出后必须经 resolve() 用反射校验签名，再交给调用方。
 */
public final class Cache {

    static final int SCHEMA = 2;

    // ---- L1 内存 ----
    private static final Map<String, List<Hit>> MEM = new ConcurrentHashMap<>();

    private static String memKey(String pkg, Mode mode, String ruleHash) {
        return pkg + '|' + mode + '|' + ruleHash;
    }

    // ---- 会话上下文（每次 Scan.run 构造一次）----
    private final Context ctx;
    private final ClassLoader cl;
    private final String pkg;
    private final Mode mode;
    private final boolean useFile;   // memOnly=false 时才落地
    private final File dir;
    private boolean metaOk;

    public Cache(Context ctx, ClassLoader cl, String pkg, Mode mode, boolean useFile) {
        this.ctx = ctx;
        this.cl = cl;
        this.pkg = pkg;
        this.mode = mode;
        this.useFile = useFile;
        this.dir = new File(ctx.getCacheDir(), "ol");
        if (useFile) validateMeta();
    }

    /** 读命中集（先内存后文件），失败返回 null。返回的 Hit 已 resolve 出 Method。 */
    public List<Hit> get(String ruleHash) {
        List<Hit> mem = MEM.get(memKey(pkg, mode, ruleHash));
        if (mem != null && resolveAll(mem)) {
            return copy(mem);
        }
        if (!useFile || !metaOk) return null;

        File f = new File(dir, "hits_" + mode + "_" + ruleHash + ".json");
        if (!f.exists()) return null;
        try {
            List<Hit> hits = readHits(f);
            if (hits == null) return null;
            if (!resolveAll(hits)) return null; // 校验失败 → 视为未命中
            MEM.put(memKey(pkg, mode, ruleHash), copy(hits));
            return hits;
        } catch (Throwable t) {
            Log.w("cache read fail " + f, t);
            return null;
        }
    }

    /** 写完整命中集。 */
    public void put(String ruleHash, List<Hit> hits) {
        MEM.put(memKey(pkg, mode, ruleHash), copy(hits));
        if (!useFile || !metaOk) return;
        File f = new File(dir, "hits_" + mode + "_" + ruleHash + ".json");
        writeHits(f, hits);
    }

    /** 清空该 App 的全部缓存（内存 + 文件）。 */
    public static void clear(Context ctx, String pkg) {
        // 内存：清掉该 pkg 的所有条目
        for (String k : new ArrayList<>(MEM.keySet())) {
            if (k.startsWith(pkg + '|')) MEM.remove(k);
        }
        try {
            deleteDir(new File(ctx.getCacheDir(), "ol"));
        } catch (Throwable t) {
            Log.w("cache clear fail", t);
        }
    }

    // ---- meta 校验 ----

    private void validateMeta() {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                metaOk = false;
                return;
            }
            JSONObject cur = currentMeta();
            File mf = new File(dir, "meta.json");
            boolean same = false;
            if (mf.exists()) {
                JSONObject old = new JSONObject(readText(mf));
                same = old.optInt("schemaVersion") == SCHEMA
                        && eq(old, cur, "pkg")
                        && old.optInt("verCode") == cur.optInt("verCode")
                        && old.optLong("apkLastModified") == cur.optLong("apkLastModified")
                        && old.optLong("apkLen") == cur.optLong("apkLen")
                        && eq(old, cur, "apkPath")
                        && eq(old, cur, "olVersion");
            }
            if (!same) {
                deleteDir(dir);
                dir.mkdirs();
                writeText(new File(dir, "meta.json"), cur.toString());
            }
            metaOk = true;
        } catch (Throwable t) {
            Log.w("meta validate fail", t);
            metaOk = false;
        }
    }

    @SuppressWarnings("deprecation") // versionCode：跨所有 API 版本可用，v1 从简
    private JSONObject currentMeta() throws Exception {
        JSONObject o = new JSONObject();
        o.put("schemaVersion", SCHEMA);
        o.put("pkg", pkg);
        o.put("olVersion", OL.VERSION);

        int verCode = 0;
        String apkPath = "";
        long apkLen = 0;
        try {
            PackageManager pm = ctx.getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), 0);
            verCode = pi.versionCode;
            apkPath = ctx.getApplicationInfo().sourceDir;
        } catch (Throwable ignore) {
        }
        long apkLastMod = 0;
        if (apkPath != null && !apkPath.isEmpty()) {
            File apk = new File(apkPath);
            apkLastMod = apk.lastModified();
            apkLen = apk.length();
        }
        o.put("verCode", verCode);
        o.put("apkPath", apkPath == null ? "" : apkPath);
        o.put("apkLastModified", apkLastMod);
        o.put("apkLen", apkLen);   // apkLastModified 在重装/OTA 后可能保留，故用长度兜底（v1 从简）
        return o;
    }

    private static boolean eq(JSONObject a, JSONObject b, String key) {
        return a.optString(key, "").equals(b.optString(key, ""));
    }

    // ---- 序列化 ----

    private static List<Hit> readHits(File f) throws Exception {
        JSONObject root = new JSONObject(readText(f));
        JSONArray arr = root.optJSONArray("hits");
        if (arr == null) return null;
        List<Hit> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Hit h = new Hit();
            h.cls = o.getString("cls");
            h.name = o.getString("name");
            h.ret = o.getString("ret");
            h.mod = o.getInt("mod");
            JSONArray a = o.optJSONArray("args");
            if (a != null) {
                h.args = new String[a.length()];
                for (int j = 0; j < a.length(); j++) h.args[j] = a.getString(j);
            } else {
                h.args = new String[0];
            }
            out.add(h);
        }
        return out;
    }

    private void writeHits(File f, List<Hit> hits) {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Hit h : hits) {
                JSONObject o = new JSONObject();
                o.put("cls", h.cls);
                o.put("name", h.name);
                o.put("ret", h.ret);
                o.put("mod", h.mod);
                JSONArray a = new JSONArray();
                if (h.args != null) for (String s : h.args) a.put(s);
                o.put("args", a);
                arr.put(o);
            }
            root.put("schemaVersion", SCHEMA);
            root.put("hits", arr);
            writeText(f, root.toString());
        } catch (Throwable t) {
            Log.w("cache write fail " + f, t);
        }
    }

    // ---- Method 重绑定 + 校验 ----

    private boolean resolveAll(List<Hit> hits) {
        for (Hit h : hits) {
            if (h.method != null) continue;
            if (!resolve(h, cl)) return false;
        }
        return true;
    }

    static boolean resolve(Hit h, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(h.cls, false, cl);
            int argn = (h.args == null) ? 0 : h.args.length;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(h.name)) continue;
                if (m.getModifiers() != h.mod) continue;
                if (!m.getReturnType().getName().equals(h.ret)) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != argn) continue;
                boolean ok = true;
                for (int i = 0; i < ps.length; i++) {
                    if (!ps[i].getName().equals(h.args[i])) { ok = false; break; }
                }
                if (ok) {
                    h.method = m;
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w("resolve fail " + h.cls, t);
        }
        return false;
    }

    // ---- 文件 IO（原子写：tmp + rename，多进程安全）----

    private static void writeText(File f, String text) {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp." + android.os.Process.myPid());
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp);
            fos.write(text.getBytes("UTF-8"));
            fos.flush();
            fos.getFD().sync();
        } catch (Throwable t) {
            Log.w("write tmp fail " + tmp, t);
            if (fos != null) try { fos.close(); } catch (Throwable ignore) {}
            tmp.delete();
            return;
        }
        try { fos.close(); } catch (Throwable ignore) {}
        if (!tmp.renameTo(f)) {
            // 目标可能已存在，先删再重命名
            f.delete();
            if (!tmp.renameTo(f)) {
                Log.w("rename fail " + tmp, null);
                tmp.delete();
            }
        }
    }

    private static String readText(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        try {
            int off = 0, r;
            while (off < buf.length && (r = fis.read(buf, off, buf.length - off)) != -1) off += r;
        } finally {
            fis.close();
        }
        return new String(buf, "UTF-8");
    }

    private static void deleteDir(File d) {
        if (d == null || !d.exists()) return;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        }
        d.delete();
    }

    private static List<Hit> copy(List<Hit> in) {
        return new ArrayList<>(in);
    }
}

package oblocator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import oblocator.api.Rule;

/**
 * 本地单元测试：验证 oblocator 的「dex 初筛层」能否在真实 dex/apk 里按规则找到方法。
 *
 * <p>覆盖的是 {@link Scan} 的第一阶段：{@link Dex} 解析 + {@link Match#dexPass}——纯 Java，
 * 不加载类、不需要设备，在 Android Studio 里对着方法点绿三角即可运行。
 *
 * <p><b>不覆盖</b>第二阶段（反射确认）：那一步要 {@code Class.forName} 加载真实 Android 类，
 * 桌面 JVM 做不到，需真机上的 instrumented 测试（见类尾注释）。
 *
 * <p>被测文件默认取模块根目录的 {@code audience_network.dex}，可用系统属性覆盖：
 * <pre>./gradlew testDebugUnitTest -Doblocator.dex=/path/to/app.apk</pre>
 * 传 apk/jar/zip 也行——{@link Dex#scanFiles} 会自动识别 raw dex 还是容器。
 */
public class DexFindTest {

    /** 被测 dex / apk 路径。相对路径以模块根目录为基准（gradle 单测的工作目录）。 */
    private static final String DEX_PATH =
            System.getProperty("oblocator.dex", "audience_network.dex");

    private static final String FB_PREFIX = "com.facebook.ads.redexgen";
    private static final String TARGET_CLASS = "com.facebook.ads.redexgen.X.a5";
    private static final String ENUM_RET = "com.facebook.ads.redexgen.X.ME";

    // ---- 复用真实扫描逻辑（等价 Scan.freshScan 第一阶段）----

    /** dexPass 命中即入候选（每类只记一次），返回候选类名。 */
    private static List<String> candidateClasses(String prefix, Mode mode, final Rule rule) {
        final Set<String> cand = new LinkedHashSet<>();
        Dex.scanFiles(new String[]{DEX_PATH}, prefix, new Dex.ClassVisitor() {
            @Override public boolean visit(Dex.DexClass c) {
                for (Dex.DexMethod m : c.methods) {
                    if (Match.dexPass(rule, mode, c, m)) {
                        cand.add(c.name);
                        break;
                    }
                }
                return true;
            }
        });
        return new ArrayList<>(cand);
    }

    /** 逐方法用 dexPass 判定，返回 "类#方法(参数)->返回值" 形式的命中列表。 */
    private static List<String> methodHits(String prefix, Mode mode, final Rule rule) {
        final List<String> hits = new ArrayList<>();
        Dex.scanFiles(new String[]{DEX_PATH}, prefix, new Dex.ClassVisitor() {
            @Override public boolean visit(Dex.DexClass c) {
                for (Dex.DexMethod m : c.methods) {
                    if (Match.dexPass(rule, mode, c, m)) {
                        hits.add(c.name + "#" + m.name
                                + Arrays.toString(m.params) + "->" + m.ret);
                    }
                }
                return true;
            }
        });
        return hits;
    }

    private static void requireDex() {
        assumeTrue("测试 dex 不存在：" + new File(DEX_PATH).getAbsolutePath()
                        + "（用 -Doblocator.dex=/path 指定 dex 或 apk）",
                new File(DEX_PATH).exists());
    }

    // ---- 测试 ----

    /**
     * 通过 OL 的公开 API 直接验证规则：{@code .dex(path).probe(rule)}。
     * probe 只跑 dex 初筛层（不反射、不需要 ctx/cl），本地就能验证规则合不合理。
     * 完整的 {@code .find()}（含反射确认）需要真机——见类尾注释。
     */
    @Test
    public void probeViaOL() {
        requireDex();

        // FAST + 真实返回类型：dex 层直接精确命中，probe 返回的就是最终结果。
        long t0 = System.nanoTime();
        List<ProbeHit> exact = OL.with(null, null)
                .dex(DEX_PATH)
                .pkg("com.facebook.ads.redexgen.X")
                .anyProc()
                .fast()
                .probe(Ri.m()
                        .retSuper(Enum.class)
                        .args(String.class, String.class, Map.class)
                        .public_());
        long fastMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[probeViaOL/FAST] " + fastMs + "ms " + exact);
        assertEquals("FAST 精确规则应恰好命中 1 个", 1, exact.size());
        assertTrue(exact.get(0).cls.equals(TARGET_CLASS) && exact.get(0).name.equals("A05"));

        // NORMAL + 父类 Enum：类型延后到反射，probe 只能给候选，a5 应在其中。
        long t1 = System.nanoTime();
        List<ProbeHit> cand = OL.with(null, null)
                .dex(DEX_PATH)
                .pkg("com.facebook.ads.redexgen.X")
                .anyProc()
                .normal()
                .probe(Ri.m()
                        .ret(Enum.class)
                        .args(String.class, String.class, Map.class)
                        .public_());
        long normalMs = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("[probeViaOL/NORMAL] " + normalMs + "ms candidate methods = " + cand.size());
        boolean hasA5 = false;
        for (ProbeHit h : cand) if (TARGET_CLASS.equals(h.cls)) hasA5 = true;
        assertTrue("NORMAL 候选里应含 a5", hasA5);
    }

    /**
     * retSuper：不写死被混淆的返回值类名，只声明"返回值是 Enum 的子类"。
     * dex 层沿父类链判定（ME → java.lang.Enum），配合精确参数收敛到唯一目标。
     */
    @Test
    public void retSuperNarrowsWithoutObfName() {
        requireDex();

        // FAST + retSuper(Enum) + 精确参数：dex 层直接 1 个，全程无混淆名。
        List<ProbeHit> fast = OL.with(null, null)
                .dex(DEX_PATH)
                .pkg("com.facebook.ads.redexgen.X")
                .anyProc()
                .fast()
                .probe(Ri.m()
                        .retSuper(Enum.class)
                        .args(String.class, String.class, Map.class)
                        .public_());
        System.out.println("[retSuper/FAST] " + fast);
        assertEquals("retSuper + 精确参数应恰好命中 1 个", 1, fast.size());
        assertTrue(fast.get(0).cls.equals(TARGET_CLASS) && fast.get(0).name.equals("A05"));

        // NORMAL + retSuper：参数仍延后到反射，但 retSuper 已在 dex 层把 1989 砍到很小。
        List<ProbeHit> normal = OL.with(null, null)
                .dex(DEX_PATH)
                .pkg("com.facebook.ads.redexgen.X")
                .anyProc()
                .normal()
                .probe(Ri.m()
                        .retSuper(Enum.class)
                        .args(String.class, String.class, Map.class)
                        .public_());
        System.out.println("[retSuper/NORMAL] candidate methods = " + normal.size());
        assertTrue("retSuper 应把候选大幅收窄（远小于 1989）", normal.size() < 1989);
        boolean hasA5 = false;
        for (ProbeHit h : normal) if (TARGET_CLASS.equals(h.cls)) hasA5 = true;
        assertTrue("候选里应仍含 a5", hasA5);
    }

    /** 解析器健全性：整包应能解析出大量类，且目标类可见（回归 method_ids_off 那个 bug）。 */
    @Test
    public void parsesWholeDex() {
        requireDex();
        final int[] n = {0};
        final boolean[] sawTarget = {false};
        Dex.scanFiles(new String[]{DEX_PATH}, null, new Dex.ClassVisitor() {
            @Override public boolean visit(Dex.DexClass c) {
                n[0]++;
                if (TARGET_CLASS.equals(c.name)) sawTarget[0] = true;
                return true;
            }
        });
        System.out.println("[parsesWholeDex] parsed classes = " + n[0]);
        assertTrue("解析出的类太少（" + n[0] + "），解析器可能又坏了", n[0] > 1000);
        assertTrue("应解析到目标类 " + TARGET_CLASS, sawTarget[0]);
    }

    /** FAST 精确匹配：返回值/参数用真实全名，dex 层即可精确命中 public final 的 a5#A05。 */
    @Test
    public void findsTargetFastExact() {
        requireDex();
        Rule r = Ri.m()
                .ret(ENUM_RET)                                   // 真实返回类型 …X.ME
                .args(String.class, String.class, Map.class)
                .public_();
        List<String> hits = methodHits(FB_PREFIX, Mode.FAST, r);
        System.out.println("[findsTargetFastExact] hits = " + hits);
        assertEquals("应恰好命中 1 个 public 方法", 1, hits.size());
        assertTrue("命中的应是 a5#A05，实际 " + hits.get(0),
                hits.get(0).startsWith(TARGET_CLASS + "#A05"));
    }

    /**
     * NORMAL 候选：类型条件延后到反射阶段，dex 层只按 名字/参数数/修饰符 入候选。
     * 用 {@code ret(Enum.class)}（父类）——这在 FAST 下匹配不到，只有 NORMAL 反射阶段才成立，
     * 但 dex 初筛应把 a5 纳入候选。
     */
    @Test
    public void findsTargetNormalCandidate() {
        requireDex();
        Rule r = Ri.m()
                .ret(Enum.class)
                .args(String.class, String.class, Map.class)
                .public_();
        List<String> cand = candidateClasses(FB_PREFIX, Mode.NORMAL, r);
        System.out.println("[findsTargetNormalCandidate] candidates = " + cand.size()
                + " -> " + cand);
        assertTrue("a5 应在 NORMAL 候选内", cand.contains(TARGET_CLASS));
    }

    /** 反面用例：FAST 下用父类 Enum 作返回值应匹配不到（强匹配是字符串相等）。 */
    @Test
    public void fastDoesNotMatchSupertype() {
        requireDex();
        Rule r = Ri.m()
                .ret(Enum.class)                                 // "java.lang.Enum" != "…X.ME"
                .args(String.class, String.class, Map.class)
                .public_();
        List<String> hits = methodHits(FB_PREFIX, Mode.FAST, r);
        System.out.println("[fastDoesNotMatchSupertype] hits = " + hits);
        assertTrue("FAST 用父类不该命中（应改用 .normal()），实际 " + hits, hits.isEmpty());
    }

    /**
     * 单格通配符 "*"：3 个参数里让中间那格通配（模拟被混淆的参数类），
     * 其余两格精确。FAST 下应与精确写法命中同一个 a5#A05。
     */
    @Test
    public void argWildcardMatchesObfuscatedSlot() {
        requireDex();

        // 基准：三格全精确，FAST 恰好命中 a5#A05。
        List<ProbeHit> exact = OL.with(null, null)
                .dex(DEX_PATH).pkg(FB_PREFIX).anyProc().fast()
                .probe(Ri.m().retSuper(Enum.class)
                        .args(String.class, String.class, Map.class).public_());
        assertEquals("基准精确规则应命中 1 个", 1, exact.size());

        // 中间那格用通配符 "*"（假装它是混淆类，名字写不出来）：仍应命中同一个方法。
        List<ProbeHit> wild = OL.with(null, null)
                .dex(DEX_PATH).pkg(FB_PREFIX).anyProc().fast()
                .probe(Ri.m().retSuper(Enum.class)
                        .argsLike(String.class, Rule.ANY, Map.class).public_());
        System.out.println("[argWildcard] wild hits = " + wild);
        assertEquals("通配中间格后仍应恰好命中 1 个", 1, wild.size());
        assertTrue("命中应仍是 a5#A05",
                wild.get(0).cls.equals(TARGET_CLASS) && wild.get(0).name.equals("A05"));

        // 反面：把已知格写错类型，通配符不该"救回来"——应命中 0。
        List<ProbeHit> wrong = OL.with(null, null)
                .dex(DEX_PATH).pkg(FB_PREFIX).anyProc().fast()
                .probe(Ri.m().retSuper(Enum.class)
                        .argsLike("java.lang.Integer", Rule.ANY, Map.class).public_());
        assertTrue("已知格类型不符时通配符不应命中", wrong.isEmpty());
    }

    /**
     * body 级条件（str）：先用 parseBody 扫出目标类里某方法的一个字符串常量，
     * 再用 {@code .str(那个字符串)} probe，断言该方法被命中——证明 code_item 解析 + dexPass body 校验通路。
     */
    @Test
    public void findsByBodyString() {
        requireDex();

        // 第一遍：打开 parseBody，找一个"字符串够长、够独特"的方法作靶子。
        final String[] hitCls = {null};
        final String[] hitMethod = {null};
        final String[] hitStr = {null};
        Dex.scanFiles(new String[]{DEX_PATH}, FB_PREFIX, true, new Dex.ClassVisitor() {
            @Override public boolean visit(Dex.DexClass c) {
                if (hitStr[0] != null) return false;
                for (Dex.DexMethod m : c.methods) {
                    for (String s : m.strings) {
                        if (s != null && s.length() >= 8) {
                            hitCls[0] = c.name; hitMethod[0] = m.name; hitStr[0] = s;
                            return false;
                        }
                    }
                }
                return true;
            }
        });
        assumeTrue("该 dex 未解析出可用字符串常量，跳过", hitStr[0] != null);
        System.out.println("[findsByBodyString] target " + hitCls[0] + "#" + hitMethod[0]
                + " str=\"" + hitStr[0] + "\"");

        // 第二遍：用该字符串作 body 条件 probe，应命中同一个方法。
        List<ProbeHit> hits = OL.with(null, null)
                .dex(DEX_PATH)
                .pkg(FB_PREFIX)
                .anyProc()
                .normal()
                .probe(Ri.m().str(hitStr[0]));
        System.out.println("[findsByBodyString] str-probe hits = " + hits.size());
        boolean found = false;
        for (ProbeHit h : hits) {
            if (hitCls[0].equals(h.cls) && hitMethod[0].equals(h.name)) found = true;
        }
        assertTrue("str 条件应命中含该字符串的方法 " + hitCls[0] + "#" + hitMethod[0], found);
    }

    /** 反面用例：body 里不存在的字符串，str 条件应命中为空（证明 body 校验真的在过滤）。 */
    @Test
    public void bodyStringMissesWhenAbsent() {
        requireDex();
        List<ProbeHit> hits = OL.with(null, null)
                .dex(DEX_PATH)
                .pkg(FB_PREFIX)
                .anyProc()
                .normal()
                .probe(Ri.m().str("__xpfind_no_such_string_literal_zzz__"));
        System.out.println("[bodyStringMissesWhenAbsent] hits = " + hits.size());
        assertTrue("不存在的字符串不该命中任何方法", hits.isEmpty());
    }

    /*
     * ── 第二阶段（反射确认）如何测？ ─────────────────────────────────────────────
     * 反射要真机/模拟器，写成 instrumented 测试放 src/androidTest：
     *   1) 拿到加载了目标 dex 的 ClassLoader（FB 动态 dex → 它自己的 DexClassLoader；
     *      普通 apk → InstrumentationRegistry 的 context.getClassLoader）。
     *   2) OL.with(ctx, cl).asset("audience_network.dex").pkg("com.facebook.ads.redexgen.X")
     *          .anyProc().normal()
     *          .find(Ri.m().ret(Enum.class).args(String.class,String.class,Map.class).public_());
     *   3) 断言返回的 Hit 里含 …X.a5#A05，再用 hit.method 自己 hook。
     * 本地 JVM 加载不了 dalvik 类，所以第二阶段只能在设备上验。
     */
}

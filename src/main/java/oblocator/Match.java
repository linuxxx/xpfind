package oblocator;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import oblocator.api.Rule;

/**
 * 匹配器。两级：
 *   dexPass    — dex 字节码层，便宜条件初筛（不加载类）。
 *   reflectPass — 反射层，对候选类做最终匹配，NORMAL 走弱匹配。
 * 匹配语义见方案 §4.3 / §6.2。
 */
public final class Match {

    private Match() {}

    // ---- 通用便宜条件 ----

    static boolean clsOk(Rule r, String cname) {
        if (r.cls != null && !r.cls.equals(cname)) return false;
        if (r.hasCls != null && !cname.contains(r.hasCls)) return false;
        return true;
    }

    static boolean nameOk(Rule r, String mname) {
        if (r.name != null && !r.name.equals(mname)) return false;
        if (r.hasName != null && !mname.contains(r.hasName)) return false;
        return true;
    }

    /** 参数数量：args 最强（隐含长度）> argc > min/max。 */
    static boolean countOk(Rule r, int n) {
        if (r.args != null) return n == r.args.length;
        if (r.argc != null) return n == r.argc;
        boolean ok = true;
        if (r.min != null) ok = ok && n >= r.min;
        if (r.max != null) ok = ok && n <= r.max;
        return ok;
    }

    /** 三态修饰符：TRUE 必须有，FALSE 必须无，null 不关心。 */
    static boolean modsOk(Rule r, int mod) {
        if (r.isStatic != null && Modifier.isStatic(mod) != r.isStatic) return false;
        if (r.isPublic != null && Modifier.isPublic(mod) != r.isPublic) return false;
        if (r.isPrivate != null && Modifier.isPrivate(mod) != r.isPrivate) return false;
        if (r.isProtected != null && Modifier.isProtected(mod) != r.isProtected) return false;
        if (r.isNative != null && Modifier.isNative(mod) != r.isNative) return false;
        if (r.isSync != null && Modifier.isSynchronized(mod) != r.isSync) return false;
        return true;
    }

    // ---- dex 阶段 ----

    static boolean dexPass(Rule r, Mode mode, Dex.DexClass c, Dex.DexMethod m) {
        return dexPass(r, mode, c, m, null);
    }

    /**
     * superIndex：全 dex 的 类名→父类名 索引，用于 {@link Rule#retSuper} 在 dex 层判定返回值的父类链。
     * 传 null 表示没有该索引（此时 retSuper 不在 dex 层过滤，留给反射阶段）。
     */
    static boolean dexPass(Rule r, Mode mode, Dex.DexClass c, Dex.DexMethod m,
                           Map<String, String> superIndex) {
        if (!clsOk(r, c.name)) return false;
        if (!nameOk(r, m.name)) return false;
        if (!countOk(r, m.params.length)) return false;
        if (!modsOk(r, m.accessFlags)) return false;
        if (r.superCls != null && !r.superCls.equals(c.superName)) return false;
        if (r.hasItf != null && !hasItf(c.interfaces, r.hasItf)) return false;
        if (r.fieldc != null && c.fieldCount != r.fieldc) return false;
        if (r.methodc != null && c.methodCount != r.methodc) return false;

        // retSuper：返回值的父类链里含目标类。需要全 dex 的 super 索引，没有就跳过（反射阶段兜底）。
        if (r.retSuper != null && superIndex != null
                && !isSubOf(m.ret, r.retSuper, superIndex)) {
            return false;
        }

        if (mode == Mode.FAST) {
            // FAST 走强匹配，类型在 dex 层即可判定
            if (r.ret != null && !r.ret.equals(m.ret)) return false;
            if (!argsStrict(r, m.params)) return false;
        }
        // NORMAL：类型条件（弱匹配）推迟到反射阶段
        return true;
    }

    /** type 自身或其父类链（在 superIndex 内可追溯的部分）里是否含 wantSuper。 */
    private static boolean isSubOf(String type, String wantSuper, Map<String, String> superIndex) {
        String cur = type;
        for (int guard = 0; cur != null && guard < 64; guard++) {
            if (cur.equals(wantSuper)) return true;
            cur = superIndex.get(cur);   // 父类不在本 dex 里就到 null，链结束
        }
        return false;
    }

    private static boolean argsStrict(Rule r, String[] params) {
        if (r.args == null) return true;
        if (params.length != r.args.length) return false;
        for (int i = 0; i < params.length; i++) {
            if (!r.args[i].equals(params[i])) return false;
        }
        return true;
    }

    private static boolean hasItf(String[] itfs, String want) {
        if (itfs == null) return false;
        for (String s : itfs) {
            if (s != null && s.contains(want)) return true;
        }
        return false;
    }

    // ---- 反射阶段 ----

    static boolean reflectPass(Rule r, Mode mode, Class<?> c, Method m, ClassLoader cl) {
        if (!clsOk(r, c.getName())) return false;
        if (!nameOk(r, m.getName())) return false;

        Class<?>[] ps = m.getParameterTypes();
        if (!countOk(r, ps.length)) return false;
        if (!modsOk(r, m.getModifiers())) return false;

        boolean weak = (mode == Mode.NORMAL);
        Class<?> retType = m.getReturnType();
        if (!weak) {
            if (r.ret != null && !r.ret.equals(retType.getName())) return false;
            if (!argsStrictC(r, ps)) return false;
        } else {
            if (!retWeak(r, retType, cl)) return false;
            if (!argsWeak(r, ps, cl)) return false;
        }

        // retSuper：返回值必须是该类的子类（含自身），两种模式都校验。
        if (r.retSuper != null) {
            Class<?> want = load(r.retSuper, cl);
            if (want == null || !want.isAssignableFrom(retType)) return false;
        }

        if (r.superCls != null) {
            Class<?> sc = c.getSuperclass();
            if (sc == null || !sc.getName().equals(r.superCls)) return false;
        }
        if (r.hasItf != null && !hasItfC(c, r.hasItf)) return false;

        if (r.filter != null && !safeFilter(r, c, m)) return false;
        return true;
    }

    private static boolean argsStrictC(Rule r, Class<?>[] ps) {
        if (r.args == null) return true;
        if (ps.length != r.args.length) return false;
        for (int i = 0; i < ps.length; i++) {
            if (!r.args[i].equals(ps[i].getName())) return false;
        }
        return true;
    }

    private static boolean retWeak(Rule r, Class<?> ret, ClassLoader cl) {
        if (r.ret == null) return true;
        Class<?> want = load(r.ret, cl);
        if (want == null) return r.ret.equals(ret.getName());
        return assignable(want, ret);
    }

    private static boolean argsWeak(Rule r, Class<?>[] ps, ClassLoader cl) {
        if (r.args == null) return true;
        if (ps.length != r.args.length) return false;
        for (int i = 0; i < ps.length; i++) {
            Class<?> want = load(r.args[i], cl);
            if (want == null) {
                if (!r.args[i].equals(ps[i].getName())) return false;
            } else if (!assignable(want, ps[i])) {
                return false;
            }
        }
        return true;
    }

    /** 目标可赋值给规则声明类型；基本类型与包装类型视为等价。 */
    private static boolean assignable(Class<?> want, Class<?> actual) {
        if (want.equals(actual)) return true;
        if (box(want).equals(box(actual))) return true;
        return want.isAssignableFrom(actual);
    }

    private static boolean hasItfC(Class<?> c, String want) {
        for (Class<?> i : c.getInterfaces()) {
            if (i.getName().contains(want)) return true;
        }
        return false;
    }

    private static boolean safeFilter(Rule r, Class<?> c, Method m) {
        try {
            return r.filter.ok(c, m);
        } catch (Throwable t) {
            Log.w("filter throw " + (r.filter == null ? "?" : r.filter.id()), t);
            return false;
        }
    }

    // ---- 类型解析工具 ----

    private static final Map<String, Class<?>> PRIM = new HashMap<>();
    static {
        PRIM.put("int", int.class);
        PRIM.put("long", long.class);
        PRIM.put("boolean", boolean.class);
        PRIM.put("byte", byte.class);
        PRIM.put("char", char.class);
        PRIM.put("short", short.class);
        PRIM.put("float", float.class);
        PRIM.put("double", double.class);
        PRIM.put("void", void.class);
    }

    /** 把 Class.getName() 形式的名字解析为 Class，失败返回 null。 */
    static Class<?> load(String name, ClassLoader cl) {
        if (name == null) return null;
        Class<?> p = PRIM.get(name);
        if (p != null) return p;
        try {
            // 数组名 [B / [Ljava.lang.String; 由 Class.forName 直接支持
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        if (c == short.class) return Short.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == void.class) return Void.class;
        return c;
    }
}

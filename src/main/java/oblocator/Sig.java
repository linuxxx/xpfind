package oblocator;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;

/** 方法签名与 hash 工具。签名格式：className#methodName(returnType|arg1,arg2|modifiers) */
public final class Sig {

    private Sig() {}

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    public static String of(Method m) {
        Class<?>[] ps = m.getParameterTypes();
        String[] a = new String[ps.length];
        for (int i = 0; i < ps.length; i++) a[i] = ps[i].getName();
        return of(m.getDeclaringClass().getName(), m.getName(),
                m.getReturnType().getName(), a, m.getModifiers());
    }

    public static String of(String cls, String name, String ret, String[] args, int mod) {
        StringBuilder sb = new StringBuilder();
        sb.append(cls).append('#').append(name).append('(');
        sb.append(ret).append('|');
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(args[i]);
            }
        }
        sb.append('|').append(mods(mod)).append(')');
        return sb.toString();
    }

    public static String hash(Method m) {
        return md5(of(m));
    }

    private static String mods(int mod) {
        StringBuilder sb = new StringBuilder();
        if (Modifier.isStatic(mod)) add(sb, "static");
        if (Modifier.isPublic(mod)) add(sb, "public");
        if (Modifier.isPrivate(mod)) add(sb, "private");
        if (Modifier.isProtected(mod)) add(sb, "protected");
        if (Modifier.isNative(mod)) add(sb, "native");
        if (Modifier.isSynchronized(mod)) add(sb, "synchronized");
        return sb.toString();
    }

    private static void add(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append(',');
        sb.append(s);
    }
}

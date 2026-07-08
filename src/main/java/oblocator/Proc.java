package oblocator;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;

/** 进程工具。用于 §28 多进程门控：默认只在主进程执行。 */
public final class Proc {

    private Proc() {}

    /** 当前进程名，取不到返回 null。 */
    public static String name() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentProcessName");
            m.setAccessible(true);
            Object r = m.invoke(null);
            if (r instanceof String) return (String) r;
        } catch (Throwable ignore) {
        }
        return cmdline();
    }

    private static String cmdline() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/self/cmdline"));
            String line = br.readLine();
            if (line != null) {
                int nul = line.indexOf('\0');   // cmdline 以 NUL 分隔，进程名是第一段
                if (nul >= 0) line = line.substring(0, nul);
                line = line.trim();
                if (!line.isEmpty()) return line;
            }
        } catch (Throwable ignore) {
        } finally {
            if (br != null) try { br.close(); } catch (Throwable ignore2) {}
        }
        return null;
    }

    /** 是否主进程（进程名等于包名）。取不到进程名时保守返回 true，不误跳过。 */
    public static boolean isMain(String pkg) {
        String n = name();
        return n == null || pkg == null || n.equals(pkg);
    }

    public static boolean is(String proc) {
        String n = name();
        return n != null && n.equals(proc);
    }
}

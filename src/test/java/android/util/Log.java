package android.util;

/**
 * 纯 JVM 单元测试用的 android.util.Log 桩。
 *
 * <p>生产环境由系统提供真正的实现（本库编译期用 android.jar 的符号，见 build.gradle 的 compileOnly）。
 * 桌面 JVM 里没有 Android 运行时，真正的 android.jar 方法体是 {@code throw RuntimeException("Stub!")}，
 * 会让触及 {@link oblocator.Log#e} 等日志路径的测试崩掉；此桩把它们变成无副作用的 no-op。
 *
 * <p>仅存在于 src/test，不进产物 jar。
 */
public final class Log {

    private Log() {}

    public static int d(String tag, String msg) { return 0; }

    public static int i(String tag, String msg) { return 0; }

    public static int w(String tag, String msg) { return 0; }

    public static int w(String tag, String msg, Throwable t) { return 0; }

    public static int e(String tag, String msg) { return 0; }

    public static int e(String tag, String msg, Throwable t) { return 0; }
}

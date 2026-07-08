package oblocator;

/** 轻量日志。默认关闭（见 §33 log=false）；error 级始终输出。 */
public final class Log {

    public static final String TAG = "OL";
    public static boolean enabled = false;

    private Log() {}

    public static void d(String msg) {
        if (enabled) android.util.Log.d(TAG, msg);
    }

    public static void i(String msg) {
        if (enabled) android.util.Log.i(TAG, msg);
    }

    public static void w(String msg) {
        if (enabled) android.util.Log.w(TAG, msg);
    }

    public static void w(String msg, Throwable t) {
        if (enabled) android.util.Log.w(TAG, msg, t);
    }

    public static void e(String msg, Throwable t) {
        android.util.Log.e(TAG, msg, t);
    }
}

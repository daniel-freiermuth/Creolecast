package android.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * JVM unit-test stand-in for {@code android.util.Log}. The mockable android.jar
 * throws "Method ... not mocked" from every Log call, which would turn any code
 * path that logs into a test failure. Test classes precede that jar on the
 * unit-test classpath, so this no-op version is the one loaded. Nullability
 * matches the platform signatures.
 */
public final class Log {
    private Log() {}

    public static int v(@Nullable String tag, @NonNull String msg) { return 0; }
    public static int v(@Nullable String tag, @Nullable String msg, @Nullable Throwable tr) { return 0; }
    public static int d(@Nullable String tag, @NonNull String msg) { return 0; }
    public static int d(@Nullable String tag, @Nullable String msg, @Nullable Throwable tr) { return 0; }
    public static int i(@Nullable String tag, @NonNull String msg) { return 0; }
    public static int i(@Nullable String tag, @Nullable String msg, @Nullable Throwable tr) { return 0; }
    public static int w(@Nullable String tag, @NonNull String msg) { return 0; }
    public static int w(@Nullable String tag, @Nullable String msg, @Nullable Throwable tr) { return 0; }
    public static int w(@Nullable String tag, @Nullable Throwable tr) { return 0; }
    public static int e(@Nullable String tag, @NonNull String msg) { return 0; }
    public static int e(@Nullable String tag, @Nullable String msg, @Nullable Throwable tr) { return 0; }
}

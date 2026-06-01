package com.example.agent;

/**
 * Static registry for the bridge ClassLoader that logback should use instead
 * of its normal {@code obj.getClass().getClassLoader()} lookup.
 *
 * <p>This class is added to the <em>bootstrap</em> ClassLoader by
 * {@link AgentMain}, making it accessible from any ClassLoader in the JVM —
 * including the one that loads {@code ch.qos.logback.core.util.Loader}
 * (whether that is the system CL in embedded mode or an isolated SDK CL in
 * non-embedded mode).
 *
 * <p>Init-order safety: {@link #get()} returns {@code null} until
 * {@link #register(ClassLoader)} is called.  The instrumented
 * {@code Loader.getClassLoaderOfObject} falls back to the original behaviour
 * when the value is {@code null}, so it is harmless if logback initialises
 * slightly before the bridge is wired up (the caller will just get the
 * original classloader and miss the downward search that one time).
 *
 * <p>In practice the bridge must be registered <em>before</em> the first
 * {@code LoggerFactory.getLogger()} call, which is straightforward: call
 * {@link #register} before loading any class that has a static Logger field.
 */
public final class LogbackBridge {

    private static volatile ClassLoader bridge;

    private LogbackBridge() {}

    /** Register the ClassLoader that logback should use for class/resource lookup. */
    public static void register(ClassLoader cl) {
        bridge = cl;
    }

    /**
     * Returns the registered bridge ClassLoader, or {@code null} if not yet
     * registered.  Called from the instrumented logback Loader method.
     */
    public static ClassLoader get() {
        return bridge;
    }
}

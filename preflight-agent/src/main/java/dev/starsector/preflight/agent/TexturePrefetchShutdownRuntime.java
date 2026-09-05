package dev.starsector.preflight.agent;

import java.util.Map;

/** Lets the exact finite Windows worker finish before stock interrupt/retention cleanup. */
public final class TexturePrefetchShutdownRuntime {
    public static final String PROPERTY = "preflight.texture.windowsPrefetchDrain";
    static final long MAX_WAIT_MILLIS = 5_000;
    private static long calls, completed, timeouts, interrupted, declined, errors, waitNanos;

    private TexturePrefetchShutdownRuntime() { }

    static synchronized void reset() {
        calls = completed = timeouts = interrupted = declined = errors = waitNanos = 0;
    }

    static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY, "true"))
                && Boolean.getBoolean(TexturePreparedPrefetchPlan.WINDOWS_KALEIDOSCOPE_PROPERTY)
                // The typed prototype already owns its own bounded drain before this seam.
                && !TexturePreparedResourceRuntime.requested()
                && Integer.getInteger(TexturePreparedPrefetchPlan.WINDOWS_WORKERS_PROPERTY, 1) == 1
                && !Boolean.getBoolean(TexturePreparedPrefetchPlan.WINDOWS_SPLIT_QUEUES_PROPERTY);
    }

    /** The caller retains the original Thread.interrupt and both original map-cleanup operations. */
    public static void finish(Thread worker) {
        if (!enabled()) return;
        await(worker, MAX_WAIT_MILLIS);
    }

    static void await(Thread worker, long timeoutMillis) {
        if (worker == null || worker == Thread.currentThread()
                || worker.getClass() != Thread.class || timeoutMillis <= 0
                || timeoutMillis > MAX_WAIT_MILLIS) {
            synchronized (TexturePrefetchShutdownRuntime.class) { declined++; }
            return;
        }
        long started = System.nanoTime();
        synchronized (TexturePrefetchShutdownRuntime.class) { calls++; }
        try {
            worker.join(timeoutMillis);
            synchronized (TexturePrefetchShutdownRuntime.class) {
                if (worker.isAlive()) timeouts++; else completed++;
            }
        } catch (InterruptedException cancellation) {
            Thread.currentThread().interrupt();
            synchronized (TexturePrefetchShutdownRuntime.class) { interrupted++; }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            synchronized (TexturePrefetchShutdownRuntime.class) { errors++; }
        } finally {
            synchronized (TexturePrefetchShutdownRuntime.class) {
                waitNanos += System.nanoTime() - started;
            }
        }
    }

    static synchronized Map<String, Object> report() {
        return Map.of("enabled", enabled(), "maxWaitMillis", MAX_WAIT_MILLIS,
                "calls", calls, "completed", completed, "timeouts", timeouts,
                "interrupted", interrupted, "declined", declined, "errors", errors,
                "waitMillis", waitNanos / 1_000_000L);
    }
}

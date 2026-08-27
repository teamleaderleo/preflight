package dev.starsector.preflight.agent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/** Opt-in deadline pacing experiment at the reviewed LWJGL display-update boundary. */
public final class FramePacingRuntime {
    static final String PLAN_ID = "lwjgl-display-deadline-pacing-v1";
    static final String FPS_PROPERTY = "preflight.framePacingFps";
    static final String SPIN_MICROS_PROPERTY = "preflight.framePacingSpinMicros";

    private static final int MIN_FPS = 15;
    private static final int MAX_FPS = 1000;
    private static final int DEFAULT_SPIN_MICROS = 250;
    private static final int MAX_SPIN_MICROS = 2_000;
    private static final long OVERSHOOT_BIN_NANOS = 10_000L;
    private static final int OVERSHOOT_BINS = 10_000;

    private static volatile boolean initialized;
    private static volatile int targetFps;
    private static volatile long frameNanos;
    private static volatile long spinMarginNanos;
    private static long previousCompletionNanos = Long.MIN_VALUE;
    private static long calls;
    private static long waits;
    private static long lateFrames;
    private static long totalWaitNanos;
    private static long totalSpinNanos;
    private static long maximumWaitNanos;
    private static long maximumSpinNanos;
    private static long maximumOvershootNanos;
    private static final long[] overshootHistogram = new long[OVERSHOOT_BINS + 1];

    private FramePacingRuntime() {
    }

    static boolean enabled() {
        initializeFromProperties();
        return targetFps > 0;
    }

    /** Waits after presentation so the next game-loop iteration starts on a tighter deadline. */
    public static void awaitNextFrame() {
        if (!enabled()) return;
        long started = System.nanoTime();
        long deadline;
        synchronized (FramePacingRuntime.class) {
            calls++;
            if (previousCompletionNanos == Long.MIN_VALUE) {
                previousCompletionNanos = started;
                return;
            }
            deadline = previousCompletionNanos + frameNanos;
        }

        if (started >= deadline) {
            synchronized (FramePacingRuntime.class) {
                lateFrames++;
                previousCompletionNanos = started;
            }
            return;
        }

        long parkNanos = Math.max(0L, deadline - started - spinMarginNanos);
        if (parkNanos > 0L) LockSupport.parkNanos(parkNanos);

        long spinStarted = System.nanoTime();
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        long completed = System.nanoTime();
        long waitNanos = completed - started;
        long spinNanos = Math.max(0L, completed - spinStarted);
        long overshootNanos = Math.max(0L, completed - deadline);

        synchronized (FramePacingRuntime.class) {
            waits++;
            totalWaitNanos += waitNanos;
            totalSpinNanos += spinNanos;
            maximumWaitNanos = Math.max(maximumWaitNanos, waitNanos);
            maximumSpinNanos = Math.max(maximumSpinNanos, spinNanos);
            maximumOvershootNanos = Math.max(maximumOvershootNanos, overshootNanos);
            int bin = (int) Math.min(OVERSHOOT_BINS, overshootNanos / OVERSHOOT_BIN_NANOS);
            overshootHistogram[bin]++;
            // Match Fast Rendering's no-catch-up policy: an overshoot moves the next deadline.
            previousCompletionNanos = completed;
        }
    }

    static synchronized void beginSession(int fps, int spinMicros) {
        initialized = true;
        targetFps = validFps(fps) ? fps : 0;
        frameNanos = targetFps == 0 ? 0L : 1_000_000_000L / targetFps;
        int boundedSpin = Math.max(0, Math.min(MAX_SPIN_MICROS, spinMicros));
        spinMarginNanos = boundedSpin * 1_000L;
        resetCounters();
    }

    static synchronized void reset() {
        initialized = false;
        targetFps = 0;
        frameNanos = 0L;
        spinMarginNanos = 0L;
        resetCounters();
    }

    static synchronized Map<String, Object> telemetry() {
        initializeFromProperties();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", targetFps > 0);
        result.put("targetFps", targetFps == 0 ? null : targetFps);
        result.put("targetFrameMicros", targetFps == 0 ? null : frameNanos / 1_000.0);
        result.put("spinMarginMicros", targetFps == 0 ? null : spinMarginNanos / 1_000L);
        result.put("calls", calls);
        result.put("waits", waits);
        result.put("lateFrames", lateFrames);
        result.put("averageWaitMicros", waits == 0L ? null : totalWaitNanos / 1_000.0 / waits);
        result.put("maximumWaitMicros", waits == 0L ? null : maximumWaitNanos / 1_000.0);
        result.put("averageSpinMicros", waits == 0L ? null : totalSpinNanos / 1_000.0 / waits);
        result.put("maximumSpinMicros", waits == 0L ? null : maximumSpinNanos / 1_000.0);
        result.put("p99OvershootMicros", percentileOvershootMicros(990));
        result.put("maximumOvershootMicros", waits == 0L ? null : maximumOvershootNanos / 1_000.0);
        return result;
    }

    static long plannedParkNanos(long now, long deadline, long spinMargin) {
        return Math.max(0L, deadline - now - Math.max(0L, spinMargin));
    }

    private static void initializeFromProperties() {
        if (initialized) return;
        synchronized (FramePacingRuntime.class) {
            if (initialized) return;
            int fps = integerProperty(FPS_PROPERTY, 0);
            int spinMicros = integerProperty(SPIN_MICROS_PROPERTY, DEFAULT_SPIN_MICROS);
            targetFps = validFps(fps) ? fps : 0;
            frameNanos = targetFps == 0 ? 0L : 1_000_000_000L / targetFps;
            spinMarginNanos = Math.max(0, Math.min(MAX_SPIN_MICROS, spinMicros)) * 1_000L;
            resetCounters();
            initialized = true;
        }
    }

    private static int integerProperty(String name, int fallback) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean validFps(int fps) {
        return fps >= MIN_FPS && fps <= MAX_FPS;
    }

    private static void resetCounters() {
        previousCompletionNanos = Long.MIN_VALUE;
        calls = 0L;
        waits = 0L;
        lateFrames = 0L;
        totalWaitNanos = 0L;
        totalSpinNanos = 0L;
        maximumWaitNanos = 0L;
        maximumSpinNanos = 0L;
        maximumOvershootNanos = 0L;
        Arrays.fill(overshootHistogram, 0L);
    }

    private static Double percentileOvershootMicros(int perThousand) {
        if (waits == 0L) return null;
        long rank = Math.max(1L, (waits * perThousand + 999L) / 1_000L);
        long cumulative = 0L;
        for (int i = 0; i < overshootHistogram.length; i++) {
            cumulative += overshootHistogram[i];
            if (cumulative >= rank) {
                long nanos = i == OVERSHOOT_BINS
                        ? OVERSHOOT_BINS * OVERSHOOT_BIN_NANOS
                        : (i + 1L) * OVERSHOOT_BIN_NANOS;
                return nanos / 1_000.0;
            }
        }
        return maximumOvershootNanos / 1_000.0;
    }
}

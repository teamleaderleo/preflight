package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/** Opt-in high-resolution replacement for the exact campaign FPS-cap sleep. */
public final class FrameLimiterPacingRuntime {
    static final String PLAN_ID = "campaign-frame-limiter-precision-v1";
    static final String FPS_PROPERTY = "preflight.framePacing.precisionLimiterFps";
    static final String SPIN_MICROS_PROPERTY = "preflight.framePacing.spinMicros";

    private static final int MIN_FPS = 15;
    private static final int MAX_FPS = 1000;
    private static final int DEFAULT_SPIN_MICROS = 250;
    private static final int MAX_SPIN_MICROS = 2_000;

    private static volatile boolean initialized;
    private static volatile boolean installed;
    private static volatile int targetFps;
    private static volatile long targetFrameNanos;
    private static volatile long spinMarginNanos;

    private static long previousCompletionNanos = Long.MIN_VALUE;
    private static long calls;
    private static long waits;
    private static long lateCadenceCalls;
    private static long interruptedWaits;
    private static long parkCalls;
    private static long requestedMillisTotal;
    private static long deadlineExtensionNanosTotal;
    private static long totalWaitNanos;
    private static long totalSpinNanos;
    private static long totalOvershootNanos;
    private static long maximumWaitNanos;
    private static long maximumSpinNanos;
    private static long maximumOvershootNanos;

    private FrameLimiterPacingRuntime() {
    }

    static boolean enabled() {
        initializeFromProperties();
        return targetFps > 0;
    }

    static synchronized void installed() {
        installed = true;
    }

    /**
     * Preserves vanilla's integer-millisecond wait as a floor and extends it only as needed to
     * reach the configured absolute frame cadence. The configured cadence can therefore slow a
     * mismatched higher game cap, but it cannot shorten the sleep duration vanilla requested.
     */
    public static void sleep(long requestedMillis) throws InterruptedException {
        if (!enabled()) {
            Thread.sleep(requestedMillis);
            return;
        }
        if (requestedMillis < 0L) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        if (Thread.interrupted()) {
            synchronized (FrameLimiterPacingRuntime.class) {
                interruptedWaits++;
            }
            throw new InterruptedException();
        }

        long started = System.nanoTime();
        long previous;
        synchronized (FrameLimiterPacingRuntime.class) {
            calls++;
            requestedMillisTotal += requestedMillis;
            previous = previousCompletionNanos;
        }

        long deadline = deadlineNanos(started, requestedMillis, previous, targetFrameNanos);
        long vanillaFloor = saturatingAdd(started, millisToNanos(requestedMillis));
        long extension = Math.max(0L, deadline - vanillaFloor);
        if (previous != Long.MIN_VALUE
                && saturatingAdd(previous, targetFrameNanos) <= started) {
            synchronized (FrameLimiterPacingRuntime.class) {
                lateCadenceCalls++;
            }
        }

        long localParkCalls = 0L;
        long spinStarted = deadline;
        while (true) {
            long now = System.nanoTime();
            long parkNanos = Math.max(0L, deadline - now - spinMarginNanos);
            if (parkNanos <= 0L) {
                spinStarted = now;
                break;
            }
            LockSupport.parkNanos(parkNanos);
            localParkCalls++;
            if (Thread.interrupted()) {
                synchronized (FrameLimiterPacingRuntime.class) {
                    parkCalls += localParkCalls;
                    interruptedWaits++;
                    previousCompletionNanos = System.nanoTime();
                }
                throw new InterruptedException();
            }
        }

        long completed = spinStarted;
        if (completed < deadline) {
            do {
                Thread.onSpinWait();
                completed = System.nanoTime();
            } while (completed < deadline);
        }

        long waitNanos = Math.max(0L, completed - started);
        long spinNanos = spinStarted < deadline ? completed - spinStarted : 0L;
        long overshootNanos = Math.max(0L, completed - deadline);
        synchronized (FrameLimiterPacingRuntime.class) {
            waits++;
            parkCalls += localParkCalls;
            deadlineExtensionNanosTotal += extension;
            totalWaitNanos += waitNanos;
            totalSpinNanos += spinNanos;
            totalOvershootNanos += overshootNanos;
            maximumWaitNanos = Math.max(maximumWaitNanos, waitNanos);
            maximumSpinNanos = Math.max(maximumSpinNanos, spinNanos);
            maximumOvershootNanos = Math.max(maximumOvershootNanos, overshootNanos);
            // Match Fast Rendering's no-catch-up policy: scheduler delay shifts the next deadline.
            previousCompletionNanos = completed;
        }
    }

    static synchronized void beginSession(int fps, int spinMicros) {
        initialized = true;
        installed = false;
        configure(fps, spinMicros);
    }

    static synchronized void reset() {
        initialized = false;
        installed = false;
        targetFps = 0;
        targetFrameNanos = 0L;
        spinMarginNanos = 0L;
        resetCounters();
    }

    static synchronized Map<String, Object> telemetry() {
        initializeFromProperties();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("requested", targetFps > 0);
        result.put("installed", installed);
        result.put("active", targetFps > 0 && installed);
        result.put("targetFps", targetFps == 0 ? null : targetFps);
        result.put("targetFrameMicros", targetFps == 0 ? null : targetFrameNanos / 1_000.0);
        result.put("spinMarginMicros", targetFps == 0 ? null : spinMarginNanos / 1_000L);
        result.put("calls", calls);
        result.put("waits", waits);
        result.put("lateCadenceCalls", lateCadenceCalls);
        result.put("interruptedWaits", interruptedWaits);
        result.put("parkCalls", parkCalls);
        result.put("requestedMillisTotal", requestedMillisTotal);
        result.put("averageRequestedMillis", calls == 0L ? null : requestedMillisTotal / (double) calls);
        result.put("averageDeadlineExtensionMicros", waits == 0L
                ? null : deadlineExtensionNanosTotal / 1_000.0 / waits);
        result.put("averageWaitMicros", waits == 0L ? null : totalWaitNanos / 1_000.0 / waits);
        result.put("maximumWaitMicros", waits == 0L ? null : maximumWaitNanos / 1_000.0);
        result.put("averageSpinMicros", waits == 0L ? null : totalSpinNanos / 1_000.0 / waits);
        result.put("maximumSpinMicros", waits == 0L ? null : maximumSpinNanos / 1_000.0);
        result.put("averageOvershootMicros", waits == 0L
                ? null : totalOvershootNanos / 1_000.0 / waits);
        result.put("maximumOvershootMicros", waits == 0L ? null : maximumOvershootNanos / 1_000.0);
        return result;
    }

    static long deadlineNanos(
            long startedNanos, long requestedMillis, long previousCompletion, long frameNanos) {
        long vanillaFloor = saturatingAdd(startedNanos, millisToNanos(requestedMillis));
        if (previousCompletion == Long.MIN_VALUE || frameNanos <= 0L) return vanillaFloor;
        long cadence = saturatingAdd(previousCompletion, frameNanos);
        return Math.max(vanillaFloor, cadence);
    }

    private static void initializeFromProperties() {
        if (initialized) return;
        synchronized (FrameLimiterPacingRuntime.class) {
            if (initialized) return;
            configure(integerProperty(FPS_PROPERTY, 0),
                    integerProperty(SPIN_MICROS_PROPERTY, DEFAULT_SPIN_MICROS));
            initialized = true;
        }
    }

    private static void configure(int fps, int spinMicros) {
        targetFps = fps >= MIN_FPS && fps <= MAX_FPS ? fps : 0;
        targetFrameNanos = targetFps == 0 ? 0L : 1_000_000_000L / targetFps;
        spinMarginNanos = Math.max(0, Math.min(MAX_SPIN_MICROS, spinMicros)) * 1_000L;
        resetCounters();
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

    private static long millisToNanos(long millis) {
        if (millis <= 0L) return 0L;
        if (millis >= Long.MAX_VALUE / 1_000_000L) return Long.MAX_VALUE;
        return millis * 1_000_000L;
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L) return left;
        if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static void resetCounters() {
        previousCompletionNanos = Long.MIN_VALUE;
        calls = 0L;
        waits = 0L;
        lateCadenceCalls = 0L;
        interruptedWaits = 0L;
        parkCalls = 0L;
        requestedMillisTotal = 0L;
        deadlineExtensionNanosTotal = 0L;
        totalWaitNanos = 0L;
        totalSpinNanos = 0L;
        totalOvershootNanos = 0L;
        maximumWaitNanos = 0L;
        maximumSpinNanos = 0L;
        maximumOvershootNanos = 0L;
    }
}

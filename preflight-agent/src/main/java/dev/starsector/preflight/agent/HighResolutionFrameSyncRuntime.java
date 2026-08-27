package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** High-resolution replacement for the vanilla frame loop's millisecond-truncated final wait. */
public final class HighResolutionFrameSyncRuntime {
    static final String ENABLED_PROPERTY = "preflight.frameSync";
    static final String SPIN_MARGIN_PROPERTY = "preflight.frameSync.spinMarginNanos";
    static final String REPORT_PROPERTY = "preflight.frameSync.report";

    private static final long DEFAULT_SPIN_MARGIN_NANOS = 2_000_000L;
    private static final long MAX_SPIN_MARGIN_NANOS = 10_000_000L;
    private static final float MAX_PRECISE_WAIT_SECONDS = 1.0f;

    private static volatile boolean initialized;
    private static volatile boolean enabled;
    private static volatile long spinMarginNanos = DEFAULT_SPIN_MARGIN_NANOS;
    private static volatile Path reportPath;
    private static boolean shutdownHookInstalled;
    private static boolean installed;

    private static long calls;
    private static long preciseCalls;
    private static long fallbackCalls;
    private static long interruptedCalls;
    private static long requestedNanos;
    private static long waitedNanos;
    private static long requestedSleepMillis;
    private static long actualCoarseSleepNanos;
    private static long actualSpinNanos;
    private static long overshootNanos;
    private static long maximumOvershootNanos;
    private static long maximumWaitNanos;

    private HighResolutionFrameSyncRuntime() {
    }

    static boolean enabled() {
        initializeFromProperties();
        return enabled;
    }

    static void installed() {
        installed = true;
    }

    /**
     * Waits for the original loop's already-computed remaining interval.
     *
     * <p>The disabled and out-of-contract paths reproduce the old float-to-int millisecond
     * conversion exactly. The experimental path keeps the sub-millisecond remainder, sleeps for the
     * coarse portion, then spins through the final configured margin, following starsector-render's
     * frame-pacing approach while retaining an immediate fallback.
     */
    public static void sleepSeconds(float remainingSeconds) throws InterruptedException {
        initializeFromProperties();
        calls++;
        if (!enabled || !Float.isFinite(remainingSeconds)
                || remainingSeconds <= 0f || remainingSeconds > MAX_PRECISE_WAIT_SECONDS) {
            fallbackCalls++;
            Thread.sleep(originalMillis(remainingSeconds));
            return;
        }

        long requested = preciseNanos(remainingSeconds);
        if (requested <= 0L) {
            fallbackCalls++;
            Thread.sleep(originalMillis(remainingSeconds));
            return;
        }

        preciseCalls++;
        requestedNanos += requested;
        long start = System.nanoTime();
        long deadline = start + requested;
        if (deadline < start) {
            fallbackCalls++;
            preciseCalls--;
            requestedNanos -= requested;
            Thread.sleep(originalMillis(remainingSeconds));
            return;
        }

        long coarseTarget = Math.max(0L, requested - spinMarginNanos);
        long sleepMillis = coarseTarget / 1_000_000L;
        long afterSleep = start;
        if (sleepMillis > 0L) {
            requestedSleepMillis += sleepMillis;
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException interrupted) {
                interruptedCalls++;
                throw interrupted;
            }
            afterSleep = System.nanoTime();
            actualCoarseSleepNanos += Math.max(0L, afterSleep - start);
        }

        long spinStart = afterSleep;
        long now = spinStart;
        while (now < deadline) {
            Thread.onSpinWait();
            now = System.nanoTime();
        }
        long spin = Math.max(0L, now - spinStart);
        long waited = Math.max(0L, now - start);
        long overshoot = Math.max(0L, waited - requested);
        actualSpinNanos += spin;
        waitedNanos += waited;
        overshootNanos += overshoot;
        maximumOvershootNanos = Math.max(maximumOvershootNanos, overshoot);
        maximumWaitNanos = Math.max(maximumWaitNanos, waited);
    }

    static long originalMillis(float remainingSeconds) {
        return (long) (int) (remainingSeconds * 1000f);
    }

    static long preciseNanos(float remainingSeconds) {
        return (long) (remainingSeconds * 1_000_000_000.0d);
    }

    static synchronized Map<String, Object> telemetry() {
        initializeFromProperties();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("installed", installed);
        values.put("spinMarginNanos", spinMarginNanos);
        values.put("calls", calls);
        values.put("preciseCalls", preciseCalls);
        values.put("fallbackCalls", fallbackCalls);
        values.put("interruptedCalls", interruptedCalls);
        values.put("requestedNanos", requestedNanos);
        values.put("waitedNanos", waitedNanos);
        values.put("requestedSleepMillis", requestedSleepMillis);
        values.put("actualCoarseSleepNanos", actualCoarseSleepNanos);
        values.put("actualSpinNanos", actualSpinNanos);
        values.put("overshootNanos", overshootNanos);
        values.put("maximumOvershootNanos", maximumOvershootNanos);
        values.put("maximumWaitNanos", maximumWaitNanos);
        values.put("reportPath", reportPath == null ? "" : reportPath.toString());
        return values;
    }

    static synchronized void beginSessionForTest(boolean requested, long marginNanos) {
        configure(requested, marginNanos, null, false);
    }

    static synchronized void resetForTest() {
        initialized = false;
        enabled = false;
        spinMarginNanos = DEFAULT_SPIN_MARGIN_NANOS;
        reportPath = null;
        installed = false;
        clearCounters();
    }

    private static void initializeFromProperties() {
        if (initialized) {
            return;
        }
        synchronized (HighResolutionFrameSyncRuntime.class) {
            if (initialized) {
                return;
            }
            boolean requested = Boolean.getBoolean(ENABLED_PROPERTY);
            long margin = readMargin(System.getProperty(SPIN_MARGIN_PROPERTY));
            Path report = readPath(System.getProperty(REPORT_PROPERTY));
            configure(requested, margin, report, true);
        }
    }

    private static void configure(boolean requested, long marginNanos, Path report, boolean hook) {
        enabled = requested;
        spinMarginNanos = Math.max(0L, Math.min(MAX_SPIN_MARGIN_NANOS, marginNanos));
        reportPath = report;
        installed = false;
        clearCounters();
        initialized = true;
        if (hook && report != null && !shutdownHookInstalled) {
            shutdownHookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(
                    HighResolutionFrameSyncRuntime::writeReport,
                    "Preflight-FrameSync-Report"));
        }
    }

    private static void clearCounters() {
        calls = 0L;
        preciseCalls = 0L;
        fallbackCalls = 0L;
        interruptedCalls = 0L;
        requestedNanos = 0L;
        waitedNanos = 0L;
        requestedSleepMillis = 0L;
        actualCoarseSleepNanos = 0L;
        actualSpinNanos = 0L;
        overshootNanos = 0L;
        maximumOvershootNanos = 0L;
        maximumWaitNanos = 0L;
    }

    private static long readMargin(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SPIN_MARGIN_NANOS;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return DEFAULT_SPIN_MARGIN_NANOS;
        }
    }

    private static Path readPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Path.of(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private static void writeReport() {
        Path destination = reportPath;
        if (destination == null) {
            return;
        }
        try {
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    destination,
                    Json.object(telemetry()) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException ignored) {
            // Diagnostic output is optional; frame pacing must survive report failures.
        }
    }
}

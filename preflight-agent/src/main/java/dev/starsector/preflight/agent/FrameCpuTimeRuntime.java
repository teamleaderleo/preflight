package dev.starsector.preflight.agent;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Estimates main-thread active CPU versus off-CPU time between display boundaries. */
public final class FrameCpuTimeRuntime {
    static final String PLAN_ID = "main-thread-frame-cpu-time-v1";

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final long HISTOGRAM_BIN_NANOS = 100_000L;
    private static final int HISTOGRAM_REGULAR_BINS = 20_000;

    private static volatile boolean enabled;
    private static volatile boolean observedActive = true;
    private static volatile boolean focusBreak;
    private static boolean cpuTimeAvailable;
    private static long boundaries;
    private static long samples;
    private static long inactiveIntervals;
    private static long invalidIntervals;
    private static long measurementSamples;
    private static long measurementTotalNanos;
    private static long measurementMaximumNanos;
    private static long firstBoundaryNanos = Long.MIN_VALUE;
    private static long lastBoundaryNanos = Long.MIN_VALUE;
    private static long lastCpuNanos = Long.MIN_VALUE;
    private static boolean lastBoundaryActive = true;
    private static long acceptedFrameTotalNanos;
    private static final Distribution cpuActive = new Distribution();
    private static final Distribution offCpuApprox = new Distribution();

    private FrameCpuTimeRuntime() {
    }

    static synchronized void beginSession(boolean requested) {
        enabled = requested;
        cpuTimeAvailable = requested
                && THREADS.isCurrentThreadCpuTimeSupported()
                && THREADS.isThreadCpuTimeEnabled();
        observedActive = true;
        focusBreak = false;
        boundaries = 0L;
        samples = 0L;
        inactiveIntervals = 0L;
        invalidIntervals = 0L;
        measurementSamples = 0L;
        measurementTotalNanos = 0L;
        measurementMaximumNanos = 0L;
        firstBoundaryNanos = Long.MIN_VALUE;
        lastBoundaryNanos = Long.MIN_VALUE;
        lastCpuNanos = Long.MIN_VALUE;
        lastBoundaryActive = true;
        acceptedFrameTotalNanos = 0L;
        cpuActive.reset();
        offCpuApprox.reset();
    }

    public static void observeActive(boolean active) {
        if (!enabled) return;
        observedActive = active;
        if (!active) focusBreak = true;
    }

    public static void boundary() {
        if (!enabled) return;
        long measurementStarted = System.nanoTime();
        long cpuNow = currentThreadCpuTime();
        long now = System.nanoTime();
        try {
            recordBoundary(now, cpuNow);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Diagnostics live on the display loop and must stay contained.
        } finally {
            recordMeasurementOverhead(System.nanoTime() - measurementStarted);
        }
    }

    static synchronized void recordBoundary(long now, long cpuNow) {
        boundaries++;
        boolean active = observedActive;
        boolean crossedFocusBreak = focusBreak;
        focusBreak = false;
        if (firstBoundaryNanos == Long.MIN_VALUE) {
            firstBoundaryNanos = now;
            lastBoundaryNanos = now;
            lastCpuNanos = cpuNow;
            lastBoundaryActive = active;
            return;
        }

        long wallDuration = now - lastBoundaryNanos;
        long cpuDuration = cpuNow < 0L || lastCpuNanos < 0L ? -1L : cpuNow - lastCpuNanos;
        lastBoundaryNanos = now;
        lastCpuNanos = cpuNow;
        if (wallDuration <= 0L || cpuDuration < 0L) {
            invalidIntervals++;
            lastBoundaryActive = active;
            return;
        }
        if (crossedFocusBreak || !lastBoundaryActive || !active) {
            inactiveIntervals++;
            lastBoundaryActive = active;
            return;
        }

        long boundedCpu = Math.min(cpuDuration, wallDuration);
        long offCpu = Math.max(0L, wallDuration - boundedCpu);
        long endOffset = now - firstBoundaryNanos;
        samples++;
        acceptedFrameTotalNanos += wallDuration;
        cpuActive.record(boundedCpu, endOffset);
        offCpuApprox.record(offCpu, endOffset);
        lastBoundaryActive = active;
    }

    static synchronized void recordMeasurementOverhead(long elapsedNanos) {
        if (elapsedNanos < 0L) return;
        measurementSamples++;
        measurementTotalNanos += elapsedNanos;
        measurementMaximumNanos = Math.max(measurementMaximumNanos, elapsedNanos);
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("cpuTimeAvailable", cpuTimeAvailable);
        result.put("boundaries", boundaries);
        result.put("samples", samples);
        result.put("inactiveIntervalsDropped", inactiveIntervals);
        result.put("invalidIntervalsDropped", invalidIntervals);
        Map<String, Object> measurement = new LinkedHashMap<>();
        measurement.put("samples", measurementSamples);
        measurement.put("averageMicros", measurementSamples == 0L
                ? null
                : measurementTotalNanos / 1_000.0 / measurementSamples);
        measurement.put("maximumMicros", measurementSamples == 0L
                ? null
                : measurementMaximumNanos / 1_000.0);
        result.put("measurementOverhead", measurement);
        result.put("cpuActive", cpuActive.toSpanMap(samples, acceptedFrameTotalNanos));
        result.put("offCpuApprox", offCpuApprox.toSpanMap(samples, acceptedFrameTotalNanos));
        return result;
    }

    static synchronized void reset() {
        beginSession(false);
    }

    private static long currentThreadCpuTime() {
        if (!cpuTimeAvailable) return -1L;
        try {
            return THREADS.getCurrentThreadCpuTime();
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private static final class Distribution {
        private final long[] histogram = new long[HISTOGRAM_REGULAR_BINS + 1];
        private long count;
        private long totalNanos;
        private long minimumNanos;
        private long maximumNanos;

        void reset() {
            Arrays.fill(histogram, 0L);
            count = 0L;
            totalNanos = 0L;
            minimumNanos = Long.MAX_VALUE;
            maximumNanos = 0L;
        }

        void record(long durationNanos, long ignoredEndOffsetNanos) {
            count++;
            totalNanos += durationNanos;
            minimumNanos = Math.min(minimumNanos, durationNanos);
            maximumNanos = Math.max(maximumNanos, durationNanos);
            int bin = durationNanos == 0L
                    ? 0
                    : (int) Math.min(HISTOGRAM_REGULAR_BINS,
                            (durationNanos - 1L) / HISTOGRAM_BIN_NANOS);
            histogram[bin]++;
        }

        Map<String, Object> toSpanMap(long frameCount, long frameTotalNanos) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("samples", count);
            result.put("sampleCoveragePercent", frameCount == 0L
                    ? null
                    : round(100.0 * count / frameCount));
            result.put("meanMicros", count == 0L ? null : totalNanos / 1_000.0 / count);
            result.put("minimumMicros", count == 0L ? null : minimumNanos / 1_000L);
            result.put("maximumMicros", count == 0L ? null : maximumNanos / 1_000L);
            result.put("p50Micros", percentile(500));
            result.put("p95Micros", percentile(950));
            result.put("p99Micros", percentile(990));
            result.put("p999Micros", percentile(999));
            result.put("meanFrameSharePercent", count == 0L || frameCount == 0L
                            || frameTotalNanos <= 0L
                    ? null
                    : round(100.0 * (totalNanos / (double) count)
                            / (frameTotalNanos / (double) frameCount)));
            return result;
        }

        private Long percentile(int perThousand) {
            if (count == 0L) return null;
            long rank = Math.max(1L, (count * perThousand + 999L) / 1_000L);
            long cumulative = 0L;
            for (int i = 0; i < histogram.length; i++) {
                cumulative += histogram[i];
                if (cumulative >= rank) {
                    return (i == HISTOGRAM_REGULAR_BINS
                            ? (long) HISTOGRAM_REGULAR_BINS
                            : i + 1L) * HISTOGRAM_BIN_NANOS / 1_000L;
                }
            }
            return maximumNanos / 1_000L;
        }

        private static double round(double value) {
            return Math.round(value * 100.0) / 100.0;
        }
    }
}

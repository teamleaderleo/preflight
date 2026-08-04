package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Low-allocation frame-pacing telemetry at LWJGL's display-update boundary. */
public final class FrameTimeRuntime {
    static final String PLAN_ID = "lwjgl-display-frame-time-probe-v1";

    private static final long HISTOGRAM_BIN_NANOS = 100_000L;
    private static final int HISTOGRAM_REGULAR_BINS = 20_000;
    private static final int WORST_FRAME_LIMIT = 128;
    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_CAMPAIGN = 1;
    private static final int STATE_COMBAT = 2;

    private static volatile boolean enabled;
    private static volatile boolean observedActive = true;
    private static volatile boolean focusBreak;
    private static volatile int observedState;
    private static boolean installed;
    private static boolean startupComplete;
    private static long boundaries;
    private static volatile long focusObservations;
    private static long inactiveIntervals;
    private static long invalidIntervals;
    private static long stateTransitionIntervals;
    private static long firstBoundaryNanos = Long.MIN_VALUE;
    private static long firstBoundaryEpochMillis = -1L;
    private static long lastBoundaryNanos = Long.MIN_VALUE;
    private static boolean lastBoundaryActive = true;
    private static int lastBoundaryState;
    private static final Distribution allActive = new Distribution();
    private static final Distribution postStartupActive = new Distribution();
    private static final Distribution campaignActive = new Distribution();
    private static final Distribution combatActive = new Distribution();

    private FrameTimeRuntime() {
    }

    static synchronized void beginSession(boolean requested) {
        enabled = requested;
        installed = false;
        startupComplete = false;
        boundaries = 0L;
        focusObservations = 0L;
        inactiveIntervals = 0L;
        invalidIntervals = 0L;
        stateTransitionIntervals = 0L;
        firstBoundaryNanos = Long.MIN_VALUE;
        firstBoundaryEpochMillis = -1L;
        lastBoundaryNanos = Long.MIN_VALUE;
        lastBoundaryActive = true;
        lastBoundaryState = STATE_UNKNOWN;
        observedActive = true;
        focusBreak = false;
        observedState = STATE_UNKNOWN;
        allActive.reset();
        postStartupActive.reset();
        campaignActive.reset();
        combatActive.reset();
    }

    static synchronized void installed() {
        installed = true;
    }

    static boolean enabled() {
        return enabled;
    }

    /** Observes the focus result Starsector already requested; it performs no additional OS query. */
    public static void observeActive(boolean active) {
        if (!enabled) return;
        observedActive = active;
        focusObservations++;
        if (!active) focusBreak = true;
    }

    /** Called from the reviewed campaign loop before the display boundary. */
    public static void observeCampaign() {
        if (enabled) observedState = STATE_CAMPAIGN;
    }

    /** Called from the reviewed combat-engine loop before the display boundary. */
    public static void observeCombat() {
        if (enabled) observedState = STATE_COMBAT;
    }

    /** Marks one completed game-loop/display-update boundary. */
    public static void boundary() {
        if (!enabled) return;
        try {
            recordBoundary(System.nanoTime());
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // This is woven into the display loop. Diagnostics must never affect the game.
        }
    }

    static synchronized void markStartupComplete() {
        if (enabled) startupComplete = true;
    }

    static synchronized void recordBoundary(long now) {
        boundaries++;
        boolean active = observedActive;
        int state = observedState;
        // Campaign/combat observers run once in their respective game-loop advance. Treat that
        // observation as a pulse for this display interval: otherwise menu, loading, and refit
        // frames after leaving a state inherit its last value indefinitely.
        observedState = STATE_UNKNOWN;
        boolean crossedFocusBreak = focusBreak;
        focusBreak = false;
        if (firstBoundaryNanos == Long.MIN_VALUE) {
            firstBoundaryNanos = now;
            firstBoundaryEpochMillis = System.currentTimeMillis();
            lastBoundaryNanos = now;
            lastBoundaryActive = active;
            lastBoundaryState = state;
            return;
        }

        long duration = now - lastBoundaryNanos;
        lastBoundaryNanos = now;
        if (duration <= 0L) {
            invalidIntervals++;
            lastBoundaryActive = active;
            lastBoundaryState = state;
            return;
        }
        if (crossedFocusBreak || !lastBoundaryActive || !active) {
            inactiveIntervals++;
            lastBoundaryActive = active;
            lastBoundaryState = state;
            return;
        }

        long endOffset = now - firstBoundaryNanos;
        allActive.record(duration, endOffset);
        if (startupComplete) postStartupActive.record(duration, endOffset);
        if (state != lastBoundaryState) {
            stateTransitionIntervals++;
        } else if (state == STATE_CAMPAIGN) {
            campaignActive.record(duration, endOffset);
        } else if (state == STATE_COMBAT) {
            combatActive.record(duration, endOffset);
        }
        lastBoundaryActive = active;
        lastBoundaryState = state;
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("installed", installed);
        result.put("startupComplete", startupComplete);
        result.put("boundaries", boundaries);
        result.put("focusObservations", focusObservations);
        result.put("inactiveIntervalsDropped", inactiveIntervals);
        result.put("invalidIntervalsDropped", invalidIntervals);
        result.put("stateTransitionIntervalsDropped", stateTransitionIntervals);
        result.put("firstBoundaryEpochMillis", firstBoundaryEpochMillis);
        result.put("histogramBinMicros", HISTOGRAM_BIN_NANOS / 1_000L);
        result.put("histogramOverflowMicros",
                HISTOGRAM_REGULAR_BINS * HISTOGRAM_BIN_NANOS / 1_000L);
        result.put("allActive", allActive.toMap(firstBoundaryEpochMillis));
        result.put("postStartupActive", postStartupActive.toMap(firstBoundaryEpochMillis));
        result.put("campaignActive", campaignActive.toMap(firstBoundaryEpochMillis));
        result.put("combatActive", combatActive.toMap(firstBoundaryEpochMillis));
        return result;
    }

    static synchronized void reset() {
        beginSession(false);
    }

    private static final class Distribution {
        private final long[] histogram = new long[HISTOGRAM_REGULAR_BINS + 1];
        private final long[] worstDurations = new long[WORST_FRAME_LIMIT];
        private final long[] worstEndOffsets = new long[WORST_FRAME_LIMIT];
        private long count;
        private long totalNanos;
        private long minimumNanos;
        private long maximumNanos;
        private long over16Millis;
        private long over33Millis;
        private long over50Millis;
        private long over100Millis;
        private long over250Millis;
        private long over1000Millis;
        private int worstCount;
        private int shortestWorst;

        void reset() {
            Arrays.fill(histogram, 0L);
            Arrays.fill(worstDurations, 0L);
            Arrays.fill(worstEndOffsets, 0L);
            count = 0L;
            totalNanos = 0L;
            minimumNanos = Long.MAX_VALUE;
            maximumNanos = 0L;
            over16Millis = 0L;
            over33Millis = 0L;
            over50Millis = 0L;
            over100Millis = 0L;
            over250Millis = 0L;
            over1000Millis = 0L;
            worstCount = 0;
            shortestWorst = 0;
        }

        void record(long durationNanos, long endOffsetNanos) {
            count++;
            totalNanos += durationNanos;
            minimumNanos = Math.min(minimumNanos, durationNanos);
            maximumNanos = Math.max(maximumNanos, durationNanos);
            int bin = (int) Math.min(HISTOGRAM_REGULAR_BINS,
                    (durationNanos - 1L) / HISTOGRAM_BIN_NANOS);
            histogram[bin]++;
            if (durationNanos > 16_666_667L) over16Millis++;
            if (durationNanos > 33_333_333L) over33Millis++;
            if (durationNanos > 50_000_000L) over50Millis++;
            if (durationNanos > 100_000_000L) over100Millis++;
            if (durationNanos > 250_000_000L) over250Millis++;
            if (durationNanos > 1_000_000_000L) over1000Millis++;
            retainWorst(durationNanos, endOffsetNanos);
        }

        private void retainWorst(long durationNanos, long endOffsetNanos) {
            if (worstCount < WORST_FRAME_LIMIT) {
                worstDurations[worstCount] = durationNanos;
                worstEndOffsets[worstCount] = endOffsetNanos;
                worstCount++;
                recomputeShortestWorst();
                return;
            }
            if (durationNanos <= worstDurations[shortestWorst]) return;
            worstDurations[shortestWorst] = durationNanos;
            worstEndOffsets[shortestWorst] = endOffsetNanos;
            recomputeShortestWorst();
        }

        private void recomputeShortestWorst() {
            if (worstCount == 0) return;
            int shortest = 0;
            for (int i = 1; i < worstCount; i++) {
                if (worstDurations[i] < worstDurations[shortest]) shortest = i;
            }
            shortestWorst = shortest;
        }

        Map<String, Object> toMap(long originEpochMillis) {
            Map<String, Object> result = new LinkedHashMap<>();
            Double meanMicros = count == 0L ? null : totalNanos / 1_000.0 / count;
            Long p50Micros = percentile(500);
            Long p95Micros = percentile(950);
            Long p99Micros = percentile(990);
            Long p999Micros = percentile(999);
            result.put("frames", count);
            result.put("meanMicros", meanMicros);
            result.put("minimumMicros", count == 0L ? null : minimumNanos / 1_000L);
            result.put("maximumMicros", count == 0L ? null : maximumNanos / 1_000L);
            result.put("p50Micros", p50Micros);
            result.put("p95Micros", p95Micros);
            result.put("p99Micros", p99Micros);
            result.put("p999Micros", p999Micros);
            result.put("averageFps", fps(meanMicros));
            result.put("medianFps", fps(p50Micros));
            result.put("onePercentLowFps", fps(p99Micros));
            result.put("pointOnePercentLowFps", fps(p999Micros));
            result.put("framesMeeting60FpsPercent", percentage(count - over16Millis));
            result.put("framesMeeting30FpsPercent", percentage(count - over33Millis));
            result.put("over16_67Millis", over16Millis);
            result.put("over33_33Millis", over33Millis);
            result.put("over50Millis", over50Millis);
            result.put("over100Millis", over100Millis);
            result.put("over250Millis", over250Millis);
            result.put("over1000Millis", over1000Millis);
            result.put("worstFrames", worstFrames(originEpochMillis));
            return result;
        }

        private Double fps(Number micros) {
            if (micros == null || micros.doubleValue() <= 0.0) return null;
            return round(1_000_000.0 / micros.doubleValue());
        }

        private Double percentage(long matching) {
            return count == 0L ? null : round(100.0 * matching / count);
        }

        private double round(double value) {
            return Math.round(value * 100.0) / 100.0;
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

        private List<Map<String, Object>> worstFrames(long originEpochMillis) {
            List<Frame> frames = new ArrayList<>(worstCount);
            for (int i = 0; i < worstCount; i++) {
                frames.add(new Frame(worstDurations[i], worstEndOffsets[i]));
            }
            frames.sort(Comparator.comparingLong(Frame::durationNanos).reversed());
            List<Map<String, Object>> result = new ArrayList<>(frames.size());
            for (Frame frame : frames) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("durationMicros", frame.durationNanos / 1_000L);
                value.put("endOffsetMillis", frame.endOffsetNanos / 1_000_000.0);
                value.put("endEpochMillis", originEpochMillis < 0L ? null
                        : originEpochMillis + frame.endOffsetNanos / 1_000_000L);
                result.add(value);
            }
            return result;
        }
    }

    private record Frame(long durationNanos, long endOffsetNanos) {
    }
}

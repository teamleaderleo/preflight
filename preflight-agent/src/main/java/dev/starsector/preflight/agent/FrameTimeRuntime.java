package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Low-allocation frame-pacing telemetry at LWJGL's display-update boundary. */
public final class FrameTimeRuntime {
    static final String PLAN_ID = "lwjgl-display-frame-time-and-presentation-v2";
    static final String FORCE_VSYNC_OFF_PROPERTY = "preflight.framePacing.forceVsyncOff";

    private static final long HISTOGRAM_BIN_NANOS = 100_000L;
    private static final int HISTOGRAM_REGULAR_BINS = 20_000;
    private static final int WORST_FRAME_LIMIT = 128;
    private static final long CAMPAIGN_WARMUP_NANOS = 30_000_000_000L;
    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_CAMPAIGN = 1;
    private static final int STATE_COMBAT = 2;
    private static final int PAUSE_UNKNOWN = 0;
    private static final int PAUSE_PAUSED = 1;
    private static final int PAUSE_UNPAUSED = 2;

    private static volatile boolean enabled;
    private static volatile boolean smoothFramePacing;
    private static volatile boolean observedActive = true;
    private static volatile boolean focusBreak;
    private static volatile int observedState;
    private static volatile int observedCampaignPause;
    private static boolean installed;
    private static boolean startupComplete;
    private static boolean startupTransitionPending;
    private static boolean mainMenuInteractive;
    private static boolean interactiveTransitionPending;
    private static long boundaries;
    private static volatile long focusObservations;
    private static long inactiveIntervals;
    private static long invalidIntervals;
    private static long stateTransitionIntervals;
    private static long startupTransitionIntervals;
    private static long interactiveTransitionIntervals;
    private static long campaignPauseTransitionIntervals;
    private static long campaignPauseUnknownIntervals;
    private static long measurementSamples;
    private static long measurementTotalNanos;
    private static long measurementMaximumNanos;
    private static volatile long vsyncRequests;
    private static volatile long vsyncEnabledRequests;
    private static volatile long vsyncRequestsForcedOff;
    private static long firstBoundaryNanos = Long.MIN_VALUE;
    private static long firstBoundaryEpochMillis = -1L;
    private static long firstCampaignBoundaryNanos = Long.MIN_VALUE;
    private static long lastBoundaryNanos = Long.MIN_VALUE;
    private static boolean lastBoundaryActive = true;
    private static int lastBoundaryState;
    private static int lastBoundaryCampaignPause;
    private static boolean measurementWindowActive;
    private static int measurementWindowState;
    private static long swapStartedNanos = Long.MIN_VALUE;
    private static long swapCompletedNanos = Long.MIN_VALUE;
    private static long messagesStartedNanos = Long.MIN_VALUE;
    private static long messagesCompletedNanos = Long.MIN_VALUE;
    private static final Distribution allActive = new Distribution();
    private static final Distribution postStartupActive = new Distribution();
    private static final Distribution postInteractiveActive = new Distribution();
    private static final Distribution campaignActive = new Distribution();
    private static final Distribution campaignFirst30SecondsActive = new Distribution();
    private static final Distribution campaignAfter30SecondsActive = new Distribution();
    private static final Distribution campaignPausedActive = new Distribution();
    private static final Distribution campaignPausedAfter30SecondsActive = new Distribution();
    private static final Distribution campaignUnpausedActive = new Distribution();
    private static final Distribution campaignUnpausedAfter30SecondsActive = new Distribution();
    private static final Distribution combatActive = new Distribution();
    private static final Distribution combatAfterCampaignActive = new Distribution();
    private static final Distribution measurementWindow = new Distribution();
    private static final DisplayPhases allActivePhases = new DisplayPhases();
    private static final DisplayPhases campaignActivePhases = new DisplayPhases();
    private static final DisplayPhases campaignAfter30SecondsActivePhases = new DisplayPhases();

    private FrameTimeRuntime() {
    }

    static synchronized void beginSession(boolean requested) {
        beginSession(requested, false);
    }

    static synchronized void beginSession(boolean telemetryRequested, boolean smoothRequested) {
        enabled = telemetryRequested;
        smoothFramePacing = smoothRequested;
        installed = false;
        startupComplete = false;
        startupTransitionPending = false;
        mainMenuInteractive = false;
        interactiveTransitionPending = false;
        boundaries = 0L;
        focusObservations = 0L;
        inactiveIntervals = 0L;
        invalidIntervals = 0L;
        stateTransitionIntervals = 0L;
        startupTransitionIntervals = 0L;
        interactiveTransitionIntervals = 0L;
        campaignPauseTransitionIntervals = 0L;
        campaignPauseUnknownIntervals = 0L;
        measurementSamples = 0L;
        measurementTotalNanos = 0L;
        measurementMaximumNanos = 0L;
        vsyncRequests = 0L;
        vsyncEnabledRequests = 0L;
        vsyncRequestsForcedOff = 0L;
        firstBoundaryNanos = Long.MIN_VALUE;
        firstBoundaryEpochMillis = -1L;
        firstCampaignBoundaryNanos = Long.MIN_VALUE;
        lastBoundaryNanos = Long.MIN_VALUE;
        lastBoundaryActive = true;
        lastBoundaryState = STATE_UNKNOWN;
        lastBoundaryCampaignPause = PAUSE_UNKNOWN;
        measurementWindowActive = false;
        measurementWindowState = STATE_UNKNOWN;
        resetDisplayPhaseTimestamps();
        observedActive = true;
        focusBreak = false;
        observedState = STATE_UNKNOWN;
        observedCampaignPause = PAUSE_UNKNOWN;
        allActive.reset();
        postStartupActive.reset();
        postInteractiveActive.reset();
        campaignActive.reset();
        campaignFirst30SecondsActive.reset();
        campaignAfter30SecondsActive.reset();
        campaignPausedActive.reset();
        campaignPausedAfter30SecondsActive.reset();
        campaignUnpausedActive.reset();
        campaignUnpausedAfter30SecondsActive.reset();
        combatActive.reset();
        combatAfterCampaignActive.reset();
        measurementWindow.reset();
        allActivePhases.reset();
        campaignActivePhases.reset();
        campaignAfter30SecondsActivePhases.reset();
    }

    static synchronized void installed() {
        installed = true;
    }

    static boolean enabled() {
        return enabled;
    }

    static boolean planEnabled() {
        return enabled || smoothFramePacing;
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
        RuntimeSemanticState.campaignReady();
        if (enabled) observedState = STATE_CAMPAIGN;
    }

    /** Records the pause state already owned by the exact reviewed campaign engine. */
    public static void observeCampaignPaused(boolean paused) {
        if (enabled) observedCampaignPause = paused ? PAUSE_PAUSED : PAUSE_UNPAUSED;
    }

    /** Called from the reviewed combat-engine loop before the display boundary. */
    public static void observeCombat() {
        RuntimeSemanticState.combatReady();
        if (enabled) observedState = STATE_COMBAT;
    }

    /** Starts a clean steady-state window after smoke-only combat setup has settled. */
    static synchronized void beginCombatMeasurementWindow() {
        if (!enabled) throw new IllegalStateException("frame-time-telemetry-is-disabled");
        measurementWindow.reset();
        measurementWindowState = STATE_COMBAT;
        measurementWindowActive = true;
    }

    /** Timestamp immediately before LWJGL hands the rendered frame to the native presentation path. */
    public static void beforeSwap() {
        if (enabled) recordSwapStarted(System.nanoTime());
    }

    /** Timestamp immediately after the native buffer swap returns. */
    public static void afterSwap() {
        if (enabled) recordSwapCompleted(System.nanoTime());
    }

    /** Timestamp immediately before LWJGL processes native window and input messages. */
    public static void beforeMessages() {
        if (enabled) recordMessagesStarted(System.nanoTime());
    }

    /** Timestamp immediately after LWJGL finishes processing native window and input messages. */
    public static void afterMessages() {
        if (enabled) recordMessagesCompleted(System.nanoTime());
    }

    /** Applies the opt-in presentation experiment without changing Starsector's FPS cap. */
    public static boolean requestedVsync(boolean requested) {
        if (!smoothFramePacing) return requested;
        vsyncRequests++;
        if (requested) vsyncEnabledRequests++;
        if (requested) {
            vsyncRequestsForcedOff++;
            return false;
        }
        return requested;
    }

    /** Marks one completed game-loop/display-update boundary. */
    public static void boundary() {
        if (!enabled) return;
        long started = System.nanoTime();
        try {
            recordBoundary(started);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // This is woven into the display loop. Diagnostics must never affect the game.
        } finally {
            recordMeasurementOverhead(System.nanoTime() - started);
        }
    }

    static synchronized void recordMeasurementOverhead(long elapsedNanos) {
        if (elapsedNanos < 0L) return;
        measurementSamples++;
        measurementTotalNanos += elapsedNanos;
        measurementMaximumNanos = Math.max(measurementMaximumNanos, elapsedNanos);
    }

    static void recordSwapStarted(long now) {
        swapStartedNanos = now;
    }

    static void recordSwapCompleted(long now) {
        swapCompletedNanos = now;
    }

    static void recordMessagesStarted(long now) {
        messagesStartedNanos = now;
    }

    static void recordMessagesCompleted(long now) {
        messagesCompletedNanos = now;
    }

    /** Called from an exact transformed game class when resource initialization returns. */
    public static synchronized void markStartupComplete() {
        if (enabled && !startupComplete) {
            startupComplete = true;
            startupTransitionPending = true;
        }
        RuntimeSemanticState.mainMenuReady();
        LoadJsonMemoRuntime.markStartupComplete();
        MergedReadCacheRuntime.complete();
        RuleTokenCacheRuntime.complete();
    }

    /** Called when the title screen removes its final preloading label and accepts input. */
    public static synchronized void markMainMenuInteractive() {
        if (enabled && !mainMenuInteractive) {
            mainMenuInteractive = true;
            interactiveTransitionPending = true;
        }
    }

    static synchronized void recordBoundary(long now) {
        boundaries++;
        boolean active = observedActive;
        int state = observedState;
        int campaignPause = observedCampaignPause;
        // Campaign/combat observers run once in their respective game-loop advance. Treat that
        // observation as a pulse for this display interval: otherwise menu, loading, and refit
        // frames after leaving a state inherit its last value indefinitely.
        observedState = STATE_UNKNOWN;
        observedCampaignPause = PAUSE_UNKNOWN;
        boolean crossedFocusBreak = focusBreak;
        focusBreak = false;
        if (firstBoundaryNanos == Long.MIN_VALUE) {
            firstBoundaryNanos = now;
            firstBoundaryEpochMillis = System.currentTimeMillis();
            lastBoundaryNanos = now;
            lastBoundaryActive = active;
            lastBoundaryState = state;
            lastBoundaryCampaignPause = campaignPause;
            startupTransitionPending = false;
            interactiveTransitionPending = false;
            resetDisplayPhaseTimestamps();
            return;
        }

        boolean crossedStartupCompletion = startupTransitionPending;
        boolean crossedMainMenuInteractive = interactiveTransitionPending;
        startupTransitionPending = false;
        interactiveTransitionPending = false;
        long previousBoundaryNanos = lastBoundaryNanos;
        long duration = now - previousBoundaryNanos;
        lastBoundaryNanos = now;
        if (duration <= 0L) {
            invalidIntervals++;
            lastBoundaryActive = active;
            lastBoundaryState = state;
            lastBoundaryCampaignPause = campaignPause;
            resetDisplayPhaseTimestamps();
            return;
        }
        if (crossedFocusBreak || !lastBoundaryActive || !active) {
            inactiveIntervals++;
            lastBoundaryActive = active;
            lastBoundaryState = state;
            lastBoundaryCampaignPause = campaignPause;
            resetDisplayPhaseTimestamps();
            return;
        }

        long endOffset = now - firstBoundaryNanos;
        allActive.record(duration, endOffset);
        allActivePhases.record(duration, previousBoundaryNanos, now, endOffset);
        if (startupComplete) {
            if (crossedStartupCompletion) startupTransitionIntervals++;
            else postStartupActive.record(duration, endOffset);
        }
        if (mainMenuInteractive) {
            if (crossedMainMenuInteractive) interactiveTransitionIntervals++;
            else postInteractiveActive.record(duration, endOffset);
        }
        if (state == STATE_CAMPAIGN && firstCampaignBoundaryNanos == Long.MIN_VALUE) {
            firstCampaignBoundaryNanos = now;
        }
        if (state != lastBoundaryState) {
            stateTransitionIntervals++;
        } else if (state == STATE_CAMPAIGN) {
            campaignActive.record(duration, endOffset);
            campaignActivePhases.record(duration, previousBoundaryNanos, now, endOffset);
            if (now - firstCampaignBoundaryNanos < CAMPAIGN_WARMUP_NANOS) {
                campaignFirst30SecondsActive.record(duration, endOffset);
            } else {
                campaignAfter30SecondsActive.record(duration, endOffset);
                campaignAfter30SecondsActivePhases.record(
                        duration, previousBoundaryNanos, now, endOffset);
            }
            if (campaignPause == PAUSE_UNKNOWN || lastBoundaryCampaignPause == PAUSE_UNKNOWN) {
                campaignPauseUnknownIntervals++;
            } else if (campaignPause != lastBoundaryCampaignPause) {
                campaignPauseTransitionIntervals++;
            } else if (campaignPause == PAUSE_PAUSED) {
                campaignPausedActive.record(duration, endOffset);
                if (now - firstCampaignBoundaryNanos >= CAMPAIGN_WARMUP_NANOS) {
                    campaignPausedAfter30SecondsActive.record(duration, endOffset);
                }
            } else {
                campaignUnpausedActive.record(duration, endOffset);
                if (now - firstCampaignBoundaryNanos >= CAMPAIGN_WARMUP_NANOS) {
                    campaignUnpausedAfter30SecondsActive.record(duration, endOffset);
                }
            }
        } else if (state == STATE_COMBAT) {
            combatActive.record(duration, endOffset);
            if (firstCampaignBoundaryNanos != Long.MIN_VALUE) {
                combatAfterCampaignActive.record(duration, endOffset);
            }
        }
        if (measurementWindowActive && state == lastBoundaryState
                && state == measurementWindowState) {
            measurementWindow.record(duration, endOffset);
        }
        lastBoundaryActive = active;
        lastBoundaryState = state;
        lastBoundaryCampaignPause = campaignPause;
        resetDisplayPhaseTimestamps();
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put(FrameTimeTelemetry.ENABLED, enabled);
        result.put("installed", installed);
        result.put("startupComplete", startupComplete);
        result.put("mainMenuInteractive", mainMenuInteractive);
        result.put("boundaries", boundaries);
        result.put("focusObservations", focusObservations);
        result.put("inactiveIntervalsDropped", inactiveIntervals);
        result.put("invalidIntervalsDropped", invalidIntervals);
        result.put("stateTransitionIntervalsDropped", stateTransitionIntervals);
        result.put("startupTransitionIntervalsExcluded", startupTransitionIntervals);
        result.put("interactiveTransitionIntervalsExcluded", interactiveTransitionIntervals);
        result.put("campaignPauseTransitionIntervalsExcluded", campaignPauseTransitionIntervals);
        result.put("campaignPauseUnknownIntervalsExcluded", campaignPauseUnknownIntervals);
        Map<String, Object> presentationPolicy = new LinkedHashMap<>();
        presentationPolicy.put("forceVsyncOffProperty", FORCE_VSYNC_OFF_PROPERTY);
        presentationPolicy.put("forceVsyncOff", smoothFramePacing);
        presentationPolicy.put("requests", vsyncRequests);
        presentationPolicy.put("enabledRequests", vsyncEnabledRequests);
        presentationPolicy.put("requestsForcedOff", vsyncRequestsForcedOff);
        presentationPolicy.put("frameRateCap", "unchanged; owned by Starsector's main loop");
        result.put("presentationPolicy", presentationPolicy);
        Map<String, Object> measurement = new LinkedHashMap<>();
        measurement.put("samples", measurementSamples);
        measurement.put("totalNanos", measurementTotalNanos);
        measurement.put(FrameTimeTelemetry.AVERAGE_MICROS, measurementSamples == 0L
                ? null
                : measurementTotalNanos / 1_000.0 / measurementSamples);
        measurement.put("maximumMicros", measurementSamples == 0L
                ? null
                : measurementMaximumNanos / 1_000.0);
        measurement.put("scope", "display-boundary hook; four phase timestamp hooks excluded");
        result.put(FrameTimeTelemetry.MEASUREMENT_OVERHEAD, measurement);
        result.put("firstBoundaryEpochMillis", firstBoundaryEpochMillis);
        result.put("campaignWarmupWindowMillis", CAMPAIGN_WARMUP_NANOS / 1_000_000L);
        result.put("firstCampaignBoundaryOffsetMillis",
                firstCampaignBoundaryNanos == Long.MIN_VALUE || firstBoundaryNanos == Long.MIN_VALUE
                        ? null
                        : (firstCampaignBoundaryNanos - firstBoundaryNanos) / 1_000_000.0);
        result.put("histogramBinMicros", HISTOGRAM_BIN_NANOS / 1_000L);
        result.put("histogramOverflowMicros",
                HISTOGRAM_REGULAR_BINS * HISTOGRAM_BIN_NANOS / 1_000L);
        result.put("allActive", allActive.toMap(firstBoundaryEpochMillis));
        result.put("postStartupActive", postStartupActive.toMap(firstBoundaryEpochMillis));
        result.put(FrameTimeTelemetry.POST_INTERACTIVE_ACTIVE,
                postInteractiveActive.toMap(firstBoundaryEpochMillis));
        result.put(FrameTimeTelemetry.CAMPAIGN_ACTIVE, campaignActive.toMap(firstBoundaryEpochMillis));
        result.put(FrameTimeTelemetry.CAMPAIGN_FIRST_30_SECONDS_ACTIVE,
                campaignFirst30SecondsActive.toMap(firstBoundaryEpochMillis));
        result.put(FrameTimeTelemetry.CAMPAIGN_AFTER_30_SECONDS_ACTIVE,
                campaignAfter30SecondsActive.toMap(firstBoundaryEpochMillis));
        result.put(FrameTimeTelemetry.CAMPAIGN_PAUSED_ACTIVE,
                campaignPausedActive.toMap(firstBoundaryEpochMillis));
        result.put(FrameTimeTelemetry.CAMPAIGN_PAUSED_AFTER_30_SECONDS_ACTIVE,
                campaignPausedAfter30SecondsActive.toMap(firstBoundaryEpochMillis));
        result.put(FrameTimeTelemetry.CAMPAIGN_UNPAUSED_ACTIVE,
                campaignUnpausedActive.toMap(firstBoundaryEpochMillis));
        result.put(FrameTimeTelemetry.CAMPAIGN_UNPAUSED_AFTER_30_SECONDS_ACTIVE,
                campaignUnpausedAfter30SecondsActive.toMap(firstBoundaryEpochMillis));
        result.put("combatActive", combatActive.toMap(firstBoundaryEpochMillis));
        result.put(FrameTimeTelemetry.COMBAT_AFTER_CAMPAIGN_ACTIVE,
                combatAfterCampaignActive.toMap(firstBoundaryEpochMillis));
        Map<String, Object> window = measurementWindow.toMap(firstBoundaryEpochMillis);
        window.put("active", measurementWindowActive);
        window.put("state", measurementWindowState == STATE_COMBAT ? "combat" : null);
        result.put("measurementWindow", window);
        Map<String, Object> displayPhases = new LinkedHashMap<>();
        displayPhases.put("timestampReadsPerPresentedFrame", 6);
        displayPhases.put("scope", "pre-swap (game work and limiter) vs native swap vs messages");
        displayPhases.put("allActive", allActivePhases.toMap(firstBoundaryEpochMillis));
        displayPhases.put(FrameTimeTelemetry.CAMPAIGN_ACTIVE,
                campaignActivePhases.toMap(firstBoundaryEpochMillis));
        displayPhases.put(FrameTimeTelemetry.CAMPAIGN_AFTER_30_SECONDS_ACTIVE,
                campaignAfter30SecondsActivePhases.toMap(firstBoundaryEpochMillis));
        result.put("displayPhases", displayPhases);
        return result;
    }

    static synchronized void reset() {
        beginSession(false, false);
    }

    private static void resetDisplayPhaseTimestamps() {
        swapStartedNanos = Long.MIN_VALUE;
        swapCompletedNanos = Long.MIN_VALUE;
        messagesStartedNanos = Long.MIN_VALUE;
        messagesCompletedNanos = Long.MIN_VALUE;
    }

    private static final class DisplayPhases {
        private static final long SLOW_FRAME_NANOS = 33_333_333L;
        private static final int WORST_LIMIT = 128;

        private final PhaseStats preSwap = new PhaseStats();
        private final PhaseStats swap = new PhaseStats();
        private final PhaseStats messages = new PhaseStats();
        private final PhaseStats otherAfterSwap = new PhaseStats();
        private final long[] worstTotal = new long[WORST_LIMIT];
        private final long[] worstPreSwap = new long[WORST_LIMIT];
        private final long[] worstSwap = new long[WORST_LIMIT];
        private final long[] worstMessages = new long[WORST_LIMIT];
        private final long[] worstOther = new long[WORST_LIMIT];
        private final long[] worstOffsets = new long[WORST_LIMIT];
        private long frames;
        private long completeFrames;
        private long missingSwap;
        private long missingMessages;
        private long invalidOrder;
        private long slowFrames;
        private long slowFramesPreSwapLargest;
        private long slowFramesSwapLargest;
        private long slowFramesAfterSwapLargest;
        private int worstCount;
        private int shortestWorst;

        void reset() {
            preSwap.reset();
            swap.reset();
            messages.reset();
            otherAfterSwap.reset();
            Arrays.fill(worstTotal, 0L);
            Arrays.fill(worstPreSwap, 0L);
            Arrays.fill(worstSwap, 0L);
            Arrays.fill(worstMessages, 0L);
            Arrays.fill(worstOther, 0L);
            Arrays.fill(worstOffsets, 0L);
            frames = 0L;
            completeFrames = 0L;
            missingSwap = 0L;
            missingMessages = 0L;
            invalidOrder = 0L;
            slowFrames = 0L;
            slowFramesPreSwapLargest = 0L;
            slowFramesSwapLargest = 0L;
            slowFramesAfterSwapLargest = 0L;
            worstCount = 0;
            shortestWorst = 0;
        }

        void record(long total, long previousBoundary, long now, long endOffset) {
            frames++;
            if (swapStartedNanos == Long.MIN_VALUE || swapCompletedNanos == Long.MIN_VALUE) {
                missingSwap++;
                return;
            }
            boolean messagesPresent = messagesStartedNanos != Long.MIN_VALUE
                    && messagesCompletedNanos != Long.MIN_VALUE;
            if (!messagesPresent) missingMessages++;
            if (swapStartedNanos < previousBoundary
                    || swapCompletedNanos < swapStartedNanos
                    || swapCompletedNanos > now
                    || (messagesPresent && (messagesStartedNanos < swapCompletedNanos
                            || messagesCompletedNanos < messagesStartedNanos
                            || messagesCompletedNanos > now))) {
                invalidOrder++;
                return;
            }
            long preSwapNanos = swapStartedNanos - previousBoundary;
            long swapNanos = swapCompletedNanos - swapStartedNanos;
            long messageNanos = messagesPresent
                    ? messagesCompletedNanos - messagesStartedNanos : 0L;
            long afterSwapNanos = now - swapCompletedNanos;
            long otherNanos = afterSwapNanos - messageNanos;
            if (otherNanos < 0L) {
                invalidOrder++;
                return;
            }
            completeFrames++;
            preSwap.record(preSwapNanos);
            swap.record(swapNanos);
            messages.record(messageNanos);
            otherAfterSwap.record(otherNanos);
            if (total > SLOW_FRAME_NANOS) {
                slowFrames++;
                if (preSwapNanos >= swapNanos && preSwapNanos >= afterSwapNanos) {
                    slowFramesPreSwapLargest++;
                } else if (swapNanos >= afterSwapNanos) {
                    slowFramesSwapLargest++;
                } else {
                    slowFramesAfterSwapLargest++;
                }
            }
            retainWorst(total, preSwapNanos, swapNanos, messageNanos, otherNanos, endOffset);
        }

        Map<String, Object> toMap(long originEpochMillis) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("frames", frames);
            values.put("completeFrames", completeFrames);
            values.put("missingSwap", missingSwap);
            values.put("missingMessages", missingMessages);
            values.put("invalidOrder", invalidOrder);
            values.put("preSwap", preSwap.toMap());
            values.put("nativeSwap", swap.toMap());
            values.put("messageProcessing", messages.toMap());
            values.put("otherAfterSwap", otherAfterSwap.toMap());
            values.put("framesOver33_33Millis", slowFrames);
            values.put("slowFramesWherePreSwapWasLargest", slowFramesPreSwapLargest);
            values.put("slowFramesWhereSwapWasLargest", slowFramesSwapLargest);
            values.put("slowFramesWhereAfterSwapWasLargest", slowFramesAfterSwapLargest);
            List<Map<String, Object>> worst = new ArrayList<>();
            Integer[] order = new Integer[worstCount];
            for (int index = 0; index < worstCount; index++) order[index] = index;
            Arrays.sort(order, Comparator.comparingLong((Integer index) -> worstTotal[index])
                    .reversed());
            for (int index : order) {
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("durationMicros", worstTotal[index] / 1_000L);
                frame.put("preSwapMicros", worstPreSwap[index] / 1_000L);
                frame.put("swapMicros", worstSwap[index] / 1_000L);
                frame.put("messageMicros", worstMessages[index] / 1_000L);
                frame.put("otherAfterSwapMicros", worstOther[index] / 1_000L);
                frame.put("endOffsetMillis", worstOffsets[index] / 1_000_000.0);
                frame.put("endEpochMillis", originEpochMillis < 0L ? null
                        : originEpochMillis + worstOffsets[index] / 1_000_000L);
                worst.add(frame);
            }
            values.put("worstFrames", worst);
            return values;
        }

        private void retainWorst(long total, long preSwapNanos, long swapNanos, long message,
                long other, long endOffset) {
            int target;
            if (worstCount < WORST_LIMIT) {
                target = worstCount++;
            } else {
                if (total <= worstTotal[shortestWorst]) return;
                target = shortestWorst;
            }
            worstTotal[target] = total;
            worstPreSwap[target] = preSwapNanos;
            worstSwap[target] = swapNanos;
            worstMessages[target] = message;
            worstOther[target] = other;
            worstOffsets[target] = endOffset;
            shortestWorst = 0;
            for (int index = 1; index < worstCount; index++) {
                if (worstTotal[index] < worstTotal[shortestWorst]) shortestWorst = index;
            }
        }
    }

    private static final class PhaseStats {
        private final long[] histogram = new long[HISTOGRAM_REGULAR_BINS + 1];
        private long count;
        private long totalNanos;
        private long maximumNanos;

        void reset() {
            Arrays.fill(histogram, 0L);
            count = 0L;
            totalNanos = 0L;
            maximumNanos = 0L;
        }

        void record(long durationNanos) {
            count++;
            totalNanos += durationNanos;
            maximumNanos = Math.max(maximumNanos, durationNanos);
            int bin = (int) Math.min(HISTOGRAM_REGULAR_BINS,
                    Math.max(0L, durationNanos - 1L) / HISTOGRAM_BIN_NANOS);
            histogram[bin]++;
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("samples", count);
            values.put("totalMillis", totalNanos / 1_000_000.0);
            values.put("averageMicros", count == 0L ? null : totalNanos / 1_000.0 / count);
            values.put("maximumMicros", count == 0L ? null : maximumNanos / 1_000.0);
            values.put("p95Micros", percentile(950));
            values.put("p99Micros", percentile(990));
            return values;
        }

        private Long percentile(int permille) {
            if (count == 0L) return null;
            long wanted = Math.max(1L, (count * permille + 999L) / 1_000L);
            long seen = 0L;
            for (int index = 0; index < histogram.length; index++) {
                seen += histogram[index];
                if (seen >= wanted) return (index + 1L) * HISTOGRAM_BIN_NANOS / 1_000L;
            }
            return null;
        }
    }

    private static final class Distribution {
        private static final long FRAME_BUDGET_NANOS = 16_666_667L;
        private static final long SLOW_FRAME_NANOS = 33_333_333L;
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
        private long slowFrameTimeNanos;
        private long excessFrameTimeOverBudgetNanos;
        private long excessSlowFrameTimeNanos;
        private long slowFrameClusters;
        private long repeatedSlowFrameClusters;
        private long framesInRepeatedSlowFrameClusters;
        private long isolatedSlowFrames;
        private long currentSlowClusterFrames;
        private long currentSlowClusterNanos;
        private long longestSlowClusterFrames;
        private long longestSlowClusterNanos;
        private long lastRecordedEndOffsetNanos;
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
            slowFrameTimeNanos = 0L;
            excessFrameTimeOverBudgetNanos = 0L;
            excessSlowFrameTimeNanos = 0L;
            slowFrameClusters = 0L;
            repeatedSlowFrameClusters = 0L;
            framesInRepeatedSlowFrameClusters = 0L;
            isolatedSlowFrames = 0L;
            currentSlowClusterFrames = 0L;
            currentSlowClusterNanos = 0L;
            longestSlowClusterFrames = 0L;
            longestSlowClusterNanos = 0L;
            lastRecordedEndOffsetNanos = Long.MIN_VALUE;
            worstCount = 0;
            shortestWorst = 0;
        }

        void record(long durationNanos, long endOffsetNanos) {
            long startOffsetNanos = endOffsetNanos - durationNanos;
            if (lastRecordedEndOffsetNanos != Long.MIN_VALUE
                    && startOffsetNanos != lastRecordedEndOffsetNanos) {
                finishSlowCluster();
            }
            lastRecordedEndOffsetNanos = endOffsetNanos;
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
            excessFrameTimeOverBudgetNanos += Math.max(0L, durationNanos - FRAME_BUDGET_NANOS);
            if (durationNanos > SLOW_FRAME_NANOS) {
                slowFrameTimeNanos += durationNanos;
                excessSlowFrameTimeNanos += durationNanos - SLOW_FRAME_NANOS;
                recordSlowClusterFrame(durationNanos);
            } else {
                finishSlowCluster();
            }
            retainWorst(durationNanos, endOffsetNanos);
        }

        private void recordSlowClusterFrame(long durationNanos) {
            if (currentSlowClusterFrames == 0L) slowFrameClusters++;
            currentSlowClusterFrames++;
            currentSlowClusterNanos += durationNanos;
            if (currentSlowClusterFrames == 2L) {
                repeatedSlowFrameClusters++;
                framesInRepeatedSlowFrameClusters += 2L;
            } else if (currentSlowClusterFrames > 2L) {
                framesInRepeatedSlowFrameClusters++;
            }
            longestSlowClusterFrames = Math.max(longestSlowClusterFrames, currentSlowClusterFrames);
            longestSlowClusterNanos = Math.max(longestSlowClusterNanos, currentSlowClusterNanos);
        }

        private void finishSlowCluster() {
            if (currentSlowClusterFrames == 1L) isolatedSlowFrames++;
            currentSlowClusterFrames = 0L;
            currentSlowClusterNanos = 0L;
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
            result.put(FrameTimeTelemetry.TOTAL_ACTIVE_NANOS, totalNanos);
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
            Map<String, Object> stutter = new LinkedHashMap<>();
            stutter.put("slowFrameThresholdMicros", SLOW_FRAME_NANOS / 1_000L);
            stutter.put("slowFramesPerMinute", ratePerMinute(over33Millis));
            stutter.put("slowFrameTimePercent", percentageOfTime(slowFrameTimeNanos));
            stutter.put("excessFrameTimeOver16_67Millis", nanosToMillis(excessFrameTimeOverBudgetNanos));
            stutter.put("excessSlowFrameTimeMillis", nanosToMillis(excessSlowFrameTimeNanos));
            stutter.put("stutterBurdenMillisPerSecond", millisPerSecond(excessSlowFrameTimeNanos));
            stutter.put("slowFrameClusters", slowFrameClusters);
            stutter.put("isolatedSlowFrames", isolatedSlowFrames
                    + (currentSlowClusterFrames == 1L ? 1L : 0L));
            stutter.put("repeatedSlowFrameClusters", repeatedSlowFrameClusters);
            stutter.put("framesInRepeatedSlowFrameClusters", framesInRepeatedSlowFrameClusters);
            stutter.put("repeatedSlowFramesPercent", percentage(framesInRepeatedSlowFrameClusters));
            stutter.put("longestSlowFrameClusterFrames", longestSlowClusterFrames);
            stutter.put("longestSlowFrameClusterMillis", nanosToMillis(longestSlowClusterNanos));
            stutter.put("interpretation", "repeated >33.33ms clusters and excess time rank ahead of isolated hitches");
            result.put("stutterProfile", stutter);
            result.put("worstFrames", worstFrames(originEpochMillis));
            return result;
        }

        private Double ratePerMinute(long matching) {
            return totalNanos == 0L ? null : round(matching * 60_000_000_000.0 / totalNanos);
        }

        private Double percentageOfTime(long nanos) {
            return totalNanos == 0L ? null : round(100.0 * nanos / totalNanos);
        }

        private Double millisPerSecond(long nanos) {
            return totalNanos == 0L ? null : round(nanos * 1_000.0 / totalNanos);
        }

        private double nanosToMillis(long nanos) {
            return Math.round(nanos / 10_000.0) / 100.0;
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

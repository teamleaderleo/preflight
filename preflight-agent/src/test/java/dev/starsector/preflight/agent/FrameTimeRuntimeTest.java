package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FrameTimeRuntimeTest {
    @AfterEach
    void reset() {
        FrameTimeRuntime.reset();
    }

    @Test
    void reportsTailPercentilesAndSeparatesPostStartupFrames() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(1_000_000_000L);
        FrameTimeRuntime.recordBoundary(1_010_000_000L);
        FrameTimeRuntime.recordBoundary(1_026_000_000L);
        FrameTimeRuntime.markStartupComplete();
        FrameTimeRuntime.recordBoundary(1_046_000_000L);
        FrameTimeRuntime.recordBoundary(1_086_000_000L);
        FrameTimeRuntime.recordBoundary(1_186_000_000L);

        Map<String, Object> telemetry = FrameTimeRuntime.telemetry();
        Map<String, Object> all = map(telemetry.get("allActive"));
        Map<String, Object> post = map(telemetry.get("postStartupActive"));
        assertEquals(5L, all.get("frames"));
        assertEquals(20_000L, all.get("p50Micros"));
        assertEquals(100_000L, all.get("p95Micros"));
        assertEquals(100_000L, all.get("p99Micros"));
        assertEquals(26.88, all.get("averageFps"));
        assertEquals(50.0, all.get("medianFps"));
        assertEquals(10.0, all.get("onePercentLowFps"));
        assertEquals(10.0, all.get("pointOnePercentLowFps"));
        assertEquals(40.0, all.get("framesMeeting60FpsPercent"));
        assertEquals(60.0, all.get("framesMeeting30FpsPercent"));
        assertEquals(3L, all.get("over16_67Millis"));
        assertEquals(2L, post.get("frames"));
        assertEquals(40_000L, post.get("p50Micros"));
        assertEquals(1L, post.get("over50Millis"));
        assertEquals(1L, telemetry.get("startupTransitionIntervalsExcluded"));
        assertFalse(list(post.get("worstFrames")).isEmpty());
    }

    @Test
    void postInteractiveFramesExcludeStartupTitleWorkAndTheCrossingInterval() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(1_000_000_000L);
        FrameTimeRuntime.recordBoundary(2_000_000_000L); // resource loading
        FrameTimeRuntime.markStartupComplete();
        FrameTimeRuntime.recordBoundary(8_000_000_000L); // crosses resource completion
        FrameTimeRuntime.recordBoundary(9_000_000_000L); // title construction
        FrameTimeRuntime.markMainMenuInteractive();
        FrameTimeRuntime.recordBoundary(10_000_000_000L); // crosses interactive marker
        FrameTimeRuntime.recordBoundary(10_016_000_000L); // clean interactive frame

        Map<String, Object> telemetry = FrameTimeRuntime.telemetry();
        Map<String, Object> postStartup = map(telemetry.get("postStartupActive"));
        Map<String, Object> postInteractive =
                map(telemetry.get(FrameTimeTelemetry.POST_INTERACTIVE_ACTIVE));
        assertEquals(true, telemetry.get("mainMenuInteractive"));
        assertEquals(3L, postStartup.get("frames"));
        assertEquals(1L, postInteractive.get("frames"));
        assertEquals(16_000L, postInteractive.get("maximumMicros"));
        assertEquals(1L, telemetry.get("startupTransitionIntervalsExcluded"));
        assertEquals(1L, telemetry.get("interactiveTransitionIntervalsExcluded"));
    }

    @Test
    void ranksRepeatedSlowFrameClustersAndKeepsASettledMeasurementWindow() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(0L);
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(10_000_000L); // state transition
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(26_000_000L); // pre-window setup
        FrameTimeRuntime.beginCombatMeasurementWindow();
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(42_000_000L);
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(82_000_000L); // repeated cluster starts
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(127_000_000L);
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(143_000_000L); // cluster ends
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(183_000_000L); // isolated current hitch

        Map<String, Object> window = map(FrameTimeRuntime.telemetry().get("measurementWindow"));
        Map<String, Object> stutter = map(window.get("stutterProfile"));
        assertEquals(true, window.get("active"));
        assertEquals("combat", window.get("state"));
        assertEquals(5L, window.get("frames"));
        assertEquals(3L, window.get("over33_33Millis"));
        assertEquals(2L, stutter.get("slowFrameClusters"));
        assertEquals(1L, stutter.get("isolatedSlowFrames"));
        assertEquals(1L, stutter.get("repeatedSlowFrameClusters"));
        assertEquals(2L, stutter.get("framesInRepeatedSlowFrameClusters"));
        assertEquals(2L, stutter.get("longestSlowFrameClusterFrames"));
        assertEquals(85.0, stutter.get("longestSlowFrameClusterMillis"));
        assertEquals(25.0, stutter.get("excessSlowFrameTimeMillis"));
        List<Object> clusters = list(window.get("repeatedSlowFrameWindows"));
        assertEquals(1, clusters.size());
        Map<String, Object> cluster = map(clusters.get(0));
        assertEquals(2L, cluster.get("frames"));
        assertEquals(85_000L, cluster.get("durationMicros"));
        assertEquals(18_333L, cluster.get("excessSlowFrameMicros"));
        assertEquals(42.0, cluster.get("startOffsetMillis"));
        assertEquals(127.0, cluster.get("endOffsetMillis"));
    }

    @Test
    void reportsAnInProgressRepeatedClusterWithoutMutatingIt() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(0L);
        FrameTimeRuntime.recordBoundary(40_000_000L);
        FrameTimeRuntime.recordBoundary(90_000_000L);

        Map<String, Object> first = map(list(map(FrameTimeRuntime.telemetry().get("allActive"))
                .get("repeatedSlowFrameWindows")).get(0));
        Map<String, Object> second = map(list(map(FrameTimeRuntime.telemetry().get("allActive"))
                .get("repeatedSlowFrameWindows")).get(0));
        assertEquals(first, second);
        assertEquals(2L, first.get("frames"));
        assertEquals(90_000L, first.get("durationMicros"));
        assertEquals(0.0, first.get("startOffsetMillis"));
        assertEquals(90.0, first.get("endOffsetMillis"));
    }

    @Test
    void dropsIntervalsAcrossFocusLossAndCountsFreshActiveFrames() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(10L);
        FrameTimeRuntime.recordBoundary(20L);
        FrameTimeRuntime.observeActive(false);
        FrameTimeRuntime.recordBoundary(1_000L);
        FrameTimeRuntime.observeActive(true);
        FrameTimeRuntime.recordBoundary(1_010L);
        FrameTimeRuntime.recordBoundary(1_020L);

        Map<String, Object> telemetry = FrameTimeRuntime.telemetry();
        assertEquals(2L, map(telemetry.get("allActive")).get("frames"));
        assertEquals(2L, telemetry.get("inactiveIntervalsDropped"));
        assertEquals(2L, telemetry.get("focusObservations"));
    }

    @Test
    void hitchPacketsStopAtFocusExcludedSequenceGaps() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(0L);
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.observeCampaignPaused(true);
        FrameTimeRuntime.recordBoundary(1_000_000L);
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(10_000_000L); // state transition
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(70_000_000L); // retained 60 ms trigger
        FrameTimeRuntime.observeActive(false);
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(80_000_000L); // focus loss
        FrameTimeRuntime.observeActive(true);
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(90_000_000L); // focus recovery
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(100_000_000L); // first stable frame after the gap

        Map<String, Object> recorder = map(FrameTimeRuntime.telemetry().get("hitchPackets"));
        Map<String, Object> packet = map(list(recorder.get("packets")).get(0));
        assertEquals("combat", packet.get("state"));
        assertEquals(1, packet.get("frames"));
        assertFalse((Boolean) packet.get("postWindowComplete"));
        assertEquals(4L, map(list(packet.get("frameHistory")).get(0)).get("sequence"));
    }

    @Test
    void titleDemoCombatCannotConsumeGameplayHitchPacketSlots() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(0L);
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(10_000_000L);
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(70_000_000L);

        Map<String, Object> recorder = map(
                FrameTimeRuntime.telemetry().get(FrameTimeTelemetry.HITCH_PACKETS));
        assertTrue(list(recorder.get("packets")).isEmpty());
    }

    @Test
    void segmentsStableCampaignAndCombatIntervalsAndDropsTransitions() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(0L);
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.recordBoundary(10L); // unknown -> campaign
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.recordBoundary(20L);
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.recordBoundary(30L);
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(40L); // campaign -> combat
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(50L);

        Map<String, Object> telemetry = FrameTimeRuntime.telemetry();
        assertEquals(5L, map(telemetry.get("allActive")).get("frames"));
        assertEquals(2L, telemetry.get("stateTransitionIntervalsDropped"));
        assertEquals(2L, map(telemetry.get("campaignActive")).get("frames"));
        assertEquals(1L, map(telemetry.get("combatActive")).get("frames"));
    }

    @Test
    void stateObservationExpiresAtTheNextDisplayBoundary() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(0L);
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.recordBoundary(10L); // unknown -> campaign
        FrameTimeRuntime.recordBoundary(20L); // campaign -> unknown
        FrameTimeRuntime.recordBoundary(30L); // stable unknown, not campaign
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.recordBoundary(40L); // unknown -> campaign
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.recordBoundary(50L); // stable campaign

        Map<String, Object> telemetry = FrameTimeRuntime.telemetry();
        assertEquals(3L, telemetry.get("stateTransitionIntervalsDropped"));
        assertEquals(1L, map(telemetry.get("campaignActive")).get("frames"));
        assertEquals(0L, map(telemetry.get("combatActive")).get("frames"));
    }

    @Test
    void separatesCampaignWarmupAndExcludesTitleCombatFromCampaignSessionCombat() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(0L);
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(10L); // title demo transition
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(20L); // stable title demo
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.recordBoundary(30L); // first campaign boundary
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.recordBoundary(40L); // first-30-seconds campaign
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.recordBoundary(30_000_000_030L); // exactly 30 seconds later
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(30_000_000_040L); // campaign -> combat
        FrameTimeRuntime.observeCombat();
        FrameTimeRuntime.recordBoundary(30_000_000_050L); // campaign-session combat

        Map<String, Object> telemetry = FrameTimeRuntime.telemetry();
        assertEquals(30_000L, telemetry.get("campaignWarmupWindowMillis"));
        assertEquals(0.00003, telemetry.get("firstCampaignBoundaryOffsetMillis"));
        assertEquals(2L, map(telemetry.get(FrameTimeTelemetry.CAMPAIGN_ACTIVE)).get("frames"));
        assertEquals(1L,
                map(telemetry.get(FrameTimeTelemetry.CAMPAIGN_FIRST_30_SECONDS_ACTIVE)).get("frames"));
        assertEquals(10L,
                map(telemetry.get(FrameTimeTelemetry.CAMPAIGN_FIRST_30_SECONDS_ACTIVE))
                        .get(FrameTimeTelemetry.TOTAL_ACTIVE_NANOS));
        assertEquals(1L,
                map(telemetry.get(FrameTimeTelemetry.CAMPAIGN_AFTER_30_SECONDS_ACTIVE)).get("frames"));
        assertEquals(29_999_999_990L,
                map(telemetry.get(FrameTimeTelemetry.CAMPAIGN_AFTER_30_SECONDS_ACTIVE))
                        .get(FrameTimeTelemetry.TOTAL_ACTIVE_NANOS));
        assertEquals(2L, map(telemetry.get("combatActive")).get("frames"));
        assertEquals(1L,
                map(telemetry.get(FrameTimeTelemetry.COMBAT_AFTER_CAMPAIGN_ACTIVE)).get("frames"));
    }

    @Test
    void segmentsPausedAndUnpausedCampaignIntervalsAndExcludesTransitions() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(0L);
        observeCampaignPaused(true);
        FrameTimeRuntime.recordBoundary(10L); // unknown -> paused campaign
        observeCampaignPaused(true);
        FrameTimeRuntime.recordBoundary(20L); // paused warmup
        observeCampaignPaused(true);
        FrameTimeRuntime.recordBoundary(30_000_000_010L); // paused settled
        observeCampaignPaused(false);
        FrameTimeRuntime.recordBoundary(30_000_000_020L); // pause transition
        observeCampaignPaused(false);
        FrameTimeRuntime.recordBoundary(30_000_000_030L); // unpaused settled

        Map<String, Object> telemetry = FrameTimeRuntime.telemetry();
        assertEquals(1L, telemetry.get("campaignPauseTransitionIntervalsExcluded"));
        assertEquals(0L, telemetry.get("campaignPauseUnknownIntervalsExcluded"));
        assertEquals(2L,
                map(telemetry.get(FrameTimeTelemetry.CAMPAIGN_PAUSED_ACTIVE)).get("frames"));
        assertEquals(1L,
                map(telemetry.get(FrameTimeTelemetry.CAMPAIGN_PAUSED_AFTER_30_SECONDS_ACTIVE))
                        .get("frames"));
        assertEquals(1L,
                map(telemetry.get(FrameTimeTelemetry.CAMPAIGN_UNPAUSED_ACTIVE)).get("frames"));
        assertEquals(1L,
                map(telemetry.get(FrameTimeTelemetry.CAMPAIGN_UNPAUSED_AFTER_30_SECONDS_ACTIVE))
                        .get("frames"));
    }

    @Test
    void excludesUnknownCampaignPauseObservations() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(0L);
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.recordBoundary(10L);
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.recordBoundary(20L);

        Map<String, Object> telemetry = FrameTimeRuntime.telemetry();
        assertEquals(1L, telemetry.get("campaignPauseUnknownIntervalsExcluded"));
        assertEquals(0L,
                map(telemetry.get(FrameTimeTelemetry.CAMPAIGN_PAUSED_ACTIVE)).get("frames"));
        assertEquals(0L,
                map(telemetry.get(FrameTimeTelemetry.CAMPAIGN_UNPAUSED_ACTIVE)).get("frames"));
    }

    private static void observeCampaignPaused(boolean paused) {
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.observeCampaignPaused(paused);
    }

    @Test
    void disabledProbeIsAStableNoOp() {
        FrameTimeRuntime.beginSession(false);
        FrameTimeRuntime.observeActive(false);
        FrameTimeRuntime.boundary();
        Map<String, Object> telemetry = FrameTimeRuntime.telemetry();
        assertFalse((Boolean) telemetry.get("enabled"));
        assertEquals(0L, telemetry.get("boundaries"));
        assertEquals(0L, map(telemetry.get("allActive")).get("frames"));
        assertNull(map(telemetry.get("allActive")).get("p99Micros"));
        assertNull(map(telemetry.get("allActive")).get("averageFps"));
        assertNull(map(telemetry.get("allActive")).get("onePercentLowFps"));
    }

    @Test
    void reportsBoundedMeasurementOverheadSeparatelyFromFrameDurations() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordMeasurementOverhead(1_500L);
        FrameTimeRuntime.recordMeasurementOverhead(2_500L);

        Map<String, Object> overhead = map(
                FrameTimeRuntime.telemetry().get("measurementOverhead"));
        assertEquals(2L, overhead.get("samples"));
        assertEquals(4_000L, overhead.get("totalNanos"));
        assertEquals(2.0, overhead.get("averageMicros"));
        assertEquals(2.5, overhead.get("maximumMicros"));
    }

    @Test
    void attributesSlowFramesAcrossGameWorkSwapAndMessagePhases() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(0L);
        FrameTimeRuntime.recordSwapStarted(20_000_000L);
        FrameTimeRuntime.recordSwapCompleted(35_000_000L);
        FrameTimeRuntime.recordMessagesStarted(36_000_000L);
        FrameTimeRuntime.recordMessagesCompleted(37_000_000L);
        FrameTimeRuntime.recordBoundary(40_000_000L);

        Map<String, Object> phases = map(FrameTimeRuntime.telemetry().get("displayPhases"));
        assertEquals(6, phases.get("baseTimestampReadsPerPresentedFrame"));
        assertEquals(2, phases.get("additionalTimestampReadsPerCampaignLimiterCall"));
        Map<String, Object> all = map(phases.get("allActive"));
        assertEquals(1L, all.get("completeFrames"));
        assertEquals(1L, all.get("framesOver33_33Millis"));
        assertEquals(1L, all.get("slowFramesWherePreSwapWasLargest"));
        assertEquals(20_000.0, map(all.get("preSwap")).get("maximumMicros"));
        assertEquals(15_000.0, map(all.get("nativeSwap")).get("maximumMicros"));
        assertEquals(1_000.0, map(all.get("messageProcessing")).get("maximumMicros"));
        assertEquals(4_000.0, map(all.get("otherAfterSwap")).get("maximumMicros"));
        Map<String, Object> worst = map(list(all.get("worstFrames")).get(0));
        assertEquals(40_000L, worst.get("durationMicros"));
        assertEquals(20_000L, worst.get("preSwapMicros"));
        assertEquals(15_000L, worst.get("swapMicros"));
    }

    @Test
    void splitsExactCampaignLimiterSleepFromTheSameFramePreSwapInterval() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.limiterInstalled();
        FrameTimeRuntime.recordBoundary(0L);
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.observeCampaignPaused(true);
        FrameTimeRuntime.recordBoundary(1_000_000L); // state transition

        FrameTimeRuntime.recordLimiterSleepStarted(10L, 2_000_000L);
        FrameTimeRuntime.recordLimiterSleepCompleted(12_500_000L);
        FrameTimeRuntime.observeCampaign();
        FrameTimeRuntime.observeCampaignPaused(true);
        FrameTimeRuntime.recordSwapStarted(55_000_000L);
        FrameTimeRuntime.recordSwapCompleted(56_000_000L);
        FrameTimeRuntime.recordMessagesStarted(57_000_000L);
        FrameTimeRuntime.recordMessagesCompleted(58_000_000L);
        FrameTimeRuntime.recordBoundary(61_000_000L);

        Map<String, Object> telemetry = FrameTimeRuntime.telemetry();
        Map<String, Object> limiter = map(telemetry.get("frameLimiter"));
        assertEquals(true, limiter.get("installed"));
        assertEquals(1L, limiter.get("sleepCalls"));
        assertEquals(1L, limiter.get("sleepCompletions"));
        assertEquals(10L, limiter.get("requestedMillisTotal"));

        Map<String, Object> campaign = map(
                map(telemetry.get("displayPhases")).get(FrameTimeTelemetry.CAMPAIGN_ACTIVE));
        assertEquals(1L, campaign.get("limiterCompleteFrames"));
        assertEquals(0L, campaign.get("limiterSplitUnavailableFrames"));
        assertEquals(10_500.0, map(campaign.get("limiterSleep")).get("maximumMicros"));
        assertEquals(43_500.0,
                map(campaign.get("preSwapExcludingLimiter")).get("maximumMicros"));

        Map<String, Object> packet = map(list(
                map(telemetry.get(FrameTimeTelemetry.HITCH_PACKETS)).get("packets")).get(0));
        Map<String, Object> trigger = map(list(packet.get("frameHistory")).get(0));
        assertEquals(true, trigger.get("limiterSplitComplete"));
        assertEquals(10L, trigger.get("limiterRequestedMillis"));
        assertEquals(10_500L, trigger.get("limiterElapsedMicros"));
        assertEquals(43_500L, trigger.get("preSwapExcludingLimiterMicros"));
        assertEquals(500L, trigger.get("limiterOvershootMicros"));
    }

    @Test
    void forceVsyncOffIsExplicitAndLeavesDisabledRequestsDisabled() {
        FrameTimeRuntime.beginSession(false, true);
        assertFalse(FrameTimeRuntime.requestedVsync(true));
        assertFalse(FrameTimeRuntime.requestedVsync(false));
        Map<String, Object> telemetry = FrameTimeRuntime.telemetry();
        Map<String, Object> policy = map(telemetry.get("presentationPolicy"));
        assertFalse((Boolean) telemetry.get("enabled"));
        assertEquals(true, policy.get("forceVsyncOff"));
        assertEquals(2L, policy.get("requests"));
        assertEquals(1L, policy.get("enabledRequests"));
        assertEquals(1L, policy.get("requestsForcedOff"));
        assertEquals(0L, telemetry.get("boundaries"));
    }

    @Test
    void reportsMissingAndInvalidDisplayPhaseObservationsWithoutAffectingFrameTimes() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(0L);
        FrameTimeRuntime.recordBoundary(10L);
        FrameTimeRuntime.recordSwapStarted(20L);
        FrameTimeRuntime.recordSwapCompleted(19L);
        FrameTimeRuntime.recordBoundary(30L);

        Map<String, Object> phases = map(
                map(FrameTimeRuntime.telemetry().get("displayPhases")).get("allActive"));
        assertEquals(2L, phases.get("frames"));
        assertEquals(1L, phases.get("missingSwap"));
        assertEquals(1L, phases.get("invalidOrder"));
        assertEquals(2L, map(FrameTimeRuntime.telemetry().get("allActive")).get("frames"));
    }

    @Test
    void retainsOnlyTheWorstBoundedSet() {
        FrameTimeRuntime.beginSession(true);
        long now = 0L;
        FrameTimeRuntime.recordBoundary(now);
        for (int i = 1; i <= 140; i++) {
            now += i * 1_000_000L;
            FrameTimeRuntime.recordBoundary(now);
        }
        List<Object> worst = list(map(FrameTimeRuntime.telemetry().get("allActive"))
                .get("worstFrames"));
        assertEquals(128, worst.size());
        assertEquals(140_000L, map(worst.get(0)).get("durationMicros"));
        assertEquals(13_000L, map(worst.get(127)).get("durationMicros"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}

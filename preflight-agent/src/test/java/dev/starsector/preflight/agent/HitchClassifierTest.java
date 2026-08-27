package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HitchClassifierTest {
    @Test
    void classifiesGpuHeavyFrameWhenJoinedGpuTimeDominates() {
        Map<String, Object> result = HitchClassifier.classifyFrame(frame(values(
                "durationMicros", 100_000L,
                "gpuElapsedMicros", 78_000L,
                "preSwapMicros", 15_000L,
                "nativeSwapMicros", 500L,
                "swapThreadCpuComplete", true,
                "swapThreadCpuMicros", 200L,
                "swapInferredOffCpuMicros", 300L,
                "messageMicros", 300L,
                "otherAfterSwapMicros", 200L)));

        assertEquals(HitchClassifier.GPU_HEAVY, result.get("label"));
        assertEquals("gpuElapsedMicros", result.get("dominantTrack"));
    }

    @Test
    void classifiesPausedPresentationWaitBelowTheJavaSwapBoundary() {
        Map<String, Object> result = HitchClassifier.classifyFrame(frame(values(
                "durationMicros", 33_500L,
                "preSwapMicros", 14_000L,
                "nativeSwapMicros", 17_000L,
                "swapThreadCpuComplete", true,
                "swapThreadCpuMicros", 400L,
                "swapInferredOffCpuMicros", 16_600L,
                "messageMicros", 1_000L,
                "otherAfterSwapMicros", 1_500L)));

        assertEquals(HitchClassifier.PRESENTATION_OFF_CPU, result.get("label"));
        assertEquals("swapInferredOffCpuMicros", result.get("dominantTrack"));
    }

    @Test
    void classifiesNativeSwapCpuWhenRenderThreadConsumesTheFrame() {
        Map<String, Object> result = HitchClassifier.classifyFrame(frame(values(
                "durationMicros", 20_000L,
                "preSwapMicros", 4_000L,
                "nativeSwapMicros", 10_000L,
                "swapThreadCpuComplete", true,
                "swapThreadCpuMicros", 9_000L,
                "swapInferredOffCpuMicros", 1_000L,
                "messageMicros", 3_000L,
                "otherAfterSwapMicros", 3_000L)));

        assertEquals(HitchClassifier.NATIVE_SWAP_CPU_OR_DRIVER, result.get("label"));
        assertEquals("swapThreadCpuMicros", result.get("dominantTrack"));
    }

    @Test
    void classifiesLimiterOversleepFromOvershootRatherThanRequestedSleep() {
        Map<String, Object> result = HitchClassifier.classifyFrame(frame(values(
                "preSwapMicros", 45_000L,
                "nativeSwapMicros", 5_000L,
                "swapThreadCpuComplete", true,
                "swapThreadCpuMicros", 500L,
                "swapInferredOffCpuMicros", 4_500L,
                "messageMicros", 2_000L,
                "otherAfterSwapMicros", 3_000L,
                "limiterSplitComplete", true,
                "limiterElapsedMicros", 25_000L,
                "limiterOvershootMicros", 20_000L,
                "preSwapExcludingLimiterMicros", 20_000L)));

        assertEquals(HitchClassifier.LIMITER_OVERSLEEP, result.get("label"));
        assertEquals("limiterOvershootMicros", result.get("dominantTrack"));
    }

    @Test
    void classifiesPausedPreSwapWorkAfterExactLimiterSubtraction() {
        Map<String, Object> result = HitchClassifier.classifyFrame(frame(values(
                "preSwapMicros", 53_000L,
                "nativeSwapMicros", 400L,
                "swapThreadCpuComplete", true,
                "swapThreadCpuMicros", 300L,
                "swapInferredOffCpuMicros", 100L,
                "messageMicros", 300L,
                "otherAfterSwapMicros", 300L,
                "limiterSplitComplete", true,
                "limiterElapsedMicros", 0L,
                "limiterOvershootMicros", 0L,
                "preSwapExcludingLimiterMicros", 53_000L)));

        assertEquals(HitchClassifier.PRE_SWAP_WORK, result.get("label"));
        assertTrue(result.get("nextExperiment").toString().contains("simulation advancement"));
    }

    @Test
    void unpausedPreSwapWorkRoutesThroughPauseControl() {
        Map<String, Object> candidate = frame(values(
                "pause", "unpaused",
                "preSwapMicros", 40_000L,
                "nativeSwapMicros", 5_000L,
                "messageMicros", 2_000L,
                "otherAfterSwapMicros", 3_000L));
        Map<String, Object> result = HitchClassifier.classifyFrame(candidate);

        assertEquals(HitchClassifier.PRE_SWAP_WORK, result.get("label"));
        assertTrue(result.get("nextExperiment").toString().contains("paused/unpaused control"));
    }

    @Test
    void classifiesMessagePumpAndResidualAfterSwapDomains() {
        Map<String, Object> message = HitchClassifier.classifyFrame(frame(values(
                "durationMicros", 20_000L,
                "preSwapMicros", 5_000L,
                "nativeSwapMicros", 3_000L,
                "messageMicros", 8_000L,
                "otherAfterSwapMicros", 4_000L)));
        Map<String, Object> residual = HitchClassifier.classifyFrame(frame(values(
                "durationMicros", 20_000L,
                "preSwapMicros", 5_000L,
                "nativeSwapMicros", 3_000L,
                "messageMicros", 4_000L,
                "otherAfterSwapMicros", 8_000L)));

        assertEquals(HitchClassifier.MESSAGE_PUMP, message.get("label"));
        assertEquals(HitchClassifier.AFTER_SWAP_OTHER, residual.get("label"));
    }

    @Test
    void mixedFrameKeepsTheLargestResidualExplicit() {
        Map<String, Object> result = HitchClassifier.classifyFrame(frame(values(
                "durationMicros", 20_000L,
                "preSwapMicros", 8_000L,
                "nativeSwapMicros", 6_000L,
                "messageMicros", 3_000L,
                "otherAfterSwapMicros", 3_000L)));

        assertEquals(HitchClassifier.MIXED, result.get("label"));
        assertEquals(8_000L, result.get("dominantMicros"));
    }

    @Test
    void packetSummaryInjectsPacketSemanticContextAndClassifiesOnlyTriggers() {
        Map<String, Object> first = frame(values(
                "sequence", 10L,
                "trigger", true,
                "preSwapMicros", 53_000L,
                "nativeSwapMicros", 400L,
                "swapThreadCpuComplete", true,
                "swapThreadCpuMicros", 300L,
                "swapInferredOffCpuMicros", 100L,
                "messageMicros", 300L,
                "otherAfterSwapMicros", 300L,
                "limiterSplitComplete", true,
                "preSwapExcludingLimiterMicros", 53_000L));
        Map<String, Object> context = frame(values(
                "sequence", 11L,
                "trigger", false,
                "durationMicros", 33_500L,
                "preSwapMicros", 14_000L,
                "nativeSwapMicros", 17_000L,
                "swapThreadCpuComplete", true,
                "swapThreadCpuMicros", 400L,
                "swapInferredOffCpuMicros", 16_600L,
                "messageMicros", 1_000L,
                "otherAfterSwapMicros", 1_500L));
        first.remove("pause");
        context.remove("pause");
        Map<String, Object> hitchTelemetry = Map.of(
                "format", HitchPacketRuntime.FORMAT,
                "packets", List.of(Map.of(
                        "index", 0,
                        "state", "campaign",
                        "pause", "paused",
                        "frameHistory", List.of(first, context))));

        Map<String, Object> classified = HitchClassifier.classifyPackets(hitchTelemetry);
        Map<String, Object> packet = map(list(classified.get("packets")).get(0));
        assertEquals(1, packet.get("triggerFrames"));
        assertEquals(HitchClassifier.PRE_SWAP_WORK, packet.get("primaryLabel"));
        assertFalse((Boolean) packet.get("mixedLabels"));
        Map<String, Object> trigger = map(list(packet.get("triggers")).get(0));
        assertTrue(trigger.get("nextExperiment").toString().contains("simulation advancement"));
    }

    @Test
    void packetSummaryMarksMixedTriggerDomains() {
        Map<String, Object> preSwap = frame(values(
                "sequence", 20L,
                "preSwapMicros", 55_000L,
                "nativeSwapMicros", 2_000L,
                "messageMicros", 1_000L,
                "otherAfterSwapMicros", 2_000L));
        Map<String, Object> message = frame(values(
                "sequence", 21L,
                "durationMicros", 60_000L,
                "preSwapMicros", 15_000L,
                "nativeSwapMicros", 10_000L,
                "messageMicros", 30_000L,
                "otherAfterSwapMicros", 5_000L));
        Map<String, Object> classified = HitchClassifier.classifyPackets(Map.of(
                "format", HitchPacketRuntime.FORMAT,
                "packets", List.of(Map.of(
                        "index", 2,
                        "state", "combat",
                        "pause", "unavailable",
                        "frameHistory", List.of(preSwap, message)))));

        Map<String, Object> packet = map(list(classified.get("packets")).get(0));
        assertTrue((Boolean) packet.get("mixedLabels"));
        assertEquals(2, map(packet.get("labelCounts")).size());
    }

    @Test
    void emptyPacketAndMissingPhaseCaptureStayExplicit() {
        Map<String, Object> result = HitchClassifier.classifyFrame(Map.of(
                "durationMicros", 60_000L,
                "phasesComplete", false));
        Map<String, Object> classified = HitchClassifier.classifyPackets(Map.of(
                "format", HitchPacketRuntime.FORMAT,
                "packets", List.of(Map.of(
                        "index", 9,
                        "state", "campaign",
                        "pause", "paused",
                        "frameHistory", List.of()))));
        Map<String, Object> packet = map(list(classified.get("packets")).get(0));

        assertEquals(HitchClassifier.PHASES_UNAVAILABLE, result.get("label"));
        assertEquals(HitchClassifier.PHASES_UNAVAILABLE, packet.get("primaryLabel"));
        assertEquals(0, packet.get("triggerFrames"));
    }

    private static Map<String, Object> frame(Map<String, Object> additions) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("sequence", 1L);
        values.put("trigger", true);
        values.put("durationMicros", 60_000L);
        values.put("phasesComplete", true);
        values.put("pause", "paused");
        values.putAll(additions);
        return values;
    }

    private static Map<String, Object> values(Object... keyValues) {
        if ((keyValues.length & 1) != 0) throw new IllegalArgumentException("odd key/value count");
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put((String) keyValues[index], keyValues[index + 1]);
        }
        return values;
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

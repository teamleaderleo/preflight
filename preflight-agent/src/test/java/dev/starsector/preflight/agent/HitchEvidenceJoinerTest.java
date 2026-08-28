package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HitchEvidenceJoinerTest {
    @Test
    void exactSequenceAndFrameDurationJoinPromotesGpuHeavyClassification() {
        Map<String, Object> joined = HitchEvidenceJoiner.joinGpu(
                hitchTelemetry(123L, 100_000L),
                gpuTelemetry("combatComparable", 123L, 100_000.0, 78_000.0));

        assertEquals(1, joined.get("joinedTriggerFrames"));
        Map<String, Object> joinedHitch = map(joined.get("hitchPackets"));
        Map<String, Object> packet = map(list(joinedHitch.get("packets")).get(0));
        Map<String, Object> frame = map(list(packet.get("frameHistory")).get(0));
        assertEquals(78_000.0, frame.get("gpuElapsedMicros"));
        assertEquals("combatComparable", frame.get("gpuJoinSource"));
        assertEquals(100.0, frame.get("gpuSwapOffCpuMicros"));
        assertEquals(1, frame.get("gpuSwapInterval"));

        Map<String, Object> classification = HitchClassifier.classifyPackets(joinedHitch);
        Map<String, Object> classifiedPacket = map(list(classification.get("packets")).get(0));
        assertEquals(HitchClassifier.GPU_HEAVY, classifiedPacket.get("primaryLabel"));
    }

    @Test
    void frameDurationMismatchRejectsSequenceCollision() {
        Map<String, Object> joined = HitchEvidenceJoiner.joinGpu(
                hitchTelemetry(123L, 100_000L),
                gpuTelemetry("combatComparable", 123L, 90_000.0, 78_000.0));

        assertEquals(0, joined.get("joinedTriggerFrames"));
        assertEquals(1, joined.get("unjoinedTriggerFrames"));
        assertEquals(1, joined.get("frameIdentityMismatches"));
    }

    @Test
    void specificSemanticBucketWinsOverAllComparableDuplicate() {
        Map<String, Object> gpu = new LinkedHashMap<>();
        gpu.put("requested", true);
        gpu.put("combatComparable", bucket(44L, 53_000.0, 2_000.0));
        gpu.put("allComparable", bucket(44L, 53_000.0, 30_000.0));

        Map<String, Object> joined = HitchEvidenceJoiner.joinGpu(
                hitchTelemetry(44L, 53_000L), gpu);
        Map<String, Object> hitch = map(joined.get("hitchPackets"));
        Map<String, Object> packet = map(list(hitch.get("packets")).get(0));
        Map<String, Object> frame = map(list(packet.get("frameHistory")).get(0));

        assertEquals("combatComparable", frame.get("gpuJoinSource"));
        assertEquals(2_000.0, frame.get("gpuElapsedMicros"));
    }

    @Test
    void absentBoundedGpuPairStaysUnknownAndPreservesPreSwapDiagnosis() {
        Map<String, Object> joined = HitchEvidenceJoiner.joinGpu(
                hitchTelemetry(500L, 53_000L), Map.of("requested", true));
        Map<String, Object> hitch = map(joined.get("hitchPackets"));
        Map<String, Object> classification = HitchClassifier.classifyPackets(hitch);
        Map<String, Object> packet = map(list(classification.get("packets")).get(0));

        assertEquals(1, joined.get("unjoinedTriggerFrames"));
        assertEquals(HitchClassifier.PRE_SWAP_WORK, packet.get("primaryLabel"));
        assertTrue(joined.get("coverageBoundary").toString().contains("unknown"));
    }

    @Test
    void nonTriggerContextCanJoinWithoutInflatingTriggerCoverage() {
        Map<String, Object> trigger = frame(70L, 53_000L, true);
        Map<String, Object> context = frame(71L, 40_000L, false);
        Map<String, Object> hitch = Map.of(
                "format", HitchPacketRuntime.FORMAT,
                "packets", List.of(Map.of(
                        "index", 0,
                        "state", "combat",
                        "pause", "unavailable",
                        "frameHistory", List.of(trigger, context))));
        Map<String, Object> gpu = new LinkedHashMap<>();
        gpu.put("requested", true);
        gpu.put("allComparable", Map.of("worstFramePairs", List.of(
                gpuPair(70L, 53_000.0, 2_000.0),
                gpuPair(71L, 40_000.0, 20_000.0))));

        Map<String, Object> joined = HitchEvidenceJoiner.joinGpu(hitch, gpu);

        assertEquals(2, joined.get("joinedFrames"));
        assertEquals(1, joined.get("joinedTriggerFrames"));
        assertEquals(0, joined.get("unjoinedTriggerFrames"));
    }

    @Test
    void malformedGpuPairsAreSkippedWithoutInventingEvidence() {
        Map<String, Object> gpu = Map.of(
                "requested", true,
                "allComparable", Map.of("worstFramePairs", List.of(
                        Map.of("sequence", 1L, "frameMicros", 53_000.0),
                        Map.of("frameMicros", 53_000.0, "gpuMicros", 10_000.0),
                        "bad-pair")));

        Map<String, Object> joined = HitchEvidenceJoiner.joinGpu(hitchTelemetry(1L, 53_000L), gpu);

        assertEquals(0, joined.get("gpuIndexedWorstPairs"));
        assertEquals(1, joined.get("unjoinedTriggerFrames"));
    }

    @Test
    void diagnosisBundleCombinesJoinClassifierAndWorkloadSummary() {
        Map<String, Object> diagnosis = HitchEvidenceJoiner.diagnose(
                hitchTelemetry(88L, 100_000L),
                gpuTelemetry("combatComparable", 88L, 100_000.0, 78_000.0),
                workload());

        assertEquals("starsector-preflight-hitch-diagnosis-v1", diagnosis.get("format"));
        Map<String, Object> join = map(diagnosis.get("gpuJoin"));
        assertFalse(join.containsKey("hitchPackets"));
        assertEquals(1, join.get("joinedTriggerFrames"));
        Map<String, Object> classifier = map(diagnosis.get("classifier"));
        Map<String, Object> packet = map(list(classifier.get("packets")).get(0));
        assertEquals(HitchClassifier.GPU_HEAVY, packet.get("primaryLabel"));
        Map<String, Object> workload = map(diagnosis.get("combatWorkloadFingerprint"));
        assertEquals(102, workload.get("beginShips"));
    }

    @Test
    void emptyTelemetryProducesBoundedEmptyJoin() {
        Map<String, Object> joined = HitchEvidenceJoiner.joinGpu(Map.of(), Map.of());

        assertEquals(0, joined.get("packetFrames"));
        assertEquals(0, joined.get("triggerFrames"));
        assertEquals(0, joined.get("gpuIndexedWorstPairs"));
        assertTrue(list(map(joined.get("hitchPackets")).get("packets")).isEmpty());
    }

    private static Map<String, Object> hitchTelemetry(long sequence, long durationMicros) {
        return Map.of(
                "format", HitchPacketRuntime.FORMAT,
                "packets", List.of(Map.of(
                        "index", 0,
                        "state", "combat",
                        "pause", "unavailable",
                        "frameHistory", List.of(frame(sequence, durationMicros, true)))));
    }

    private static Map<String, Object> frame(long sequence, long durationMicros, boolean trigger) {
        LinkedHashMap<String, Object> frame = new LinkedHashMap<>();
        frame.put("sequence", sequence);
        frame.put("trigger", trigger);
        frame.put("durationMicros", durationMicros);
        frame.put("phasesComplete", true);
        frame.put("preSwapMicros", Math.max(15_000L, durationMicros - 1_000L));
        frame.put("nativeSwapMicros", 400L);
        frame.put("swapThreadCpuComplete", true);
        frame.put("swapThreadCpuMicros", 300L);
        frame.put("swapInferredOffCpuMicros", 100L);
        frame.put("messageMicros", 300L);
        frame.put("otherAfterSwapMicros", 300L);
        frame.put("limiterSplitComplete", true);
        frame.put("limiterOvershootMicros", 0L);
        frame.put("preSwapExcludingLimiterMicros", Math.max(15_000L, durationMicros - 1_000L));
        return frame;
    }

    private static Map<String, Object> gpuTelemetry(
            String bucket, long sequence, double frameMicros, double gpuMicros) {
        return Map.of(
                "requested", true,
                bucket, bucket(sequence, frameMicros, gpuMicros));
    }

    private static Map<String, Object> bucket(
            long sequence, double frameMicros, double gpuMicros) {
        return Map.of("worstFramePairs", List.of(gpuPair(sequence, frameMicros, gpuMicros)));
    }

    private static Map<String, Object> gpuPair(
            long sequence, double frameMicros, double gpuMicros) {
        return Map.of(
                "sequence", sequence,
                "frameMicros", frameMicros,
                "gpuMicros", gpuMicros,
                "swapOffCpuMicros", 100.0,
                "swapInterval", 1);
    }

    private static Map<String, Object> workload() {
        Map<String, Object> side = Map.of(
                "aliveFighters", 4,
                "hulks", 0,
                "aliveNonFighters", 24,
                "aliveHullFractionSum", 20.0,
                "aliveFluxFractionSum", 5.0);
        Map<String, Object> snapshot = Map.of(
                "ships", 102,
                "missiles", 20,
                "projectiles", 200,
                "combatOver", false,
                "sideZero", side,
                "sideOne", side,
                "otherOwners", Map.of());
        return Map.of(
                "recipeId", CombatStressFixtureRuntime.RECIPE_ID,
                "begin", snapshot,
                "end", snapshot,
                "combatSecondsElapsed", 39.0);
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

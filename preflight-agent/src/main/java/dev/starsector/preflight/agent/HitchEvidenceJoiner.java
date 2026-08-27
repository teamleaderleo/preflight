package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Report-time exact-sequence join from bounded GPU pairs into retained hitch packet frames. */
final class HitchEvidenceJoiner {
    static final String FORMAT = "starsector-preflight-hitch-evidence-join-v1";
    private static final double FRAME_IDENTITY_TOLERANCE_MICROS = 2.0;
    private static final List<String> GPU_BUCKETS = List.of(
            "combatComparable",
            "campaignPausedAfter30Seconds",
            "campaignUnpausedAfter30Seconds",
            "campaignAfter30Seconds",
            "allComparable");

    private HitchEvidenceJoiner() {
    }

    static Map<String, Object> joinGpu(
            Map<String, Object> hitchTelemetry, Map<String, Object> gpuTelemetry) {
        Map<Long, GpuPair> gpuPairs = indexGpuPairs(gpuTelemetry);
        Map<String, Object> joinedHitch = new LinkedHashMap<>(hitchTelemetry);
        List<Map<String, Object>> packets = new ArrayList<>();
        int packetFrames = 0;
        int triggerFrames = 0;
        int joinedFrames = 0;
        int joinedTriggers = 0;
        int identityMismatches = 0;

        Object rawPackets = hitchTelemetry.get("packets");
        if (rawPackets instanceof List<?> values) {
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> rawPacket)) continue;
                Map<String, Object> packet = new LinkedHashMap<>(cast(rawPacket));
                List<Map<String, Object>> history = new ArrayList<>();
                Object rawHistory = packet.get("frameHistory");
                if (rawHistory instanceof List<?> frames) {
                    for (Object frameValue : frames) {
                        if (!(frameValue instanceof Map<?, ?> rawFrame)) continue;
                        Map<String, Object> frame = new LinkedHashMap<>(cast(rawFrame));
                        packetFrames++;
                        boolean trigger = Boolean.TRUE.equals(frame.get("trigger"));
                        if (trigger) triggerFrames++;
                        Long sequence = numberLong(frame.get("sequence"));
                        GpuPair pair = sequence == null ? null : gpuPairs.get(sequence);
                        if (pair != null && frameIdentityMatches(frame, pair)) {
                            frame.put("gpuElapsedMicros", pair.gpuMicros());
                            frame.put("gpuJoinedFrameMicros", pair.frameMicros());
                            frame.put("gpuJoinSource", pair.source());
                            frame.put("gpuSwapOffCpuMicros", pair.swapOffCpuMicros());
                            frame.put("gpuSwapInterval", pair.swapInterval());
                            joinedFrames++;
                            if (trigger) joinedTriggers++;
                        } else if (pair != null) {
                            identityMismatches++;
                        }
                        history.add(frame);
                    }
                }
                packet.put("frameHistory", history);
                packets.add(packet);
            }
        }
        joinedHitch.put("packets", packets);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", FORMAT);
        result.put("classification", "report-time exact frame-sequence join; no frame hot-path work");
        result.put("gpuRequested", gpuTelemetry.get("requested"));
        result.put("gpuIndexedWorstPairs", gpuPairs.size());
        result.put("packetFrames", packetFrames);
        result.put("triggerFrames", triggerFrames);
        result.put("joinedFrames", joinedFrames);
        result.put("joinedTriggerFrames", joinedTriggers);
        result.put("unjoinedTriggerFrames", Math.max(0, triggerFrames - joinedTriggers));
        result.put("frameIdentityMismatches", identityMismatches);
        result.put("frameIdentityToleranceMicros", FRAME_IDENTITY_TOLERANCE_MICROS);
        result.put("coverageBoundary",
                "GPU telemetry retains only bounded worst-frame pairs per bucket; an absent join is unknown, not zero GPU time");
        result.put("hitchPackets", joinedHitch);
        return result;
    }

    static Map<String, Object> diagnose(
            Map<String, Object> hitchTelemetry,
            Map<String, Object> gpuTelemetry,
            Map<String, Object> combatWorkload) {
        Map<String, Object> joined = joinGpu(hitchTelemetry, gpuTelemetry);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", "starsector-preflight-hitch-diagnosis-v1");
        result.put("gpuJoin", withoutJoinedPackets(joined));
        result.put("classifier", HitchClassifier.classifyPackets(map(joined.get("hitchPackets"))));
        result.put("combatWorkloadFingerprint", CombatWorkloadFingerprintGate.summarize(combatWorkload));
        result.put("decisionBoundary",
                "classification routes the next causal experiment; workload comparison is a separate cohort gate");
        return result;
    }

    private static Map<Long, GpuPair> indexGpuPairs(Map<String, Object> gpuTelemetry) {
        Map<Long, GpuPair> pairs = new LinkedHashMap<>();
        for (String bucket : GPU_BUCKETS) {
            Map<String, Object> values = map(gpuTelemetry.get(bucket));
            Object rawPairs = values.get("worstFramePairs");
            if (!(rawPairs instanceof List<?> pairValues)) continue;
            for (Object value : pairValues) {
                if (!(value instanceof Map<?, ?> rawPair)) continue;
                Map<String, Object> pair = cast(rawPair);
                Long sequence = numberLong(pair.get("sequence"));
                Double frameMicros = numberDouble(pair.get("frameMicros"));
                Double gpuMicros = numberDouble(pair.get("gpuMicros"));
                if (sequence == null || frameMicros == null || gpuMicros == null) continue;
                pairs.putIfAbsent(sequence, new GpuPair(
                        bucket,
                        frameMicros,
                        gpuMicros,
                        numberDouble(pair.get("swapOffCpuMicros")),
                        numberInteger(pair.get("swapInterval"))));
            }
        }
        return pairs;
    }

    private static boolean frameIdentityMatches(Map<String, Object> frame, GpuPair pair) {
        Double duration = numberDouble(frame.get("durationMicros"));
        return duration != null
                && Math.abs(duration - pair.frameMicros()) <= FRAME_IDENTITY_TOLERANCE_MICROS;
    }

    private static Map<String, Object> withoutJoinedPackets(Map<String, Object> joined) {
        Map<String, Object> values = new LinkedHashMap<>(joined);
        values.remove("hitchPackets");
        return values;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? cast(raw) : Map.of();
    }

    private static Long numberLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Double numberDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Integer numberInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private record GpuPair(
            String source,
            double frameMicros,
            double gpuMicros,
            Double swapOffCpuMicros,
            Integer swapInterval) {
    }
}

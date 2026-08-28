package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure report-time classifier for retained hitch-packet frames. */
final class HitchClassifier {
    static final String FORMAT = "starsector-preflight-hitch-classifier-v1";

    static final String GPU_HEAVY = "GPU_HEAVY";
    static final String PRESENTATION_OFF_CPU = "PRESENTATION_OFF_CPU";
    static final String NATIVE_SWAP_CPU_OR_DRIVER = "NATIVE_SWAP_CPU_OR_DRIVER";
    static final String LIMITER_OVERSLEEP = "LIMITER_OVERSLEEP";
    static final String PRE_SWAP_WORK = "PRE_SWAP_WORK";
    static final String MESSAGE_PUMP = "MESSAGE_PUMP";
    static final String AFTER_SWAP_OTHER = "AFTER_SWAP_OTHER";
    static final String MIXED = "MIXED";
    static final String PHASES_UNAVAILABLE = "PHASES_UNAVAILABLE";

    private static final long MIN_GPU_HEAVY_MICROS = 12_000L;
    private static final long MIN_PRESENTATION_WAIT_MICROS = 8_000L;
    private static final long MIN_NATIVE_SWAP_CPU_MICROS = 5_000L;
    private static final long MIN_LIMITER_OVERSHOOT_MICROS = 2_000L;

    private HitchClassifier() {
    }

    static Map<String, Object> classifyFrame(Map<String, Object> frame) {
        long total = positiveLong(frame, "durationMicros");
        if (total <= 0L || !Boolean.TRUE.equals(frame.get("phasesComplete"))) {
            return result(
                    PHASES_UNAVAILABLE,
                    "capture a complete presentation-phase frame before escalating",
                    total,
                    null,
                    0L,
                    0.0,
                    "required presentation spans are unavailable");
        }

        Long gpu = optionalLong(frame, "gpuElapsedMicros", "gpuMicros");
        long preSwap = nonNegativeLong(frame, "preSwapMicros");
        long swap = nonNegativeLong(frame, "nativeSwapMicros");
        long messages = nonNegativeLong(frame, "messageMicros");
        long other = nonNegativeLong(frame, "otherAfterSwapMicros");
        boolean swapCpuComplete = Boolean.TRUE.equals(frame.get("swapThreadCpuComplete"));
        long swapCpu = swapCpuComplete ? nonNegativeLong(frame, "swapThreadCpuMicros") : 0L;
        long swapOffCpu = swapCpuComplete
                ? nonNegativeLong(frame, "swapInferredOffCpuMicros") : 0L;
        boolean limiterComplete = Boolean.TRUE.equals(frame.get("limiterSplitComplete"));
        long preSwapExcludingLimiter = limiterComplete
                ? nonNegativeLong(frame, "preSwapExcludingLimiterMicros") : preSwap;
        long limiterOvershoot = limiterComplete
                ? Math.max(0L, signedLong(frame, "limiterOvershootMicros")) : 0L;

        if (gpu != null && gpu >= MIN_GPU_HEAVY_MICROS && share(gpu, total) >= 0.45) {
            return result(
                    GPU_HEAVY,
                    "sweep camera density, zoom, resolution, and effect families; then time the dominant render family",
                    total,
                    "gpuElapsedMicros",
                    gpu,
                    share(gpu, total),
                    "whole-frame GPU elapsed time consumes a material share of the bad frame");
        }

        if (limiterComplete
                && limiterOvershoot >= MIN_LIMITER_OVERSHOOT_MICROS
                && share(limiterOvershoot, total) >= 0.25) {
            return result(
                    LIMITER_OVERSLEEP,
                    "sweep FPS cap values and measure requested sleep versus overshoot under a thin pacing probe",
                    total,
                    "limiterOvershootMicros",
                    limiterOvershoot,
                    share(limiterOvershoot, total),
                    "limiter overshoot itself consumes a material share of the frame");
        }

        if (swapCpuComplete
                && swapOffCpu >= MIN_PRESENTATION_WAIT_MICROS
                && share(swapOffCpu, total) >= 0.35
                && (swap == 0L || share(swapOffCpu, swap) >= 0.70)) {
            return result(
                    PRESENTATION_OFF_CPU,
                    "sweep VSync/swap interval and cap policy; pair with asynchronous GPU time to separate backlog from compositor wait",
                    total,
                    "swapInferredOffCpuMicros",
                    swapOffCpu,
                    share(swapOffCpu, total),
                    "native swap is dominated by time off the render thread");
        }

        if (swapCpuComplete
                && swapCpu >= MIN_NATIVE_SWAP_CPU_MICROS
                && share(swapCpu, total) >= 0.35) {
            return result(
                    NATIVE_SWAP_CPU_OR_DRIVER,
                    "count and attribute the exact GL/native submission family active before and inside swap",
                    total,
                    "swapThreadCpuMicros",
                    swapCpu,
                    share(swapCpu, total),
                    "current render-thread CPU inside native swap consumes a material share of the frame");
        }

        if (share(preSwapExcludingLimiter, total) >= 0.45) {
            String pause = string(frame.get("pause"));
            String next = "run paused/unpaused control and packet-triggered CPU/native/JVM attribution around pre-swap work";
            if ("paused".equals(pause)) {
                next = "keep rendering paused and escalate packet-triggered CPU/native/JVM attribution; ordinary simulation advancement is already suppressed";
            }
            return result(
                    PRE_SWAP_WORK,
                    next,
                    total,
                    limiterComplete ? "preSwapExcludingLimiterMicros" : "preSwapMicros",
                    preSwapExcludingLimiter,
                    share(preSwapExcludingLimiter, total),
                    "pre-swap work dominates after subtracting the exact limiter when available");
        }

        if (share(messages, total) >= 0.35) {
            return result(
                    MESSAGE_PUMP,
                    "repeat with focus/input/window-event controls and inspect native message-pump activity",
                    total,
                    "messageMicros",
                    messages,
                    share(messages, total),
                    "native message processing consumes a material share of the frame");
        }

        if (share(other, total) >= 0.35) {
            return result(
                    AFTER_SWAP_OTHER,
                    "split the residual post-swap span before selecting an optimization",
                    total,
                    "otherAfterSwapMicros",
                    other,
                    share(other, total),
                    "the residual after-swap span is the largest unresolved component");
        }

        long largest = Math.max(preSwapExcludingLimiter, Math.max(swap, Math.max(messages, other)));
        return result(
                MIXED,
                "add the cheapest missing causal track for the largest unresolved span; avoid a broad optimization guess",
                total,
                null,
                largest,
                share(largest, total),
                "no single retained phase crosses the classifier dominance threshold");
    }

    static Map<String, Object> classifyPackets(Map<String, Object> hitchTelemetry) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", FORMAT);
        result.put("classification", "report-time only; no frame hot-path work");
        result.put("sourceFormat", hitchTelemetry.get("format"));

        List<Map<String, Object>> packets = new ArrayList<>();
        Object rawPackets = hitchTelemetry.get("packets");
        if (rawPackets instanceof List<?> values) {
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> rawPacket)) continue;
                packets.add(classifyPacket(cast(rawPacket)));
            }
        }
        result.put("packets", packets);
        return result;
    }

    private static Map<String, Object> classifyPacket(Map<String, Object> packet) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Map<String, Object>> triggers = new ArrayList<>();
        Object history = packet.get("frameHistory");
        if (history instanceof List<?> frames) {
            for (Object value : frames) {
                if (!(value instanceof Map<?, ?> rawFrame)) continue;
                Map<String, Object> raw = cast(rawFrame);
                if (!Boolean.TRUE.equals(raw.get("trigger"))) continue;
                Map<String, Object> frame = new LinkedHashMap<>(raw);
                frame.putIfAbsent("state", packet.get("state"));
                frame.putIfAbsent("pause", packet.get("pause"));
                Map<String, Object> classified = classifyFrame(frame);
                String label = string(classified.get("label"));
                counts.merge(label, 1, Integer::sum);
                Map<String, Object> retained = new LinkedHashMap<>();
                retained.put("sequence", frame.get("sequence"));
                retained.putAll(classified);
                triggers.add(retained);
            }
        }

        String primary = PHASES_UNAVAILABLE;
        int best = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > best) {
                primary = entry.getKey();
                best = entry.getValue();
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packetIndex", packet.get("index"));
        result.put("state", packet.get("state"));
        result.put("pause", packet.get("pause"));
        result.put("triggerFrames", triggers.size());
        result.put("primaryLabel", primary);
        result.put("mixedLabels", counts.size() > 1);
        result.put("labelCounts", counts);
        result.put("triggers", triggers);
        return result;
    }

    private static Map<String, Object> result(
            String label,
            String nextExperiment,
            long totalMicros,
            String dominantTrack,
            long dominantMicros,
            double dominantShare,
            String evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("label", label);
        value.put("nextExperiment", nextExperiment);
        value.put("durationMicros", totalMicros);
        value.put("dominantTrack", dominantTrack);
        value.put("dominantMicros", dominantMicros);
        value.put("dominantShare", dominantShare);
        value.put("evidence", evidence);
        return value;
    }

    private static double share(long part, long total) {
        if (part <= 0L || total <= 0L) return 0.0;
        return part * 1.0 / total;
    }

    private static long positiveLong(Map<String, Object> values, String key) {
        Long value = optionalLong(values, key);
        return value == null || value <= 0L ? 0L : value;
    }

    private static long nonNegativeLong(Map<String, Object> values, String key) {
        Long value = optionalLong(values, key);
        return value == null ? 0L : Math.max(0L, value);
    }

    private static long signedLong(Map<String, Object> values, String key) {
        Long value = optionalLong(values, key);
        return value == null ? 0L : value;
    }

    private static Long optionalLong(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value instanceof Number number) return number.longValue();
        }
        return null;
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }
}

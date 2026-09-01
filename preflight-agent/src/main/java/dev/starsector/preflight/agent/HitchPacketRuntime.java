package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded primitive flight recorder for semantically stable gameplay hitches. */
final class HitchPacketRuntime {
    static final String FORMAT = "starsector-preflight-hitch-packets-v1";
    static final long TRIGGER_NANOS = 50_000_000L;
    static final long SEVERE_NANOS = 100_000_000L;
    static final long PRE_TRIGGER_NANOS = 2_000_000_000L;
    static final long POST_TRIGGER_NANOS = 1_000_000_000L;
    static final int RECENT_FRAME_CAPACITY = 256;
    static final int PACKET_LIMIT = 8;
    static final int PACKET_FRAME_CAPACITY = 384;
    static final int CALL_SPAN_CAPACITY = 32_768;
    private static final int CAMPAIGN_PHASES = 9;
    private static final int STATE_CAMPAIGN = 1;
    private static final int STATE_COMBAT = 2;
    private static final int PAUSE_PAUSED = 1;
    private static final int PAUSE_UNPAUSED = 2;

    private static final long[] recentSequence = new long[RECENT_FRAME_CAPACITY];
    private static final long[] recentStart = new long[RECENT_FRAME_CAPACITY];
    private static final long[] recentEnd = new long[RECENT_FRAME_CAPACITY];
    private static final long[] recentTotal = new long[RECENT_FRAME_CAPACITY];
    private static final long[] recentPreSwap = new long[RECENT_FRAME_CAPACITY];
    private static final long[] recentSwap = new long[RECENT_FRAME_CAPACITY];
    private static final long[] recentSwapThreadCpu = new long[RECENT_FRAME_CAPACITY];
    private static final long[] recentSwapOffCpu = new long[RECENT_FRAME_CAPACITY];
    private static final long[] recentMessages = new long[RECENT_FRAME_CAPACITY];
    private static final long[] recentOther = new long[RECENT_FRAME_CAPACITY];
    private static final long[] recentLimiterRequestedMillis = new long[RECENT_FRAME_CAPACITY];
    private static final long[] recentLimiter = new long[RECENT_FRAME_CAPACITY];
    private static final long[] recentPreSwapExcludingLimiter = new long[RECENT_FRAME_CAPACITY];
    private static final int[] recentState = new int[RECENT_FRAME_CAPACITY];
    private static final int[] recentPause = new int[RECENT_FRAME_CAPACITY];
    private static final boolean[] recentPhasesComplete = new boolean[RECENT_FRAME_CAPACITY];
    private static final boolean[] recentSwapThreadCpuComplete =
            new boolean[RECENT_FRAME_CAPACITY];
    private static final boolean[] recentLimiterComplete = new boolean[RECENT_FRAME_CAPACITY];

    private static final int PACKET_FRAME_SLOTS = PACKET_LIMIT * PACKET_FRAME_CAPACITY;
    private static final long[] packetSequence = new long[PACKET_FRAME_SLOTS];
    private static final long[] packetStart = new long[PACKET_FRAME_SLOTS];
    private static final long[] packetEnd = new long[PACKET_FRAME_SLOTS];
    private static final long[] packetTotal = new long[PACKET_FRAME_SLOTS];
    private static final long[] packetPreSwap = new long[PACKET_FRAME_SLOTS];
    private static final long[] packetSwap = new long[PACKET_FRAME_SLOTS];
    private static final long[] packetSwapThreadCpu = new long[PACKET_FRAME_SLOTS];
    private static final long[] packetSwapOffCpu = new long[PACKET_FRAME_SLOTS];
    private static final long[] packetMessages = new long[PACKET_FRAME_SLOTS];
    private static final long[] packetOther = new long[PACKET_FRAME_SLOTS];
    private static final long[] packetLimiterRequestedMillis = new long[PACKET_FRAME_SLOTS];
    private static final long[] packetLimiter = new long[PACKET_FRAME_SLOTS];
    private static final long[] packetPreSwapExcludingLimiter = new long[PACKET_FRAME_SLOTS];
    private static final boolean[] packetPhasesComplete = new boolean[PACKET_FRAME_SLOTS];
    private static final boolean[] packetSwapThreadCpuComplete =
            new boolean[PACKET_FRAME_SLOTS];
    private static final boolean[] packetLimiterComplete = new boolean[PACKET_FRAME_SLOTS];
    private static final int[] packetFrameCounts = new int[PACKET_LIMIT];
    private static final int[] packetStates = new int[PACKET_LIMIT];
    private static final int[] packetPauses = new int[PACKET_LIMIT];
    private static final long[] packetTriggerSequences = new long[PACKET_LIMIT];
    private static final int[] packetTriggers = new int[PACKET_LIMIT];
    private static final int[] packetSevereTriggers = new int[PACKET_LIMIT];
    private static final long[] packetPostDeadlines = new long[PACKET_LIMIT];
    private static final long[] packetTruncatedFrames = new long[PACKET_LIMIT];
    private static final boolean[] packetFinalized = new boolean[PACKET_LIMIT];
    private static final boolean[] packetPostWindowComplete = new boolean[PACKET_LIMIT];
    private static final boolean[] packetPhaseProducerEnabled = new boolean[PACKET_LIMIT];
    private static final boolean[] packetPhaseCoverageComplete = new boolean[PACKET_LIMIT];
    private static final long[] packetJoinedCallSpans = new long[PACKET_LIMIT];
    private static final Object[] packetReports = new Object[PACKET_LIMIT];

    private static final int PACKET_PHASE_SLOTS = PACKET_FRAME_SLOTS * CAMPAIGN_PHASES;
    private static final long[] packetPhaseOverlap = new long[PACKET_PHASE_SLOTS];
    private static final long[] packetPhaseMaximum = new long[PACKET_PHASE_SLOTS];
    private static final int[] packetPhaseCalls = new int[PACKET_PHASE_SLOTS];

    private static final long[] callStart = new long[CALL_SPAN_CAPACITY];
    private static final long[] callEnd = new long[CALL_SPAN_CAPACITY];
    private static final int[] callPhase = new int[CALL_SPAN_CAPACITY];

    private static boolean enabled;
    private static boolean campaignPhaseProducerEnabled;
    private static long originNanos = Long.MIN_VALUE;
    private static long originEpochMillis = -1L;
    private static int recentHead;
    private static int recentCount;
    private static int packetCount;
    private static int activePacket = -1;
    private static long packetTriggersDropped;
    private static int callHead;
    private static int callCount;
    private static long callWrites;
    private static long callOverwrites;

    private HitchPacketRuntime() {
    }

    static void beginSession(boolean requested) {
        enabled = requested;
        campaignPhaseProducerEnabled = false;
        originNanos = Long.MIN_VALUE;
        originEpochMillis = -1L;
        recentHead = 0;
        recentCount = 0;
        packetCount = 0;
        activePacket = -1;
        packetTriggersDropped = 0L;
        callHead = 0;
        callCount = 0;
        callWrites = 0L;
        callOverwrites = 0L;
        Arrays.fill(packetFrameCounts, 0);
        Arrays.fill(packetFinalized, false);
        Arrays.fill(packetPostWindowComplete, false);
        Arrays.fill(packetTruncatedFrames, 0L);
        Arrays.fill(packetJoinedCallSpans, 0L);
        Arrays.fill(packetReports, null);
    }

    static void configureOrigin(long monotonicNanos, long epochMillis) {
        if (!enabled || originNanos != Long.MIN_VALUE) return;
        originNanos = monotonicNanos;
        originEpochMillis = epochMillis;
    }

    static void configureCampaignPhaseProducer(boolean requested) {
        campaignPhaseProducerEnabled = requested;
    }

    static void recordCampaignPhase(int phase, long startedNanos, long endedNanos) {
        if (!enabled || !campaignPhaseProducerEnabled || phase < 0 || phase >= CAMPAIGN_PHASES
                || startedNanos < 0L || endedNanos <= startedNanos) {
            return;
        }
        int target;
        if (callCount < CALL_SPAN_CAPACITY) {
            target = (callHead + callCount) % CALL_SPAN_CAPACITY;
            callCount++;
        } else {
            target = callHead;
            callHead = (callHead + 1) % CALL_SPAN_CAPACITY;
            callOverwrites++;
        }
        callStart[target] = startedNanos;
        callEnd[target] = endedNanos;
        callPhase[target] = phase;
        callWrites++;
    }

    static void recordFrame(
            long sequence,
            long startedNanos,
            long endedNanos,
            int state,
            int pause,
            boolean phasesComplete,
            long preSwapNanos,
            long swapNanos,
            boolean swapThreadCpuComplete,
            long swapThreadCpuNanos,
            long swapOffCpuNanos,
            long messagesNanos,
            long otherAfterSwapNanos,
            boolean limiterComplete,
            long limiterRequestedMillis,
            long limiterNanos,
            long preSwapExcludingLimiterNanos) {
        if (!enabled || sequence < 0L || endedNanos <= startedNanos
                || (state != STATE_CAMPAIGN && state != STATE_COMBAT)) {
            return;
        }
        long totalNanos = endedNanos - startedNanos;
        appendRecent(sequence, startedNanos, endedNanos, state, pause, phasesComplete,
                totalNanos, preSwapNanos, swapNanos,
                swapThreadCpuComplete, swapThreadCpuNanos, swapOffCpuNanos,
                messagesNanos, otherAfterSwapNanos,
                limiterComplete, limiterRequestedMillis, limiterNanos,
                preSwapExcludingLimiterNanos);
        boolean trigger = totalNanos >= TRIGGER_NANOS;

        if (activePacket >= 0) {
            int last = packetFrameCounts[activePacket] - 1;
            int lastSlot = frameSlot(activePacket, last);
            if (last < 0 || packetStates[activePacket] != state
                    || packetPauses[activePacket] != pause
                    || packetSequence[lastSlot] + 1L != sequence
                    || startedNanos > packetPostDeadlines[activePacket]) {
                finalizePacket(activePacket, false);
                activePacket = -1;
            }
        }

        if (activePacket < 0 && trigger) {
            startPacket(sequence, startedNanos, endedNanos, state, pause, totalNanos);
        } else if (activePacket >= 0) {
            appendRecentTailToActive(sequence);
            if (trigger) {
                packetTriggers[activePacket]++;
                if (totalNanos >= SEVERE_NANOS) packetSevereTriggers[activePacket]++;
                packetPostDeadlines[activePacket] = Math.max(
                        packetPostDeadlines[activePacket], endedNanos + POST_TRIGGER_NANOS);
            }
            if (endedNanos >= packetPostDeadlines[activePacket]) {
                finalizePacket(activePacket, true);
                activePacket = -1;
            }
        }
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", FORMAT);
        result.put("enabled", enabled);
        result.put("classification", "measurement-safe primitive frame ring");
        result.put("clockDomain", "System.nanoTime shared by frame and exact campaign-phase producers");
        result.put("triggerMillis", TRIGGER_NANOS / 1_000_000L);
        result.put("severeMillis", SEVERE_NANOS / 1_000_000L);
        result.put("preTriggerMillis", PRE_TRIGGER_NANOS / 1_000_000L);
        result.put("postTriggerMillis", POST_TRIGGER_NANOS / 1_000_000L);
        result.put("recentFrameCapacity", RECENT_FRAME_CAPACITY);
        result.put("packetLimit", PACKET_LIMIT);
        result.put("packetFrameCapacity", PACKET_FRAME_CAPACITY);
        result.put("packetTriggersDropped", packetTriggersDropped);
        result.put("overheadSource", "frameTimes.measurementOverhead includes recorder hot-path work");
        result.put("liveSnapshotConsistency",
                "best effort while the game is running; the sealed shutdown report is authoritative");
        Map<String, Object> producer = new LinkedHashMap<>();
        producer.put("name", "CampaignEngine major phases");
        producer.put("enabled", campaignPhaseProducerEnabled);
        producer.put("classification", "discovery-only exact call-site timing");
        producer.put("callSpanCapacity", CALL_SPAN_CAPACITY);
        producer.put("callSpansWritten", callWrites);
        producer.put("callSpansOverwritten", callOverwrites);
        result.put("campaignPhaseProducer", producer);

        List<Map<String, Object>> packets = new ArrayList<>(packetCount);
        for (int packet = 0; packet < packetCount; packet++) {
            packets.add(reportPacket(packet));
        }
        result.put("packets", packets);
        result.put("activePacket", activePacket >= 0);
        return result;
    }

    private static void appendRecent(
            long sequence,
            long startedNanos,
            long endedNanos,
            int state,
            int pause,
            boolean phasesComplete,
            long totalNanos,
            long preSwapNanos,
            long swapNanos,
            boolean swapThreadCpuComplete,
            long swapThreadCpuNanos,
            long swapOffCpuNanos,
            long messagesNanos,
            long otherAfterSwapNanos,
            boolean limiterComplete,
            long limiterRequestedMillis,
            long limiterNanos,
            long preSwapExcludingLimiterNanos) {
        int target;
        if (recentCount < RECENT_FRAME_CAPACITY) {
            target = (recentHead + recentCount) % RECENT_FRAME_CAPACITY;
            recentCount++;
        } else {
            target = recentHead;
            recentHead = (recentHead + 1) % RECENT_FRAME_CAPACITY;
        }
        recentSequence[target] = sequence;
        recentStart[target] = startedNanos;
        recentEnd[target] = endedNanos;
        recentState[target] = state;
        recentPause[target] = pause;
        recentPhasesComplete[target] = phasesComplete;
        recentTotal[target] = totalNanos;
        recentPreSwap[target] = preSwapNanos;
        recentSwap[target] = swapNanos;
        recentSwapThreadCpuComplete[target] = swapThreadCpuComplete;
        recentSwapThreadCpu[target] = swapThreadCpuNanos;
        recentSwapOffCpu[target] = swapOffCpuNanos;
        recentMessages[target] = messagesNanos;
        recentOther[target] = otherAfterSwapNanos;
        recentLimiterComplete[target] = limiterComplete;
        recentLimiterRequestedMillis[target] = limiterRequestedMillis;
        recentLimiter[target] = limiterNanos;
        recentPreSwapExcludingLimiter[target] = preSwapExcludingLimiterNanos;
    }

    private static void startPacket(
            long sequence, long startedNanos, long endedNanos, int state, int pause, long totalNanos) {
        if (packetCount >= PACKET_LIMIT) {
            packetTriggersDropped++;
            return;
        }
        int packet = packetCount++;
        activePacket = packet;
        packetFrameCounts[packet] = 0;
        packetStates[packet] = state;
        packetPauses[packet] = pause;
        packetTriggerSequences[packet] = sequence;
        packetTriggers[packet] = 1;
        packetSevereTriggers[packet] = totalNanos >= SEVERE_NANOS ? 1 : 0;
        packetPostDeadlines[packet] = endedNanos + POST_TRIGGER_NANOS;
        packetTruncatedFrames[packet] = 0L;
        packetFinalized[packet] = false;
        packetPostWindowComplete[packet] = false;
        packetPhaseProducerEnabled[packet] = campaignPhaseProducerEnabled;
        packetPhaseCoverageComplete[packet] = false;
        packetJoinedCallSpans[packet] = 0L;

        int selected = 0;
        long expectedSequence = sequence;
        long earliest = startedNanos - PRE_TRIGGER_NANOS;
        for (int offset = 0; offset < recentCount; offset++) {
            int recent = recentIndexFromNewest(offset);
            if (recentEnd[recent] < earliest || recentState[recent] != state
                    || recentPause[recent] != pause || recentSequence[recent] != expectedSequence) {
                break;
            }
            selected++;
            expectedSequence--;
        }
        for (int offset = selected - 1; offset >= 0; offset--) {
            appendRecentToPacket(packet, recentIndexFromNewest(offset));
        }
    }

    private static void appendRecentTailToActive(long sequence) {
        int packet = activePacket;
        int count = packetFrameCounts[packet];
        if (count > 0 && packetSequence[frameSlot(packet, count - 1)] == sequence) return;
        appendRecentToPacket(packet, recentIndexFromNewest(0));
    }

    private static void appendRecentToPacket(int packet, int recent) {
        int count = packetFrameCounts[packet];
        if (count >= PACKET_FRAME_CAPACITY) {
            packetTruncatedFrames[packet]++;
            return;
        }
        int target = frameSlot(packet, count);
        packetFrameCounts[packet] = count + 1;
        packetSequence[target] = recentSequence[recent];
        packetStart[target] = recentStart[recent];
        packetEnd[target] = recentEnd[recent];
        packetTotal[target] = recentTotal[recent];
        packetPreSwap[target] = recentPreSwap[recent];
        packetSwap[target] = recentSwap[recent];
        packetSwapThreadCpuComplete[target] = recentSwapThreadCpuComplete[recent];
        packetSwapThreadCpu[target] = recentSwapThreadCpu[recent];
        packetSwapOffCpu[target] = recentSwapOffCpu[recent];
        packetMessages[target] = recentMessages[recent];
        packetOther[target] = recentOther[recent];
        packetLimiterComplete[target] = recentLimiterComplete[recent];
        packetLimiterRequestedMillis[target] = recentLimiterRequestedMillis[recent];
        packetLimiter[target] = recentLimiter[recent];
        packetPreSwapExcludingLimiter[target] = recentPreSwapExcludingLimiter[recent];
        packetPhasesComplete[target] = recentPhasesComplete[recent];
        int phaseStart = phaseSlot(target, 0);
        Arrays.fill(packetPhaseOverlap, phaseStart, phaseStart + CAMPAIGN_PHASES, 0L);
        Arrays.fill(packetPhaseMaximum, phaseStart, phaseStart + CAMPAIGN_PHASES, 0L);
        Arrays.fill(packetPhaseCalls, phaseStart, phaseStart + CAMPAIGN_PHASES, 0);
    }

    private static void finalizePacket(int packet, boolean postWindowComplete) {
        packetFinalized[packet] = true;
        packetPostWindowComplete[packet] = postWindowComplete;
        Join join = joinCalls(packet, packetPhaseOverlap, packetPhaseCalls, packetPhaseMaximum);
        packetPhaseCoverageComplete[packet] = join.coverageComplete();
        packetJoinedCallSpans[packet] = join.callSpans();
    }

    private static Map<String, Object> reportPacket(int packet) {
        if (packetFinalized[packet] && packetReports[packet] instanceof Map<?, ?> cached) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) cached;
            return typed;
        }
        int frames = packetFrameCounts[packet];
        long[] overlap = packetPhaseOverlap;
        int[] calls = packetPhaseCalls;
        long[] maximum = packetPhaseMaximum;
        Join join;
        if (packetFinalized[packet]) {
            join = new Join(packetJoinedCallSpans[packet], packetPhaseCoverageComplete[packet]);
        } else {
            overlap = new long[PACKET_PHASE_SLOTS];
            calls = new int[PACKET_PHASE_SLOTS];
            maximum = new long[PACKET_PHASE_SLOTS];
            join = joinCalls(packet, overlap, calls, maximum);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", packet);
        result.put("state", stateName(packetStates[packet]));
        result.put("pause", pauseName(packetStates[packet], packetPauses[packet]));
        result.put("frames", frames);
        result.put("triggerSequence", packetTriggerSequences[packet]);
        result.put("triggers", packetTriggers[packet]);
        result.put("severeTriggers", packetSevereTriggers[packet]);
        result.put("postWindowComplete",
                packetFinalized[packet] && packetPostWindowComplete[packet]);
        result.put("truncatedFrames", packetTruncatedFrames[packet]);
        result.put("campaignPhaseProducerEnabled", packetPhaseProducerEnabled[packet]);
        result.put("campaignPhaseCoverageComplete",
                packetFinalized[packet] && join.coverageComplete());
        result.put("joinedCampaignCallSpans", join.callSpans());
        if (frames > 0) {
            int first = frameSlot(packet, 0);
            int last = frameSlot(packet, frames - 1);
            result.put("startOffsetMillis", offsetMillis(packetStart[first]));
            result.put("endOffsetMillis", offsetMillis(packetEnd[last]));
            result.put("startEpochMillis", epochMillis(packetStart[first]));
            result.put("endEpochMillis", epochMillis(packetEnd[last]));
        }
        List<Map<String, Object>> frameValues = new ArrayList<>(frames);
        for (int frame = 0; frame < frames; frame++) {
            int slot = frameSlot(packet, frame);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("sequence", packetSequence[slot]);
            value.put("trigger", packetTotal[slot] >= TRIGGER_NANOS);
            value.put("severe", packetTotal[slot] >= SEVERE_NANOS);
            value.put("durationMicros", packetTotal[slot] / 1_000L);
            value.put("startOffsetMillis", offsetMillis(packetStart[slot]));
            value.put("endOffsetMillis", offsetMillis(packetEnd[slot]));
            value.put("phasesComplete", packetPhasesComplete[slot]);
            if (packetPhasesComplete[slot]) {
                value.put("preSwapMicros", packetPreSwap[slot] / 1_000L);
                value.put("nativeSwapMicros", packetSwap[slot] / 1_000L);
                value.put("swapThreadCpuComplete", packetSwapThreadCpuComplete[slot]);
                if (packetSwapThreadCpuComplete[slot]) {
                    value.put("swapThreadCpuMicros", packetSwapThreadCpu[slot] / 1_000L);
                    value.put("swapInferredOffCpuMicros", packetSwapOffCpu[slot] / 1_000L);
                }
                value.put("messageMicros", packetMessages[slot] / 1_000L);
                value.put("otherAfterSwapMicros", packetOther[slot] / 1_000L);
            }
            value.put("limiterSplitComplete", packetLimiterComplete[slot]);
            if (packetLimiterComplete[slot]) {
                value.put("limiterRequestedMillis", packetLimiterRequestedMillis[slot]);
                value.put("limiterElapsedMicros", packetLimiter[slot] / 1_000L);
                value.put("preSwapExcludingLimiterMicros",
                        packetPreSwapExcludingLimiter[slot] / 1_000L);
                value.put("limiterOvershootMicros",
                        packetLimiter[slot] / 1_000L
                                - packetLimiterRequestedMillis[slot] * 1_000L);
            }
            List<Map<String, Object>> phaseValues = new ArrayList<>();
            for (int phase = 0; phase < CAMPAIGN_PHASES; phase++) {
                int phaseSlot = phaseSlot(slot, phase);
                if (calls[phaseSlot] == 0) continue;
                Map<String, Object> phaseValue = new LinkedHashMap<>();
                phaseValue.put("name", CampaignEngineTimeRuntime.phaseName(phase));
                phaseValue.put("calls", calls[phaseSlot]);
                phaseValue.put("overlapMicros", overlap[phaseSlot] / 1_000L);
                phaseValue.put("maximumOverlapMicros", maximum[phaseSlot] / 1_000L);
                phaseValues.add(phaseValue);
            }
            value.put("campaignPhaseOverlaps", phaseValues);
            frameValues.add(value);
        }
        result.put("frameHistory", frameValues);
        if (packetFinalized[packet]) packetReports[packet] = result;
        return result;
    }

    private static Join joinCalls(int packet, long[] overlaps, int[] calls, long[] maximum) {
        int frames = packetFrameCounts[packet];
        if (packetStates[packet] != STATE_CAMPAIGN
                || !packetPhaseProducerEnabled[packet]
                || frames == 0) {
            return new Join(0L, false);
        }
        int firstFrame = frameSlot(packet, 0);
        int lastFrame = frameSlot(packet, frames - 1);
        long packetStartNanos = packetStart[firstFrame];
        long packetEndNanos = packetEnd[lastFrame];
        long joined = 0L;
        for (int offset = 0; offset < callCount; offset++) {
            int call = (callHead + offset) % CALL_SPAN_CAPACITY;
            if (callEnd[call] <= packetStartNanos || callStart[call] >= packetEndNanos) continue;
            joined++;
            int phase = callPhase[call];
            for (int frame = 0; frame < frames; frame++) {
                int slot = frameSlot(packet, frame);
                long intersection = Math.min(callEnd[call], packetEnd[slot])
                        - Math.max(callStart[call], packetStart[slot]);
                if (intersection <= 0L) continue;
                int phaseSlot = phaseSlot(slot, phase);
                overlaps[phaseSlot] += intersection;
                calls[phaseSlot]++;
                maximum[phaseSlot] = Math.max(maximum[phaseSlot], intersection);
            }
        }
        boolean coverageComplete = callWrites <= CALL_SPAN_CAPACITY;
        if (!coverageComplete && callCount > 0) {
            coverageComplete = callStart[callHead] <= packetStartNanos;
        }
        return new Join(joined, coverageComplete);
    }

    private static int recentIndexFromNewest(int offset) {
        return (recentHead + recentCount - 1 - offset + RECENT_FRAME_CAPACITY)
                % RECENT_FRAME_CAPACITY;
    }

    private static int frameSlot(int packet, int frame) {
        return packet * PACKET_FRAME_CAPACITY + frame;
    }

    private static int phaseSlot(int frameSlot, int phase) {
        return frameSlot * CAMPAIGN_PHASES + phase;
    }

    private static Double offsetMillis(long nanos) {
        return originNanos == Long.MIN_VALUE ? null : (nanos - originNanos) / 1_000_000.0;
    }

    private static Long epochMillis(long nanos) {
        return originNanos == Long.MIN_VALUE || originEpochMillis < 0L
                ? null : originEpochMillis + (nanos - originNanos) / 1_000_000L;
    }

    private static String stateName(int state) {
        return state == STATE_CAMPAIGN ? "campaign" : state == STATE_COMBAT ? "combat" : "unknown";
    }

    private static String pauseName(int state, int pause) {
        if (state != STATE_CAMPAIGN) return "not-applicable";
        return pause == PAUSE_PAUSED ? "paused"
                : pause == PAUSE_UNPAUSED ? "unpaused" : "unknown";
    }

    private record Join(long callSpans, boolean coverageComplete) {
    }
}

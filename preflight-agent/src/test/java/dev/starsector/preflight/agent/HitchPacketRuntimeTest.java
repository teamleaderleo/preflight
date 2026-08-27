package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HitchPacketRuntimeTest {
    private static final int CAMPAIGN = 1;
    private static final int PAUSED = 1;

    @BeforeEach
    void enable() {
        HitchPacketRuntime.beginSession(true);
        HitchPacketRuntime.configureOrigin(0L, 1_000L);
        HitchPacketRuntime.configureCampaignPhaseProducer(true);
    }

    @AfterEach
    void reset() {
        HitchPacketRuntime.beginSession(false);
        HitchPacketRuntime.configureCampaignPhaseProducer(false);
    }

    @Test
    void retainsPreAndPostFramesAndJoinsExactCampaignPhaseByMonotonicTime() {
        frame(1L, 0L, 20_000_000L);
        HitchPacketRuntime.recordCampaignPhase(
                CampaignEngineTimeRuntime.ECONOMY, 25_000_000L, 65_000_000L);
        frame(2L, 20_000_000L, 80_000_000L);
        long now = 80_000_000L;
        for (long sequence = 3L; sequence <= 52L; sequence++) {
            frame(sequence, now, now + 20_000_000L);
            now += 20_000_000L;
        }

        Map<String, Object> report = HitchPacketRuntime.telemetry();
        assertEquals(HitchPacketRuntime.FORMAT, report.get("format"));
        assertEquals("measurement-safe primitive frame ring", report.get("classification"));
        Map<String, Object> packet = map(list(report.get("packets")).get(0));
        assertEquals("campaign", packet.get("state"));
        assertEquals("paused", packet.get("pause"));
        assertEquals(52, packet.get("frames"));
        assertEquals(1, packet.get("triggers"));
        assertEquals(0, packet.get("severeTriggers"));
        assertTrue((Boolean) packet.get("postWindowComplete"));
        assertTrue((Boolean) packet.get("campaignPhaseCoverageComplete"));
        assertEquals(1L, packet.get("joinedCampaignCallSpans"));
        assertEquals(1_000L, packet.get("startEpochMillis"));
        assertEquals(2_080L, packet.get("endEpochMillis"));

        Map<String, Object> trigger = map(list(packet.get("frameHistory")).get(1));
        assertTrue((Boolean) trigger.get("trigger"));
        assertEquals(60_000L, trigger.get("durationMicros"));
        assertEquals(30_000L, trigger.get("preSwapMicros"));
        assertEquals(20_000L, trigger.get("nativeSwapMicros"));
        Map<String, Object> phase = map(list(trigger.get("campaignPhaseOverlaps")).get(0));
        assertEquals("economy", phase.get("name"));
        assertEquals(1, phase.get("calls"));
        assertEquals(40_000L, phase.get("overlapMicros"));
        assertEquals(40_000L, phase.get("maximumOverlapMicros"));
    }

    @Test
    void coalescesOverlappingTriggersAndExtendsThePostWindow() {
        frame(1L, 0L, 60_000_000L);
        long now = 60_000_000L;
        long sequence = 2L;
        while (now < 500_000_000L) {
            frame(sequence++, now, now + 20_000_000L);
            now += 20_000_000L;
        }
        frame(sequence++, now, now + 120_000_000L);
        now += 120_000_000L;
        while (now < 1_620_000_000L) {
            frame(sequence++, now, now + 20_000_000L);
            now += 20_000_000L;
        }

        Map<String, Object> report = HitchPacketRuntime.telemetry();
        assertEquals(1, list(report.get("packets")).size());
        Map<String, Object> packet = map(list(report.get("packets")).get(0));
        assertEquals(2, packet.get("triggers"));
        assertEquals(1, packet.get("severeTriggers"));
        assertTrue((Boolean) packet.get("postWindowComplete"));
    }

    @Test
    void capsPacketsAndBreaksCaptureAcrossExcludedSequenceGaps() {
        long now = 0L;
        for (int index = 0; index <= HitchPacketRuntime.PACKET_LIMIT; index++) {
            long sequence = index * 2L + 1L;
            frame(sequence, now, now + 60_000_000L);
            now += 60_000_000L;
        }

        Map<String, Object> report = HitchPacketRuntime.telemetry();
        assertEquals(HitchPacketRuntime.PACKET_LIMIT, list(report.get("packets")).size());
        assertEquals(1L, report.get("packetTriggersDropped"));
        for (Object value : list(report.get("packets"))) {
            assertFalse((Boolean) map(value).get("postWindowComplete"));
        }
    }

    @Test
    void recentFrameRingWrapIsBoundedAndChronological() {
        long now = 0L;
        for (long sequence = 1L; sequence <= 300L; sequence++) {
            frame(sequence, now, now + 1_000_000L);
            now += 1_000_000L;
        }
        frame(301L, now, now + 60_000_000L);

        Map<String, Object> packet = map(list(
                HitchPacketRuntime.telemetry().get("packets")).get(0));
        List<Object> frames = list(packet.get("frameHistory"));
        assertEquals(HitchPacketRuntime.RECENT_FRAME_CAPACITY, frames.size());
        assertEquals(46L, map(frames.get(0)).get("sequence"));
        assertEquals(301L, map(frames.get(frames.size() - 1)).get("sequence"));
    }

    @Test
    void activePacketReportingIsIncompleteAndDoesNotMutateTheRecorder() {
        frame(1L, 0L, 60_000_000L);

        Map<String, Object> first = HitchPacketRuntime.telemetry();
        Map<String, Object> second = HitchPacketRuntime.telemetry();

        assertEquals(first, second);
        assertTrue((Boolean) first.get("activePacket"));
        Map<String, Object> packet = map(list(first.get("packets")).get(0));
        assertFalse((Boolean) packet.get("postWindowComplete"));
        assertFalse((Boolean) packet.get("campaignPhaseCoverageComplete"));
        assertEquals(1, packet.get("frames"));
    }

    @Test
    void campaignCallSpanRingReportsBoundedOverwrites() {
        for (int index = 0; index <= HitchPacketRuntime.CALL_SPAN_CAPACITY; index++) {
            long start = index * 2L + 1L;
            HitchPacketRuntime.recordCampaignPhase(
                    CampaignEngineTimeRuntime.ECONOMY, start, start + 1L);
        }

        Map<String, Object> producer = map(
                HitchPacketRuntime.telemetry().get("campaignPhaseProducer"));
        assertEquals((long) HitchPacketRuntime.CALL_SPAN_CAPACITY + 1L,
                producer.get("callSpansWritten"));
        assertEquals(1L, producer.get("callSpansOverwritten"));
    }

    @Test
    void absentCampaignProducerLeavesPresentationPacketUsefulAndMarksJoinUnavailable() {
        HitchPacketRuntime.configureCampaignPhaseProducer(false);
        frame(1L, 0L, 60_000_000L);

        Map<String, Object> packet = map(list(
                HitchPacketRuntime.telemetry().get("packets")).get(0));
        assertFalse((Boolean) packet.get("campaignPhaseProducerEnabled"));
        assertFalse((Boolean) packet.get("campaignPhaseCoverageComplete"));
        assertEquals(0L, packet.get("joinedCampaignCallSpans"));
        assertEquals(60_000L,
                map(list(packet.get("frameHistory")).get(0)).get("durationMicros"));
    }

    private static void frame(long sequence, long start, long end) {
        long duration = end - start;
        long preSwap = duration / 2L;
        long swap = duration / 3L;
        long messages = duration / 12L;
        long other = duration - preSwap - swap - messages;
        HitchPacketRuntime.recordFrame(
                sequence,
                start,
                end,
                CAMPAIGN,
                PAUSED,
                true,
                preSwap,
                swap,
                messages,
                other);
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

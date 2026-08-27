package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.EXTTimerQuery;
import org.lwjgl.opengl.GL15;

class GpuFrameTimeRuntimeTest {
    @AfterEach
    void reset() {
        System.clearProperty(GpuFrameTimeRuntime.ENABLE_PROPERTY);
        GL15.reset();
        EXTTimerQuery.reset();
        GpuFrameTimeRuntime.beginSession(false);
    }

    @Test
    void remainsOffWithoutExplicitRequest() {
        GpuFrameTimeRuntime.beginSession(true);
        GpuFrameTimeRuntime.beforeSwap(1L, true);

        Map<String, Object> telemetry = GpuFrameTimeRuntime.telemetry();
        assertEquals(false, telemetry.get("requested"));
        assertEquals(false, telemetry.get("attempted"));
        assertEquals(0L, telemetry.get("queriesGenerated"));
    }

    @Test
    void declinesUnsupportedContextWithoutCreatingQueries() {
        System.setProperty(GpuFrameTimeRuntime.ENABLE_PROPERTY, "true");
        GpuFrameTimeRuntime.beginSession(true);
        GpuFrameTimeRuntime.beforeSwap(1L, false);

        Map<String, Object> telemetry = GpuFrameTimeRuntime.telemetry();
        assertEquals(true, telemetry.get("requested"));
        assertEquals(true, telemetry.get("attempted"));
        assertEquals(false, telemetry.get("initialized"));
        assertEquals(0L, telemetry.get("queriesGenerated"));
    }

    @Test
    void readsCompletedResultWithoutWaitingAndPairsItToFrame() {
        System.setProperty(GpuFrameTimeRuntime.ENABLE_PROPERTY, "true");
        GpuFrameTimeRuntime.beginSession(true);
        GpuFrameTimeRuntime.beforeSwap(1L, true);
        GpuFrameTimeRuntime.afterSwap();
        GpuFrameTimeRuntime.beforeSwap(2L, true);
        GpuFrameTimeRuntime.observeFrame(
                2L, true, true, 1, false, 20_000_000L, 16_000_000L, 1);

        Map<String, Object> telemetry = GpuFrameTimeRuntime.telemetry();
        assertEquals(16L, telemetry.get("queriesGenerated"));
        assertEquals(1L, telemetry.get("queriesBegun"));
        assertEquals(1L, telemetry.get("queriesEnded"));
        assertEquals(1L, telemetry.get("resultsRead"));
        Map<String, Object> settled = map(telemetry.get("campaignPausedAfter30Seconds"));
        assertEquals(1L, settled.get("pairedFrames"));
        Map<String, Object> gpu = map(settled.get("gpuTime"));
        assertEquals(4_000.0, gpu.get("averageMicros"));
        assertFalse(list(settled.get("worstFramePairs")).isEmpty());
    }

    @Test
    void unavailableResultsFillOnlyTheFixedRingAndNeverBlock() {
        System.setProperty(GpuFrameTimeRuntime.ENABLE_PROPERTY, "true");
        GL15.setAvailable(false);
        GpuFrameTimeRuntime.beginSession(true);
        GpuFrameTimeRuntime.beforeSwap(1L, true);
        for (long sequence = 2L; sequence < 25L; sequence++) {
            GpuFrameTimeRuntime.afterSwap();
            GpuFrameTimeRuntime.beforeSwap(sequence, true);
            GpuFrameTimeRuntime.observeFrame(
                    sequence, true, false, 0, true, 20_000_000L, 1_000_000L, 1);
        }

        Map<String, Object> telemetry = GpuFrameTimeRuntime.telemetry();
        assertEquals(16, telemetry.get("pendingSlots"));
        assertTrue((Long) telemetry.get("skippedNoFreeSlot") > 0L);
        assertTrue((Long) telemetry.get("unavailablePolls") > 0L);
        assertEquals(0L, telemetry.get("resultsRead"));
        assertEquals(0L, telemetry.get("containedFailures"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Map<String, Object>> list(Object value) {
        return (java.util.List<Map<String, Object>>) value;
    }
}

package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FramePresentationTimingRuntimeTest {
    @AfterEach
    void reset() {
        FrameTimeRuntime.reset();
    }

    @Test
    void separatesDisplayAndSwapDurationsFromAcceptedFrameIntervals() {
        FrameTimeRuntime.beginSession(true);

        FrameTimeRuntime.recordDisplayUpdateStart(1_000_000L);
        FrameTimeRuntime.recordSwapBuffersStart(4_000_000L);
        FrameTimeRuntime.recordSwapBuffersEnd(7_000_000L);
        FrameTimeRuntime.recordBoundary(10_000_000L); // establishes the first boundary

        FrameTimeRuntime.recordDisplayUpdateStart(15_000_000L);
        FrameTimeRuntime.recordSwapBuffersStart(20_000_000L);
        FrameTimeRuntime.recordSwapBuffersEnd(24_000_000L);
        FrameTimeRuntime.recordBoundary(30_000_000L);

        Map<String, Object> telemetry = FrameTimeRuntime.telemetry();
        Map<String, Object> frames = map(telemetry.get("allActive"));
        Map<String, Object> display = map(telemetry.get("displayUpdateActive"));
        Map<String, Object> swap = map(telemetry.get("swapBuffersActive"));

        assertEquals(1L, frames.get("frames"));
        assertEquals(20_000L, frames.get("p50Micros"));
        assertEquals(1L, display.get("samples"));
        assertEquals(15_000.0, display.get("meanMicros"));
        assertEquals(100.0, display.get("sampleCoveragePercent"));
        assertEquals(75.0, display.get("meanFrameSharePercent"));
        assertEquals(1L, swap.get("samples"));
        assertEquals(4_000.0, swap.get("meanMicros"));
        assertEquals(100.0, swap.get("sampleCoveragePercent"));
        assertEquals(20.0, swap.get("meanFrameSharePercent"));
    }

    @Test
    void dropsPresentationSpansWithInactiveFrameIntervals() {
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.recordBoundary(0L);

        FrameTimeRuntime.recordDisplayUpdateStart(1_000_000L);
        FrameTimeRuntime.recordSwapBuffersStart(2_000_000L);
        FrameTimeRuntime.recordSwapBuffersEnd(3_000_000L);
        FrameTimeRuntime.observeActive(false);
        FrameTimeRuntime.recordBoundary(10_000_000L);

        FrameTimeRuntime.observeActive(true);
        FrameTimeRuntime.recordBoundary(20_000_000L); // crosses inactive state and is dropped

        FrameTimeRuntime.recordDisplayUpdateStart(21_000_000L);
        FrameTimeRuntime.recordSwapBuffersStart(22_000_000L);
        FrameTimeRuntime.recordSwapBuffersEnd(23_000_000L);
        FrameTimeRuntime.recordBoundary(30_000_000L);

        Map<String, Object> telemetry = FrameTimeRuntime.telemetry();
        assertEquals(1L, map(telemetry.get("allActive")).get("frames"));
        assertEquals(1L, map(telemetry.get("displayUpdateActive")).get("samples"));
        assertEquals(1L, map(telemetry.get("swapBuffersActive")).get("samples"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}

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
        assertEquals(3L, all.get("over16_67Millis"));
        assertEquals(3L, post.get("frames"));
        assertEquals(40_000L, post.get("p50Micros"));
        assertEquals(1L, post.get("over50Millis"));
        assertFalse(list(post.get("worstFrames")).isEmpty());
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
    void disabledProbeIsAStableNoOp() {
        FrameTimeRuntime.beginSession(false);
        FrameTimeRuntime.observeActive(false);
        FrameTimeRuntime.boundary();
        Map<String, Object> telemetry = FrameTimeRuntime.telemetry();
        assertFalse((Boolean) telemetry.get("enabled"));
        assertEquals(0L, telemetry.get("boundaries"));
        assertEquals(0L, map(telemetry.get("allActive")).get("frames"));
        assertNull(map(telemetry.get("allActive")).get("p99Micros"));
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

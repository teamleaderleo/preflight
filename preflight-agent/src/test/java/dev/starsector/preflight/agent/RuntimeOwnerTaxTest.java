package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RuntimeOwnerTaxTest {
    @AfterEach
    void resetRecorder() {
        HitchPacketRuntime.beginSession(false);
    }

    @Test
    void keepsSteadyCpuTaxSeparateFromRetainedHitchAssociation() {
        long originNanos = 1_000_000_000L;
        long originEpochMillis = 2_000_000L;
        HitchPacketRuntime.beginSession(true);
        HitchPacketRuntime.configureOrigin(originNanos, originEpochMillis);
        HitchPacketRuntime.recordFrame(
                7L,
                originNanos + 10_000_000L,
                originNanos + 70_000_000L,
                1,
                2,
                false,
                0L,
                0L,
                false,
                0L,
                0L,
                0L,
                0L,
                false,
                0L,
                0L,
                0L);

        Map<String, Object> modA = callback(
                "mod:a", "a", 120.0, 12.0,
                originEpochMillis + 20.0, originEpochMillis + 50.0);
        Map<String, Object> modB = callback(
                "mod:b", "b", 240.0, 4.0,
                originEpochMillis + 200.0, originEpochMillis + 204.0);

        Map<String, Object> report = RuntimeOwnerTax.report(
                List.of(modA, modB), "totalMillis", "maximumMillis");

        List<Map<String, Object>> frameTax = maps(report.get("frameTax"));
        assertEquals("mod:b", frameTax.get(0).get("ownerKey"));
        assertEquals(240.0, frameTax.get(0).get("totalMillis"));

        List<Map<String, Object>> hitchTax = maps(report.get("hitchTax"));
        assertEquals("mod:a", hitchTax.get(0).get("ownerKey"));
        assertEquals(1L, hitchTax.get(0).get("callsOverlapping50msFrames"));
        assertEquals(0L, hitchTax.get(0).get("callsOverlapping100msFrames"));
        assertTrue(((Number) hitchTax.get(0).get("callbackOverlapMillis")).doubleValue() > 0.0);
        assertEquals(60.0,
                ((Number) hitchTax.get(0).get("maximumAssociatedFrameMillis")).doubleValue(),
                0.001);

        Map<?, ?> classHitch = (Map<?, ?>) modA.get("hitchTax");
        assertEquals(Boolean.TRUE, classHitch.get("available"));
        assertEquals(1L, classHitch.get("frameAssociationsOver50ms"));
    }

    private static Map<String, Object> callback(
            String ownerKey,
            String modId,
            double totalMillis,
            double maximumMillis,
            double slowStartEpoch,
            double slowEndEpoch) {
        Map<String, Object> ownership = new LinkedHashMap<>();
        ownership.put("ownerKey", ownerKey);
        ownership.put("ownerKind", "MOD");
        ownership.put("ownerName", modId);
        ownership.put("modId", modId);

        Map<String, Object> slow = new LinkedHashMap<>();
        slow.put("durationMillis", slowEndEpoch - slowStartEpoch);
        slow.put("startEpochMillis", slowStartEpoch);
        slow.put("endEpochMillis", slowEndEpoch);

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", modId + ".Callback");
        value.put("calls", 100L);
        value.put("totalMillis", totalMillis);
        value.put("maximumMillis", maximumMillis);
        value.put("slowestCalls", List.of(slow));
        value.put("ownership", ownership);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}

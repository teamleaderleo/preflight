package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HighResolutionFrameSyncRuntimeTest {
    @AfterEach
    void reset() {
        HighResolutionFrameSyncRuntime.resetForTest();
    }

    @Test
    void fallbackMatchesVanillaFloatToIntMillisecondConversion() {
        assertEquals(16L, HighResolutionFrameSyncRuntime.originalMillis(0.016999f));
        assertEquals(0L, HighResolutionFrameSyncRuntime.originalMillis(Float.NaN));
        assertEquals((long) Integer.MAX_VALUE,
                HighResolutionFrameSyncRuntime.originalMillis(Float.POSITIVE_INFINITY));
    }

    @Test
    void precisePathRetainsSubMillisecondRemainderAndPublishesCausalCounters() throws Exception {
        HighResolutionFrameSyncRuntime.beginSessionForTest(true, 2_000_000L);
        HighResolutionFrameSyncRuntime.installed();
        float interval = 0.00005f;
        long requested = HighResolutionFrameSyncRuntime.preciseNanos(interval);

        HighResolutionFrameSyncRuntime.sleepSeconds(interval);

        Map<String, Object> telemetry = HighResolutionFrameSyncRuntime.telemetry();
        assertEquals(true, telemetry.get("enabled"));
        assertEquals(true, telemetry.get("installed"));
        assertEquals(1L, telemetry.get("calls"));
        assertEquals(1L, telemetry.get("preciseCalls"));
        assertEquals(0L, telemetry.get("fallbackCalls"));
        assertEquals(requested, telemetry.get("requestedNanos"));
        assertTrue((long) telemetry.get("waitedNanos") >= requested);
    }

    @Test
    void disabledPathKeepsOriginalWaitContract() throws Exception {
        HighResolutionFrameSyncRuntime.beginSessionForTest(false, 2_000_000L);
        HighResolutionFrameSyncRuntime.sleepSeconds(0f);
        Map<String, Object> telemetry = HighResolutionFrameSyncRuntime.telemetry();
        assertEquals(1L, telemetry.get("calls"));
        assertEquals(0L, telemetry.get("preciseCalls"));
        assertEquals(1L, telemetry.get("fallbackCalls"));
    }
}

package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DynamicParticleGroupRenderProbeRuntimeTest {
    @AfterEach
    void reset() {
        DynamicParticleGroupRenderProbeRuntime.resetForTest();
    }

    @Test
    void recordsAggregateDurationAndStaticFingerprint() {
        DynamicParticleGroupRenderProbeRuntime.beginSessionForTest(true);
        DynamicParticleGroupRenderProbeRuntime.installed(2, 1, 1, 3, 2, 2, 1, 1);
        long start = DynamicParticleGroupRenderProbeRuntime.begin();
        DynamicParticleGroupRenderProbeRuntime.end(start);

        Map<String, Object> telemetry = DynamicParticleGroupRenderProbeRuntime.telemetry();
        assertEquals(true, telemetry.get("enabled"));
        assertEquals(true, telemetry.get("installed"));
        assertEquals(1L, telemetry.get("calls"));
        assertNotNull(telemetry.get("meanMicros"));
        assertTrue(((Double) telemetry.get("maximumMicros")) >= 0.0);
        assertEquals(2, telemetry.get("returnSites"));
        assertEquals(1, telemetry.get("glBeginSites"));
        assertEquals(1, telemetry.get("glEndSites"));
        assertEquals(3, telemetry.get("vertexSites"));
        assertEquals(2, telemetry.get("texCoordSites"));
        assertEquals(2, telemetry.get("colorSites"));
        assertEquals(1, telemetry.get("bindTextureSites"));
        assertEquals(1, telemetry.get("blendFuncSites"));
    }

    @Test
    void disabledSessionDoesNotEnablePlanSelection() {
        DynamicParticleGroupRenderProbeRuntime.beginSessionForTest(false);
        assertEquals(false, DynamicParticleGroupRenderProbeRuntime.enabled());
    }
}

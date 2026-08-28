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
        FrameTimeRuntime.reset();
        RuntimeSemanticState.reset();
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
        @SuppressWarnings("unchecked")
        Map<String, Object> combatWindow =
                (Map<String, Object>) telemetry.get("combatMeasurementWindow");
        @SuppressWarnings("unchecked")
        Map<String, Object> combat = (Map<String, Object>) telemetry.get("semanticCombat");
        assertEquals(0L, combat.get("calls"));
        assertEquals(0L, combatWindow.get("calls"));
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

    @Test
    void separatesTheExactCombatMeasurementWindowFromWholeProcessTraffic() {
        DynamicParticleGroupRenderProbeRuntime.beginSessionForTest(true);
        FrameTimeRuntime.beginSession(true);

        DynamicParticleGroupRenderProbeRuntime.end(
                DynamicParticleGroupRenderProbeRuntime.begin());
        FrameTimeRuntime.beginCombatMeasurementWindow();
        DynamicParticleGroupRenderProbeRuntime.end(
                DynamicParticleGroupRenderProbeRuntime.begin());
        FrameTimeRuntime.endCombatMeasurementWindow();
        DynamicParticleGroupRenderProbeRuntime.end(
                DynamicParticleGroupRenderProbeRuntime.begin());

        Map<String, Object> telemetry = DynamicParticleGroupRenderProbeRuntime.telemetry();
        @SuppressWarnings("unchecked")
        Map<String, Object> combatWindow =
                (Map<String, Object>) telemetry.get("combatMeasurementWindow");
        assertEquals(3L, telemetry.get("calls"));
        assertEquals(1L, combatWindow.get("calls"));
        assertNotNull(combatWindow.get("meanMicros"));
    }

    @Test
    void separatesSemanticCombatFromDecorativeAndMenuParticleTraffic() throws Exception {
        DynamicParticleGroupRenderProbeRuntime.beginSessionForTest(true);
        java.nio.file.Path state = java.nio.file.Files.createTempFile("particle-state", ".json");
        RuntimeSemanticState.beginSession(state);

        DynamicParticleGroupRenderProbeRuntime.end(
                DynamicParticleGroupRenderProbeRuntime.begin());
        RuntimeSemanticState.combatReady();
        DynamicParticleGroupRenderProbeRuntime.end(
                DynamicParticleGroupRenderProbeRuntime.begin());

        Map<String, Object> telemetry = DynamicParticleGroupRenderProbeRuntime.telemetry();
        @SuppressWarnings("unchecked")
        Map<String, Object> combat = (Map<String, Object>) telemetry.get("semanticCombat");
        assertEquals(2L, telemetry.get("calls"));
        assertEquals(1L, combat.get("calls"));
        assertNotNull(combat.get("meanMicros"));
        java.nio.file.Files.deleteIfExists(state);
        RuntimeSemanticState.reset();
    }
}

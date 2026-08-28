package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class GlMatrixIdentityElisionRuntimeTest {
    @AfterEach
    void reset() {
        System.clearProperty(GlMatrixIdentityElisionRuntime.ENABLE_PROPERTY);
        System.clearProperty(GlCommandCountRuntime.ENABLE_PROPERTY);
        AdapterPlanControl.configure(java.util.Set.of());
        GlMatrixIdentityElisionRuntime.reset();
        GlCommandCountRuntime.reset();
    }

    @Test
    void suppressesOnlyExactIdentityTransformsOutsideBeginEnd() {
        System.setProperty(GlMatrixIdentityElisionRuntime.ENABLE_PROPERTY, "true");
        GlMatrixIdentityElisionRuntime.beginSession(true);
        GlMatrixIdentityElisionRuntime.installed(
                GlMatrixIdentityElisionPlan.TARGET_CLASS,
                GlMatrixIdentityElisionPlan.EXPECTED_METHODS);
        GlMatrixIdentityElisionRuntime.beginFrame();
        GlMatrixIdentityElisionRuntime.beginMeasurementWindow();
        GlMatrixIdentityElisionRuntime.beginFrame();

        assertTrue(GlMatrixIdentityElisionRuntime.shouldSkipTranslateF(0.0f, -0.0f, 0.0f));
        assertFalse(GlMatrixIdentityElisionRuntime.shouldSkipTranslateF(0.0f, 1.0f, 0.0f));
        assertTrue(GlMatrixIdentityElisionRuntime.shouldSkipRotateF(-0.0f, 0.0f, 0.0f, 1.0f));
        assertFalse(GlMatrixIdentityElisionRuntime.shouldSkipRotateF(
                0.0f, Float.NaN, 0.0f, 1.0f));
        assertTrue(GlMatrixIdentityElisionRuntime.shouldSkipScaleD(1.0d, 1.0d, 1.0d));
        assertFalse(GlMatrixIdentityElisionRuntime.shouldSkipScaleD(1.0d, 2.0d, 1.0d));

        GlMatrixIdentityElisionRuntime.beginPrimitive();
        assertFalse(GlMatrixIdentityElisionRuntime.shouldSkipRotateD(0.0d, 0.0d, 0.0d, 1.0d));
        GlMatrixIdentityElisionRuntime.endPrimitive();
        GlMatrixIdentityElisionRuntime.beginFrame();

        Map<String, Object> telemetry = GlMatrixIdentityElisionRuntime.telemetry();
        assertTrue((Boolean) telemetry.get("enabled"));
        assertEquals(1L, telemetry.get("frames"));
        assertEquals(7L, telemetry.get("transformCalls"));
        assertEquals(3L, telemetry.get("suppressedCalls"));
        assertEquals(4L, telemetry.get("originalCalls"));
        assertEquals(1L, telemetry.get("primitiveDeclines"));
        assertEquals(1L, telemetry.get("beginCalls"));
        assertEquals(1L, telemetry.get("endCalls"));
        assertEquals(0L, telemetry.get("frameBoundaryPrimitiveLeaks"));
        assertEquals(1, telemetry.get("installedTargetCount"));
        assertEquals(8, telemetry.get("installedMethodCount"));
    }

    @Test
    void conflictingDiscoveryProbeAndKillSwitchDeclineCandidate() {
        System.setProperty(GlMatrixIdentityElisionRuntime.ENABLE_PROPERTY, "true");
        System.setProperty(GlCommandCountRuntime.ENABLE_PROPERTY, "true");
        GlMatrixIdentityElisionRuntime.beginSession(true);
        assertEquals("conflicting-opengl-diagnostic-requested",
                GlMatrixIdentityElisionRuntime.telemetry().get("problem"));

        System.clearProperty(GlCommandCountRuntime.ENABLE_PROPERTY);
        AdapterPlanControl.configure(java.util.Set.of(GlMatrixIdentityElisionRuntime.PLAN_ID));
        GlMatrixIdentityElisionRuntime.beginSession(true);
        assertEquals("plan-disabled-or-out-of-scope",
                GlMatrixIdentityElisionRuntime.telemetry().get("problem"));
    }

    @Test
    void frameBoundaryFailsOpenAfterUnbalancedPrimitiveScope() {
        System.setProperty(GlMatrixIdentityElisionRuntime.ENABLE_PROPERTY, "true");
        GlMatrixIdentityElisionRuntime.beginSession(true);
        GlMatrixIdentityElisionRuntime.beginFrame();
        GlMatrixIdentityElisionRuntime.beginMeasurementWindow();
        GlMatrixIdentityElisionRuntime.beginFrame();
        GlMatrixIdentityElisionRuntime.beginPrimitive();
        GlMatrixIdentityElisionRuntime.beginFrame();

        assertFalse(GlMatrixIdentityElisionRuntime.shouldSkipScaleF(1.0f, 1.0f, 1.0f));
        assertEquals(1L, GlMatrixIdentityElisionRuntime.telemetry()
                .get("frameBoundaryPrimitiveLeaks"));
        assertEquals("glBegin-scope-crossed-frame-boundary",
                GlMatrixIdentityElisionRuntime.telemetry().get("runtimeDisableReason"));
    }
}

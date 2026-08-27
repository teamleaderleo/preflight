package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class GlMatrixOperationRuntimeTest {
    @AfterEach
    void reset() {
        System.clearProperty(GlMatrixOperationRuntime.ENABLE_PROPERTY);
        System.clearProperty(GpuFrameTimeRuntime.ENABLE_PROPERTY);
        AdapterPlanControl.configure(java.util.Set.of());
        GpuFrameTimeRuntime.beginSession(false);
        GlMatrixOperationRuntime.reset();
    }

    @Test
    void remainsOffWithoutExplicitRequest() {
        GlMatrixOperationRuntime.beginSession(true);
        Map<String, Object> telemetry = GlMatrixOperationRuntime.telemetry();
        assertFalse((Boolean) telemetry.get("requested"));
        assertFalse((Boolean) telemetry.get("enabled"));
        assertNull(telemetry.get("meanCallsPerFrame"));
    }

    @Test
    void countsExactIdentityTransformsOnlyInComparableCompleteFrames() {
        System.setProperty(GlMatrixOperationRuntime.ENABLE_PROPERTY, "true");
        GlMatrixOperationRuntime.beginSession(true);
        GlMatrixOperationRuntime.installed("org/lwjgl/opengl/GL11", 16);
        GlMatrixOperationRuntime.beginMeasurementWindow();
        GlMatrixOperationRuntime.recordTranslateF(0.0f, -0.0f, 0.0f);
        GlMatrixOperationRuntime.observeFrame(1L, true); // partial action frame

        GlMatrixOperationRuntime.recordTranslateF(0.0f, 0.0f, 0.0f);
        GlMatrixOperationRuntime.recordTranslateF(1.0f, 0.0f, 0.0f);
        GlMatrixOperationRuntime.recordRotateD(-0.0d);
        GlMatrixOperationRuntime.recordScaleF(1.0f, 1.0f, 1.0f);
        GlMatrixOperationRuntime.recordScaleD(1.0d, 2.0d, 1.0d);
        GlMatrixOperationRuntime.record(GlMatrixOperationRuntime.PUSH_MATRIX);
        GlMatrixOperationRuntime.observeFrame(40_000_000L, true);
        GlMatrixOperationRuntime.recordRotateF(0.0f);
        GlMatrixOperationRuntime.observeFrame(20_000_000L, false);

        Map<String, Object> telemetry = GlMatrixOperationRuntime.telemetry();
        assertTrue((Boolean) telemetry.get("enabled"));
        assertEquals(1L, telemetry.get("frames"));
        assertEquals(1L, telemetry.get("slowFrames"));
        assertEquals(2L, telemetry.get("discardedFrames"));
        assertEquals(6L, telemetry.get("calls"));
        assertEquals(3L, telemetry.get("identityOrNoOpCalls"));
        assertEquals(1, telemetry.get("installedTargetCount"));
        assertEquals(16, telemetry.get("installedMethodCount"));
        List<Map<String, Object>> methods = list(telemetry.get("methods"));
        assertMethod(methods, "glTranslatef(float,float,float)", 2L, 1L);
        assertMethod(methods, "glRotated(double,double,double,double)", 1L, 1L);
        assertMethod(methods, "glScalef(float,float,float)", 1L, 1L);
        assertMethod(methods, "glScaled(double,double,double)", 1L, 0L);
    }

    @Test
    void gpuTimerAndIndependentKillSwitchDeclineTheProbe() {
        System.setProperty(GlMatrixOperationRuntime.ENABLE_PROPERTY, "true");
        System.setProperty(GpuFrameTimeRuntime.ENABLE_PROPERTY, "true");
        GpuFrameTimeRuntime.beginSession(true);
        GlMatrixOperationRuntime.beginSession(true);
        assertEquals("gpu-frame-timer-also-requested",
                GlMatrixOperationRuntime.telemetry().get("problem"));

        GpuFrameTimeRuntime.beginSession(false);
        AdapterPlanControl.configure(java.util.Set.of(GlMatrixOperationRuntime.PLAN_ID));
        GlMatrixOperationRuntime.beginSession(true);
        assertEquals("plan-disabled-or-out-of-scope",
                GlMatrixOperationRuntime.telemetry().get("problem"));
    }

    private static void assertMethod(
            List<Map<String, Object>> methods, String name, long calls, long identity) {
        Map<String, Object> method = methods.stream()
                .filter(value -> name.equals(value.get("name")))
                .findFirst().orElseThrow();
        assertEquals(calls, method.get("calls"));
        assertEquals(identity, method.get("identityOrNoOpCalls"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}

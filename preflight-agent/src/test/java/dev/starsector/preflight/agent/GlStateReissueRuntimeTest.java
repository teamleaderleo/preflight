package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class GlStateReissueRuntimeTest {
    @AfterEach
    void reset() {
        System.clearProperty(GlStateReissueRuntime.ENABLE_PROPERTY);
        System.clearProperty(GpuFrameTimeRuntime.ENABLE_PROPERTY);
        AdapterPlanControl.configure(java.util.Set.of());
        GpuFrameTimeRuntime.beginSession(false);
        GlStateReissueRuntime.reset();
    }

    @Test
    void remainsOffWithoutExplicitRequest() {
        GpuFrameTimeRuntime.beginSession(false);
        GlStateReissueRuntime.beginSession(true);

        Map<String, Object> telemetry = GlStateReissueRuntime.telemetry();
        assertFalse((Boolean) telemetry.get("requested"));
        assertFalse((Boolean) telemetry.get("enabled"));
        assertNull(telemetry.get("sameStatePercentOfCalls"));
    }

    @Test
    void retainsKnownSameStateReissuesAndInvalidatesConservatively() {
        System.setProperty(GlStateReissueRuntime.ENABLE_PROPERTY, "true");
        GpuFrameTimeRuntime.beginSession(false);
        GlStateReissueRuntime.beginSession(true);
        GlStateReissueRuntime.installed("org/lwjgl/opengl/GL11", 17);
        GlStateReissueRuntime.beginMeasurementWindow();
        GlStateReissueRuntime.observeFrame(1L, true); // partial action frame

        GlStateReissueRuntime.recordBindTexture(3553, 7);
        GlStateReissueRuntime.recordBindTexture(3553, 7);
        GlStateReissueRuntime.recordCapability(3042, true);
        GlStateReissueRuntime.recordCapability(3042, true);
        GlStateReissueRuntime.recordCapability(3042, false);
        GlStateReissueRuntime.recordCapability(3042, false);
        GlStateReissueRuntime.recordSingle(GlStateReissueRuntime.MATRIX_MODE, 5888);
        GlStateReissueRuntime.recordSingle(GlStateReissueRuntime.MATRIX_MODE, 5888);
        GlStateReissueRuntime.recordInvalidation(GlStateReissueRuntime.INVALIDATE_CALL_LIST);
        GlStateReissueRuntime.recordSingle(GlStateReissueRuntime.MATRIX_MODE, 5888);
        GlStateReissueRuntime.recordSingle(GlStateReissueRuntime.MATRIX_MODE, 5888);
        GlStateReissueRuntime.observeFrame(40_000_000L, true);

        Map<String, Object> telemetry = GlStateReissueRuntime.telemetry();
        assertTrue((Boolean) telemetry.get("enabled"));
        assertEquals(1L, telemetry.get("frames"));
        assertEquals(1L, telemetry.get("slowFrames"));
        assertEquals(1L, telemetry.get("discardedFrames"));
        assertEquals(10L, telemetry.get("calls"));
        assertEquals(6L, telemetry.get("knownComparisons"));
        assertEquals(5L, telemetry.get("sameStateReissues"));
        assertEquals(1, telemetry.get("installedTargetCount"));

        List<Map<String, Object>> methods = list(telemetry.get("methods"));
        assertMethod(methods, "glBindTexture", 2L, 1L, 1L);
        assertMethod(methods, "glEnableOrDisable", 4L, 3L, 2L);
        assertMethod(methods, "glMatrixMode", 4L, 2L, 2L);
        Map<String, Object> invalidations = map(telemetry.get("modelInvalidations"));
        assertEquals(1L, invalidations.get("retainedCalls"));
        assertEquals(1L, invalidations.get("displayListCall"));
    }

    @Test
    void gpuTimerAndIndependentKillSwitchDeclineTheProbe() {
        System.setProperty(GlStateReissueRuntime.ENABLE_PROPERTY, "true");
        System.setProperty(GpuFrameTimeRuntime.ENABLE_PROPERTY, "true");
        GpuFrameTimeRuntime.beginSession(true);
        GlStateReissueRuntime.beginSession(true);
        assertEquals("gpu-frame-timer-also-requested",
                GlStateReissueRuntime.telemetry().get("problem"));

        GpuFrameTimeRuntime.beginSession(false);
        AdapterPlanControl.configure(java.util.Set.of(GlStateReissueRuntime.PLAN_ID));
        GlStateReissueRuntime.beginSession(true);
        assertEquals("plan-disabled-or-out-of-scope",
                GlStateReissueRuntime.telemetry().get("problem"));
    }

    private static void assertMethod(
            List<Map<String, Object>> methods, String name,
            long calls, long known, long redundant) {
        Map<String, Object> method = methods.stream()
                .filter(value -> name.equals(value.get("name")))
                .findFirst().orElseThrow();
        assertEquals(calls, method.get("calls"));
        assertEquals(known, method.get("knownComparisons"));
        assertEquals(redundant, method.get("sameStateReissues"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}

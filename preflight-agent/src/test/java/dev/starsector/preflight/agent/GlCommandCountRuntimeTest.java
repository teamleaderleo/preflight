package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class GlCommandCountRuntimeTest {
    @AfterEach
    void reset() {
        System.clearProperty(GlCommandCountRuntime.ENABLE_PROPERTY);
        System.clearProperty(GlStateReissueRuntime.ENABLE_PROPERTY);
        System.clearProperty(GlMatrixOperationRuntime.ENABLE_PROPERTY);
        System.clearProperty(GpuFrameTimeRuntime.ENABLE_PROPERTY);
        GpuFrameTimeRuntime.beginSession(false);
        GlCommandCountRuntime.reset();
        GlStateReissueRuntime.reset();
        GlMatrixOperationRuntime.reset();
    }

    @Test
    void remainsOffWithoutAnExplicitDiscoveryRequest() {
        GlCommandCountRuntime.beginSession(true);

        Map<String, Object> telemetry = GlCommandCountRuntime.telemetry();
        assertFalse((Boolean) telemetry.get("requested"));
        assertFalse((Boolean) telemetry.get("enabled"));
        assertNull(telemetry.get("meanCommandsPerFrame"));
    }

    @Test
    void retainsBoundedCategoryCountsOnlyForCompleteComparableWindowFrames() {
        System.setProperty(GlCommandCountRuntime.ENABLE_PROPERTY, "true");
        GlCommandCountRuntime.beginSession(true);
        GlCommandCountRuntime.installed("org/lwjgl/opengl/GL11", 4);
        GlCommandCountRuntime.beginMeasurementWindow("campaign", "unpaused");

        GlCommandCountRuntime.record(GlCommandCountRuntime.ARRAY_DRAW);
        GlCommandCountRuntime.observeFrame(1L, 16_000_000L, true); // partial action frame
        GlCommandCountRuntime.record(GlCommandCountRuntime.ARRAY_DRAW);
        GlCommandCountRuntime.record(GlCommandCountRuntime.ARRAY_DRAW);
        GlCommandCountRuntime.record(GlCommandCountRuntime.TEXTURE_BIND);
        GlCommandCountRuntime.observeFrame(2L, 40_000_000L, true);
        GlCommandCountRuntime.record(GlCommandCountRuntime.TEXTURE_UPLOAD);
        GlCommandCountRuntime.observeFrame(3L, 20_000_000L, false);

        Map<String, Object> telemetry = GlCommandCountRuntime.telemetry();
        assertTrue((Boolean) telemetry.get("enabled"));
        assertEquals("campaign", telemetry.get("state"));
        assertEquals("unpaused", telemetry.get("campaignPause"));
        assertEquals(1L, telemetry.get("frames"));
        assertEquals(2L, telemetry.get("discardedFrames"));
        assertEquals(1L, telemetry.get("initialPartialFramesDropped"));
        assertEquals(1L, telemetry.get("slowFrames"));
        assertEquals(3L, telemetry.get("commands"));
        assertEquals(5L, telemetry.get("wrapperCallsObserved"));
        assertEquals(1, telemetry.get("installedTargetCount"));
        assertEquals(4, telemetry.get("installedMethodCount"));

        List<Map<String, Object>> categories = list(telemetry.get("categories"));
        assertEquals(2L, category(categories, "arrayOrPixelDraw").get("calls"));
        assertEquals(1L, category(categories, "textureBind").get("calls"));
        assertEquals(0L, category(categories, "textureUpload").get("calls"));
        List<Map<String, Object>> worst = list(telemetry.get("worstFrames"));
        assertEquals(1, worst.size());
        assertEquals(2L, worst.get(0).get("sequence"));
        assertEquals(40_000L, worst.get(0).get("durationMicros"));
        assertEquals(3L, worst.get(0).get("commands"));
    }

    @Test
    void declinesWhenTheGpuTimerDiscoveryProbeIsAlreadyRequested() {
        System.setProperty(GpuFrameTimeRuntime.ENABLE_PROPERTY, "true");
        System.setProperty(GlCommandCountRuntime.ENABLE_PROPERTY, "true");
        GpuFrameTimeRuntime.beginSession(true);
        GlCommandCountRuntime.beginSession(true);

        Map<String, Object> telemetry = GlCommandCountRuntime.telemetry();
        assertTrue((Boolean) telemetry.get("requested"));
        assertFalse((Boolean) telemetry.get("enabled"));
        assertEquals("gpu-frame-timer-also-requested", telemetry.get("problem"));
    }

    @Test
    void stateReissueRequestEnablesTheSharedExactCommandBoundary() {
        System.setProperty(GlStateReissueRuntime.ENABLE_PROPERTY, "true");
        GpuFrameTimeRuntime.beginSession(false);
        GlStateReissueRuntime.beginSession(true);
        GlCommandCountRuntime.beginSession(true);

        Map<String, Object> telemetry = GlCommandCountRuntime.telemetry();
        assertTrue((Boolean) telemetry.get("requested"));
        assertTrue((Boolean) telemetry.get("requestedByStateReissue"));
        assertTrue((Boolean) telemetry.get("enabled"));
    }

    @Test
    void matrixOperationRequestEnablesTheSharedExactCommandBoundary() {
        System.setProperty(GlMatrixOperationRuntime.ENABLE_PROPERTY, "true");
        GpuFrameTimeRuntime.beginSession(false);
        GlMatrixOperationRuntime.beginSession(true);
        GlCommandCountRuntime.beginSession(true);

        Map<String, Object> telemetry = GlCommandCountRuntime.telemetry();
        assertTrue((Boolean) telemetry.get("requested"));
        assertTrue((Boolean) telemetry.get("requestedByMatrixOperations"));
        assertTrue((Boolean) telemetry.get("enabled"));
    }

    private static Map<String, Object> category(
            List<Map<String, Object>> categories, String name) {
        return categories.stream()
                .filter(value -> name.equals(value.get("name")))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}

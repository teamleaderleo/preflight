package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class GlTextureBindDedupRuntimeTest {
    @BeforeEach
    void enable() {
        AdapterPlanControl.configure(Set.of());
        System.setProperty(GlTextureBindDedupRuntime.ENABLE_PROPERTY, "true");
        System.clearProperty(GpuFrameTimeRuntime.ENABLE_PROPERTY);
        System.clearProperty(GlStateReissueRuntime.ENABLE_PROPERTY);
        System.clearProperty(GlCommandCountRuntime.ENABLE_PROPERTY);
        GpuFrameTimeRuntime.beginSession(false);
        GlStateReissueRuntime.beginSession(false);
        GlCommandCountRuntime.beginSession(false);
        GlTextureBindDedupRuntime.beginSession(true);
        GlTextureBindDedupRuntime.beginFrame();
    }

    @AfterEach
    void reset() {
        System.clearProperty(GlTextureBindDedupRuntime.ENABLE_PROPERTY);
        AdapterPlanControl.configure(Set.of());
        GlTextureBindDedupRuntime.reset();
    }

    @Test
    void suppressesOnlyARepeatedSupportedBindWithinOneFrame() {
        assertFalse(GlTextureBindDedupRuntime.shouldSkip(3553, 41));
        GlTextureBindDedupRuntime.originalBindCompleted(3553, 41);
        assertTrue(GlTextureBindDedupRuntime.shouldSkip(3553, 41));
        assertFalse(GlTextureBindDedupRuntime.shouldSkip(3553, 42));
        GlTextureBindDedupRuntime.originalBindCompleted(3553, 42);

        GlTextureBindDedupRuntime.beginFrame();
        assertFalse(GlTextureBindDedupRuntime.shouldSkip(3553, 42));
        GlTextureBindDedupRuntime.originalBindCompleted(3553, 42);
        GlTextureBindDedupRuntime.invalidate();
        assertFalse(GlTextureBindDedupRuntime.shouldSkip(3553, 42));

        Map<String, Object> telemetry = GlTextureBindDedupRuntime.telemetry();
        assertEquals(5L, telemetry.get("bindCalls"));
        assertEquals(1L, telemetry.get("suppressedCalls"));
        assertEquals(4L, telemetry.get("originalCalls"));
        assertEquals(1L, telemetry.get("invalidations"));
    }

    @Test
    void displayListAndUnsupportedTargetsAlwaysKeepOriginalCalls() {
        GlTextureBindDedupRuntime.beginDisplayList();
        assertFalse(GlTextureBindDedupRuntime.shouldSkip(3553, 7));
        GlTextureBindDedupRuntime.originalBindCompleted(3553, 7);
        assertFalse(GlTextureBindDedupRuntime.shouldSkip(3553, 7));
        GlTextureBindDedupRuntime.endDisplayList();
        assertFalse(GlTextureBindDedupRuntime.shouldSkip(3553, 7));
        GlTextureBindDedupRuntime.originalBindCompleted(3553, 7);
        assertFalse(GlTextureBindDedupRuntime.shouldSkip(34067, 7));

        Map<String, Object> telemetry = GlTextureBindDedupRuntime.telemetry();
        assertEquals(4L, telemetry.get("originalCalls"));
        assertEquals(0L, telemetry.get("suppressedCalls"));
        assertEquals(3L, telemetry.get("unsupportedCalls"));
        assertEquals(1L, telemetry.get("displayListCompilations"));
    }

    @Test
    void conflictingDiscoveryProbeDeclinesTheCandidate() {
        System.setProperty(GlStateReissueRuntime.ENABLE_PROPERTY, "true");
        GlTextureBindDedupRuntime.beginSession(true);
        Map<String, Object> telemetry = GlTextureBindDedupRuntime.telemetry();
        assertEquals(false, telemetry.get("enabled"));
        assertEquals("conflicting-opengl-diagnostic-requested", telemetry.get("problem"));
    }
}

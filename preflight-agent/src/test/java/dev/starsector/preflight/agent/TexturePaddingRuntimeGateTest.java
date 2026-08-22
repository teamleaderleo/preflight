package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.lwjgl.opengl.GLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Padding removal governs one half of an invariant, and the other half is a bytecode rewrite this
 * class cannot see. Before 2026-07-31 the gate read only the property, so setting it enabled the
 * true-size buffer while the allocation stayed padded. The first non-power-of-two texture then
 * killed the process -- {@code Number of remaining buffer elements is 668043, must be at least
 * 1572864}, against graphics/ui/launcher_bg.jpg, which the launcher loads before a human can click
 * anything. Nothing installed the fold bypass at all, so the property could only ever break a run.
 */
class TexturePaddingRuntimeGateTest {
    @BeforeEach
    void resetSession() {
        // The latch is process-wide, and any test that successfully weaves the plan sets it for
        // the rest of the JVM. That is correct in production -- one process, one installed loader
        // -- and it means a test asserting the shut state has to open a session of its own.
        TexturePaddingRuntime.beginSession();
        GLContext.reset();
    }

    @AfterEach
    void clearProperty() {
        TexturePaddingRuntime.beginSession();
        GLContext.reset();
        System.clearProperty(TexturePaddingRuntime.UNPADDED_PROPERTY);
    }

    @Test
    void thePropertyAloneDoesNotUnpadAnything() {
        System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");

        // A fresh JVM has never woven the fold bypass, so the gate must stay shut no matter what
        // the property says. Failing closed costs a padded upload; failing open costs the run.
        assertFalse(TexturePaddingRuntime.foldBypassReady());
        assertFalse(TexturePaddingRuntime.enabled());
        assertFalse(TexturePaddingRuntime.unpadded());
    }

    @Test
    void anUninstalledFoldLeavesTheGateShutEvenWithoutTheProperty() {
        assertFalse(TexturePaddingRuntime.enabled());
        assertFalse(TexturePaddingRuntime.unpadded());
    }

    @Test
    void aContextWithoutNpotSupportCannotBeForcedOpen() {
        GLContext.setCapabilities(false, false);
        System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");
        TexturePaddingRuntime.foldBypassInstalled();

        assertFalse(TexturePaddingRuntime.enabled());
        assertFalse(TexturePaddingRuntime.unpadded());
        assertEquals("unsupported", TexturePaddingRuntime.report().get("npotCapability"));
    }

    @Test
    void openGl20MakesNpotAContextGuarantee() {
        GLContext.setCapabilities(true, false);
        System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");
        TexturePaddingRuntime.foldBypassInstalled();

        assertTrue(TexturePaddingRuntime.enabled());
        assertFalse(TexturePaddingRuntime.unpadded(),
                "capability alone cannot change a fallback allocation");
        assertEquals("supported", TexturePaddingRuntime.report().get("npotCapability"));
        assertEquals(true, TexturePaddingRuntime.report().get("openGl20"));
    }

    @Test
    void theArbExtensionCoversAnOlderContext() {
        GLContext.setCapabilities(false, true);
        System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");
        TexturePaddingRuntime.foldBypassInstalled();

        assertTrue(TexturePaddingRuntime.enabled());
        assertFalse(TexturePaddingRuntime.unpadded(),
                "the fold opens only for an in-flight prepared upload");
        assertEquals(true, TexturePaddingRuntime.report().get("arbTextureNonPowerOfTwo"));
    }

    @Test
    void aTransientMissingContextCanRecoverLater() {
        GLContext.setCurrent(false);
        System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");
        TexturePaddingRuntime.foldBypassInstalled();

        assertFalse(TexturePaddingRuntime.enabled());
        assertEquals("unchecked", TexturePaddingRuntime.report().get("npotCapability"));

        GLContext.setCapabilities(true, false);
        assertTrue(TexturePaddingRuntime.enabled());
    }

    @Test
    void theReportPerformsTheProbeBeforeRecordingItsResult() {
        GLContext.setCapabilities(false, true);
        System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");
        TexturePaddingRuntime.foldBypassInstalled();

        assertEquals("supported", TexturePaddingRuntime.report().get("npotCapability"));
        assertEquals(true, TexturePaddingRuntime.report().get("unpaddedEffective"));
    }
}

package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PreparedTextureUploadPolicyTest {
    @Test
    void recommendedWindowsLlvmpipeKeepsThePresetButUsesPaddedCoherentDirectPixels() {
        PreparedTextureUploadPolicy.Resolution resolution = PreparedTextureUploadPolicy.resolve(
                Platform.WINDOWS,
                OptimizationPreset.RECOMMENDED,
                false,
                true,
                Map.of(PreparedTextureUploadPolicy.GALLIUM_DRIVER, "llvmpipe"));

        assertTrue(resolution.npotDirect());
        assertFalse(resolution.unpadded());
        assertEquals(true, resolution.toReportValues().get("npotDirect"));
        assertEquals(false, resolution.toReportValues().get("unpadded"));
    }

    @Test
    void nativeWindowsAndOtherPlatformsRetainTheRequestedLayout() {
        PreparedTextureUploadPolicy.Resolution nativeWindows = PreparedTextureUploadPolicy.resolve(
                Platform.WINDOWS,
                OptimizationPreset.RECOMMENDED,
                false,
                true,
                Map.of());
        PreparedTextureUploadPolicy.Resolution linux = PreparedTextureUploadPolicy.resolve(
                Platform.LINUX,
                OptimizationPreset.RECOMMENDED,
                false,
                true,
                Map.of(PreparedTextureUploadPolicy.GALLIUM_DRIVER, "llvmpipe"));

        assertFalse(nativeWindows.npotDirect());
        assertTrue(nativeWindows.unpadded());
        assertFalse(linux.npotDirect());
        assertTrue(linux.unpadded());
    }

    @Test
    void conservativePresetRemainsExplicitlyConservative() {
        PreparedTextureUploadPolicy.Resolution resolution = PreparedTextureUploadPolicy.resolve(
                Platform.WINDOWS,
                OptimizationPreset.CONSERVATIVE,
                true,
                false,
                Map.of(PreparedTextureUploadPolicy.GALLIUM_DRIVER, "llvmpipe"));

        assertTrue(resolution.npotDirect());
        assertFalse(resolution.unpadded());
    }
}

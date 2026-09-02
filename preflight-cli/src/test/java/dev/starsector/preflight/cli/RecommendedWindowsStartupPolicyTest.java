package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RecommendedWindowsStartupPolicyTest {
    @Test
    void recommendedWindowsEnablesAcceptedKaleidoscopePrefetch() {
        assertEquals(
                "-Dexisting=true -Dpreflight.texture.windowsKaleidoscopePrefetch=true",
                RecommendedWindowsStartupPolicy.appendOptions(
                        "-Dexisting=true", Platform.WINDOWS, OptimizationPreset.RECOMMENDED));
    }

    @Test
    void explicitKillSwitchWins() {
        String existing = "-Dpreflight.texture.windowsKaleidoscopePrefetch=false";
        assertEquals(
                existing,
                RecommendedWindowsStartupPolicy.appendOptions(
                        existing, Platform.WINDOWS, OptimizationPreset.RECOMMENDED));
    }

    @Test
    void otherPlatformsAndPresetsRemainUnchanged() {
        assertEquals(
                "-Dexisting=true",
                RecommendedWindowsStartupPolicy.appendOptions(
                        "-Dexisting=true", Platform.LINUX, OptimizationPreset.RECOMMENDED));
        assertEquals(
                "-Dexisting=true",
                RecommendedWindowsStartupPolicy.appendOptions(
                        "-Dexisting=true", Platform.WINDOWS, OptimizationPreset.CONSERVATIVE));
    }
}

package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class RecommendedWindowsStartupPolicyTest {
    @Test
    void validatedAudioEnablesTheAcceptedWindowsCombinationOnly() {
        String options = RecommendedWindowsStartupPolicy.appendOptions(
                "", Platform.WINDOWS, OptimizationPreset.RECOMMENDED, true);
        for (String property : java.util.List.of("preflight.texture.windowsPreparedResources",
                "preflight.texture.windowsPreparedPrestart", "preflight.startup.windowsFactionPriorityCache",
                "preflight.janino.unitMemo", "preflight.texture.preparedStaging")) {
            assertTrue(options.contains("-D" + property + "=true"));
        }
        assertEquals(options, RecommendedWindowsStartupPolicy.appendOptions(
                options, Platform.WINDOWS, OptimizationPreset.RECOMMENDED, true));
        assertFalse(RecommendedWindowsStartupPolicy.appendOptions(
                "", Platform.WINDOWS, OptimizationPreset.RECOMMENDED, false).contains("windowsPreparedPrestart"));
        for (Platform platform : java.util.List.of(Platform.LINUX, Platform.MAC)) {
            assertEquals("-Dexisting=true", RecommendedWindowsStartupPolicy.appendOptions(
                    "-Dexisting=true", platform, OptimizationPreset.RECOMMENDED, true));
        }
        assertEquals("", RecommendedWindowsStartupPolicy.appendOptions(
                "", Platform.WINDOWS, OptimizationPreset.CONSERVATIVE, true));
    }

    @Test
    void explicitAdmissionOptOutDoesNotEnableTheDependentTypedPath() {
        for (String property : java.util.List.of("preflight.texture.windowsPreparedResources",
                "preflight.texture.windowsPreparedPrestart")) {
            String options = RecommendedWindowsStartupPolicy.appendOptions(
                    "-D" + property + "=false -Dpreflight.startup.windowsFactionPriorityCache=false",
                    Platform.WINDOWS, OptimizationPreset.RECOMMENDED, true);
            assertFalse(options.contains("windowsPreparedResources=true"));
            assertFalse(options.contains("windowsPreparedPrestart=true"));
            assertFalse(options.contains("windowsFactionPriorityCache=true"));
            assertTrue(options.contains("-D" + property + "=false"));
        }
    }
    @Test
    void eitherCompilerOrStagingOptOutDeclinesAutomaticComposition() {
        for (String property : java.util.List.of("preflight.janino.unitMemo", "preflight.texture.preparedStaging")) {
            String options = RecommendedWindowsStartupPolicy.appendOptions("-D" + property + "=false",
                    Platform.WINDOWS, OptimizationPreset.RECOMMENDED, true);
            assertFalse(options.contains("unitMemo=true"));
            assertFalse(options.contains("preparedStaging=true"));
            assertTrue(options.contains("-D" + property + "=false"));
        }
    }

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

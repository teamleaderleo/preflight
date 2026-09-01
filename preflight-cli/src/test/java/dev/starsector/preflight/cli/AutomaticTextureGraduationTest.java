package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutomaticTextureGraduationTest {
    @Test
    void standaloneSuccessfulRecommendedLaunchGraduates() {
        CommandLine options = CommandLine.parse(new String[] {"run", "--fast"}, 1);

        assertTrue(AutomaticTextureGraduation.eligible(
                options, texture(true, true), 0, Map.of()));
    }

    @Test
    void failureManualTextureAndDesktopOwnedRunsDoNotGraduate() {
        CommandLine options = CommandLine.parse(new String[] {"run", "--fast"}, 1);

        assertFalse(AutomaticTextureGraduation.eligible(
                options, texture(true, true), 143, Map.of()));
        assertFalse(AutomaticTextureGraduation.eligible(
                options, texture(false, true), 0, Map.of()));
        assertFalse(AutomaticTextureGraduation.eligible(
                options, texture(true, true), 0,
                Map.of(DesktopRunEvents.ENVIRONMENT_VARIABLE, "stderr-v1")));
        assertFalse(AutomaticTextureGraduation.eligible(
                options, texture(true, true), 0,
                Map.of(AutomaticTextureGraduation.DISABLE_ENVIRONMENT, "1")));
    }

    @Test
    void onlyAnExactReadyFullBalancedProfileNeedsGraduation() {
        CacheHealth.Report candidate = health("profile", "balanced", "full", true);
        CacheHealth.Report compact = health("profile", "balanced", "learned", true);
        CacheHealth.Report unavailable = health("profile", "balanced", "full", false);

        assertTrue(AutomaticTextureGraduation.needsGraduation(candidate, "profile"));
        assertFalse(AutomaticTextureGraduation.needsGraduation(candidate, "other"));
        assertFalse(AutomaticTextureGraduation.needsGraduation(compact, "profile"));
        assertFalse(AutomaticTextureGraduation.needsGraduation(unavailable, "profile"));
        assertTrue(AutomaticTextureGraduation.isLearnedReady(compact, "profile"));
    }

    private static CacheHealth.Report health(
            String profile, String storage, String scope, boolean compactAvailable) {
        return new CacheHealth.Report(
                "ready", profile, true, storage, scope, compactAvailable, List.of(), List.of());
    }

    private static LaunchCacheContexts.Texture texture(boolean automatic, boolean prepared) {
        return new LaunchCacheContexts.Texture(
                Path.of("cache"),
                Path.of("cache/manifest.spfm"),
                Path.of("cache/index.spfi"),
                null,
                automatic,
                "profile",
                "manifest-sha",
                "index-sha",
                1,
                1,
                prepared);
    }
}

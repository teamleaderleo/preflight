package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpecCacheKeyTest {
    private static final List<String> VARIANTS = List.of("data/variants/");
    private static final List<String> WEAPONS =
            List.of("data/weapons/", "data/shipsystems/wpn/");

    @Test
    void keepsNamingRelativePathsExactlyAsBefore() {
        assertEquals("data/variants/hound_assault.variant",
                SpecCacheKey.of("data/variants/hound_Assault.variant", VARIANTS, ".variant"));
        assertEquals("data/shipsystems/wpn/burndrive.wpn",
                SpecCacheKey.of("data/shipsystems/wpn/burndrive.wpn", WEAPONS, ".wpn"));
    }

    @Test
    void namesAnAbsolutePathThatTheNormalizerUsedToRefuse() {
        String key = SpecCacheKey.of(
                "/Applications/Starsector.app/Contents/Resources/Java/data/variants/hound_Assault.variant",
                VARIANTS, ".variant");
        assertEquals("abs:data/variants/hound_assault.variant", key);
    }

    @Test
    void dropsTheInstallPrefixSoAnArtifactSurvivesAMovedInstall() {
        assertEquals(
                SpecCacheKey.of("/Applications/Starsector.app/Contents/Resources/Java"
                        + "/data/variants/wolf.variant", VARIANTS, ".variant"),
                SpecCacheKey.of("/home/leo/games/starsector/data/variants/wolf.variant",
                        VARIANTS, ".variant"));
    }

    @Test
    void neverFoldsAnAbsolutePathOntoTheRelativeOne() {
        // Reading the relative path merges every root that provides it; reading the absolute one
        // names a single file. Sharing a key would serve a mod's overlay where vanilla returned the
        // core file alone.
        String relative = SpecCacheKey.of("data/variants/wolf.variant", VARIANTS, ".variant");
        String absolute = SpecCacheKey.of("/install/data/variants/wolf.variant", VARIANTS, ".variant");
        assertNotEquals(relative, absolute);
    }

    @Test
    void takesTheLastServedDirectorySoAModNamedDataCannotTruncateThePath() {
        assertEquals("abs:data/variants/wolf.variant",
                SpecCacheKey.of("/install/data/variants/mods/data/variants/wolf.variant",
                        VARIANTS, ".variant"));
    }

    @Test
    void answersNullForAnythingThisCacheDoesNotServe() {
        assertNull(SpecCacheKey.of(null, VARIANTS, ".variant"));
        assertNull(SpecCacheKey.of("data/hulls/wolf.ship", VARIANTS, ".variant"));
        assertNull(SpecCacheKey.of("data/variants/wolf.ship", VARIANTS, ".variant"));
        assertNull(SpecCacheKey.of("/install/data/hulls/wolf.ship", VARIANTS, ".variant"));
        assertNull(SpecCacheKey.of("", VARIANTS, ".variant"));
        assertNull(SpecCacheKey.of("/install/somewhere/else/wolf.variant", VARIANTS, ".variant"));
    }

    @Test
    void readsWindowsPathsTheSameWayAsPosixOnes() {
        assertEquals("abs:data/variants/wolf.variant",
                SpecCacheKey.of("C:\\Games\\Starsector\\data\\variants\\wolf.variant",
                        VARIANTS, ".variant"));
        assertEquals("data/variants/wolf.variant",
                SpecCacheKey.of("data\\variants\\wolf.variant", VARIANTS, ".variant"));
    }

    @Test
    void matchesAnyOfTheDirectoriesACacheServes() {
        assertEquals("abs:data/shipsystems/wpn/burndrive.wpn",
                SpecCacheKey.of("/install/data/shipsystems/wpn/burndrive.wpn", WEAPONS, ".wpn"));
        assertEquals("abs:data/weapons/lightmg.wpn",
                SpecCacheKey.of("/install/data/weapons/lightmg.wpn", WEAPONS, ".wpn"));
    }
}

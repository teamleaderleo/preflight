package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CacheCommandIdentityDepthTest {
    @Test
    void ordinarySummaryAndJsonDoNotRequireAudioCompatibilityIdentity() {
        assertFalse(CacheCommand.requiresFullIdentity(false, false, false));
    }

    @Test
    void healthInspectionAndRepairStillRequireFullIdentity() {
        assertTrue(CacheCommand.requiresFullIdentity(true, false, false));
        assertTrue(CacheCommand.requiresFullIdentity(false, true, false));
        assertTrue(CacheCommand.requiresFullIdentity(false, false, true));
    }
}

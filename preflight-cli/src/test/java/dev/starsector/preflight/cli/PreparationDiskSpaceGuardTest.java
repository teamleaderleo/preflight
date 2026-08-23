package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PreparationDiskSpaceGuardTest {
    @Test
    void accountsForConcurrentWritesAndReleasesReservations() throws Exception {
        AtomicLong usable = new AtomicLong(1_000);
        PreparationDiskSpaceGuard guard = new PreparationDiskSpaceGuard(usable::get, 100);

        try (PreparationDiskSpaceGuard.Lease ignored = guard.reserve(600, "first blob")) {
            IOException refused = assertThrows(
                    IOException.class, () -> guard.reserve(301, "second blob"));
            assertTrue(refused.getMessage().contains("second blob"));
        }

        assertDoesNotThrow(() -> {
            try (PreparationDiskSpaceGuard.Lease ignored = guard.reserve(800, "pack")) {
                // The reservation is released by close.
            }
        });
    }

    @Test
    void rechecksLiveFreeSpaceBeforeEveryWrite() throws Exception {
        AtomicLong usable = new AtomicLong(1_000);
        PreparationDiskSpaceGuard guard = new PreparationDiskSpaceGuard(usable::get, 100);

        try (PreparationDiskSpaceGuard.Lease ignored = guard.reserve(400, "first blob")) {
            // Simulate another process consuming disk after preparation began.
            usable.set(450);
            assertThrows(IOException.class, () -> guard.reserve(1, "next blob"));
        }
    }
}

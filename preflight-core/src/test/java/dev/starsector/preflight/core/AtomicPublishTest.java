package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicPublishTest {
    @Test
    void publishesOverAnAbsentTarget(@TempDir Path directory) throws IOException {
        Path temporary = Files.writeString(directory.resolve("new.tmp"), "published");
        Path target = directory.resolve("cache.spfi");

        AtomicPublish.replace(temporary, target);

        assertArrayEquals("published".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
        assertFalse(Files.exists(temporary), "the temporary is consumed by the move");
    }

    @Test
    void replacesAnExistingTargetWholesale(@TempDir Path directory) throws IOException {
        Path target = Files.writeString(directory.resolve("cache.spfi"), "stale and longer");
        Path temporary = Files.writeString(directory.resolve("new.tmp"), "fresh");

        AtomicPublish.replace(temporary, target);

        assertArrayEquals("fresh".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
    }

    @Test
    void reportsAMissingTemporaryImmediatelyInsteadOfRetrying(@TempDir Path directory) {
        Path temporary = directory.resolve("never-written.tmp");
        Path target = directory.resolve("cache.spfi");

        Instant start = Instant.now();
        assertThrows(NoSuchFileException.class, () -> AtomicPublish.replace(temporary, target));

        // A real failure must not spend the contention backoff before it is reported.
        assertTrue(
                Duration.between(start, Instant.now()).toMillis() < 100,
                "a missing temporary is not a contended publication");
    }

    @Test
    void givesUpOnPersistentDenialInsteadOfBlockingForever(@TempDir Path directory) throws IOException {
        // Windows denies a rename while another process holds the target open. An unwritable
        // directory is the portable way to produce the same exception and prove the retry is
        // bounded; root ignores the permission, so skip there.
        Path locked = Files.createDirectory(directory.resolve("locked"));
        Path temporary = Files.writeString(directory.resolve("new.tmp"), "fresh");
        Path target = locked.resolve("cache.spfi");
        try {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException notPosix) {
            return;
        }
        assumeFalse(Files.isWritable(locked), "running with permission to write anyway");

        Instant start = Instant.now();
        try {
            assertThrows(AccessDeniedException.class, () -> AtomicPublish.replace(temporary, target));
            long elapsed = Duration.between(start, Instant.now()).toMillis();
            assertTrue(elapsed < 5_000, "the retry is bounded, took " + elapsed + "ms");
            assertTrue(Files.exists(temporary), "a failed publication leaves the temporary to clean up");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void publishesAcrossDirectoriesWithinTheSameStore(@TempDir Path directory) throws IOException {
        Path from = Files.createDirectory(directory.resolve("staging"));
        Path to = Files.createDirectory(directory.resolve("packs"));
        Path temporary = Files.writeString(from.resolve("blob.tmp"), "content");
        Path target = to.resolve("blob.spfp");

        AtomicPublish.replace(temporary, target);

        assertArrayEquals("content".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
    }
}

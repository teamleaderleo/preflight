package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SafetyArtifactRetentionTest {
    private static final Pattern OWNED = Pattern.compile("backup-\\d+\\.json");

    @TempDir
    Path temporary;

    @Test
    void keepsNewestOwnedFilesAndLeavesUnknownEntriesAlone() throws Exception {
        Path directory = Files.createDirectories(temporary.resolve("backups"));
        for (int index = 0; index < 5; index++) {
            Path file = Files.writeString(directory.resolve("backup-" + index + ".json"), "x");
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_000L + index));
        }
        Path unknown = Files.writeString(directory.resolve("keep-me.txt"), "operator data");

        SafetyArtifactRetention.retainNewest(directory, OWNED, 2);

        assertFalse(Files.exists(directory.resolve("backup-0.json")));
        assertFalse(Files.exists(directory.resolve("backup-1.json")));
        assertFalse(Files.exists(directory.resolve("backup-2.json")));
        assertTrue(Files.exists(directory.resolve("backup-3.json")));
        assertTrue(Files.exists(directory.resolve("backup-4.json")));
        assertTrue(Files.exists(unknown));
    }

    @Test
    void removesOnlyOwnedFilesOlderThanTheCutoff() throws Exception {
        Path directory = Files.createDirectories(temporary.resolve("reviews"));
        Path old = Files.writeString(directory.resolve("backup-1.json"), "old");
        Path current = Files.writeString(directory.resolve("backup-2.json"), "current");
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(current, FileTime.from(Instant.parse("2026-01-03T00:00:00Z")));

        SafetyArtifactRetention.deleteOlderThan(
                directory, OWNED, Instant.parse("2026-01-02T00:00:00Z"));

        assertFalse(Files.exists(old));
        assertTrue(Files.exists(current));
    }

    @Test
    void refusesASymlinkedRetentionDirectory() throws Exception {
        Path real = Files.createDirectories(temporary.resolve("real"));
        Path link = temporary.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException unavailable) {
            return;
        }

        IOException error = assertThrows(
                IOException.class,
                () -> SafetyArtifactRetention.retainNewest(link, OWNED, 1));

        assertTrue(error.getMessage().contains("not a real directory"));
    }

    @Test
    void refusesASymlinkedDirectoryBeforeCreatingAnArtifact() throws Exception {
        Path real = Files.createDirectories(temporary.resolve("real-create"));
        Path link = temporary.resolve("link-create");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException unavailable) {
            return;
        }

        assertThrows(IOException.class, () -> SafetyArtifactRetention.requireRealDirectory(link));
        try (var entries = Files.list(real)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void rejectsNegativeLimits() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SafetyArtifactRetention.retainNewest(temporary, OWNED, -1));
    }

    @Test
    void missingDirectoryIsAnEmptyInventory() throws Exception {
        SafetyArtifactRetention.retainNewest(temporary.resolve("missing"), OWNED, 1);
        try (var entries = Files.list(temporary)) {
            assertEquals(0, entries.count());
        }
    }
}

package dev.starsector.preflight.core.checkpoints;

import static org.junit.jupiter.api.Assertions.*;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckpointStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Saves and loads complete checkpoint with 100% fidelity")
    void savesAndLoadsCompleteCheckpoint() throws Exception {
        Path checkpointsDir = tempDir.resolve("checkpoints");
        Path installRoot = tempDir.resolve("Starsector.app");

        Checkpoint.ModSignature sig1 = new Checkpoint.ModSignature("nexerelin", "Nexerelin", "0.11.1b", "0".repeat(64), 420, 18450200L);
        Checkpoint.ModSignature sig2 = new Checkpoint.ModSignature("uaf", "United Aurora Federation", "0.7.4a", "1".repeat(64), 1250, 182400100L);

        Checkpoint.LaunchSettingsSnapshot settings = new Checkpoint.LaunchSettingsSnapshot(
                "2560x1440", false, true, 4, 1.25, 500, 6144);
        Checkpoint.LastRunSummary lastRun = new Checkpoint.LastRunSummary(
                "SUCCESS", 14250L, 1820400L, 0L, Instant.now().toString());

        Checkpoint checkpoint = new Checkpoint(
                Checkpoint.FORMAT,
                "Cycle 214 Heavy Fleet",
                "Stable fleet configuration",
                installRoot,
                Instant.now().toString(),
                null,
                "profile-fingerprint-123",
                List.of("nexerelin", "uaf"),
                List.of(sig1, sig2),
                settings,
                lastRun,
                null
        );

        Path saved = CheckpointStore.save(checkpointsDir, checkpoint);
        assertTrue(Files.isRegularFile(saved));

        Checkpoint loaded = CheckpointStore.load(checkpointsDir, "Cycle 214 Heavy Fleet");
        assertEquals("Cycle 214 Heavy Fleet", loaded.name());
        assertEquals("Stable fleet configuration", loaded.description());
        assertEquals(installRoot.toAbsolutePath().normalize(), loaded.installRoot());
        assertEquals(List.of("nexerelin", "uaf"), loaded.enabledMods());
        assertEquals(2, loaded.modSignatures().size());
        assertEquals("nexerelin", loaded.modSignatures().get(0).modId());
        assertEquals("2560x1440", loaded.launchSettings().resolution());
        assertEquals(6144, loaded.launchSettings().memoryMiB());
        assertEquals("SUCCESS", loaded.lastRunSummary().outcome());
        assertEquals(checkpoint.checkpointFingerprint(), loaded.checkpointFingerprint());
    }

    @Test
    @DisplayName("Lists all checkpoints sorted alphabetically and captures diagnostics for corrupt files")
    void listsAllCheckpointsWithDiagnostics() throws Exception {
        Path checkpointsDir = tempDir.resolve("checkpoints");
        Path installRoot = tempDir.resolve("Starsector.app");

        Checkpoint cpZ = new Checkpoint(Checkpoint.FORMAT, "Zeta", "desc", installRoot, Instant.now().toString(), null, "p1", List.of(), List.of(), null, null, null);
        Checkpoint cpA = new Checkpoint(Checkpoint.FORMAT, "Alpha", "desc", installRoot, Instant.now().toString(), null, "p2", List.of(), List.of(), null, null, null);
        Checkpoint cpB = new Checkpoint(Checkpoint.FORMAT, "Beta", "desc", installRoot, Instant.now().toString(), null, "p3", List.of(), List.of(), null, null, null);

        CheckpointStore.save(checkpointsDir, cpZ);
        CheckpointStore.save(checkpointsDir, cpA);
        CheckpointStore.save(checkpointsDir, cpB);

        // Inject corrupt JSON file
        Files.writeString(checkpointsDir.resolve("corrupt.json"), "{ invalid json");

        CheckpointStore.LoadedCheckpoints result = CheckpointStore.listAll(checkpointsDir);
        assertEquals(3, result.checkpoints().size());
        assertEquals("Alpha", result.checkpoints().get(0).name());
        assertEquals("Beta", result.checkpoints().get(1).name());
        assertEquals("Zeta", result.checkpoints().get(2).name());
        assertEquals(1, result.diagnostics().size());
        assertTrue(result.diagnostics().get(0).contains("corrupt.json"));
    }

    @Test
    @DisplayName("Deletes checkpoint with safety backup preservation")
    void deleteCreatesBackupAndRemovesFile() throws Exception {
        Path checkpointsDir = tempDir.resolve("checkpoints");
        Path backupsDir = tempDir.resolve("checkpoint-backups");

        Checkpoint cp = new Checkpoint(Checkpoint.FORMAT, "Temp", "desc", tempDir, Instant.now().toString(), null, "p", List.of(), List.of(), null, null, null);
        Path saved = CheckpointStore.save(checkpointsDir, cp);
        Checkpoint loaded = CheckpointStore.load(checkpointsDir, "Temp");

        CheckpointStore.delete(checkpointsDir, backupsDir, loaded);
        assertFalse(Files.exists(saved));

        try (var stream = Files.list(backupsDir)) {
            List<Path> backups = stream.toList();
            assertEquals(1, backups.size());
            assertTrue(backups.get(0).getFileName().toString().startsWith("deleted-checkpoint-"));
        }
    }

    @Test
    @DisplayName("Validates checkpoint names rejecting blank, control characters, and excess length")
    void validatesCheckpointNames() {
        assertThrows(IllegalArgumentException.class, () -> CheckpointStore.validateName(null));
        assertThrows(IllegalArgumentException.class, () -> CheckpointStore.validateName(""));
        assertThrows(IllegalArgumentException.class, () -> CheckpointStore.validateName("   "));
        assertThrows(IllegalArgumentException.class, () -> CheckpointStore.validateName("Name\nWith\nNewline"));
        assertThrows(IllegalArgumentException.class, () -> CheckpointStore.validateName("A".repeat(101)));

        assertEquals("Valid Name", CheckpointStore.validateName("  Valid Name  "));
        assertEquals("A".repeat(100), CheckpointStore.validateName("A".repeat(100)));
    }
}

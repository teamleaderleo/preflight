package dev.starsector.preflight.core.checkpoints;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckpointComparatorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Fast status accurately identifies MATCHED, DIVERGED, DRIFTED, and INCOMPLETE")
    void fastStatusEvaluation() {
        Checkpoint.LaunchSettingsSnapshot settings = new Checkpoint.LaunchSettingsSnapshot(
                "2560x1440", false, true, 4, 1.25, 500, 6144);
        Checkpoint cp = new Checkpoint(
                Checkpoint.FORMAT, "Stable", "", tempDir, Instant.now().toString(), null,
                "prof1", List.of("mod_a", "mod_b"), List.of(), settings, null, null);

        // 1. Matched
        CheckpointComparator.Status s1 = CheckpointComparator.evaluateFastStatus(
                cp, List.of("mod_a", "mod_b"), Set.of("mod_a", "mod_b", "mod_c"), settings, "prof1");
        assertEquals(CheckpointComparator.Status.MATCHED, s1);

        // 2. Incomplete (missing mod_b on disk)
        CheckpointComparator.Status s2 = CheckpointComparator.evaluateFastStatus(
                cp, List.of("mod_a", "mod_b"), Set.of("mod_a"), settings, "prof1");
        assertEquals(CheckpointComparator.Status.INCOMPLETE, s2);

        // 3. Diverged (live enabled mods list differs)
        CheckpointComparator.Status s3 = CheckpointComparator.evaluateFastStatus(
                cp, List.of("mod_a"), Set.of("mod_a", "mod_b"), settings, "prof1");
        assertEquals(CheckpointComparator.Status.DIVERGED, s3);

        // 4. Drifted (settings modified)
        Checkpoint.LaunchSettingsSnapshot modifiedSettings = new Checkpoint.LaunchSettingsSnapshot(
                "1920x1080", false, true, 4, 1.25, 500, 6144);
        CheckpointComparator.Status s4 = CheckpointComparator.evaluateFastStatus(
                cp, List.of("mod_a", "mod_b"), Set.of("mod_a", "mod_b"), modifiedSettings, "prof1");
        assertEquals(CheckpointComparator.Status.DRIFTED, s4);

        // 5. Drifted (profile cache fingerprint changed)
        CheckpointComparator.Status s5 = CheckpointComparator.evaluateFastStatus(
                cp, List.of("mod_a", "mod_b"), Set.of("mod_a", "mod_b"), settings, "prof_different");
        assertEquals(CheckpointComparator.Status.DRIFTED, s5);
    }

    @Test
    @DisplayName("Deep live comparison produces structured diff with added/removed mods and settings deltas")
    void deepLiveComparison() {
        Checkpoint.ModSignature sigA = new Checkpoint.ModSignature("mod_a", "Mod A", "1.0", "0".repeat(64), 10, 1000L);
        Checkpoint.LaunchSettingsSnapshot settings = new Checkpoint.LaunchSettingsSnapshot(
                "2560x1440", false, true, 4, 1.25, 500, 6144);
        Checkpoint cp = new Checkpoint(
                Checkpoint.FORMAT, "Baseline", "", tempDir, Instant.now().toString(), null,
                "prof1", List.of("mod_a"), List.of(sigA), settings, null, null);

        ModContentSignature liveSigA = new ModContentSignature(
                "mod_a", "Mod A", "1.0", "mod_a", "1".repeat(64), null, 1050L, 10, List.of(), Map.of(), false);

        Checkpoint.LaunchSettingsSnapshot liveSettings = new Checkpoint.LaunchSettingsSnapshot(
                "3840x2160", true, true, 8, 1.5, 600, 8192);

        Map<String, Object> diff = CheckpointComparator.compareWithLive(
                cp, tempDir, List.of("mod_a", "mod_b"), Map.of("mod_a", liveSigA), liveSettings, "prof2");

        assertFalse((Boolean) diff.get("matched"));
        assertEquals("DIVERGED", diff.get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> enabledDiff = (Map<String, Object>) diff.get("enabledModsDiff");
        assertEquals(List.of("mod_b"), enabledDiff.get("added"));
        assertEquals(List.of(), enabledDiff.get("removed"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modDrift = (List<Map<String, Object>>) diff.get("modDrift");
        assertEquals(1, modDrift.size());
        assertEquals("mod_a", modDrift.get(0).get("modId"));
        assertEquals("CONTENT_MODIFIED", modDrift.get(0).get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> settingsDiff = (Map<String, Object>) diff.get("launchSettingsDiff");
        assertTrue(settingsDiff.containsKey("resolution"));
        assertTrue(settingsDiff.containsKey("memoryMiB"));
    }

    @Test
    @DisplayName("Direct checkpoint vs checkpoint comparison")
    void checkpointVsCheckpointComparison() {
        Checkpoint.ModSignature sigA1 = new Checkpoint.ModSignature("mod_a", "Mod A", "1.0", "0".repeat(64), 10, 1000L);
        Checkpoint.ModSignature sigA2 = new Checkpoint.ModSignature("mod_a", "Mod A", "2.0", "1".repeat(64), 10, 1000L);

        Checkpoint cp1 = new Checkpoint(
                Checkpoint.FORMAT, "CP1", "", tempDir, Instant.now().toString(), null,
                "prof1", List.of("mod_a"), List.of(sigA1), null, null, null);
        Checkpoint cp2 = new Checkpoint(
                Checkpoint.FORMAT, "CP2", "", tempDir, Instant.now().toString(), null,
                "prof2", List.of("mod_a"), List.of(sigA2), null, null, null);

        Map<String, Object> diff = CheckpointComparator.compareTwoCheckpoints(cp1, cp2);
        assertFalse((Boolean) diff.get("matched"));
        assertEquals("DRIFTED", diff.get("status"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modDrift = (List<Map<String, Object>>) diff.get("modDrift");
        assertEquals(1, modDrift.size());
        assertEquals("VERSION_CHANGED", modDrift.get(0).get("status"));
        assertEquals("1.0", modDrift.get(0).get("checkpointVersion"));
        assertEquals("2.0", modDrift.get(0).get("currentVersion"));
    }
}

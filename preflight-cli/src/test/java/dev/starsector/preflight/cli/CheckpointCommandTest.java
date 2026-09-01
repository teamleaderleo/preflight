package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.checkpoints.Checkpoint;
import dev.starsector.preflight.core.checkpoints.CheckpointStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckpointCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("CLI create captures live mod signatures, settings, and saves checkpoint")
    void createCheckpoint() throws Exception {
        Fixture fixture = createFixture(List.of("mod_a", "mod_b"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);

        int exit = CheckpointCommand.create(
                fixture.home(), fixture.game(), "Campaign Alpha", "Stable fleet", false, true, ps);
        assertEquals(0, exit);

        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"format\":\"" + Checkpoint.FORMAT + "\""));
        assertTrue(json.contains("\"name\":\"Campaign Alpha\""));
        assertTrue(json.contains("\"enabledMods\":[\"mod_a\",\"mod_b\"]"));

        Checkpoint loaded = CheckpointStore.load(fixture.home().checkpoints(), "Campaign Alpha");
        assertEquals("Campaign Alpha", loaded.name());
        assertEquals(2, loaded.enabledMods().size());
        assertEquals(2, loaded.modSignatures().size());
    }

    @Test
    @DisplayName("CLI list evaluates live installation status for checkpoints")
    void listCheckpoints() throws Exception {
        Fixture fixture = createFixture(List.of("mod_a", "mod_b"));
        ByteArrayOutputStream createOut = new ByteArrayOutputStream();
        CheckpointCommand.create(
                fixture.home(), fixture.game(), "Pristine", "desc", false, true, new PrintStream(createOut));

        ByteArrayOutputStream listOut = new ByteArrayOutputStream();
        int exit = CheckpointCommand.list(fixture.home(), fixture.game(), true, new PrintStream(listOut));
        assertEquals(0, exit);

        String json = listOut.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"format\":\"starsector-preflight-checkpoint-list-v1\""));
        assertTrue(json.contains("\"status\":\"MATCHED\""));
    }

    @Test
    @DisplayName("CLI compare outputs structured diff between checkpoint and live state")
    void compareCheckpoint() throws Exception {
        Fixture fixture = createFixture(List.of("mod_a"));
        CheckpointCommand.create(
                fixture.home(), fixture.game(), "Baseline", "desc", false, true, new PrintStream(new ByteArrayOutputStream()));

        // Switch live state to enable mod_b and disable mod_a
        Files.writeString(fixture.enabledFile(), Json.object(Map.of("enabledMods", List.of("mod_b"))));

        ByteArrayOutputStream compareOut = new ByteArrayOutputStream();
        int exit = CheckpointCommand.compare(fixture.home(), fixture.game(), "Baseline", null, true, new PrintStream(compareOut));
        assertEquals(0, exit);

        String json = compareOut.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"format\":\"starsector-preflight-checkpoint-diff-v1\""));
        assertTrue(json.contains("\"matched\":false"));
        assertTrue(json.contains("\"added\":[\"mod_b\"]"));
        assertTrue(json.contains("\"removed\":[\"mod_a\"]"));
    }

    @Test
    @DisplayName("CLI restore enforces two-phase review and executes atomic restoration")
    void restoreCheckpoint() throws Exception {
        Fixture fixture = createFixture(List.of("mod_a"));
        CheckpointCommand.create(
                fixture.home(), fixture.game(), "AlphaOnly", "desc", false, true, new PrintStream(new ByteArrayOutputStream()));

        // Live state changed to mod_b
        Files.writeString(fixture.enabledFile(), Json.object(Map.of("enabledMods", List.of("mod_b"))));

        // Phase 1: Preview (yes = false)
        ByteArrayOutputStream prevOut = new ByteArrayOutputStream();
        int prevExit = CheckpointCommand.restore(
                fixture.home(), fixture.game(), "AlphaOnly", null, false, false, true, new PrintStream(prevOut));
        assertEquals(0, prevExit);
        String prevJson = prevOut.toString(StandardCharsets.UTF_8);
        assertTrue(prevJson.contains("\"applied\":false"));
        assertTrue(prevJson.contains("\"enable\":[\"mod_a\"]"));

        // Live state must still be mod_b
        assertEquals(List.of("mod_b"), JsonText.stringArray(Files.readString(fixture.enabledFile(), StandardCharsets.UTF_8), "enabledMods"));

        // Phase 2: Execute (yes = true)
        ByteArrayOutputStream applyOut = new ByteArrayOutputStream();
        int applyExit = CheckpointCommand.restore(
                fixture.home(), fixture.game(), "AlphaOnly", null, false, true, true, new PrintStream(applyOut));
        assertEquals(0, applyExit);
        String applyJson = applyOut.toString(StandardCharsets.UTF_8);
        assertTrue(applyJson.contains("\"applied\":true"));

        // Live state must now be mod_a
        assertEquals(List.of("mod_a"), JsonText.stringArray(Files.readString(fixture.enabledFile(), StandardCharsets.UTF_8), "enabledMods"));

        // Verify profile backup created
        try (var stream = Files.list(fixture.home().profileBackups())) {
            assertFalse(stream.toList().isEmpty());
        }
    }

    @Test
    @DisplayName("CLI rename and delete operate atomically with backup")
    void renameAndDeleteCheckpoint() throws Exception {
        Fixture fixture = createFixture(List.of("mod_a"));
        ByteArrayOutputStream createOut = new ByteArrayOutputStream();
        CheckpointCommand.create(
                fixture.home(), fixture.game(), "Original", "desc", false, true, new PrintStream(createOut));
        Checkpoint cp = CheckpointStore.load(fixture.home().checkpoints(), "Original");

        // Rename
        ByteArrayOutputStream renameOut = new ByteArrayOutputStream();
        int renameExit = CheckpointCommand.rename(
                fixture.home(), fixture.game(), "Original", "Renamed", cp.checkpointFingerprint(), true, true, new PrintStream(renameOut));
        assertEquals(0, renameExit);
        assertTrue(renameOut.toString(StandardCharsets.UTF_8).contains("\"applied\":true"));

        Checkpoint renamedCp = CheckpointStore.load(fixture.home().checkpoints(), "Renamed");
        assertNotNull(renamedCp);

        // Delete
        ByteArrayOutputStream deleteOut = new ByteArrayOutputStream();
        int deleteExit = CheckpointCommand.delete(
                fixture.home(), fixture.game(), "Renamed", renamedCp.checkpointFingerprint(), true, true, new PrintStream(deleteOut));
        assertEquals(0, deleteExit);
        assertTrue(deleteOut.toString(StandardCharsets.UTF_8).contains("\"applied\":true"));

        // Checkpoint-backups contains backup
        try (var stream = Files.list(fixture.home().checkpointBackups())) {
            assertFalse(stream.toList().isEmpty());
        }
    }

    private Fixture createFixture(List<String> enabled) throws Exception {
        Path game = Files.createDirectories(tempDir.resolve("game_" + System.nanoTime()));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Path enabledFile = mods.resolve("enabled_mods.json");
        Files.writeString(enabledFile, Json.object(Map.of("enabledMods", enabled)));

        for (String id : List.of("mod_a", "mod_b")) {
            Path dir = Files.createDirectories(mods.resolve(id));
            Files.writeString(dir.resolve("mod_info.json"), "{\"id\":\"" + id + "\",\"name\":\"Mod " + id + "\",\"version\":\"1.0\"}");
            Path data = Files.createDirectories(dir.resolve("data"));
            Files.writeString(data.resolve("config.json"), "{\"id\":\"" + id + "\"}");
        }

        Path homeRoot = tempDir.resolve("home_" + System.nanoTime()).resolve(PreflightHome.DIRECTORY_NAME);
        PreflightHome home = new PreflightHome(homeRoot.toAbsolutePath().normalize(), List.of());

        return new Fixture(game.toAbsolutePath().normalize(), mods, enabledFile, home);
    }

    private record Fixture(Path game, Path mods, Path enabledFile, PreflightHome home) {}
}

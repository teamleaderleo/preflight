package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProfileDependencyRepairTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void previewShowsExactOrderPreservingChangeWithoutWriting() throws Exception {
        Fixture fixture = fixture(false);
        byte[] before = Files.readAllBytes(fixture.enabled());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertEquals(0, ProfileDependencyRepair.repair(
                fixture.home(), fixture.game(), false, true, stream(output)));

        assertArrayEquals(before, Files.readAllBytes(fixture.enabled()));
        String json = output.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"before\":[\"alpha\"]"));
        assertTrue(json.contains("\"enable\":[\"beta\"]"));
        assertTrue(json.contains("\"after\":[\"alpha\",\"beta\"]"));
        assertTrue(json.contains("\"applied\":false"));
        assertFalse(Files.exists(fixture.home().profileBackups()));
    }

    @Test
    void confirmationWithoutReviewRefusesWithoutWriting() throws Exception {
        Fixture fixture = fixture(false);
        byte[] before = Files.readAllBytes(fixture.enabled());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertEquals(2, ProfileDependencyRepair.repair(
                fixture.home(), fixture.game(), true, true, stream(output)));

        assertArrayEquals(before, Files.readAllBytes(fixture.enabled()));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"reviewChanged\":true"));
        assertFalse(Files.exists(fixture.home().profileBackups()));
    }

    @Test
    void reviewedConfirmationBacksUpAndAppliesExactlyTheProposal() throws Exception {
        Fixture fixture = fixture(false);
        assertEquals(0, ProfileDependencyRepair.repair(
                fixture.home(), fixture.game(), false, true, stream(new ByteArrayOutputStream())));
        ByteArrayOutputStream applied = new ByteArrayOutputStream();

        assertEquals(0, ProfileDependencyRepair.repair(
                fixture.home(), fixture.game(), true, true, stream(applied)));

        assertEquals(List.of("alpha", "beta"), enabled(fixture.enabled()));
        assertTrue(applied.toString(StandardCharsets.UTF_8).contains("\"applied\":true"));
        try (var backups = Files.list(fixture.home().profileBackups())) {
            Path backup = backups.findFirst().orElseThrow();
            assertEquals(List.of("alpha"), enabled(backup));
        }
    }

    @Test
    void sourceChangeAfterReviewRefusesAndLeavesFreshPlan() throws Exception {
        Fixture fixture = fixture(false);
        assertEquals(0, ProfileDependencyRepair.repair(
                fixture.home(), fixture.game(), false, true, stream(new ByteArrayOutputStream())));
        createMod(fixture.mods(), "gamma", null);
        byte[] changed = dev.starsector.preflight.core.Json.object(Map.of("enabledMods", List.of("alpha", "gamma")))
                .getBytes(StandardCharsets.UTF_8);
        Files.write(fixture.enabled(), changed);
        ByteArrayOutputStream refused = new ByteArrayOutputStream();

        assertEquals(2, ProfileDependencyRepair.repair(
                fixture.home(), fixture.game(), true, true, stream(refused)));

        assertArrayEquals(changed, Files.readAllBytes(fixture.enabled()));
        String json = refused.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"sourceChanged\":true"));
        assertTrue(json.contains("\"applied\":false"));
        assertFalse(Files.exists(fixture.home().profileBackups()));
    }

    @Test
    void proposalDoesNotAutoEnableATransitiveDependencyChain() throws Exception {
        Fixture fixture = fixture(true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertEquals(0, ProfileDependencyRepair.repair(
                fixture.home(), fixture.game(), false, true, stream(output)));

        String json = output.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"enable\":[\"beta\"]"));
        assertFalse(json.contains("\"enable\":[\"beta\",\"gamma\"]"));
        assertEquals(List.of("alpha"), enabled(fixture.enabled()));
    }

    private Fixture fixture(boolean transitive) throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve("game-" + transitive));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Path enabled = mods.resolve("enabled_mods.json");
        Files.writeString(enabled, dev.starsector.preflight.core.Json.object(Map.of("enabledMods", List.of("alpha"))));
        createMod(mods, "alpha", "beta");
        createMod(mods, "beta", transitive ? "gamma" : null);
        if (transitive) {
            createMod(mods, "gamma", null);
        }
        Path homeRoot = temporaryDirectory.resolve("home-" + transitive).resolve(PreflightHome.DIRECTORY_NAME);
        return new Fixture(
                game.toAbsolutePath().normalize(),
                mods,
                enabled,
                new PreflightHome(homeRoot.toAbsolutePath().normalize(), List.of()));
    }

    private static void createMod(Path mods, String id, String dependency) throws Exception {
        Path directory = Files.createDirectories(mods.resolve(id));
        String dependencies = dependency == null
                ? ""
                : ",\"dependencies\":[{\"id\":\"" + dependency + "\"}]";
        Files.writeString(
                directory.resolve("mod_info.json"),
                "{\"id\":\"" + id + "\",\"name\":\"" + id
                        + "\",\"version\":\"1\",\"gameVersion\":\"0.98a-RC8\""
                        + dependencies + "}");
    }

    private static List<String> enabled(Path file) throws Exception {
        return JsonText.stringArray(Files.readString(file), "enabledMods");
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    private record Fixture(Path game, Path mods, Path enabled, PreflightHome home) {
    }
}

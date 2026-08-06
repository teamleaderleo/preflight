package dev.starsector.preflight.cli;

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

final class ProfileCommandTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndListsTheCurrentOrderedModSet() throws Exception {
        Fixture fixture = fixture(List.of("alpha", "beta"));
        ByteArrayOutputStream saved = new ByteArrayOutputStream();

        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "Heavy campaign", true, stream(saved)));
        String savedJson = saved.toString(StandardCharsets.UTF_8);
        assertTrue(savedJson.contains("\"name\":\"Heavy campaign\""));
        assertTrue(savedJson.contains("\"enabledMods\":[\"alpha\",\"beta\"]"));

        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.list(fixture.home(), fixture.game(), true, stream(listed)));
        String listJson = listed.toString(StandardCharsets.UTF_8);
        assertTrue(listJson.contains("starsector-preflight-profile-list-v1"));
        assertTrue(listJson.contains("\"active\":true"));
        assertTrue(listJson.contains("\"missingMods\":[]"));
        String fingerprint = JsonText.string(savedJson, "profileFingerprint");
        assertEquals(
                java.util.Set.of(fingerprint),
                ProfileCommand.retainedFingerprints(fixture.home()).fingerprints());
    }

    @Test
    void activationIsPreviewOnlyUntilConfirmedThenBacksUpAndReplacesAtomically() throws Exception {
        Fixture fixture = fixture(List.of("alpha"));
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "alpha only", false, stream(new ByteArrayOutputStream())));
        Files.writeString(fixture.enabled(), "{\"enabledMods\":[\"beta\"]}");

        ByteArrayOutputStream preview = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.activate(
                fixture.home(), fixture.game(), "alpha only", false, true, stream(preview)));
        assertEquals(List.of("beta"), enabled(fixture.enabled()));
        assertTrue(preview.toString(StandardCharsets.UTF_8).contains("\"applied\":false"));

        ByteArrayOutputStream applied = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.activate(
                fixture.home(), fixture.game(), "alpha only", true, true, stream(applied)));
        assertEquals(List.of("alpha"), enabled(fixture.enabled()));
        assertTrue(applied.toString(StandardCharsets.UTF_8).contains("\"applied\":true"));
        try (var backups = Files.list(fixture.home().profileBackups())) {
            Path backup = backups.findFirst().orElseThrow();
            assertEquals(List.of("beta"), enabled(backup));
        }
    }

    @Test
    void refusesActivationWhenASavedModIsNoLongerInstalled() throws Exception {
        Fixture fixture = fixture(List.of("alpha", "beta"));
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "both", false, stream(new ByteArrayOutputStream())));
        Files.writeString(fixture.enabled(), "{\"enabledMods\":[\"alpha\"]}");
        moveBetaAway(fixture);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(2, ProfileCommand.activate(
                fixture.home(), fixture.game(), "both", true, true, stream(output)));
        assertEquals(List.of("alpha"), enabled(fixture.enabled()));
        String json = output.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"canActivate\":false"));
        assertTrue(json.contains("\"missingMods\":[\"beta\"]"));
        assertFalse(Files.exists(fixture.home().profileBackups()));
    }

    @Test
    void unreadableNamedProfileIsReportedToRetentionInsteadOfSilentlyDropped() throws Exception {
        Fixture fixture = fixture(List.of("alpha"));
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "valid", false, stream(new ByteArrayOutputStream())));
        Files.writeString(fixture.home().profiles().resolve("broken.json"), "not json");

        ProfileCommand.RetainedFingerprints retained =
                ProfileCommand.retainedFingerprints(fixture.home());

        assertEquals(1, retained.fingerprints().size());
        assertEquals(1, retained.diagnostics().size());
        assertTrue(retained.diagnostics().get(0).contains("broken.json"));
    }

    private Fixture fixture(List<String> enabled) throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve("game"));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Path enabledFile = mods.resolve("enabled_mods.json");
        Files.writeString(enabledFile, dev.starsector.preflight.core.Json.object(Map.of("enabledMods", enabled)));
        createMod(mods, "alpha");
        createMod(mods, "beta");
        Path root = temporaryDirectory.resolve("home").resolve(PreflightHome.DIRECTORY_NAME);
        return new Fixture(
                game.toAbsolutePath().normalize(),
                mods,
                enabledFile,
                new PreflightHome(root.toAbsolutePath().normalize(), List.of()));
    }

    private static void createMod(Path mods, String id) throws Exception {
        Path directory = Files.createDirectories(mods.resolve(id));
        Files.writeString(directory.resolve("mod_info.json"), "{\"id\":\"" + id + "\"}");
    }

    private static void moveBetaAway(Fixture fixture) throws Exception {
        Files.move(fixture.mods().resolve("beta"), fixture.game().resolve("beta-uninstalled"));
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

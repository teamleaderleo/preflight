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
        String previewJson = preview.toString(StandardCharsets.UTF_8);
        assertTrue(previewJson.contains("\"applied\":false"));
        assertTrue(previewJson.contains("\"sourceChanged\":false"));
        assertTrue(previewJson.contains("\"reviewChanged\":false"));
        String sourceStateSha256 = JsonText.string(previewJson, "sourceStateSha256");
        assertTrue(sourceStateSha256.matches("[0-9a-f]{64}"));

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
    void activationRefusesWhenEnabledModsChangedAfterPreview() throws Exception {
        Fixture fixture = fixture(List.of("alpha"));
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "alpha only", false, stream(new ByteArrayOutputStream())));
        Files.writeString(fixture.enabled(), "{\"enabledMods\":[\"beta\"]}");

        ByteArrayOutputStream preview = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.activate(
                fixture.home(), fixture.game(), "alpha only", false, true, stream(preview)));
        String reviewedState = JsonText.string(
                preview.toString(StandardCharsets.UTF_8), "sourceStateSha256");

        byte[] externallyEdited = "{\"enabledMods\":[]}".getBytes(StandardCharsets.UTF_8);
        Files.write(fixture.enabled(), externallyEdited);
        ByteArrayOutputStream refused = new ByteArrayOutputStream();
        assertEquals(2, ProfileCommand.activate(
                fixture.home(), fixture.game(), "alpha only", true, true, stream(refused)));

        assertArrayEquals(externallyEdited, Files.readAllBytes(fixture.enabled()));
        String refusalJson = refused.toString(StandardCharsets.UTF_8);
        assertTrue(refusalJson.contains("\"sourceChanged\":true"));
        assertTrue(refusalJson.contains("\"reviewChanged\":true"));
        assertTrue(refusalJson.contains("\"applied\":false"));
        String refreshedState = JsonText.string(refusalJson, "sourceStateSha256");
        assertTrue(refreshedState.matches("[0-9a-f]{64}"));
        assertFalse(refreshedState.equals(reviewedState));
        assertFalse(Files.exists(fixture.home().profileBackups()));

        // The refusal is the fresh plan now in front of the caller. Confirming that exact updated
        // plan can proceed, proving the conflict is recoverable instead of leaving a poisoned token.
        ByteArrayOutputStream retried = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.activate(
                fixture.home(), fixture.game(), "alpha only", true, true, stream(retried)));
        assertEquals(List.of("alpha"), enabled(fixture.enabled()));
        assertTrue(retried.toString(StandardCharsets.UTF_8).contains("\"applied\":true"));
    }

    @Test
    void activationWithoutAReviewRefusesWithoutWriting() throws Exception {
        Fixture fixture = fixture(List.of("alpha"));
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "alpha only", false, stream(new ByteArrayOutputStream())));
        byte[] before = "{\"enabledMods\":[\"beta\"]}".getBytes(StandardCharsets.UTF_8);
        Files.write(fixture.enabled(), before);

        ByteArrayOutputStream refused = new ByteArrayOutputStream();
        assertEquals(2, ProfileCommand.activate(
                fixture.home(), fixture.game(), "alpha only", true, true, stream(refused)));

        assertArrayEquals(before, Files.readAllBytes(fixture.enabled()));
        String json = refused.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"reviewChanged\":true"));
        assertTrue(json.contains("\"sourceChanged\":false"));
        assertTrue(json.contains("\"applied\":false"));
        assertFalse(Files.exists(fixture.home().profileBackups()));
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
        Files.createDirectories(fixture.home().profiles());
        Files.writeString(fixture.home().profiles().resolve("broken.json"), "not json");

        ProfileCommand.RetainedFingerprints retained =
                ProfileCommand.retainedFingerprints(fixture.home());

        assertEquals(1, retained.fingerprints().size());
        assertEquals(1, retained.diagnostics().size());
        assertTrue(retained.diagnostics().get(0).contains("broken.json"));
    }

    @Test
    void renameIsPreviewedAndFingerprintBoundBeforeItChangesAnything() throws Exception {
        Fixture fixture = fixture(List.of("alpha", "beta"));
        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "Old name", true, stream(saved)));
        String fingerprint = JsonText.string(saved.toString(StandardCharsets.UTF_8), "profileFingerprint");

        ByteArrayOutputStream preview = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.rename(
                fixture.home(), fixture.game(), "Old name", "New name", null,
                false, true, stream(preview)));
        assertTrue(preview.toString(StandardCharsets.UTF_8).contains("\"applied\":false"));
        assertTrue(list(fixture).contains("\"name\":\"Old name\""));

        ByteArrayOutputStream applied = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.rename(
                fixture.home(), fixture.game(), "Old name", "New name", fingerprint,
                true, true, stream(applied)));
        assertTrue(applied.toString(StandardCharsets.UTF_8).contains("\"applied\":true"));
        String listed = list(fixture);
        assertTrue(listed.contains("\"name\":\"New name\""));
        assertFalse(listed.contains("\"name\":\"Old name\""));
        assertEquals(
                java.util.Set.of(fingerprint),
                ProfileCommand.retainedFingerprints(fixture.home()).fingerprints());
    }

    @Test
    void renameRefusesAnExistingNameAndDeleteKeepsARecoverableBackup() throws Exception {
        Fixture fixture = fixture(List.of("alpha"));
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "First", true, stream(first)));
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "Second", false, stream(new ByteArrayOutputStream())));
        String fingerprint = JsonText.string(first.toString(StandardCharsets.UTF_8), "profileFingerprint");

        boolean collision = false;
        try {
            ProfileCommand.rename(
                    fixture.home(), fixture.game(), "First", "Second", null,
                    false, true, stream(new ByteArrayOutputStream()));
        } catch (java.io.IOException expected) {
            collision = expected.getMessage().contains("already exists");
        }
        assertTrue(collision);

        ByteArrayOutputStream preview = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.delete(
                fixture.home(), fixture.game(), "First", null,
                false, true, stream(preview)));
        assertTrue(preview.toString(StandardCharsets.UTF_8).contains("\"preparedDataKept\":true"));
        assertTrue(list(fixture).contains("\"name\":\"First\""));

        ByteArrayOutputStream deleted = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.delete(
                fixture.home(), fixture.game(), "First", fingerprint,
                true, true, stream(deleted)));
        assertFalse(list(fixture).contains("\"name\":\"First\""));
        assertTrue(deleted.toString(StandardCharsets.UTF_8).contains("\"backup\":"));
        try (var backups = Files.list(fixture.home().profileBackups())) {
            assertTrue(backups.anyMatch(path -> path.getFileName().toString().startsWith("deleted-profile-")));
        }
    }

    @Test
    void mutationRefusesAProfileThatChangedAfterReview() throws Exception {
        Fixture fixture = fixture(List.of("alpha"));
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "Campaign", false, stream(new ByteArrayOutputStream())));

        boolean refused = false;
        try {
            ProfileCommand.delete(
                    fixture.home(), fixture.game(), "Campaign", "0".repeat(64),
                    true, true, stream(new ByteArrayOutputStream()));
        } catch (java.io.IOException expected) {
            refused = expected.getMessage().contains("changed since review");
        }
        assertTrue(refused);
        assertTrue(list(fixture).contains("\"name\":\"Campaign\""));
    }

    @Test
    void costCommandReportsPublicModFootprint() throws Exception {
        Fixture fixture = fixture(List.of("alpha", "beta"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertEquals(0, ProfileCommand.cost(fixture.game(), true, stream(output)));
        String json = output.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("preflight-mod-cost-breakdown-v1"));
        assertTrue(json.contains("\"modId\":\"alpha\""));
        assertTrue(json.contains("\"modId\":\"beta\""));
        assertFalse(json.contains(temporaryDirectory.toString()));

        ByteArrayOutputStream textOutput = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.cost(fixture.game(), false, stream(textOutput)));
        String text = textOutput.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Profile cost breakdown"));
        assertTrue(text.contains("alpha"));
    }


    private static String list(Fixture fixture) throws Exception {
        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.list(fixture.home(), fixture.game(), true, stream(listed)));
        return listed.toString(StandardCharsets.UTF_8);
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

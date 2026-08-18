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
import java.util.Set;
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
                Set.of(fingerprint),
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
        String intendedFingerprint = JsonText.string(saved.toString(StandardCharsets.UTF_8), "profileFingerprint");

        ByteArrayOutputStream preview = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.rename(
                fixture.home(), fixture.game(), "Old name", "New name", null,
                false, true, stream(preview)));
        String previewJson = preview.toString(StandardCharsets.UTF_8);
        String reviewToken = JsonText.string(previewJson, "profileFingerprint");
        assertTrue(previewJson.contains("\"applied\":false"));
        assertEquals(intendedFingerprint, JsonText.string(previewJson, "sourceProfileFingerprint"));
        assertTrue(list(fixture).contains("\"name\":\"Old name\""));

        ByteArrayOutputStream applied = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.rename(
                fixture.home(), fixture.game(), "Old name", "New name", reviewToken,
                true, true, stream(applied)));
        assertTrue(applied.toString(StandardCharsets.UTF_8).contains("\"applied\":true"));
        String listed = list(fixture);
        assertTrue(listed.contains("\"name\":\"New name\""));
        assertFalse(listed.contains("\"name\":\"Old name\""));
        assertEquals(
                Set.of(intendedFingerprint),
                ProfileCommand.retainedFingerprints(fixture.home()).fingerprints());
    }

    @Test
    void renameRefusesAnExistingNameAndDeleteKeepsARecoverableBackup() throws Exception {
        Fixture fixture = fixture(List.of("alpha"));
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "First", false, stream(new ByteArrayOutputStream())));
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "Second", false, stream(new ByteArrayOutputStream())));

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
        String previewJson = preview.toString(StandardCharsets.UTF_8);
        String reviewToken = JsonText.string(previewJson, "profileFingerprint");
        assertTrue(previewJson.contains("\"preparedDataKept\":true"));
        assertTrue(list(fixture).contains("\"name\":\"First\""));

        ByteArrayOutputStream deleted = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.delete(
                fixture.home(), fixture.game(), "First", reviewToken,
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
    void saveSharesTheOperationLeaseWithProfileMutations() throws Exception {
        Fixture fixture = fixture(List.of("alpha"));
        OperationLease.Acquisition mutation =
                OperationLease.acquire(fixture.home(), "duplicating-profile", fixture.game());
        try (OperationLease ignored = mutation.lease()) {
            boolean refused = false;
            try {
                ProfileCommand.save(
                        fixture.home(), fixture.game(), "Concurrent", false,
                        stream(new ByteArrayOutputStream()));
            } catch (OperationLease.BusyException expected) {
                refused = true;
            }
            assertTrue(refused);
        }
        assertFalse(list(fixture).contains("\"name\":\"Concurrent\""));
    }

    @Test
    void duplicateRefusesSourceRecordChangedAfterReviewEvenWhenIntendedFingerprintIsReused()
            throws Exception {
        Fixture fixture = fixture(List.of("alpha"));
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "Original", false, stream(new ByteArrayOutputStream())));

        ByteArrayOutputStream preview = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.duplicate(
                fixture.home(), fixture.game(), "Original", "Experiment", null,
                false, true, stream(preview)));
        String reviewToken = JsonText.string(
                preview.toString(StandardCharsets.UTF_8), "profileFingerprint");

        Path sourceFile = onlyProfileFile(fixture);
        String original = Files.readString(sourceFile, StandardCharsets.UTF_8);
        String externallyChanged = original.replace(
                "\"enabledMods\":[\"alpha\"]", "\"enabledMods\":[\"beta\"]");
        assertFalse(original.equals(externallyChanged));
        Files.writeString(sourceFile, externallyChanged, StandardCharsets.UTF_8);

        boolean refused = false;
        try {
            ProfileCommand.duplicate(
                    fixture.home(), fixture.game(), "Original", "Experiment", reviewToken,
                    true, true, stream(new ByteArrayOutputStream()));
        } catch (java.io.IOException expected) {
            refused = expected.getMessage().contains("changed since review");
        }
        assertTrue(refused);
        String listed = list(fixture);
        assertTrue(listed.contains("\"name\":\"Original\""));
        assertFalse(listed.contains("\"name\":\"Experiment\""));
    }

    @Test
    void duplicateCreatesIndependentPersistentIdentityWithoutChangingTheActiveModFile() throws Exception {
        Fixture fixture = fixture(List.of("alpha"));
        byte[] activeBefore = Files.readAllBytes(fixture.enabled());
        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "Original", true, stream(saved)));
        String originalFingerprint = JsonText.string(
                saved.toString(StandardCharsets.UTF_8), "profileFingerprint");

        ByteArrayOutputStream preview = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.duplicate(
                fixture.home(), fixture.game(), "Original", "Experiment", null,
                false, true, stream(preview)));
        String previewJson = preview.toString(StandardCharsets.UTF_8);
        String reviewToken = JsonText.string(previewJson, "profileFingerprint");
        assertEquals(originalFingerprint, JsonText.string(previewJson, "sourceProfileFingerprint"));
        assertTrue(previewJson.contains("\"operation\":\"duplicate\""));
        assertTrue(previewJson.contains("\"name\":\"Original\""));
        assertTrue(previewJson.contains("\"targetName\":\"Experiment\""));
        assertTrue(previewJson.contains("\"applied\":false"));
        assertFalse(list(fixture).contains("\"name\":\"Experiment\""));

        ByteArrayOutputStream applied = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.duplicate(
                fixture.home(), fixture.game(), "Original", "Experiment", reviewToken,
                true, true, stream(applied)));
        assertTrue(applied.toString(StandardCharsets.UTF_8).contains("\"applied\":true"));
        assertArrayEquals(activeBefore, Files.readAllBytes(fixture.enabled()));

        PreflightHome restarted = new PreflightHome(fixture.home().root(), List.of());
        ByteArrayOutputStream afterRestart = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.list(restarted, fixture.game(), true, stream(afterRestart)));
        String listed = afterRestart.toString(StandardCharsets.UTF_8);
        assertTrue(listed.contains("\"name\":\"Original\""));
        assertTrue(listed.contains("\"name\":\"Experiment\""));
        assertEquals(Set.of(originalFingerprint),
                ProfileCommand.retainedFingerprints(restarted).fingerprints());

        Files.writeString(fixture.enabled(), "{\"enabledMods\":[\"beta\"]}");
        ByteArrayOutputStream resaved = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.save(
                restarted, fixture.game(), "Original", true, stream(resaved)));
        String changedFingerprint = JsonText.string(
                resaved.toString(StandardCharsets.UTF_8), "profileFingerprint");
        assertFalse(originalFingerprint.equals(changedFingerprint));
        assertEquals(Set.of(originalFingerprint, changedFingerprint),
                ProfileCommand.retainedFingerprints(restarted).fingerprints());

        String originalDeleteToken = deletePreviewToken(restarted, fixture, "Original");
        assertEquals(0, ProfileCommand.delete(
                restarted, fixture.game(), "Original", originalDeleteToken,
                true, true, stream(new ByteArrayOutputStream())));
        assertEquals(Set.of(originalFingerprint),
                ProfileCommand.retainedFingerprints(restarted).fingerprints());
        assertTrue(list(restarted, fixture).contains("\"name\":\"Experiment\""));

        boolean collision = false;
        try {
            ProfileCommand.duplicate(
                    restarted, fixture.game(), "Experiment", "Experiment", null,
                    false, true, stream(new ByteArrayOutputStream()));
        } catch (IllegalArgumentException expected) {
            collision = expected.getMessage().contains("different");
        }
        assertTrue(collision);
    }

    @Test
    void duplicateRejectsInvalidNamesAndDestinationCollisions() throws Exception {
        Fixture fixture = fixture(List.of("alpha"));
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "Original", false, stream(new ByteArrayOutputStream())));
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "Existing", false, stream(new ByteArrayOutputStream())));

        boolean collision = false;
        try {
            ProfileCommand.duplicate(
                    fixture.home(), fixture.game(), "Original", "Existing", null,
                    false, true, stream(new ByteArrayOutputStream()));
        } catch (java.io.IOException expected) {
            collision = expected.getMessage().contains("already exists");
        }
        assertTrue(collision);

        for (String invalid : List.of("   ", "bad\nname", "x".repeat(101))) {
            boolean rejected = false;
            try {
                ProfileCommand.duplicate(
                        fixture.home(), fixture.game(), "Original", invalid, null,
                        false, true, stream(new ByteArrayOutputStream()));
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            assertTrue(rejected);
        }
        assertEquals(2, profileFileCount(fixture.home()));
    }

    private static String deletePreviewToken(PreflightHome home, Fixture fixture, String name)
            throws Exception {
        ByteArrayOutputStream preview = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.delete(
                home, fixture.game(), name, null, false, true, stream(preview)));
        return JsonText.string(preview.toString(StandardCharsets.UTF_8), "profileFingerprint");
    }

    private static Path onlyProfileFile(Fixture fixture) throws Exception {
        try (var files = Files.list(fixture.home().profiles())) {
            return files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
    }

    private static long profileFileCount(PreflightHome home) throws Exception {
        try (var files = Files.list(home.profiles())) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static String list(Fixture fixture) throws Exception {
        return list(fixture.home(), fixture);
    }

    private static String list(PreflightHome home, Fixture fixture) throws Exception {
        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.list(home, fixture.game(), true, stream(listed)));
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

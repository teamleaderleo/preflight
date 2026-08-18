package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProfileSaveUpdateRaceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void plainSaveRefusesExistingNameAndPreservesExactBytes() throws Exception {
        Fixture fixture = fixture("same-install");
        save(fixture, "Campaign");
        Path target = profile(fixture, "Campaign");
        byte[] original = Files.readAllBytes(target);
        byte[] enabledBefore = Files.readAllBytes(enabledFile(fixture));
        enable(fixture, "beta");
        byte[] enabledAfterSelection = Files.readAllBytes(enabledFile(fixture));

        IOException failure = assertThrows(IOException.class, () -> save(fixture, "Campaign"));

        assertTrue(failure.getMessage().contains("already exists"));
        assertTrue(failure.getMessage().contains("profile update"));
        assertArrayEquals(original, Files.readAllBytes(target));
        assertFalse(java.util.Arrays.equals(enabledBefore, enabledAfterSelection));
        assertArrayEquals(enabledAfterSelection, Files.readAllBytes(enabledFile(fixture)));
        assertEquals(List.of("Campaign"), names(fixture));
        assertNoTransactions(fixture);
    }

    @Test
    void plainSaveFinalPublicationRacePreservesExternalWinner() throws Exception {
        Fixture fixture = fixture("save-race");
        Path target = profile(fixture, "Campaign");
        byte[] external = profileBytes(fixture.game(), "Campaign", List.of("beta"), "f".repeat(64));
        byte[] enabledBefore = Files.readAllBytes(enabledFile(fixture));

        IOException failure = assertThrows(IOException.class, () -> ProfileCommand.save(
                fixture.home(),
                fixture.game(),
                "Campaign",
                true,
                stream(new ByteArrayOutputStream()),
                new ProfileCommand.DuplicatePublicationHook() {
                    @Override
                    public void beforePublication(Path ignored) throws IOException {
                        Files.write(target, external);
                    }
                }));

        assertTrue(failure.getMessage().contains("already exists"));
        assertArrayEquals(external, Files.readAllBytes(target));
        assertArrayEquals(enabledBefore, Files.readAllBytes(enabledFile(fixture)));
        assertEquals(List.of("Campaign"), names(fixture));
        assertNoTransactions(fixture);
    }

    @Test
    void reviewedUpdateChangesOnlyTheIntendedNamedRecord() throws Exception {
        Fixture fixture = fixture("reviewed-update");
        save(fixture, "Campaign");
        save(fixture, "Reference");
        Path campaign = profile(fixture, "Campaign");
        Path reference = profile(fixture, "Reference");
        byte[] campaignBefore = Files.readAllBytes(campaign);
        byte[] referenceBefore = Files.readAllBytes(reference);
        enable(fixture, "beta");
        byte[] enabledBefore = Files.readAllBytes(enabledFile(fixture));
        Tokens tokens = previewUpdate(fixture, "Campaign");
        ByteArrayOutputStream applied = new ByteArrayOutputStream();

        assertEquals(0, ProfileCommand.update(
                fixture.home(), fixture.game(), "Campaign",
                tokens.existing(), tokens.replacement(), true, true, stream(applied)));

        Map<String, Object> result = StrictJson.object(applied.toString(StandardCharsets.UTF_8));
        assertEquals(Boolean.TRUE, result.get("applied"));
        assertEquals("update", result.get("operation"));
        assertFalse(java.util.Arrays.equals(campaignBefore, Files.readAllBytes(campaign)));
        assertEquals(List.of("beta"), profileEnabled(campaign));
        assertArrayEquals(referenceBefore, Files.readAllBytes(reference));
        assertArrayEquals(enabledBefore, Files.readAllBytes(enabledFile(fixture)));
        assertEquals(List.of("Campaign", "Reference"), names(fixture));
        assertNoTransactions(fixture);
    }

    @Test
    void changedExistingRecordAfterPreviewWinsAndIsPreserved() throws Exception {
        Fixture fixture = fixture("stale-existing");
        save(fixture, "Campaign");
        enable(fixture, "beta");
        Tokens tokens = previewUpdate(fixture, "Campaign");
        Path target = profile(fixture, "Campaign");
        byte[] external = profileBytes(fixture.game(), "Campaign", List.of("alpha"), "e".repeat(64));
        Files.write(target, external);
        byte[] enabledBefore = Files.readAllBytes(enabledFile(fixture));

        IOException failure = assertThrows(IOException.class, () -> ProfileCommand.update(
                fixture.home(), fixture.game(), "Campaign",
                tokens.existing(), tokens.replacement(), true, true,
                stream(new ByteArrayOutputStream())));

        assertTrue(failure.getMessage().contains("changed since review"));
        assertArrayEquals(external, Files.readAllBytes(target));
        assertArrayEquals(enabledBefore, Files.readAllBytes(enabledFile(fixture)));
        assertNoTransactions(fixture);
    }

    @Test
    void changedProposedStateAfterPreviewRefusesWithoutTouchingProfile() throws Exception {
        Fixture fixture = fixture("stale-proposal");
        save(fixture, "Campaign");
        Path target = profile(fixture, "Campaign");
        byte[] original = Files.readAllBytes(target);
        enable(fixture, "beta");
        Tokens tokens = previewUpdate(fixture, "Campaign");
        enable(fixture, "alpha");
        byte[] enabledBefore = Files.readAllBytes(enabledFile(fixture));

        IOException failure = assertThrows(IOException.class, () -> ProfileCommand.update(
                fixture.home(), fixture.game(), "Campaign",
                tokens.existing(), tokens.replacement(), true, true,
                stream(new ByteArrayOutputStream())));

        assertTrue(failure.getMessage().contains("current mod selection changed since review"));
        assertArrayEquals(original, Files.readAllBytes(target));
        assertArrayEquals(enabledBefore, Files.readAllBytes(enabledFile(fixture)));
        assertNoTransactions(fixture);
    }

    @Test
    void externalWriterAtFinalUpdatePublicationWins() throws Exception {
        Fixture fixture = fixture("update-race");
        save(fixture, "Campaign");
        enable(fixture, "beta");
        Tokens tokens = previewUpdate(fixture, "Campaign");
        Path target = profile(fixture, "Campaign");
        byte[] external = profileBytes(fixture.game(), "Campaign", List.of("alpha"), "d".repeat(64));
        byte[] enabledBefore = Files.readAllBytes(enabledFile(fixture));

        IOException failure = assertThrows(IOException.class, () -> ProfileCommand.update(
                fixture.home(), fixture.game(), "Campaign",
                tokens.existing(), tokens.replacement(), true, true,
                stream(new ByteArrayOutputStream()), new ProfileMutationTransaction.Hook() {
                    @Override
                    public void beforeTargetPublication(Path ignored) throws IOException {
                        Files.write(target, external);
                    }
                }));

        assertTrue(failure.getMessage().contains("update commit boundary"));
        assertArrayEquals(external, Files.readAllBytes(target));
        assertArrayEquals(enabledBefore, Files.readAllBytes(enabledFile(fixture)));
        assertTrue(backupFiles(fixture).stream().anyMatch(path ->
                path.getFileName().toString().startsWith("conflicted-profile-")));
        assertNoTransactions(fixture);
    }

    @Test
    void sameNameFromAnotherInstallationCannotBeRetargetedByPlainSave() throws Exception {
        PreflightHome home = home();
        Fixture first = fixture("install-one", home);
        save(first, "Campaign");
        Path target = profile(first, "Campaign");
        byte[] original = Files.readAllBytes(target);
        Fixture second = fixture("install-two", home);
        enable(second, "beta");
        byte[] secondEnabled = Files.readAllBytes(enabledFile(second));

        IOException failure = assertThrows(IOException.class, () -> save(second, "Campaign"));

        assertTrue(failure.getMessage().contains("already exists"));
        assertArrayEquals(original, Files.readAllBytes(target));
        assertArrayEquals(secondEnabled, Files.readAllBytes(enabledFile(second)));
        assertEquals(List.of("Campaign"), names(first));
        assertNoTransactions(first);
    }

    @Test
    void interruptedUpdateRestoresReviewedGenerationBeforeNextProfileWriter() throws Exception {
        Fixture fixture = fixture("recovery");
        save(fixture, "Campaign");
        Path target = profile(fixture, "Campaign");
        byte[] original = Files.readAllBytes(target);
        enable(fixture, "beta");
        Tokens tokens = previewUpdate(fixture, "Campaign");

        assertThrows(SimulatedInterruption.class, () -> ProfileCommand.update(
                fixture.home(), fixture.game(), "Campaign",
                tokens.existing(), tokens.replacement(), true, true,
                stream(new ByteArrayOutputStream()), new ProfileMutationTransaction.Hook() {
                    @Override
                    public void afterSourceCapture(Path captured) {
                        throw new SimulatedInterruption();
                    }
                }));
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
        assertTrue(hasTransaction(fixture));

        IOException recovery = assertThrows(IOException.class, () -> save(fixture, "Other"));

        assertTrue(recovery.getMessage().contains("review"));
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals(List.of("Campaign"), names(fixture));
        assertNoTransactions(fixture);
    }

    @Test
    void committedUpdateCleanupResidueNeverEnumeratesAsAnotherProfile() throws Exception {
        Fixture fixture = fixture("cleanup");
        save(fixture, "Campaign");
        enable(fixture, "beta");
        Tokens tokens = previewUpdate(fixture, "Campaign");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertEquals(0, ProfileCommand.update(
                fixture.home(), fixture.game(), "Campaign",
                tokens.existing(), tokens.replacement(), true, true, stream(output),
                new ProfileMutationTransaction.Hook() {
                    private boolean failed;

                    @Override
                    public void delete(Path path) throws IOException {
                        if (!failed && path.getFileName().toString().equals("captured")) {
                            failed = true;
                            throw new IOException("simulated cleanup denial");
                        }
                        ProfileMutationTransaction.Hook.super.delete(path);
                    }
                }));

        Map<String, Object> result = StrictJson.object(output.toString(StandardCharsets.UTF_8));
        assertEquals(Boolean.TRUE, result.get("applied"));
        assertEquals(Boolean.TRUE, result.get("cleanupPending"));
        assertEquals(List.of("Campaign"), names(fixture));
        assertTrue(hasTransaction(fixture));

        save(fixture, "Other");
        assertEquals(List.of("Campaign", "Other"), names(fixture));
        assertNoTransactions(fixture);
    }

    private Fixture fixture(String name) throws Exception {
        return fixture(name, home());
    }

    private Fixture fixture(String name, PreflightHome home) throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve(name).resolve("game"));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Files.writeString(
                mods.resolve("enabled_mods.json"),
                Json.object(Map.of("enabledMods", List.of("alpha"))),
                StandardCharsets.UTF_8);
        for (String mod : List.of("alpha", "beta")) {
            Path directory = Files.createDirectories(mods.resolve(mod));
            Files.writeString(
                    directory.resolve("mod_info.json"),
                    Json.object(Map.of("id", mod)),
                    StandardCharsets.UTF_8);
        }
        return new Fixture(game.toAbsolutePath().normalize(), home);
    }

    private PreflightHome home() {
        Path root = temporaryDirectory.resolve("home").resolve(PreflightHome.DIRECTORY_NAME);
        return new PreflightHome(root.toAbsolutePath().normalize(), List.of());
    }

    private static void save(Fixture fixture, String name) throws Exception {
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), name, false, stream(new ByteArrayOutputStream())));
    }

    private static Tokens previewUpdate(Fixture fixture, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.update(
                fixture.home(), fixture.game(), name, null, null,
                false, true, stream(output)));
        Map<String, Object> preview = StrictJson.object(output.toString(StandardCharsets.UTF_8));
        return new Tokens(
                (String) preview.get("profileFingerprint"),
                (String) preview.get("replacementFingerprint"));
    }

    private static void enable(Fixture fixture, String mod) throws IOException {
        Files.writeString(
                enabledFile(fixture),
                Json.object(Map.of("enabledMods", List.of(mod))),
                StandardCharsets.UTF_8);
    }

    private static Path enabledFile(Fixture fixture) {
        return fixture.game().resolve("mods/enabled_mods.json");
    }

    private static Path profile(Fixture fixture, String name) {
        return fixture.home().profiles().resolve(ProfileRecordFiles.canonicalFileName(name));
    }

    private static List<String> profileEnabled(Path profile) throws IOException {
        return JsonText.stringArray(Files.readString(profile, StandardCharsets.UTF_8), "enabledMods");
    }

    private static List<String> names(Fixture fixture) throws Exception {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>)
                ProfileCommand.describeList(fixture.home(), fixture.game()).get("profiles");
        return profiles.stream().map(profile -> (String) profile.get("name")).sorted().toList();
    }

    private static byte[] profileBytes(
            Path installRoot,
            String name,
            List<String> enabledMods,
            String fingerprint) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("format", "starsector-preflight-profile-v1");
        value.put("name", name);
        value.put("installRoot", installRoot.toAbsolutePath().normalize());
        value.put("enabledMods", enabledMods);
        value.put("profileFingerprint", fingerprint);
        value.put("savedAt", "2026-08-18T00:00:00Z");
        return (Json.object(value) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    private static List<Path> backupFiles(Fixture fixture) throws IOException {
        if (!Files.isDirectory(fixture.home().profileBackups(), LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var entries = Files.list(fixture.home().profileBackups())) {
            return entries.sorted().toList();
        }
    }

    private static boolean hasTransaction(Fixture fixture) throws IOException {
        if (!Files.isDirectory(fixture.home().profiles(), LinkOption.NOFOLLOW_LINKS)) return false;
        try (var entries = Files.list(fixture.home().profiles())) {
            return entries.anyMatch(path ->
                    path.getFileName().toString().startsWith(".preflight-profile-txn-"));
        }
    }

    private static void assertNoTransactions(Fixture fixture) throws IOException {
        assertFalse(hasTransaction(fixture));
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    private record Fixture(Path game, PreflightHome home) {
    }

    private record Tokens(String existing, String replacement) {
    }

    private static final class SimulatedInterruption extends Error {
        private static final long serialVersionUID = 1L;
    }
}

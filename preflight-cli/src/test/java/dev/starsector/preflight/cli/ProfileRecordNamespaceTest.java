package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.starsector.preflight.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProfileRecordNamespaceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void completeAndPartialStagingResidueNeverEnumeratesAsProfiles() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Original");
        Path canonical = onlyCanonicalProfile(fixture.home());
        String complete = Files.readString(canonical, StandardCharsets.UTF_8);

        Files.writeString(
                fixture.home().profiles().resolve(".preflight-profile-orphan.json"),
                complete,
                StandardCharsets.UTF_8);
        Files.writeString(
                fixture.home().profiles().resolve(".preflight-profile-partial.json"),
                "{\"format\":",
                StandardCharsets.UTF_8);
        Files.writeString(
                fixture.home().profiles().resolve(".preflight-profile-create-orphan.tmp"),
                complete,
                StandardCharsets.UTF_8);
        Files.writeString(
                fixture.home().profiles().resolve(".preflight-profile.tmp-999999991-orphan"),
                complete,
                StandardCharsets.UTF_8);

        Map<String, Object> report = ProfileCommand.describeList(fixture.home(), fixture.game());

        assertEquals(1, profiles(report).size());
        assertEquals("Original", profiles(report).get(0).get("name"));
        assertEquals(List.of(), diagnostics(report), "known staging residue stays outside profile diagnostics");
        assertEquals(1, ProfileCommand.retainedFingerprints(fixture.home()).fingerprints().size());
    }

    @Test
    void validJsonOutsideTheCanonicalProfileFilenameIsDiagnosticOnly() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Original");
        Path canonical = onlyCanonicalProfile(fixture.home());
        Files.copy(canonical, fixture.home().profiles().resolve("misplaced.json"));
        Files.delete(canonical);

        Map<String, Object> report = ProfileCommand.describeList(fixture.home(), fixture.game());

        assertEquals(List.of(), profiles(report));
        assertTrue(diagnostics(report).stream().anyMatch(message ->
                message.contains("misplaced.json") && message.contains("canonical named-profile filename")));
        assertEquals(0, ProfileCommand.retainedFingerprints(fixture.home()).fingerprints().size());
    }

    @Test
    void canonicalLookingFilenameMustMatchTheEmbeddedProfileName() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Original");
        Path canonical = onlyCanonicalProfile(fixture.home());
        String otherDigest = "0".repeat(64);
        Path duplicateName = fixture.home().profiles().resolve(otherDigest + ".json");
        assertFalse(canonical.equals(duplicateName));
        Files.copy(canonical, duplicateName);

        Map<String, Object> report = ProfileCommand.describeList(fixture.home(), fixture.game());

        assertEquals(1, profiles(report).size(), "only the name-derived canonical record is actionable");
        assertTrue(diagnostics(report).stream().anyMatch(message ->
                message.contains(otherDigest + ".json") && message.contains("embedded profile name")));
        assertEquals(1, ProfileCommand.retainedFingerprints(fixture.home()).fingerprints().size());
    }

    @Test
    void canonicalLookingSymlinkNeverBecomesAProfileRecord() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Original");
        Path canonical = onlyCanonicalProfile(fixture.home());
        Path outside = temporaryDirectory.resolve("outside-profile.json");
        Files.copy(canonical, outside);
        Files.delete(canonical);
        try {
            Files.createSymbolicLink(canonical, outside);
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable in this test environment: " + unavailable);
        }

        Map<String, Object> report = ProfileCommand.describeList(fixture.home(), fixture.game());

        assertEquals(List.of(), profiles(report));
        assertTrue(diagnostics(report).stream().anyMatch(message ->
                message.contains(canonical.getFileName().toString()) && message.contains("Named profile not found")));
        assertEquals(0, ProfileCommand.retainedFingerprints(fixture.home()).fingerprints().size());
        assertTrue(Files.isRegularFile(outside));
    }

    @Test
    void pidTaggedProfileStageIsRecoveredByTheExistingOperationLeaseCleanup() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Original");
        long deadPid = 999_999_991L;
        Path stage = fixture.home().profiles().resolve(
                ".preflight-profile.tmp-" + deadPid + "-interrupted");
        Files.writeString(stage, "incomplete stage", StandardCharsets.UTF_8);
        Files.createDirectories(fixture.home().state());
        Files.writeString(
                fixture.home().state().resolve("operation.json"),
                Json.object(Map.of(
                        "format", OperationLease.FORMAT,
                        "operation", "saving-profile",
                        "pid", deadPid,
                        "startedAt", "2026-08-18T00:00:00Z",
                        "installRoot", fixture.game(),
                        "token", "interrupted-profile-writer")) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        save(fixture, "Second");

        assertFalse(Files.exists(stage));
        Map<String, Object> report = ProfileCommand.describeList(fixture.home(), fixture.game());
        assertEquals(2, profiles(report).size());
        assertEquals(List.of(), diagnostics(report));
    }

    private Fixture fixture() throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve("game"));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Files.writeString(
                mods.resolve("enabled_mods.json"),
                Json.object(Map.of("enabledMods", List.of("alpha"))),
                StandardCharsets.UTF_8);
        Path alpha = Files.createDirectories(mods.resolve("alpha"));
        Files.writeString(alpha.resolve("mod_info.json"), "{\"id\":\"alpha\"}", StandardCharsets.UTF_8);
        Path root = temporaryDirectory.resolve("home").resolve(PreflightHome.DIRECTORY_NAME);
        return new Fixture(
                game.toAbsolutePath().normalize(),
                new PreflightHome(root.toAbsolutePath().normalize(), List.of()));
    }

    private static void save(Fixture fixture, String name) throws Exception {
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), name, false, stream(new ByteArrayOutputStream())));
    }

    private static Path onlyCanonicalProfile(PreflightHome home) throws Exception {
        try (var files = Files.list(home.profiles())) {
            return files
                    .filter(path -> path.getFileName().toString().matches("[0-9a-f]{64}\\.json"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> profiles(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("profiles");
    }

    @SuppressWarnings("unchecked")
    private static List<String> diagnostics(Map<String, Object> report) {
        return (List<String>) report.get("diagnostics");
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    private record Fixture(Path game, PreflightHome home) {
    }
}

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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProfileDestructiveMutationRaceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void renamedProfileIsOwnerPrivateOnPosix() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Source");
        String token = renamePreviewToken(fixture, "Source", "Renamed");

        assertEquals(0, ProfileCommand.rename(
                fixture.home(), fixture.game(), "Source", "Renamed", token, true, true,
                stream(new ByteArrayOutputStream()), new ProfileMutationTransaction.Hook() {}));

        assertOwnerPrivateWhenPosix(profile(fixture, "Renamed"));
    }

    @Test
    void deletedProfileBackupIsOwnerPrivateOnPosix() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Source");
        String token = deletePreviewToken(fixture, "Source");

        assertEquals(0, ProfileCommand.delete(
                fixture.home(), fixture.game(), "Source", token, true, true,
                stream(new ByteArrayOutputStream()), new ProfileMutationTransaction.Hook() {}));

        Path backup = backupFiles(fixture).stream()
                .filter(path -> path.getFileName().toString().startsWith("deleted-profile-"))
                .findFirst()
                .orElseThrow();
        assertOwnerPrivateWhenPosix(backup);
    }

    @Test
    void renameDestinationCreatedAtFinalPublicationWinsWithoutLosingSource() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Source");
        Path source = profile(fixture, "Source");
        Path target = profile(fixture, "Renamed");
        String token = renamePreviewToken(fixture, "Source", "Renamed");
        byte[] racedTarget = renamedBytes(source, "Source", "Renamed");

        IOException failure = assertThrows(IOException.class, () -> ProfileCommand.rename(
                fixture.home(), fixture.game(), "Source", "Renamed", token, true, true,
                stream(new ByteArrayOutputStream()), new ProfileMutationTransaction.Hook() {
                    @Override
                    public void beforeTargetPublication(Path ignored) throws IOException {
                        Files.write(target, racedTarget);
                    }
                }));

        assertTrue(failure.getMessage().contains("already exists"));
        assertTrue(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(racedTarget, Files.readAllBytes(target));
        assertEquals(List.of("Renamed", "Source"), names(fixture));
        assertNoTransactions(fixture);
    }

    @Test
    void sameSizeSameMtimeSourceReplacementWinsRenameFinalCapture() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Source");
        Path source = profile(fixture, "Source");
        Path target = profile(fixture, "Renamed");
        String token = renamePreviewToken(fixture, "Source", "Renamed");
        byte[] external = changedGenerationSameLength(source);
        FileTime originalTime = Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS);

        IOException failure = assertThrows(IOException.class, () -> ProfileCommand.rename(
                fixture.home(), fixture.game(), "Source", "Renamed", token, true, true,
                stream(new ByteArrayOutputStream()), new ProfileMutationTransaction.Hook() {
                    @Override
                    public void beforeSourceCapture(Path path) throws IOException {
                        replaceExternally(path, external, originalTime);
                    }
                }));

        assertTrue(failure.getMessage().contains("commit boundary"));
        assertArrayEquals(external, Files.readAllBytes(source));
        assertEquals(originalTime, Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
        assertNoTransactions(fixture);
    }

    @Test
    void sameSizeSameMtimeSourceReplacementWinsDeleteFinalCapture() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Source");
        Path source = profile(fixture, "Source");
        String token = deletePreviewToken(fixture, "Source");
        byte[] external = changedGenerationSameLength(source);
        FileTime originalTime = Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS);

        IOException failure = assertThrows(IOException.class, () -> ProfileCommand.delete(
                fixture.home(), fixture.game(), "Source", token, true, true,
                stream(new ByteArrayOutputStream()), new ProfileMutationTransaction.Hook() {
                    @Override
                    public void beforeSourceCapture(Path path) throws IOException {
                        replaceExternally(path, external, originalTime);
                    }
                }));

        assertTrue(failure.getMessage().contains("commit boundary"));
        assertArrayEquals(external, Files.readAllBytes(source));
        assertEquals(originalTime, Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.notExists(fixture.home().profileBackups())
                || backupFiles(fixture).stream().noneMatch(path -> path.getFileName().toString().startsWith("deleted-profile-")));
        assertNoTransactions(fixture);
    }

    @Test
    void externalSourceAndDestinationBothWinRollbackWithoutBeingClobbered() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Source");
        Path source = profile(fixture, "Source");
        Path target = profile(fixture, "Renamed");
        String token = renamePreviewToken(fixture, "Source", "Renamed");
        byte[] externalSource = changedGenerationSameLength(source);
        byte[] externalTarget = renamedBytes(source, "Source", "Renamed");

        IOException failure = assertThrows(IOException.class, () -> ProfileCommand.rename(
                fixture.home(), fixture.game(), "Source", "Renamed", token, true, true,
                stream(new ByteArrayOutputStream()), new ProfileMutationTransaction.Hook() {
                    @Override
                    public void beforeTargetPublication(Path ignored) throws IOException {
                        Files.write(source, externalSource);
                        Files.write(target, externalTarget);
                    }
                }));

        assertTrue(failure.getMessage().contains("already exists"));
        assertArrayEquals(externalSource, Files.readAllBytes(source));
        assertArrayEquals(externalTarget, Files.readAllBytes(target));
        assertTrue(backupFiles(fixture).stream().anyMatch(path ->
                path.getFileName().toString().startsWith("conflicted-profile-")));
        assertNoTransactions(fixture);
    }

    @Test
    void postCommitExternalTargetReplacementIsNeverRolledBack() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Source");
        Path source = profile(fixture, "Source");
        Path target = profile(fixture, "Renamed");
        String token = renamePreviewToken(fixture, "Source", "Renamed");
        byte[] externalTarget = renamedBytes(source, "Source", "Renamed");
        externalTarget = replaceFingerprint(externalTarget, "f".repeat(64));
        byte[] expectedExternalTarget = externalTarget;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertEquals(0, ProfileCommand.rename(
                fixture.home(), fixture.game(), "Source", "Renamed", token, true, true,
                stream(output), new ProfileMutationTransaction.Hook() {
                    @Override
                    public void afterCommit(Path publicArtifact) throws IOException {
                        replaceExternally(publicArtifact, expectedExternalTarget, null);
                        throw new IOException("simulated ancillary failure after commit");
                    }
                }));

        Map<String, Object> result = StrictJson.object(output.toString(StandardCharsets.UTF_8));
        assertEquals(Boolean.TRUE, result.get("applied"));
        assertEquals(Boolean.TRUE, result.get("cleanupPending"));
        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(expectedExternalTarget, Files.readAllBytes(target));
        assertEquals(List.of("Renamed"), names(fixture));
    }

    @Test
    void restartBeforeRenamePublicationRestoresExactSourceAndRequiresFreshReview() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Source");
        Path source = profile(fixture, "Source");
        Path target = profile(fixture, "Renamed");
        String token = renamePreviewToken(fixture, "Source", "Renamed");

        assertThrows(SimulatedInterruption.class, () -> ProfileCommand.rename(
                fixture.home(), fixture.game(), "Source", "Renamed", token, true, true,
                stream(new ByteArrayOutputStream()), new ProfileMutationTransaction.Hook() {
                    @Override
                    public void afterSourceCapture(Path captured) {
                        throw new SimulatedInterruption();
                    }
                }));
        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
        assertTrue(hasTransaction(fixture));

        IOException recovery = assertThrows(IOException.class, () -> save(fixture, "Other"));
        assertTrue(recovery.getMessage().contains("review"));
        assertTrue(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
        assertNoTransactions(fixture);

        save(fixture, "Other");
        assertEquals(List.of("Other", "Source"), names(fixture));
    }

    @Test
    void restartWithExternalDestinationRestoresSourceAndPreservesExternalWinner() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Source");
        Path source = profile(fixture, "Source");
        Path target = profile(fixture, "Renamed");
        String token = renamePreviewToken(fixture, "Source", "Renamed");
        byte[] externalTarget = renamedBytes(source, "Source", "Renamed");
        externalTarget = replaceFingerprint(externalTarget, "d".repeat(64));

        assertThrows(SimulatedInterruption.class, () -> ProfileCommand.rename(
                fixture.home(), fixture.game(), "Source", "Renamed", token, true, true,
                stream(new ByteArrayOutputStream()), new ProfileMutationTransaction.Hook() {
                    @Override
                    public void afterSourceCapture(Path captured) {
                        throw new SimulatedInterruption();
                    }
                }));
        Files.write(target, externalTarget);

        IOException recovery = assertThrows(IOException.class, () -> save(fixture, "Other"));

        assertTrue(recovery.getMessage().contains("external destination"));
        assertTrue(recovery.getMessage().contains("source was restored"));
        assertTrue(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(externalTarget, Files.readAllBytes(target));
        assertEquals(List.of("Renamed", "Source"), names(fixture));
        assertNoTransactions(fixture);
    }

    @Test
    void restartAfterRenameCommitFinishesCleanupWithoutRestoringOldName() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Source");
        Path source = profile(fixture, "Source");
        Path target = profile(fixture, "Renamed");
        String token = renamePreviewToken(fixture, "Source", "Renamed");

        assertThrows(SimulatedInterruption.class, () -> ProfileCommand.rename(
                fixture.home(), fixture.game(), "Source", "Renamed", token, true, true,
                stream(new ByteArrayOutputStream()), new ProfileMutationTransaction.Hook() {
                    @Override
                    public void afterCommit(Path publicArtifact) {
                        throw new SimulatedInterruption();
                    }
                }));
        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS));
        assertTrue(hasTransaction(fixture));

        save(fixture, "Other");

        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS));
        assertEquals(List.of("Other", "Renamed"), names(fixture));
        assertNoTransactions(fixture);
    }

    @Test
    void committedRenameSurvivesPartialCleanupAndResidueNeverEnumerates() throws Exception {
        Fixture fixture = fixture();
        save(fixture, "Source");
        String token = renamePreviewToken(fixture, "Source", "Renamed");
        AtomicBoolean failedOnce = new AtomicBoolean();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertEquals(0, ProfileCommand.rename(
                fixture.home(), fixture.game(), "Source", "Renamed", token, true, true,
                stream(output), new ProfileMutationTransaction.Hook() {
                    @Override
                    public void delete(Path path) throws IOException {
                        if (path.getFileName().toString().equals("captured")
                                && failedOnce.compareAndSet(false, true)) {
                            throw new IOException("simulated cleanup denial");
                        }
                        ProfileMutationTransaction.Hook.super.delete(path);
                    }
                }));

        Map<String, Object> result = StrictJson.object(output.toString(StandardCharsets.UTF_8));
        assertEquals(Boolean.TRUE, result.get("applied"));
        assertEquals(Boolean.TRUE, result.get("cleanupPending"));
        assertEquals(List.of("Renamed"), names(fixture));
        assertTrue(hasTransaction(fixture));

        save(fixture, "Other");
        assertEquals(List.of("Other", "Renamed"), names(fixture));
        assertNoTransactions(fixture);
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

    private static String renamePreviewToken(Fixture fixture, String source, String target) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.rename(
                fixture.home(), fixture.game(), source, target, null, false, true, stream(output)));
        return (String) StrictJson.object(output.toString(StandardCharsets.UTF_8)).get("profileFingerprint");
    }

    private static String deletePreviewToken(Fixture fixture, String source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.delete(
                fixture.home(), fixture.game(), source, null, false, true, stream(output)));
        return (String) StrictJson.object(output.toString(StandardCharsets.UTF_8)).get("profileFingerprint");
    }

    private static Path profile(Fixture fixture, String name) {
        return fixture.home().profiles().resolve(ProfileRecordFiles.canonicalFileName(name));
    }

    private static byte[] renamedBytes(Path source, String sourceName, String targetName) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        String replaced = text.replace("\"name\":\"" + sourceName + "\"", "\"name\":\"" + targetName + "\"");
        if (replaced.equals(text)) throw new AssertionError("profile name was not replaced");
        return replaced.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] changedGenerationSameLength(Path source) throws IOException {
        byte[] original = Files.readAllBytes(source);
        byte[] changed = replaceFingerprint(original, "e".repeat(64));
        assertEquals(original.length, changed.length);
        return changed;
    }

    private static byte[] replaceFingerprint(byte[] source, String replacement) {
        String text = new String(source, StandardCharsets.UTF_8);
        String changed = text.replaceFirst("\\\"profileFingerprint\\\":\\\"[0-9a-f]{64}\\\"",
                "\\\"profileFingerprint\\\":\\\"" + replacement + "\\\"");
        if (changed.equals(text)) throw new AssertionError("profile fingerprint was not replaced");
        return changed.getBytes(StandardCharsets.UTF_8);
    }

    private static void replaceExternally(Path target, byte[] bytes, FileTime mtime) throws IOException {
        Path staged = Files.createTempFile(target.getParent(), ".external-profile-", ".json");
        try {
            Files.write(staged, bytes);
            if (mtime != null) Files.setLastModifiedTime(staged, mtime);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (mtime != null) Files.setLastModifiedTime(target, mtime);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static List<String> names(Fixture fixture) throws Exception {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>)
                ProfileCommand.describeList(fixture.home(), fixture.game()).get("profiles");
        return profiles.stream().map(profile -> (String) profile.get("name")).sorted().toList();
    }

    private static List<Path> backupFiles(Fixture fixture) throws IOException {
        if (!Files.isDirectory(fixture.home().profileBackups(), LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var files = Files.list(fixture.home().profileBackups())) {
            return files.sorted().toList();
        }
    }

    private static boolean hasTransaction(Fixture fixture) throws IOException {
        if (!Files.isDirectory(fixture.home().profiles(), LinkOption.NOFOLLOW_LINKS)) return false;
        try (var files = Files.list(fixture.home().profiles())) {
            return files.anyMatch(path -> path.getFileName().toString().startsWith(".preflight-profile-txn-"));
        }
    }

    private static void assertNoTransactions(Fixture fixture) throws IOException {
        assertFalse(hasTransaction(fixture));
    }

    private static void assertOwnerPrivateWhenPosix(Path path) throws IOException {
        if (!Files.getFileStore(path).supportsFileAttributeView("posix")) return;
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        assertEquals(
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                permissions);
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    private record Fixture(Path game, PreflightHome home) {
    }

    private static final class SimulatedInterruption extends Error {
        private static final long serialVersionUID = 1L;
    }
}

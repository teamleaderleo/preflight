package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SaveProfileObservationContainmentTest {
    @TempDir
    Path temp;

    @Test
    void runtimeDirectorySymlinkCannotRedirectObservationWrites() throws Exception {
        PreflightHome home = home();
        Files.createDirectories(home.root());
        Path outside = temp.resolve("outside-runtime");
        Files.createDirectories(outside);
        createSymlinkOrSkip(home.root().resolve("runtime"), outside);
        Path install = installWithSave("manual");

        assertThrows(IOException.class, () -> SaveProfileObservation.testSession(
                home, install, identity(), Instant.now(), false));

        assertFalse(Files.exists(outside.resolve(SaveProfileObservation.FILE_NAME)));
        assertFalse(Files.exists(outside.resolve(SaveProfileObservation.FILE_NAME + ".lock")));
    }

    @Test
    void preflightHomeSymlinkCannotRedirectObservationWrites() throws Exception {
        Path outside = temp.resolve("outside-home");
        Files.createDirectories(outside);
        Path linkedHome = temp.resolve("linked-preflight-home");
        createSymlinkOrSkip(linkedHome, outside);
        PreflightHome home = new PreflightHome(linkedHome, List.of());
        Path install = installWithSave("manual");

        assertThrows(IOException.class, () -> SaveProfileObservation.testSession(
                home, install, identity(), Instant.now(), false));

        assertFalse(Files.exists(outside.resolve("runtime")));
    }

    @Test
    void ledgerSymlinkIsRejectedWithoutChangingItsTarget() throws Exception {
        PreflightHome home = home();
        Path runtime = home.root().resolve("runtime");
        Files.createDirectories(runtime);
        Path outside = temp.resolve("outside-ledger.json");
        Files.writeString(outside, "sentinel");
        createSymlinkOrSkip(runtime.resolve(SaveProfileObservation.FILE_NAME), outside);

        assertThrows(IOException.class, () -> SaveProfileObservation.observations(home));

        assertEquals("sentinel", Files.readString(outside));
    }

    @Test
    void lockSymlinkIsRejectedWithoutChangingItsTarget() throws Exception {
        PreflightHome home = home();
        Path runtime = home.root().resolve("runtime");
        Files.createDirectories(runtime);
        Path outside = temp.resolve("outside-lock");
        Files.writeString(outside, "sentinel");
        createSymlinkOrSkip(runtime.resolve(SaveProfileObservation.FILE_NAME + ".lock"), outside);

        assertThrows(IOException.class, () -> SaveProfileObservation.observations(home));

        assertEquals("sentinel", Files.readString(outside));
    }

    @Test
    void concurrentFirstReadsCreateOneSafeRuntimeDirectoryAndLock() throws Exception {
        PreflightHome home = home();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> SaveProfileObservation.observations(home));
            var second = workers.submit(() -> SaveProfileObservation.observations(home));

            assertTrue(first.get().isEmpty());
            assertTrue(second.get().isEmpty());
        } finally {
            workers.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }

        Path runtime = home.root().resolve("runtime");
        assertTrue(Files.isDirectory(runtime));
        assertFalse(Files.isSymbolicLink(runtime));
        assertTrue(Files.isRegularFile(runtime.resolve(SaveProfileObservation.FILE_NAME + ".lock")));
        assertFalse(Files.isSymbolicLink(runtime.resolve(SaveProfileObservation.FILE_NAME + ".lock")));
    }

    @Test
    void concurrentFirstObservationWritesPreserveBothPublishedRecords() throws Exception {
        PreflightHome home = home();
        Path firstInstall = installWithSave("first-install", "first-save");
        Path secondInstall = installWithSave("second-install", "second-save");
        Instant started = Instant.now();
        SaveProfileObservation.Session first = SaveProfileObservation.testSession(
                home, firstInstall, identity("first-profile"), started, false);
        SaveProfileObservation.Session second = SaveProfileObservation.testSession(
                home, secondInstall, identity("second-profile"), started, false);
        Files.writeString(firstInstall.resolve("saves/first-save/save.dat"), "first changed");
        Files.writeString(secondInstall.resolve("saves/second-save/save.dat"), "second changed");
        Instant ended = started.plusSeconds(1);

        var workers = Executors.newFixedThreadPool(2);
        try {
            var firstWrite = workers.submit(() -> {
                first.finish(ended);
                return null;
            });
            var secondWrite = workers.submit(() -> {
                second.finish(ended);
                return null;
            });
            firstWrite.get();
            secondWrite.get();
        } finally {
            workers.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }

        List<SaveProfileObservation.Observation> observations = SaveProfileObservation.observations(home);
        assertEquals(2, observations.size());
        assertTrue(observations.stream().anyMatch(value -> value.profileFingerprint().equals("first-profile")));
        assertTrue(observations.stream().anyMatch(value -> value.profileFingerprint().equals("second-profile")));
    }

    private PreflightHome home() {
        return new PreflightHome(temp.resolve("preflight-home"), List.of());
    }

    private Path installWithSave(String name) throws IOException {
        return installWithSave("Starsector", name);
    }

    private Path installWithSave(String installName, String saveName) throws IOException {
        Path install = temp.resolve(installName);
        Path save = install.resolve("saves").resolve(saveName);
        Files.createDirectories(save);
        Files.writeString(save.resolve("save.dat"), "contents");
        return install;
    }

    private static SaveProfileObservation.SessionIdentity identity() {
        return identity("profile-fingerprint");
    }

    private static SaveProfileObservation.SessionIdentity identity(String fingerprint) {
        return new SaveProfileObservation.SessionIdentity(
                fingerprint, "Profile", "game-build", List.of());
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links unavailable: " + unavailable.getMessage());
        }
    }
}

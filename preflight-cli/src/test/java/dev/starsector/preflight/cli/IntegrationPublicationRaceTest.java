package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegrationPublicationRaceTest {
    @Test
    void linuxCreateIfAbsentPreservesWriterThatWinsAfterReview(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.LINUX, tempDir.resolve("home"));
        Path command = home.pathOf(PreflightHome.Id.LINUX_COMMAND);
        AtomicBoolean injected = new AtomicBoolean();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.AFTER_REVIEW
                    && integration.id() == PreflightHome.Id.LINUX_COMMAND
                    && injected.compareAndSet(false, true)) {
                Files.createDirectories(command.getParent());
                Files.writeString(command, "external-command\n");
            }
        })) {
            assertThrows(IOException.class, () -> InstallCommand.installLinux(home, jar(tempDir), game(tempDir)));
        }

        assertEquals("external-command\n", Files.readString(command));
        assertFalse(Files.exists(home.pathOf(PreflightHome.Id.LINUX_DESKTOP_ENTRY), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void macCreateIfAbsentPreservesBundleThatWinsAfterReview(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.MAC, tempDir.resolve("home"));
        Path app = home.pathOf(PreflightHome.Id.MAC_APP);
        AtomicBoolean injected = new AtomicBoolean();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.AFTER_REVIEW
                    && integration.id() == PreflightHome.Id.MAC_APP
                    && injected.compareAndSet(false, true)) {
                Files.createDirectories(app);
                Files.writeString(app.resolve("external.txt"), "keep");
            }
        })) {
            assertThrows(IOException.class, () -> InstallCommand.installMac(home, jar(tempDir), game(tempDir)));
        }

        assertEquals("keep", Files.readString(app.resolve("external.txt")));
    }

    @Test
    void windowsCreateIfAbsentPreservesDirectoryThatWinsAfterReview(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.WINDOWS, tempDir.resolve("home"));
        Path directory = home.pathOf(PreflightHome.Id.WINDOWS_DIRECTORY);
        AtomicBoolean injected = new AtomicBoolean();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.AFTER_REVIEW
                    && integration.id() == PreflightHome.Id.WINDOWS_DIRECTORY
                    && injected.compareAndSet(false, true)) {
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("external.txt"), "keep");
            }
        })) {
            assertThrows(IOException.class, () -> InstallCommand.installWindows(home, jar(tempDir), game(tempDir)));
        }

        assertEquals("keep", Files.readString(directory.resolve("external.txt")));
    }

    @Test
    void linuxReplacementPreservesSameSizeSameMtimeExternalGeneration(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.LINUX, tempDir.resolve("home"));
        Path jar = jar(tempDir);
        Path game = game(tempDir);
        assertEquals(0, InstallCommand.installLinux(home, jar, game));
        Path command = home.pathOf(PreflightHome.Id.LINUX_COMMAND);
        byte[] original = Files.readAllBytes(command);
        FileTime originalTime = Files.getLastModifiedTime(command, LinkOption.NOFOLLOW_LINKS);
        byte[] external = original.clone();
        external[external.length / 2] ^= 1;
        AtomicBoolean injected = new AtomicBoolean();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.AFTER_REVIEW
                    && integration.id() == PreflightHome.Id.LINUX_COMMAND
                    && injected.compareAndSet(false, true)) {
                Files.delete(command);
                Files.write(command, external);
                Files.setLastModifiedTime(command, originalTime);
            }
        })) {
            assertThrows(IOException.class, () -> InstallCommand.installLinux(home, jar, game));
        }

        assertTrue(java.util.Arrays.equals(external, Files.readAllBytes(command)));
        assertEquals(original.length, Files.size(command));
        assertEquals(originalTime, Files.getLastModifiedTime(command, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void macReplacementPreservesExternalBundleSwappedAfterReview(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.MAC, tempDir.resolve("home"));
        Path jar = jar(tempDir);
        Path game = game(tempDir);
        assertEquals(0, InstallCommand.installMac(home, jar, game));
        Path app = home.pathOf(PreflightHome.Id.MAC_APP);
        AtomicBoolean injected = new AtomicBoolean();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.AFTER_REVIEW
                    && integration.id() == PreflightHome.Id.MAC_APP
                    && injected.compareAndSet(false, true)) {
                UninstallCommand.deleteRecursively(app);
                Files.createDirectories(app.resolve("Contents"));
                Files.writeString(app.resolve("Contents/external.txt"), "external bundle");
            }
        })) {
            assertThrows(IOException.class, () -> InstallCommand.installMac(home, jar, game));
        }

        assertEquals("external bundle", Files.readString(app.resolve("Contents/external.txt")));
    }

    @Test
    void windowsReplacementPreservesExternalDirectorySwappedAfterReview(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.WINDOWS, tempDir.resolve("home"));
        Path jar = jar(tempDir);
        Path game = game(tempDir);
        assertEquals(0, InstallCommand.installWindows(home, jar, game));
        Path directory = home.pathOf(PreflightHome.Id.WINDOWS_DIRECTORY);
        AtomicBoolean injected = new AtomicBoolean();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.AFTER_REVIEW
                    && integration.id() == PreflightHome.Id.WINDOWS_DIRECTORY
                    && injected.compareAndSet(false, true)) {
                UninstallCommand.deleteRecursively(directory);
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("external.txt"), "external directory");
            }
        })) {
            assertThrows(IOException.class, () -> InstallCommand.installWindows(home, jar, game));
        }

        assertEquals("external directory", Files.readString(directory.resolve("external.txt")));
    }

    @Test
    void symlinkCreatedAfterReviewWinsLinuxPublicationRace(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.LINUX, tempDir.resolve("home"));
        Path command = home.pathOf(PreflightHome.Id.LINUX_COMMAND);
        Path outside = tempDir.resolve("outside");
        Files.writeString(outside, "outside\n");
        AtomicBoolean injected = new AtomicBoolean();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.AFTER_REVIEW
                    && integration.id() == PreflightHome.Id.LINUX_COMMAND
                    && injected.compareAndSet(false, true)) {
                Files.createDirectories(command.getParent());
                Files.createSymbolicLink(command, outside);
            }
        })) {
            assertThrows(IOException.class, () -> InstallCommand.installLinux(home, jar(tempDir), game(tempDir)));
        }

        assertTrue(Files.isSymbolicLink(command));
        assertEquals("outside\n", Files.readString(outside));
    }

    @Test
    void linuxSecondFileCollisionRollsBackFirstPublication(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.LINUX, tempDir.resolve("home"));
        Path jar = jar(tempDir);
        Path oldGame = tempDir.resolve("old-game");
        Path newGame = tempDir.resolve("new-game");
        assertEquals(0, InstallCommand.installLinux(home, jar, oldGame));
        Path command = home.pathOf(PreflightHome.Id.LINUX_COMMAND);
        Path desktop = home.pathOf(PreflightHome.Id.LINUX_DESKTOP_ENTRY);
        String oldCommand = Files.readString(command);
        Files.delete(desktop);
        AtomicBoolean injected = new AtomicBoolean();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.BEFORE_PUBLISH
                    && integration.id() == PreflightHome.Id.LINUX_DESKTOP_ENTRY
                    && injected.compareAndSet(false, true)) {
                Files.writeString(desktop, "external desktop\n");
            }
        })) {
            assertThrows(IOException.class, () -> InstallCommand.installLinux(home, jar, newGame));
        }

        assertEquals(oldCommand, Files.readString(command));
        assertEquals("external desktop\n", Files.readString(desktop));
    }

    @Test
    void rollbackPreservesExternalReplacementOfFirstLinuxFile(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.LINUX, tempDir.resolve("home"));
        Path jar = jar(tempDir);
        assertEquals(0, InstallCommand.installLinux(home, jar, tempDir.resolve("old-game")));
        Path command = home.pathOf(PreflightHome.Id.LINUX_COMMAND);
        Path desktop = home.pathOf(PreflightHome.Id.LINUX_DESKTOP_ENTRY);
        Files.delete(desktop);
        AtomicBoolean blockDesktop = new AtomicBoolean();
        AtomicBoolean replaceCommand = new AtomicBoolean();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.BEFORE_PUBLISH
                    && integration.id() == PreflightHome.Id.LINUX_DESKTOP_ENTRY
                    && blockDesktop.compareAndSet(false, true)) {
                Files.writeString(desktop, "external desktop\n");
            }
            if (event == IntegrationMutation.Event.BEFORE_ROLLBACK
                    && integration.id() == PreflightHome.Id.LINUX_COMMAND
                    && replaceCommand.compareAndSet(false, true)) {
                Files.delete(command);
                Files.writeString(command, "external command generation\n");
            }
        })) {
            IOException failure = assertThrows(
                    IOException.class,
                    () -> InstallCommand.installLinux(home, jar, tempDir.resolve("new-game")));
            assertTrue(failure.getSuppressed().length >= 1, "rollback refusal should be reported");
        }

        assertEquals("external command generation\n", Files.readString(command));
        assertEquals("external desktop\n", Files.readString(desktop));
    }

    @Test
    void macPredecessorCleanupResidueDoesNotUndoCommittedInstall(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.MAC, tempDir.resolve("home"));
        Path jar = jar(tempDir);
        assertEquals(0, InstallCommand.installMac(home, jar, tempDir.resolve("old-game")));
        Path newGame = tempDir.resolve("new-game");
        AtomicBoolean injected = new AtomicBoolean();
        AtomicReference<Path> quarantine = new AtomicReference<>();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.BEFORE_QUARANTINE_CLEANUP
                    && integration.id() == PreflightHome.Id.MAC_APP
                    && injected.compareAndSet(false, true)) {
                quarantine.set(path);
                Files.writeString(path.resolve("external.txt"), "external quarantine content\n");
            }
        })) {
            assertEquals(0, InstallCommand.installMac(home, jar, newGame));
        }

        Path executable = home.pathOf(PreflightHome.Id.MAC_APP).resolve("Contents/MacOS/preflight");
        assertTrue(home.integration(PreflightHome.Id.MAC_APP).isOwned());
        assertTrue(Files.readString(executable).contains(newGame.toString()));
        assertEquals("external quarantine content\n", Files.readString(quarantine.get().resolve("external.txt")));
    }

    @Test
    void windowsPredecessorCleanupResidueDoesNotUndoCommittedInstall(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.WINDOWS, tempDir.resolve("home"));
        Path jar = jar(tempDir);
        assertEquals(0, InstallCommand.installWindows(home, jar, tempDir.resolve("old-game")));
        Path newGame = tempDir.resolve("new-game");
        AtomicBoolean injected = new AtomicBoolean();
        AtomicReference<Path> quarantine = new AtomicReference<>();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.BEFORE_QUARANTINE_CLEANUP
                    && integration.id() == PreflightHome.Id.WINDOWS_DIRECTORY
                    && injected.compareAndSet(false, true)) {
                quarantine.set(path);
                Files.writeString(path.resolve("external.txt"), "external quarantine content\n");
            }
        })) {
            assertEquals(0, InstallCommand.installWindows(home, jar, newGame));
        }

        Path command = home.pathOf(PreflightHome.Id.WINDOWS_COMMAND);
        assertTrue(home.integration(PreflightHome.Id.WINDOWS_DIRECTORY).isOwned());
        assertTrue(Files.readString(command).contains(newGame.toString()));
        assertEquals("external quarantine content\n", Files.readString(quarantine.get().resolve("external.txt")));
    }

    @Test
    void linuxCommandPredecessorCleanupResidueKeepsCommittedPair(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.LINUX, tempDir.resolve("home"));
        Path jar = jar(tempDir);
        assertEquals(0, InstallCommand.installLinux(home, jar, tempDir.resolve("old-game")));
        Path newGame = tempDir.resolve("new-game");
        AtomicBoolean injected = new AtomicBoolean();
        AtomicReference<Path> quarantine = new AtomicReference<>();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.BEFORE_QUARANTINE_CLEANUP
                    && integration.id() == PreflightHome.Id.LINUX_COMMAND
                    && injected.compareAndSet(false, true)) {
                quarantine.set(path);
                Files.writeString(path, "changed command predecessor\n");
            }
        })) {
            assertEquals(0, InstallCommand.installLinux(home, jar, newGame));
        }

        Path command = home.pathOf(PreflightHome.Id.LINUX_COMMAND);
        assertTrue(home.integration(PreflightHome.Id.LINUX_COMMAND).isOwned());
        assertTrue(home.integration(PreflightHome.Id.LINUX_DESKTOP_ENTRY).isOwned());
        assertTrue(Files.readString(command).contains(newGame.toString()));
        assertEquals("changed command predecessor\n", Files.readString(quarantine.get()));
    }

    @Test
    void linuxDesktopPredecessorCleanupResidueKeepsCommittedPair(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.LINUX, tempDir.resolve("home"));
        Path jar = jar(tempDir);
        assertEquals(0, InstallCommand.installLinux(home, jar, tempDir.resolve("old-game")));
        Path newGame = tempDir.resolve("new-game");
        AtomicBoolean injected = new AtomicBoolean();
        AtomicReference<Path> quarantine = new AtomicReference<>();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.BEFORE_QUARANTINE_CLEANUP
                    && integration.id() == PreflightHome.Id.LINUX_DESKTOP_ENTRY
                    && injected.compareAndSet(false, true)) {
                quarantine.set(path);
                Files.writeString(path, "changed desktop predecessor\n");
            }
        })) {
            assertEquals(0, InstallCommand.installLinux(home, jar, newGame));
        }

        Path command = home.pathOf(PreflightHome.Id.LINUX_COMMAND);
        assertTrue(home.integration(PreflightHome.Id.LINUX_COMMAND).isOwned());
        assertTrue(home.integration(PreflightHome.Id.LINUX_DESKTOP_ENTRY).isOwned());
        assertTrue(Files.readString(command).contains(newGame.toString()));
        assertEquals("changed desktop predecessor\n", Files.readString(quarantine.get()));
    }

    @Test
    void stagingCleanupPreservesGenerationReplacedAfterPublicationCollision(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.LINUX, tempDir.resolve("home"));
        Path command = home.pathOf(PreflightHome.Id.LINUX_COMMAND);
        AtomicBoolean blockPublication = new AtomicBoolean();
        AtomicBoolean replaceStaging = new AtomicBoolean();
        AtomicReference<Path> staged = new AtomicReference<>();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (integration.id() != PreflightHome.Id.LINUX_COMMAND) return;
            if (event == IntegrationMutation.Event.BEFORE_PUBLISH
                    && blockPublication.compareAndSet(false, true)) {
                Files.writeString(command, "external public generation\n");
            }
            if (event == IntegrationMutation.Event.BEFORE_STAGING_CLEANUP
                    && replaceStaging.compareAndSet(false, true)) {
                staged.set(path);
                Files.delete(path);
                Files.writeString(path, "external staging generation\n");
            }
        })) {
            assertThrows(IOException.class, () -> InstallCommand.installLinux(home, jar(tempDir), game(tempDir)));
        }

        assertEquals("external public generation\n", Files.readString(command));
        assertEquals("external staging generation\n", Files.readString(staged.get()));
    }

    @Test
    void receiptOmitsIntegrationThatLostOwnershipAfterPublication(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.LINUX, tempDir.resolve("home"));
        assertEquals(0, InstallCommand.installLinux(home, jar(tempDir), game(tempDir)));
        Path desktop = home.pathOf(PreflightHome.Id.LINUX_DESKTOP_ENTRY);
        Files.writeString(desktop, "external desktop\n");

        home.recordInstalledIntegrations();

        String receipt = Files.readString(home.root().resolve("integrations.json"));
        assertTrue(receipt.contains("LINUX_COMMAND"), receipt);
        assertFalse(receipt.contains("LINUX_DESKTOP_ENTRY"), receipt);
    }

    @Test
    void macRootLevelIntruderRevokesBundleOwnership(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.MAC, tempDir.resolve("home"));
        Path jar = jar(tempDir);
        Path game = game(tempDir);
        assertEquals(0, InstallCommand.installMac(home, jar, game));
        Path app = home.pathOf(PreflightHome.Id.MAC_APP);
        Files.writeString(app.resolve("external.txt"), "preserve me");

        assertFalse(home.integration(PreflightHome.Id.MAC_APP).isOwned());
        assertThrows(IOException.class, () -> InstallCommand.installMac(home, jar, game));
        assertEquals("preserve me", Files.readString(app.resolve("external.txt")));
    }

    private static PreflightHome home(Platform platform, Path home) {
        return PreflightHome.resolve(platform, home, Map.of());
    }

    private static Path jar(Path tempDir) throws IOException {
        Path jar = tempDir.resolve("bin/preflight.jar");
        Files.createDirectories(jar.getParent());
        if (!Files.exists(jar)) Files.writeString(jar, "jar-content");
        return jar;
    }

    private static Path game(Path tempDir) {
        return tempDir.resolve("Starsector");
    }
}

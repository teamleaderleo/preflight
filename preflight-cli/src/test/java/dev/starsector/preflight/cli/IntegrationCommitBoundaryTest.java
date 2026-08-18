package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegrationCommitBoundaryTest {
    @Test
    void linuxCommandLossAfterDesktopCommitWithdrawsDesktopAndRetainsReviewedPair(@TempDir Path tempDir)
            throws Exception {
        PreflightHome home = PreflightHome.resolve(Platform.LINUX, tempDir.resolve("home"), Map.of());
        Path jar = jar(tempDir);
        assertEquals(0, InstallCommand.installLinux(home, jar, tempDir.resolve("old-game")));
        Path command = home.pathOf(PreflightHome.Id.LINUX_COMMAND);
        Path desktop = home.pathOf(PreflightHome.Id.LINUX_DESKTOP_ENTRY);
        String oldCommand = Files.readString(command);
        String oldDesktop = Files.readString(desktop);
        AtomicBoolean replaced = new AtomicBoolean();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.AFTER_COMMIT
                    && integration.id() == PreflightHome.Id.LINUX_DESKTOP_ENTRY
                    && replaced.compareAndSet(false, true)) {
                Files.delete(command);
                Files.writeString(command, "external command generation\n");
            }
        })) {
            assertThrows(
                    IOException.class,
                    () -> InstallCommand.installLinux(home, jar, tempDir.resolve("new-game")));
        }

        assertEquals("external command generation\n", Files.readString(command));
        assertFalse(home.integration(PreflightHome.Id.LINUX_COMMAND).isOwned());
        assertFalse(Files.exists(desktop));
        assertFalse(home.integration(PreflightHome.Id.LINUX_DESKTOP_ENTRY).isOwned());
        assertTrue(quarantineContains(command, oldCommand), "reviewed command predecessor should be retained");
        assertTrue(quarantineContains(desktop, oldDesktop), "reviewed desktop predecessor should be retained");

        home.recordInstalledIntegrations();
        String receipt = Files.readString(home.root().resolve("integrations.json"));
        assertFalse(receipt.contains("LINUX_COMMAND"), receipt);
        assertFalse(receipt.contains("LINUX_DESKTOP_ENTRY"), receipt);
    }

    @Test
    void linuxDesktopLossAfterCommandPublicationPreservesExternalDesktopAndRestoresCommand(@TempDir Path tempDir)
            throws Exception {
        PreflightHome home = PreflightHome.resolve(Platform.LINUX, tempDir.resolve("home"), Map.of());
        Path jar = jar(tempDir);
        Path oldGame = tempDir.resolve("old-game");
        assertEquals(0, InstallCommand.installLinux(home, jar, oldGame));
        Path command = home.pathOf(PreflightHome.Id.LINUX_COMMAND);
        Path desktop = home.pathOf(PreflightHome.Id.LINUX_DESKTOP_ENTRY);
        AtomicBoolean replaced = new AtomicBoolean();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.BEFORE_PUBLISH
                    && integration.id() == PreflightHome.Id.LINUX_DESKTOP_ENTRY
                    && replaced.compareAndSet(false, true)) {
                Files.writeString(desktop, "external desktop generation\n");
            }
        })) {
            assertThrows(
                    IOException.class,
                    () -> InstallCommand.installLinux(home, jar, tempDir.resolve("new-game")));
        }

        assertTrue(home.integration(PreflightHome.Id.LINUX_COMMAND).isOwned());
        assertTrue(Files.readString(command).contains(oldGame.toString()));
        assertEquals("external desktop generation\n", Files.readString(desktop));
        assertFalse(home.integration(PreflightHome.Id.LINUX_DESKTOP_ENTRY).isOwned());

        home.recordInstalledIntegrations();
        String receipt = Files.readString(home.root().resolve("integrations.json"));
        assertTrue(receipt.contains("LINUX_COMMAND"), receipt);
        assertFalse(receipt.contains("LINUX_DESKTOP_ENTRY"), receipt);
    }

    @Test
    void rollbackDoesNotRestoreChangedQuarantinedPredecessor(@TempDir Path tempDir) throws Exception {
        PreflightHome home = PreflightHome.resolve(Platform.LINUX, tempDir.resolve("home"), Map.of());
        Path jar = jar(tempDir);
        assertEquals(0, InstallCommand.installLinux(home, jar, tempDir.resolve("old-game")));
        Path command = home.pathOf(PreflightHome.Id.LINUX_COMMAND);
        Path desktop = home.pathOf(PreflightHome.Id.LINUX_DESKTOP_ENTRY);
        AtomicBoolean blockDesktop = new AtomicBoolean();
        AtomicBoolean changedPrevious = new AtomicBoolean();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.BEFORE_PUBLISH
                    && integration.id() == PreflightHome.Id.LINUX_DESKTOP_ENTRY
                    && blockDesktop.compareAndSet(false, true)) {
                Files.writeString(desktop, "external desktop generation\n");
            }
            if (event == IntegrationMutation.Event.BEFORE_ROLLBACK
                    && integration.id() == PreflightHome.Id.LINUX_COMMAND
                    && changedPrevious.compareAndSet(false, true)) {
                Files.delete(command);
                try (var siblings = Files.list(command.getParent())) {
                    Path previous = siblings
                            .filter(candidate -> candidate.getFileName().toString()
                                    .startsWith("preflight.preflight-quarantine-"))
                            .findFirst()
                            .orElseThrow();
                    Files.writeString(previous, "changed predecessor generation\n");
                }
            }
        })) {
            assertThrows(
                    IOException.class,
                    () -> InstallCommand.installLinux(home, jar, tempDir.resolve("new-game")));
        }

        assertFalse(Files.exists(command));
        assertEquals("external desktop generation\n", Files.readString(desktop));
        try (var siblings = Files.list(command.getParent())) {
            assertTrue(siblings.anyMatch(candidate -> {
                try {
                    return candidate.getFileName().toString().startsWith("preflight.preflight-quarantine-")
                            && Files.readString(candidate).equals("changed predecessor generation\n");
                } catch (IOException ignored) {
                    return false;
                }
            }));
        }
    }

    private static boolean quarantineContains(Path target, String expected) throws IOException {
        String prefix = target.getFileName() + ".preflight-quarantine-";
        try (var siblings = Files.list(target.getParent())) {
            return siblings.anyMatch(candidate -> {
                if (!candidate.getFileName().toString().startsWith(prefix)) return false;
                try {
                    return Files.readString(candidate).equals(expected);
                } catch (IOException ignored) {
                    return false;
                }
            });
        }
    }

    private static Path jar(Path tempDir) throws IOException {
        Path jar = tempDir.resolve("bin/preflight.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar-content");
        return jar;
    }
}

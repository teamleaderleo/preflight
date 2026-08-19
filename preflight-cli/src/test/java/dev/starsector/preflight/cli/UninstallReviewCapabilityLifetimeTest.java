package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UninstallReviewCapabilityLifetimeTest {
    @Test
    void previewDetachesReviewedParentCapabilitiesAfterCollapsingWindowsDirectoryUnit(@TempDir Path temp)
            throws Exception {
        PreflightHome home = PreflightHome.resolve(Platform.WINDOWS, temp.resolve("home"), Map.of());
        Path directory = home.pathOf(PreflightHome.Id.WINDOWS_DIRECTORY);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("Preflight.cmd"),
                "@echo off\r\n"
                        + IntegrationOwnership.WINDOWS_MARKER
                        + "\r\n\"java\" -jar \"preflight.jar\" run --fast --game \"game\" %*\r\n");

        UninstallCommand.Plan preview =
                UninstallCommand.plan(home, UninstallCommand.Scope.LAUNCHER, false);

        assertEquals(1, preview.targets().size(), "the launcher directory remains the Windows removal unit");
        assertEquals(directory.toAbsolutePath().normalize(), preview.targets().get(0).path());
        assertNull(
                preview.targets().get(0).integrationReview(),
                "preview data must not retain the native reviewed-parent authority");
    }
}

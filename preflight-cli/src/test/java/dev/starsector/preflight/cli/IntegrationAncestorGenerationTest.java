package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class IntegrationAncestorGenerationTest {
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void preExistingAncestorAliasIsRefused(@TempDir Path tempDir) throws Exception {
        Path home = tempDir.resolve("home");
        Path external = tempDir.resolve("external");
        Files.createDirectories(home);
        Files.createDirectories(external.resolve("bin"));
        Files.createSymbolicLink(home.resolve(".local"), external);

        assertThrows(
                IOException.class,
                () -> InstallCommand.requireRealDirectory(
                        home.resolve(".local").resolve("bin"), "Linux command directory"));

        assertTrue(Files.exists(external.resolve("bin")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void parentReplacementAfterReviewCannotRedirectStaging(@TempDir Path tempDir) throws Exception {
        Path parent = tempDir.resolve("owned-parent");
        Path reviewedParent = tempDir.resolve("reviewed-parent");
        Path external = tempDir.resolve("external");
        Files.createDirectories(parent);
        Files.createDirectories(external);
        Path target = parent.resolve("preflight");
        PreflightHome.Integration integration = new PreflightHome.Integration(
                PreflightHome.Id.LINUX_COMMAND,
                "command",
                target,
                false,
                false);

        IntegrationMutation.reviewForPublication(integration);
        Files.move(parent, reviewedParent);
        Files.createSymbolicLink(parent, external);

        assertThrows(IOException.class, () -> IntegrationMutation.createStagingFile(target));
        try (var entries = Files.list(external)) {
            assertTrue(entries.findAny().isEmpty(), "staging must never be redirected into the replacement parent");
        }
    }
}

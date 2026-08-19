package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class IntegrationAncestorGenerationTest {
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC, OS.WINDOWS})
    void preExistingAncestorAliasIsRefused(@TempDir Path tempDir) throws Exception {
        Path home = tempDir.resolve("home");
        Path external = tempDir.resolve("external");
        Files.createDirectories(home);
        Files.createDirectories(external.resolve("bin"));
        Path alias = home.resolve(".local");
        createDirectoryAlias(alias, external);

        try {
            assertThrows(
                    IOException.class,
                    () -> InstallCommand.requireRealDirectory(
                            alias.resolve("bin"), "Linux command directory"));

            assertTrue(Files.exists(external.resolve("bin")));
        } finally {
            Files.deleteIfExists(alias);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC, OS.WINDOWS})
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
        createDirectoryAlias(parent, external);

        try {
            assertThrows(IOException.class, () -> IntegrationMutation.createStagingFile(target));
            try (var entries = Files.list(external)) {
                assertTrue(entries.findAny().isEmpty(), "staging must never be redirected into the replacement parent");
            }
        } finally {
            Files.deleteIfExists(parent);
        }
    }

    private static void createDirectoryAlias(Path alias, Path target) throws Exception {
        if (!isWindows()) {
            Files.createSymbolicLink(alias, target);
            return;
        }
        Process process = new ProcessBuilder(
                "cmd.exe", "/c", "mklink", "/J", alias.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("Could not create Windows junction for ancestor-race test: "
                    + new String(output, StandardCharsets.UTF_8));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}

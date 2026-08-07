package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StarsectorDiscoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversMacApplicationBundle() throws Exception {
        Path app = temporaryDirectory.resolve("Starsector.app");
        Path executable = app.resolve("Contents/MacOS/JavaApplicationStub");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "stub");
        executable.toFile().setExecutable(true);

        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.MAC,
                temporaryDirectory,
                temporaryDirectory.resolve("elsewhere"),
                Map.of(),
                app,
                null);

        assertNotNull(result.selected());
        assertEquals(executable.toAbsolutePath().normalize(), result.selected().launcher());
    }

    @Test
    void explicitGameDoesNotMixWithEnvironmentDiscovery() throws Exception {
        Path explicitGame = temporaryDirectory.resolve("explicit-game");
        Path environmentGame = temporaryDirectory.resolve("environment-game");
        Files.createDirectories(explicitGame);
        Files.createDirectories(environmentGame);
        Path explicitLauncher = Files.writeString(explicitGame.resolve("starsector.sh"), "#!/bin/sh\n");
        Path competingLauncher = Files.writeString(environmentGame.resolve("fr.sh"), "#!/bin/sh\n");
        explicitLauncher.toFile().setExecutable(true);
        competingLauncher.toFile().setExecutable(true);

        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.LINUX,
                temporaryDirectory,
                temporaryDirectory.resolve("elsewhere"),
                Map.of("STARSECTOR_HOME", environmentGame.toString()),
                explicitGame,
                null);

        assertNotNull(result.selected());
        assertEquals(explicitLauncher.toAbsolutePath().normalize(), result.selected().launcher());
        assertEquals(1, result.candidates().size());
    }

    @Test
    void prefersFastRenderingLauncher() throws Exception {
        Path game = temporaryDirectory.resolve("game");
        Files.createDirectories(game);
        Path vanilla = Files.writeString(game.resolve("starsector.sh"), "#!/bin/sh\n");
        Path fastRendering = Files.writeString(game.resolve("fr.sh"), "#!/bin/sh\n");
        vanilla.toFile().setExecutable(true);
        fastRendering.toFile().setExecutable(true);

        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.LINUX,
                temporaryDirectory,
                temporaryDirectory.resolve("elsewhere"),
                Map.of(),
                game,
                null);

        assertEquals(fastRendering.toAbsolutePath().normalize(), result.selected().launcher());
        assertTrue(result.selected().score() > result.candidates().get(1).score());
    }

    @Test
    void explicitWindowsBatchLauncherUsesCmd() throws Exception {
        Path launcher = Files.writeString(temporaryDirectory.resolve("starsector.bat"), "@echo off\r\n");

        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.WINDOWS,
                temporaryDirectory,
                temporaryDirectory.resolve("elsewhere"),
                Map.of(),
                temporaryDirectory,
                launcher);

        assertEquals("cmd.exe", result.selected().command().get(0));
        assertEquals("call", result.selected().command().get(4));
        assertEquals("\"" + launcher.toAbsolutePath().normalize() + "\"", result.selected().command().get(5));
        assertEquals(launcher.toAbsolutePath().normalize(), result.selected().launcher());
    }

    @Test
    void doesNotRecursivelyInspectFilesystemRootFromPackagedWorkingDirectory() throws Exception {
        Path filesystemRoot = temporaryDirectory.toAbsolutePath().getRoot();

        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.OTHER,
                temporaryDirectory,
                filesystemRoot,
                Map.of(),
                null,
                null);

        assertTrue(result.candidates().isEmpty());
        assertTrue(result.diagnostics().stream()
                .anyMatch(message -> message.contains("Skipped filesystem root as an implicit discovery directory")));
    }
}

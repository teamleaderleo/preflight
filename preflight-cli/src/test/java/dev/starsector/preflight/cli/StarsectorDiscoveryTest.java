package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class StarsectorDiscoveryTest {
    @TempDir
    Path temporaryDirectory;

    /**
     * The note has to be about what the caller did. Telling someone who passed {@code --game} to
     * pass {@code --game} is the one thing it must never say.
     */
    @Test
    void anExplicitGamePathThatDoesNotExistIsNamed() throws Exception {
        Path missing = temporaryDirectory.resolve("not-installed-here");

        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.MAC, temporaryDirectory, temporaryDirectory, Map.of(), missing, null);

        assertNull(result.selected());
        String note = String.join(" ", result.diagnostics());
        assertTrue(note.contains(missing.toAbsolutePath().normalize().toString()), note);
        assertTrue(note.contains("does not exist"), note);
        assertFalse(note.contains("STARSECTOR_HOME"), "it already told us where to look: " + note);
    }

    /** A real folder with nothing in it is a different mistake, and needs a different note. */
    @Test
    void anExplicitGamePathWithoutALauncherSaysWhatWasExpected() throws Exception {
        Path empty = Files.createDirectories(temporaryDirectory.resolve("somewhere-else"));

        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.WINDOWS, temporaryDirectory, temporaryDirectory, Map.of(), empty, null);

        assertNull(result.selected());
        String note = String.join(" ", result.diagnostics());
        assertTrue(note.contains(empty.toAbsolutePath().normalize().toString()), note);
        assertTrue(note.contains("starsector.exe"), "the note should name what to look for: " + note);
    }

    /** With nothing given, the note is the only place the caller learns what to give. */
    @Test
    void withNoExplicitPathTheNoteStillOffersTheWayIn() throws Exception {
        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.LINUX,
                temporaryDirectory.resolve("no-home"),
                temporaryDirectory.resolve("nowhere"),
                Map.of(),
                null,
                null);

        assertNull(result.selected());
        assertTrue(String.join(" ", result.diagnostics()).contains("STARSECTOR_HOME"),
                result.diagnostics().toString());
    }

    /**
     * "Not found in the usual locations" is the answer to a question nobody asked. Someone opens
     * that note to find out whether their own install is somewhere unusual, which needs the list.
     */
    @Test
    void aFailedSearchNamesEveryPlaceItLooked() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("home"));
        Path present = Files.createDirectories(home.resolve("Games/Starsector"));

        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.LINUX, home, temporaryDirectory.resolve("nowhere"), Map.of(), null, null);

        assertNull(result.selected());
        String note = String.join("\n", result.diagnostics());
        assertTrue(note.contains("Searched " + present.getParent()), note);
        assertTrue(note.contains("Searched " + home.resolve(".local/share/starsector") + " (not present)"), note);
        // The Linux standard roots are absolute POSIX paths, and this test also runs on Windows,
        // where an absolute path picks up the current drive. Compare against what the platform makes
        // of it rather than the spelling the discovery source happens to use.
        assertTrue(note.contains("Searched " + Path.of("/opt/starsector").toAbsolutePath().normalize()), note);
        assertTrue(note.contains("(no launcher in it)"), note);
    }

    /**
     * Standard roots that differ only by case are one directory where the filesystem says so, and
     * the list is for reading: printing the same place twice is what makes it hard to scan.
     */
    @Test
    void searchedLocationsDoNotRepeatOneDirectoryUnderTwoSpellings() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("home"));
        Files.createDirectories(home.resolve("Games/starsector"));

        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.LINUX, home, temporaryDirectory.resolve("nowhere"), Map.of(), null, null);

        List<String> searched = result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.startsWith("Searched "))
                .toList();
        assertEquals(searched.size(), Set.copyOf(searched).size(), searched.toString());
    }

    @Test
    // The decoy beside the stub is excluded by not being executable, and Windows has no such bit:
    // Files.isExecutable is true for anything readable, so the filter admits both and the count is 2.
    // A macOS bundle is a macOS shape, and this asserts how it is read where it can exist.
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void discoversMacApplicationBundle() throws Exception {
        Path app = temporaryDirectory.resolve("Starsector.app");
        Path executable = app.resolve("Contents/MacOS/JavaApplicationStub");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "stub");
        executable.toFile().setExecutable(true);
        Files.writeString(executable.getParent().resolve("compiler_directives.txt"), "not a launcher");

        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.MAC,
                temporaryDirectory,
                temporaryDirectory.resolve("elsewhere"),
                Map.of(),
                app,
                null);

        assertNotNull(result.selected());
        assertEquals(executable.toAbsolutePath().normalize(), result.selected().launcher());
        assertEquals(1, result.candidates().size());
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
    void prefersLinuxPortWithoutRelyingOnLexicographicTiesAndPreservesExplicitStock() throws Exception {
        Path game = temporaryDirectory.resolve("port-game");
        Files.createDirectories(game);
        Path stock = Files.writeString(game.resolve("starsector"), "#!/bin/sh\n");
        Path port = Files.writeString(game.resolve("starsector-fr.sh"), "#!/bin/sh\n");
        stock.toFile().setExecutable(true);
        port.toFile().setExecutable(true);
        DiscoveryResult automatic = StarsectorDiscovery.discover(Platform.LINUX,
                temporaryDirectory, temporaryDirectory.resolve("elsewhere"), Map.of(), game, null);
        assertEquals(port.toAbsolutePath().normalize(), automatic.selected().launcher());
        assertTrue(automatic.selected().score() > automatic.candidates().get(1).score());
        assertTrue(LaunchOwnership.detect(automatic.selected()).fastRendering());

        DiscoveryResult explicit = StarsectorDiscovery.discover(Platform.LINUX,
                temporaryDirectory, temporaryDirectory.resolve("elsewhere"), Map.of(), game, stock);
        assertEquals(stock.toAbsolutePath().normalize(), explicit.selected().launcher());
        assertFalse(LaunchOwnership.detect(explicit.selected()).fastRendering());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void explicitSymlinkWinsWithoutDuplicatingItsDiscoveredTarget() throws Exception {
        Path game = temporaryDirectory.resolve("game");
        Files.createDirectories(game);
        Path launcher = Files.writeString(game.resolve("starsector.sh"), "#!/bin/sh\n");
        launcher.toFile().setExecutable(true);
        Path alias = game.resolve("preferred-starsector.sh");
        Files.createSymbolicLink(alias, launcher.getFileName());

        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.LINUX,
                temporaryDirectory,
                temporaryDirectory.resolve("elsewhere"),
                Map.of(),
                game,
                alias);

        assertNotNull(result.selected());
        assertEquals(alias.toAbsolutePath().normalize(), result.selected().launcher());
        assertEquals("--launcher", result.selected().source());
        assertEquals(1, result.candidates().size());
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
    void implicitWorkingDirectoryStillFindsADirectKnownLauncher() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace-direct"));
        Path launcher = Files.writeString(workspace.resolve("starsector.sh"), "#!/bin/sh\n");
        launcher.toFile().setExecutable(true);

        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.OTHER,
                temporaryDirectory.resolve("no-home"),
                workspace,
                Map.of(),
                null,
                null);

        assertNotNull(result.selected());
        assertEquals(launcher.toAbsolutePath().normalize(), result.selected().launcher());
    }

    @Test
    void implicitWorkingDirectoryFindsDocumentedNearbyStarsectorChild() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace-nearby"));
        Path game = Files.createDirectories(workspace.resolve("Starsector"));
        Path launcher = Files.writeString(game.resolve("starsector.sh"), "#!/bin/sh\n");
        launcher.toFile().setExecutable(true);

        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.OTHER,
                temporaryDirectory.resolve("no-home"),
                workspace,
                Map.of(),
                null,
                null);

        assertNotNull(result.selected());
        assertEquals(launcher.toAbsolutePath().normalize(), result.selected().launcher());
    }

    @Test
    void implicitWorkingDirectoryDoesNotTraverseUnrelatedNestedWorkspace() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace-bounded"));
        Path decoyDirectory = Files.createDirectories(workspace.resolve("unrelated/project/output"));
        Path decoy = Files.writeString(decoyDirectory.resolve("starsector.sh"), "#!/bin/sh\n");
        decoy.toFile().setExecutable(true);

        DiscoveryResult result = StarsectorDiscovery.discover(
                Platform.OTHER,
                temporaryDirectory.resolve("no-home"),
                workspace,
                Map.of(),
                null,
                null);

        assertNull(result.selected());
        assertTrue(result.candidates().isEmpty());
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

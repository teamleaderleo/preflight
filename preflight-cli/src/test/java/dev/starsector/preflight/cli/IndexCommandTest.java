package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexCommandTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsQueriesAndInvalidatesAnIndex() throws Exception {
        Path core = temporaryDirectory.resolve("starsector-core");
        Path mods = temporaryDirectory.resolve("mods");
        Path alpha = mods.resolve("Alpha");
        Files.createDirectories(core.resolve("graphics"));
        Files.createDirectories(alpha.resolve("graphics"));
        Path coreFile = Files.writeString(core.resolve("graphics/shared.png"), "core");
        Path alphaFile = Files.writeString(alpha.resolve("graphics/shared.png"), "alpha");
        Files.writeString(alpha.resolve("mod_info.json"), "{\"id\":\"alpha\"}");
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[\"alpha\"]}");
        Path launcher = Files.writeString(temporaryDirectory.resolve("starsector.sh"), "#!/bin/sh\n");
        launcher.toFile().setExecutable(true);
        Path indexFile = temporaryDirectory.resolve("resources.spfi");

        assertEquals(0, PreflightCli.run(new String[] {
                "index", "build",
                "--game", temporaryDirectory.toString(),
                "--output", indexFile.toString()
        }));
        assertTrue(Files.isRegularFile(indexFile));

        ResourceIndex index = ResourceIndexIO.read(indexFile);
        PreflightHome alternateHome = new PreflightHome(temporaryDirectory.resolve("owned-home"), List.of());
        assertEquals(
                ResourceIndexIO.directory(alternateHome.cache())
                        .resolve(index.profileFingerprint() + ".spfi")
                        .toAbsolutePath()
                        .normalize(),
                IndexCommand.defaultOutput(alternateHome, index));
        Path canonicalAlphaFile = alphaFile.toRealPath();
        Path canonicalCoreFile = coreFile.toRealPath();
        assertEquals(2, index.providers("graphics/shared.png").size());
        assertEquals(canonicalAlphaFile, index.winningFile("graphics/shared.png").orElseThrow());
        assertTrue(index.providers("graphics/shared.png").stream()
                .anyMatch(provider -> index.resolve(provider).equals(canonicalCoreFile)));

        assertEquals(0, PreflightCli.run(new String[] {
                "index", "query", indexFile.toString(), "graphics/shared.png"
        }));
        assertEquals(0, PreflightCli.run(new String[] {
                "index", "query", indexFile.toString(), "graphics/shared.png", "--all"
        }));
        assertEquals(4, PreflightCli.run(new String[] {
                "index", "query", indexFile.toString(), "graphics/missing.png"
        }));
        assertEquals(0, PreflightCli.run(new String[] {
                "index", "validate", indexFile.toString()
        }));

        Files.writeString(alphaFile, "alpha changed");
        assertEquals(5, PreflightCli.run(new String[] {
                "index", "validate", indexFile.toString()
        }));
    }
}

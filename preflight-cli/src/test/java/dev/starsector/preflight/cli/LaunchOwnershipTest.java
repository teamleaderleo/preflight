package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchOwnershipTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recognizesThePortableFastRenderingLauncherByName() throws Exception {
        Path launcher = temporaryDirectory.resolve("fast-rendering.command");
        Files.writeString(launcher, "#!/bin/sh\nexec java @fr.vmparams\n");
        assertTrue(LaunchOwnership.detect(target(launcher)).fastRendering());
    }

    @Test
    void recognizesTheWindowsLauncherAndCustomClassloaderContract() throws Exception {
        Path launcher = temporaryDirectory.resolve("renamed.cmd");
        Files.writeString(launcher, "..\\jre\\bin\\java.exe @fr.vmparams\n");
        Files.writeString(temporaryDirectory.resolve("fr.vmparams"),
                "-Djava.system.class.loader=com.genir.renderer.loaders.AppClassLoader\n"
                        + "-classpath fr.jar;janino.jar;starfarer_obf.jar\n");
        LaunchOwnership ownership = LaunchOwnership.detect(target(launcher));
        assertTrue(ownership.fastRendering());
        assertTrue(ownership.evidence().contains("fr.vmparams-custom-system-classloader"));
    }

    @Test
    void ordinaryVanillaLaunchersRemainUnownedByFastRendering() throws Exception {
        Path launcher = temporaryDirectory.resolve("starsector_mac.sh");
        Files.writeString(launcher, "#!/bin/sh\nexec java com.fs.starfarer.StarfarerLauncher\n");
        assertFalse(LaunchOwnership.detect(target(launcher)).fastRendering());
    }

    private LaunchTarget target(Path launcher) {
        return new LaunchTarget(
                temporaryDirectory, launcher, temporaryDirectory, List.of(launcher.toString()),
                "fixture", 0, "test");
    }
}

package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchOwnershipTest {
    private static final int MAX_LAUNCHER_BYTES = 64 * 1024;
    private static final int MAX_VMPARAMS_BYTES = 256 * 1024;

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

    @Test
    void exactLauncherByteLimitIsAccepted() throws Exception {
        Path launcher = temporaryDirectory.resolve("renamed-launcher.sh");
        Files.write(launcher, paddedBytes(MAX_LAUNCHER_BYTES, "#!/bin/sh\nexec java -classpath fr.jar\n"));

        LaunchOwnership ownership = LaunchOwnership.detect(target(launcher));

        assertTrue(ownership.fastRendering());
        assertTrue(ownership.evidence().contains("launcher-references-fast-rendering"));
        assertEquals(MAX_LAUNCHER_BYTES, Files.size(launcher));
    }

    @Test
    void exactVmparamsByteLimitIsAccepted() throws Exception {
        Path launcher = temporaryDirectory.resolve("renamed-launcher.sh");
        Files.writeString(launcher, "#!/bin/sh\nexec java\n");
        Path vmparams = temporaryDirectory.resolve("fr.vmparams");
        Files.write(vmparams, paddedBytes(MAX_VMPARAMS_BYTES, "-classpath fr.jar;janino.jar\n"));

        LaunchOwnership ownership = LaunchOwnership.detect(target(launcher));

        assertTrue(ownership.fastRendering());
        assertTrue(ownership.evidence().contains("fr.vmparams-classpath=fr.jar"));
        assertEquals(MAX_VMPARAMS_BYTES, Files.size(vmparams));
    }

    @Test
    void initiallyOversizedLauncherIsRefusedByTheCheapPrefilter() throws Exception {
        Path launcher = temporaryDirectory.resolve("renamed-launcher.sh");
        Files.write(launcher, paddedBytes(MAX_LAUNCHER_BYTES + 1, "fr.jar\n"));

        assertFalse(LaunchOwnership.detect(target(launcher)).fastRendering());
    }

    @Test
    void initiallyOversizedVmparamsIsRefusedByTheCheapPrefilter() throws Exception {
        Path launcher = temporaryDirectory.resolve("renamed-launcher.sh");
        Files.writeString(launcher, "#!/bin/sh\nexec java\n");
        Files.write(temporaryDirectory.resolve("fr.vmparams"),
                paddedBytes(MAX_VMPARAMS_BYTES + 1, "-classpath fr.jar\n"));

        assertFalse(LaunchOwnership.detect(target(launcher)).fastRendering());
    }

    @Test
    void openedLauncherReadRejectsGrowthPastTheLimit() throws Exception {
        assertOpenedFileGrowthIsRefused("growing-launcher.sh", MAX_LAUNCHER_BYTES);
    }

    @Test
    void openedVmparamsReadRejectsGrowthPastTheLimit() throws Exception {
        assertOpenedFileGrowthIsRefused("growing-fr.vmparams", MAX_VMPARAMS_BYTES);
    }

    @Test
    void malformedUtf8ProducesNoOwnershipEvidence() throws Exception {
        Path launcher = temporaryDirectory.resolve("renamed-launcher.sh");
        Files.write(launcher, new byte[] {(byte) 0x80});

        assertFalse(LaunchOwnership.detect(target(launcher)).fastRendering());
        assertEquals("", LaunchOwnership.boundedText(launcher, MAX_LAUNCHER_BYTES));
    }

    private void assertOpenedFileGrowthIsRefused(String name, int maximumBytes) throws Exception {
        Path file = temporaryDirectory.resolve(name);
        Files.write(file, paddedBytes(maximumBytes, "fr.jar\n"));
        boolean[] appended = {false};

        try (InputStream raw = Files.newInputStream(file);
             InputStream growing = new FilterInputStream(raw) {
                 @Override
                 public int read(byte[] buffer, int offset, int length) throws IOException {
                     int requested = appended[0] ? length : Math.min(1, length);
                     int read = super.read(buffer, offset, requested);
                     if (!appended[0] && read > 0) {
                         Files.write(file, new byte[] {' '}, StandardOpenOption.APPEND);
                         appended[0] = true;
                     }
                     return read;
                 }
             }) {
            assertEquals("", LaunchOwnership.boundedText(growing, maximumBytes));
        }

        assertTrue(appended[0]);
        assertEquals(maximumBytes + 1L, Files.size(file));
    }

    private static byte[] paddedBytes(int size, String prefix) {
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        if (prefixBytes.length > size) {
            throw new IllegalArgumentException("prefix exceeds fixture size");
        }
        byte[] bytes = new byte[size];
        System.arraycopy(prefixBytes, 0, bytes, 0, prefixBytes.length);
        for (int index = prefixBytes.length; index < bytes.length; index++) {
            bytes[index] = ' ';
        }
        return bytes;
    }

    private LaunchTarget target(Path launcher) {
        return new LaunchTarget(
                temporaryDirectory, launcher, temporaryDirectory, List.of(launcher.toString()),
                "fixture", 0, "test");
    }
}

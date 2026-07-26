package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledJorbisEquivalenceCommandIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void packagedCommandLoadsExactSyntheticDecoderJarsAndProvesCiFixtures() throws Exception {
        DecoderJars jars = decoderJars();
        Path report = temporaryDirectory.resolve("installed-jorbis-equivalence.json");
        InstalledJorbisEquivalenceCommand.Options options =
                new InstalledJorbisEquivalenceCommand.Options(jars.jogg(), jars.jorbis(), report);
        int exit = InstalledJorbisEquivalenceCommand.execute(
                options,
                Hashes.sha256(jars.jogg()),
                Hashes.sha256(jars.jorbis()),
                Path.of("target", "preflight.jar"),
                "ci");

        String json = Files.readString(report);
        assertEquals(0, exit, json);
        assertTrue(json.contains("\"format\":\"starsector-preflight-installed-jorbis-equivalence-v2\""), json);
        assertTrue(json.contains("\"fixtureProfile\":\"ci\""), json);
        assertTrue(json.contains("\"identityExact\":true"), json);
        assertTrue(json.contains("\"validPcmEquivalent\":true"), json);
        assertTrue(json.contains("\"invalidBehaviorStable\":true"), json);
        assertTrue(json.contains("\"equivalent\":true"), json);
        // The decode path is named in the report because it decides the answer. The superseded gate
        // drove com.jcraft.jorbis.VorbisFile, which no shipped JAR references.
        assertTrue(json.contains("\"decoderApi\":\"jogg-jorbis-low-level-synthesis\""), json);
        assertFalse(json.contains("VorbisFile"), json);
        assertTrue(json.contains("\"className\":\"com.jcraft.jogg.SyncState\""), json);
        assertTrue(json.contains("\"className\":\"com.jcraft.jorbis.DspState\""), json);
        assertTrue(json.contains("\"className\":\"com.jcraft.jorbis.Block\""), json);
        // What the gate now asserts per case, rather than a hash baked from a reference decoder.
        assertTrue(json.contains("\"deterministic\":true"), json);
        assertTrue(json.contains("\"preparedRoundTripExact\":true"), json);
        assertTrue(json.contains("\"audiblyCorrect\":true"), json);
        assertTrue(json.contains("\"referenceWithinTolerance\":true"), json);
        assertTrue(json.contains("\"sawEndOfStream\":true"), json);
        assertTrue(json.contains("\"validCaseCount\":3"), json);
        assertTrue(json.contains("\"invalidCaseCount\":5"), json);
        assertTrue(json.contains("\"caseCount\":8"), json);
        assertTrue(json.contains("\"streamedMusicEligible\":false"), json);
        assertTrue(json.contains("\"preparedAudioWritesEnabled\":false"), json);
        assertTrue(json.contains("\"liveTransformEnabled\":false"), json);
        assertTrue(json.contains("\"streamOwnershipExact\":true"), json);
    }

    @Test
    void rejectsAnyDecoderArchiveIdentityChangeBeforeLaunchingChild() throws Exception {
        DecoderJars jars = decoderJars();
        InstalledJorbisEquivalenceCommand.Options options = new InstalledJorbisEquivalenceCommand.Options(
                jars.jogg(), jars.jorbis(), temporaryDirectory.resolve("report.json"));
        IOException error = assertThrows(
                IOException.class,
                () -> InstalledJorbisEquivalenceCommand.execute(
                        options,
                        "00".repeat(32),
                        Hashes.sha256(jars.jorbis()),
                        Path.of("target", "preflight.jar"),
                        "ci"));
        assertTrue(error.getMessage().contains("Jogg JAR SHA-256 differs"), error.getMessage());
    }

    @Test
    void refusesToDeleteAnInputJarChosenAsTheReportPath() throws Exception {
        DecoderJars jars = decoderJars();
        String joggHash = Hashes.sha256(jars.jogg());
        InstalledJorbisEquivalenceCommand.Options options =
                new InstalledJorbisEquivalenceCommand.Options(jars.jogg(), jars.jorbis(), jars.jogg());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> InstalledJorbisEquivalenceCommand.execute(
                        options,
                        joggHash,
                        Hashes.sha256(jars.jorbis()),
                        Path.of("target", "preflight.jar"),
                        "ci"));
        assertTrue(error.getMessage().contains("collides with an input JAR"), error.getMessage());
        assertEquals(joggHash, Hashes.sha256(jars.jogg()));
    }

    private DecoderJars decoderJars() throws IOException {
        Path testClasses = Path.of("target", "test-classes").toAbsolutePath().normalize();
        Path jogg = temporaryDirectory.resolve("jogg-" + System.nanoTime() + ".jar");
        Path jorbis = temporaryDirectory.resolve("jorbis-" + System.nanoTime() + ".jar");
        writeJar(jogg, testClasses, List.of("com/jcraft/jogg"));
        writeJar(jorbis, testClasses, List.of("com/jcraft/jorbis"));
        return new DecoderJars(jogg, jorbis);
    }

    private static void writeJar(Path destination, Path classes, List<String> packageRoots) throws IOException {
        Files.createDirectories(destination.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(destination))) {
            for (String packageRoot : packageRoots) {
                Path root = classes.resolve(packageRoot);
                try (var files = Files.walk(root)) {
                    for (Path file : files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".class"))
                            .sorted(Comparator.comparing(Path::toString))
                            .toList()) {
                        String name = classes.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                        JarEntry entry = new JarEntry(name);
                        entry.setTime(0);
                        output.putNextEntry(entry);
                        Files.copy(file, output);
                        output.closeEntry();
                    }
                }
            }
        }
    }

    private record DecoderJars(Path jogg, Path jorbis) {
    }
}

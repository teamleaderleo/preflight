package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoundWrapperObservationCommandIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void packagedCommandObservesSyntheticWrapperWithoutEnablingReuse() throws Exception {
        Path testClasses = Path.of("target", "test-classes").toAbsolutePath().normalize();
        Path game = temporaryDirectory.resolve("game");
        Files.createDirectories(game);
        Path sound = game.resolve("sound-wrapper.jar");
        Path jogg = game.resolve("jogg.jar");
        Path jorbis = game.resolve("jorbis.jar");
        writeJar(sound, testClasses, List.of("sound"));
        writeJar(jogg, testClasses, List.of("com/jcraft/jogg"));
        writeJar(jorbis, testClasses, List.of("com/jcraft/jorbis"));

        Path report = temporaryDirectory.resolve("sound-wrapper-observation.json");
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
        SoundWrapperObservationCommand.Options options =
                new SoundWrapperObservationCommand.Options(game, jogg, jorbis, java, report);
        int exit = SoundWrapperObservationCommand.execute(
                options,
                Hashes.sha256(jogg),
                Hashes.sha256(jorbis),
                Path.of("target", "preflight.jar"),
                "ci");

        String json = Files.readString(report);
        assertEquals(0, exit, json);
        assertTrue(json.contains("\"format\":\"starsector-preflight-sound-wrapper-observation-v1\""), json);
        assertTrue(json.contains("\"observationComplete\":true"), json);
        assertTrue(json.contains("\"identityExact\":true"), json);
        assertTrue(json.contains("\"wrapperPayloadMatchesDirectJorbis\":false"), json);
        assertTrue(json.contains("\"equivalenceEstablished\":false"), json);
        assertTrue(json.contains("\"requiresHumanReview\":true"), json);
        assertTrue(json.contains("\"preparedAudioReadsEnabled\":false"), json);
        assertTrue(json.contains("\"preparedAudioWritesEnabled\":false"), json);
        assertTrue(json.contains("\"cacheReadsEnabled\":false"), json);
        assertTrue(json.contains("\"cacheWritesEnabled\":false"), json);
        assertTrue(json.contains("\"liveTransformEnabled\":false"), json);
        assertTrue(json.contains("\"decodedAudioIncluded\":false"), json);
    }

    private static void writeJar(Path destination, Path classes, List<String> packageRoots) throws IOException {
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
}

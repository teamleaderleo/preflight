package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class AudioFixturePackagingIT {
    private static final String PREFIX = "audio/ogg-v1/";

    @Test
    void shadedJarContainsExactlyTheReviewedProductionAudioFixtures() throws Exception {
        Path resources = Path.of("..", "preflight-core", "src", "main", "resources", "audio", "ogg-v1")
                .toAbsolutePath()
                .normalize();
        assertTrue(Files.isDirectory(resources), "production audio fixture directory is missing: " + resources);

        Set<String> expected = new TreeSet<>();
        try (var entries = Files.list(resources)) {
            entries.filter(Files::isRegularFile)
                    .map(path -> PREFIX + path.getFileName())
                    .forEach(expected::add);
        }
        assertTrue(!expected.isEmpty(), "production audio fixture allowlist must not be empty");

        Path jar = Path.of("target", "preflight.jar").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(jar), "shaded Preflight JAR is missing: " + jar);
        Set<String> actual = new TreeSet<>();
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            archive.stream()
                    .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(PREFIX))
                    .map(java.util.zip.ZipEntry::getName)
                    .forEach(actual::add);
        }

        assertEquals(expected, actual,
                "preflight.jar must contain exactly the reviewed production Ogg fixture resources");
    }
}

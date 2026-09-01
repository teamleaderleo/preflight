package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceIndexTest {
    @Test
    void returnsOrderedProvidersAndLastWinner(@TempDir Path directory) throws Exception {
        Path core = Files.createDirectories(directory.resolve("core/graphics/ships"));
        Path alpha = Files.createDirectories(directory.resolve("mods/alpha/graphics/ships"));
        Files.writeString(core.resolve("example.png"), "core");
        Files.writeString(alpha.resolve("example.png"), "alpha");
        ResourceIndex index = new ResourceIndex(
                "fingerprint",
                List.of(
                        new ResourceIndex.Root("core", directory.resolve("core"), true),
                        new ResourceIndex.Root("alpha", directory.resolve("mods/alpha"), false)),
                Map.of("Graphics\\Ships//example.png", List.of(
                        new ResourceIndex.Provider(0, "graphics/ships/example.png", 10, 1),
                        new ResourceIndex.Provider(1, "graphics/ships/example.png", 20, 2))));

        assertEquals(2, index.providers("./graphics/ships/example.png").size());
        assertEquals(1, index.winner("graphics/ships/example.png").orElseThrow().rootIndex());
        assertTrue(index.winningFile("graphics/ships/example.png").orElseThrow()
                .endsWith(Path.of("mods", "alpha", "graphics", "ships", "example.png")));
    }

    @Test
    void foldedHitsFollowTheProviderFilesystem(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectories(directory.resolve("mod/graphics"));
        Path actual = Files.writeString(root.resolve("Example.PNG"), "image");
        ResourceIndex index = new ResourceIndex(
                "fingerprint",
                List.of(new ResourceIndex.Root("mod", directory.resolve("mod"), false)),
                Map.of("graphics/Example.PNG", List.of(
                        new ResourceIndex.Provider(0, "graphics/Example.PNG", 5, 1))));

        assertEquals(1, index.providers("graphics/Example.PNG").size());
        Path folded = root.resolve("example.png");
        boolean filesystemMatches = Files.isRegularFile(folded) && Files.isSameFile(actual, folded);
        assertEquals(filesystemMatches ? 1 : 0, index.providers("graphics/example.png").size());
    }

    @Test
    void rejectsTraversalAndAbsolutePaths() {
        assertThrows(IllegalArgumentException.class, () -> ResourceIndex.normalizeLogicalPath("../secret"));
        assertThrows(IllegalArgumentException.class, () -> ResourceIndex.normalizeLogicalPath("/absolute"));
        assertThrows(IllegalArgumentException.class, () -> ResourceIndex.normalizeLogicalPath("\\absolute"));
        assertThrows(IllegalArgumentException.class, () -> ResourceIndex.normalizeLogicalPath("C:\\absolute"));
        assertThrows(IllegalArgumentException.class, () -> ResourceIndex.normalizeLogicalPath("z:relative"));
    }

    @Test
    void allocationLightNormalizerMatchesTheLegacyNormalizer() {
        List<String> fixed = List.of(
                "data/weapons/example.wpn",
                "data//weapons///example.wpn",
                "./graphics\\ships//Example.PNG",
                "a/./b",
                "a//b/",
                ".../a.b",
                "a b/c-d_e",
                "graphics/Ä/İ/Σ.png",
                ".",
                "./",
                "////",
                "a/../b");
        for (String value : fixed) {
            assertSameNormalization(value);
        }

        Random random = new Random(0x5a17c0deL);
        String alphabet = "abcXYZ01239./\\:-_ \t";
        for (int sample = 0; sample < 20_000; sample++) {
            int length = random.nextInt(65);
            StringBuilder value = new StringBuilder(length);
            for (int index = 0; index < length; index++) {
                value.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            assertSameNormalization(value.toString());
        }
    }

    @Test
    void rejectsProvidersOutsideResolutionOrder() {
        List<ResourceIndex.Root> roots = List.of(
                new ResourceIndex.Root("core", Path.of("core"), true),
                new ResourceIndex.Root("alpha", Path.of("alpha"), false));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceIndex(
                        "fingerprint",
                        roots,
                        Map.of("data/example.json", List.of(
                                new ResourceIndex.Provider(1, "data/example.json", 1, 1),
                                new ResourceIndex.Provider(0, "data/example.json", 1, 1)))));
    }

    @Test
    void rejectsUnknownProviderDuringDirectResolution() {
        ResourceIndex index = new ResourceIndex(
                "fingerprint",
                List.of(new ResourceIndex.Root("core", Path.of("core"), true)),
                Map.of("data/example.json", List.of(
                        new ResourceIndex.Provider(0, "data/example.json", 1, 1))));

        assertThrows(
                IllegalArgumentException.class,
                () -> index.resolve(new ResourceIndex.Provider(8, "data/example.json", 1, 1)));
    }

    private static void assertSameNormalization(String raw) {
        try {
            String expected = legacyNormalize(raw);
            assertEquals(expected, ResourceIndex.normalizeRelativePath(raw), raw);
            assertEquals(
                    expected.toLowerCase(Locale.ROOT),
                    ResourceIndex.normalizeLogicalPath(raw),
                    raw);
        } catch (IllegalArgumentException expected) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ResourceIndex.normalizeRelativePath(raw),
                    raw);
        }
    }

    private static String legacyNormalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException();
        }
        String value = raw.replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException();
        }

        ArrayDeque<String> segments = new ArrayDeque<>();
        for (String segment : value.split("/+")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new IllegalArgumentException();
            }
            segments.addLast(segment);
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException();
        }
        return String.join("/", new ArrayList<>(segments));
    }
}

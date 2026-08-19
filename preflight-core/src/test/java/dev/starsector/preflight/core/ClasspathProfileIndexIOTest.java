package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathProfileIndexIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsOrderedProvidersAndWinner() throws Exception {
        List<ClasspathProfileIndex.Archive> archives = List.of(
                archive("lazy", "jars/lazy.jar", "aa", 0),
                archive("luna", "jars/luna.jar", "bb", 1),
                archive("campaign", "jars/campaign.jar", "cc", 2));
        ClasspathProfileIndex index = new ClasspathProfileIndex(
                "dd".repeat(32),
                archives,
                Map.of(
                        "shared/Utility.class", List.of(0, 1, 2),
                        "campaign/config.json", List.of(2)));

        byte[] first = ClasspathProfileIndexIO.toBytes(index);
        byte[] second = ClasspathProfileIndexIO.toBytes(index);
        ClasspathProfileIndex restored = ClasspathProfileIndexIO.fromBytes(first);

        assertArrayEquals(first, second);
        assertEquals(3, restored.archives().size());
        assertEquals(2, restored.entryCount());
        assertEquals(4, restored.providerCount());
        assertEquals(List.of("lazy", "luna", "campaign"), restored.providerArchives("shared/Utility.class")
                .stream().map(ClasspathProfileIndex.Archive::modId).toList());
        assertEquals("campaign", restored.winner("shared/Utility.class").orElseThrow().modId());
        assertEquals("campaign", restored.winner("campaign/config.json").orElseThrow().modId());
    }

    @Test
    void rejectsCorruptionAndOutOfOrderProviders() throws Exception {
        ClasspathProfileIndex index = new ClasspathProfileIndex(
                "ee".repeat(32),
                List.of(archive("one", "one.jar", "11", 0), archive("two", "two.jar", "22", 1)),
                Map.of("a/A.class", List.of(0, 1)));
        byte[] bytes = ClasspathProfileIndexIO.toBytes(index);
        bytes[bytes.length - 40] ^= 0x33;
        assertThrows(IOException.class, () -> ClasspathProfileIndexIO.fromBytes(bytes));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ClasspathProfileIndex(
                        "ff".repeat(32),
                        index.archives(),
                        Map.of("a/A.class", List.of(1, 0))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ClasspathProfileIndex(
                        "ff".repeat(32),
                        index.archives(),
                        Map.of("a/A.class", List.of(0, 0))));
    }

    @Test
    void readStopsAtConfiguredBoundBeforeParsing() throws Exception {
        ClasspathProfileIndex index = new ClasspathProfileIndex(
                "dd".repeat(32),
                List.of(archive("one", "one.jar", "11", 0)),
                Map.of("a/A.class", List.of(0)));
        Path file = temporaryDirectory.resolve("classpath.spfc");
        Files.write(file, ClasspathProfileIndexIO.toBytes(index));

        IOException oversized = assertThrows(IOException.class, () -> ClasspathProfileIndexIO.read(file, 64));
        assertTrue(oversized.getMessage().contains("64 byte safety limit"));
        assertThrows(IllegalArgumentException.class, () -> ClasspathProfileIndexIO.read(file, 32));
    }

    private static ClasspathProfileIndex.Archive archive(
            String modId,
            String relative,
            String hashPair,
            long modified) {
        return new ClasspathProfileIndex.Archive(
                modId,
                relative,
                Path.of("mods", modId, relative),
                hashPair.repeat(32),
                100,
                modified,
                "classpath/archives/" + hashPair + "/" + hashPair.repeat(32) + ".spfj",
                true);
    }
}
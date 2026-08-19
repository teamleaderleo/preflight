package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceIndexIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsAndWritesDeterministically() throws Exception {
        ResourceIndex index = fixtureIndex(false);
        byte[] first = ResourceIndexIO.toBytes(index);
        byte[] second = ResourceIndexIO.toBytes(fixtureIndex(true));
        assertArrayEquals(first, second);

        Path output = temporaryDirectory.resolve("nested/resources.spfi");
        ResourceIndexIO.write(output, index);
        ResourceIndex restored = ResourceIndexIO.read(output);

        assertEquals(index.profileFingerprint(), restored.profileFingerprint());
        assertEquals(index.roots(), restored.roots());
        assertEquals(index.entries(), restored.entries());
        assertEquals("beta", restored.roots().get(restored.winner("graphics/shared.png").orElseThrow().rootIndex()).id());
        assertTrue(Files.isRegularFile(output));
    }

    @Test
    void boundedReadRejectsAtTheStreamLimit() throws Exception {
        byte[] bytes = ResourceIndexIO.toBytes(fixtureIndex(false));
        Path input = temporaryDirectory.resolve("bounded.spfi");
        Files.write(input, bytes);

        IOException error = assertThrows(
                IOException.class,
                () -> ResourceIndexIO.read(input, bytes.length - 1));

        assertTrue(error.getMessage().contains("byte safety limit"));
    }

    @Test
    void rejectsCorruptPayloads() throws Exception {
        byte[] bytes = ResourceIndexIO.toBytes(fixtureIndex(false));
        bytes[bytes.length / 2] ^= 0x5a;

        IOException error = assertThrows(IOException.class, () -> ResourceIndexIO.fromBytes(bytes));
        assertTrue(error.getMessage().contains("checksum"));
    }

    @Test
    void rejectsTruncatedFiles() throws Exception {
        byte[] bytes = ResourceIndexIO.toBytes(fixtureIndex(false));
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 5);
        assertThrows(IOException.class, () -> ResourceIndexIO.fromBytes(truncated));
    }

    private static ResourceIndex fixtureIndex(boolean reverseInsertion) {
        List<ResourceIndex.Root> roots = List.of(
                new ResourceIndex.Root("core", Path.of("core"), true),
                new ResourceIndex.Root("alpha", Path.of("mods/alpha"), false),
                new ResourceIndex.Root("beta", Path.of("mods/beta"), false));
        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        Map<String, List<ResourceIndex.Provider>> values = Map.of(
                "graphics/shared.png", List.of(
                        new ResourceIndex.Provider(0, "graphics/shared.png", 1, 10),
                        new ResourceIndex.Provider(1, "graphics/shared.png", 2, 11),
                        new ResourceIndex.Provider(2, "graphics/Shared.PNG", 3, 12)),
                "data/config.json", List.of(new ResourceIndex.Provider(0, "data/config.json", 4, 13)));
        if (reverseInsertion) {
            entries.put("graphics/shared.png", values.get("graphics/shared.png"));
            entries.put("data/config.json", values.get("data/config.json"));
        } else {
            entries.put("data/config.json", values.get("data/config.json"));
            entries.put("graphics/shared.png", values.get("graphics/shared.png"));
        }
        return new ResourceIndex("fingerprint", roots, entries);
    }
}

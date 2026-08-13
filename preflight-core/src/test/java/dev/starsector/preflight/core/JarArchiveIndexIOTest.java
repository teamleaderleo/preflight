package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JarArchiveIndexIOTest {
    @Test
    void roundTripsDeterministicallyAndSupportsExactQueries() throws Exception {
        Map<String, JarArchiveIndex.Entry> entries = new LinkedHashMap<>();
        entries.put("z/resource.txt", new JarArchiveIndex.Entry("z/resource.txt", 20, 12, 4, 8));
        entries.put("a/Example.class", new JarArchiveIndex.Entry("a/Example.class", 10, 8, 3, 8));
        JarArchiveIndex index = new JarArchiveIndex("ab".repeat(32), 123, entries);

        byte[] first = JarArchiveIndexIO.toBytes(index);
        byte[] second = JarArchiveIndexIO.toBytes(index);
        JarArchiveIndex restored = JarArchiveIndexIO.fromBytes(first);

        assertArrayEquals(first, second);
        assertEquals("ab".repeat(32), restored.sourceSha256());
        assertEquals(123, restored.sourceBytes());
        assertEquals(2, restored.entryCount());
        assertEquals(1, restored.classEntries());
        assertEquals(1, restored.resourceEntries());
        assertEquals(30, restored.uncompressedBytes());
        assertEquals(20, restored.compressedBytes());
        assertTrue(restored.contains("a//Example.class"));
        assertEquals("a.Example", restored.entry("a/Example.class").orElseThrow().className().orElseThrow());
        assertFalse(restored.contains("A/Example.class"));
    }

    @Test
    void rejectsCorruptionTruncationTraversalAndDuplicateNormalizedNames() throws Exception {
        JarArchiveIndex index = new JarArchiveIndex(
                "cd".repeat(32),
                1,
                Map.of("a/A.class", new JarArchiveIndex.Entry("a/A.class", 1, 1, 0, 0)));
        byte[] bytes = JarArchiveIndexIO.toBytes(index);
        bytes[bytes.length / 2] ^= 0x55;
        assertThrows(IOException.class, () -> JarArchiveIndexIO.fromBytes(bytes));
        assertThrows(IOException.class, () -> JarArchiveIndexIO.fromBytes(new byte[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> JarArchiveIndex.normalizeEntryName("../outside"));
        assertThrows(IllegalArgumentException.class, () -> JarArchiveIndex.normalizeEntryName("/absolute"));
        assertThrows(IllegalArgumentException.class, () -> JarArchiveIndex.normalizeEntryName("C:\\absolute"));
        assertEquals("a/b/C.class", JarArchiveIndex.normalizeEntryName("a//./b\\C.class/"));
        assertSame("a/b/C.class", JarArchiveIndex.normalizeEntryName("a/b/C.class"));

        Map<String, JarArchiveIndex.Entry> duplicates = new LinkedHashMap<>();
        duplicates.put("a//A.class", new JarArchiveIndex.Entry("a/A.class", 1, 1, 0, 0));
        duplicates.put("a/A.class", new JarArchiveIndex.Entry("a/A.class", 1, 1, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JarArchiveIndex("ef".repeat(32), 2, duplicates));
    }
}

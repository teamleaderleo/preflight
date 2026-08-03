package dev.starsector.preflight.core;

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

class PreparedMergedReadCacheIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsDeterministicallyAndAtomically() throws Exception {
        String csv = MergedReadKey.csv("data/hulls/ship_data.csv", true, false, List.of("id"));
        String json = MergedReadKey.json("data/config/engine_styles.json", List.of());
        byte[] csvTree = JsonTree.encode(List.of(Map.of("id", "wolf")));
        byte[] jsonTree = JsonTree.encode(Map.of("volume", 0.8d));
        Map<String, byte[]> reversed = new LinkedHashMap<>();
        reversed.put(json, jsonTree);
        reversed.put(csv, csvTree);
        PreparedMergedReadCache cache = new PreparedMergedReadCache("a".repeat(64), reversed);

        byte[] first = PreparedMergedReadCacheIO.toBytes(cache);
        byte[] second = PreparedMergedReadCacheIO.toBytes(new PreparedMergedReadCache(
                "a".repeat(64), Map.of(csv, csvTree, json, jsonTree)));
        assertTrue(Arrays.equals(first, second));

        Path file = temporaryDirectory.resolve("profile.spmr");
        PreparedMergedReadCacheIO.write(file, cache);
        PreparedMergedReadCache restored = PreparedMergedReadCacheIO.read(file);
        assertEquals(cache.profileIdentitySha256(), restored.profileIdentitySha256());
        assertEquals(cache.entries().keySet(), restored.entries().keySet());
        for (String key : cache.entries().keySet()) {
            assertTrue(Arrays.equals(cache.entries().get(key), restored.entries().get(key)));
        }
    }

    @Test
    void rejectsCorruptionTruncationAndInvalidRecords() throws Exception {
        String key = MergedReadKey.json("data/config/engine_styles.json", List.of());
        PreparedMergedReadCache cache = new PreparedMergedReadCache(
                "b".repeat(64), Map.of(key, JsonTree.encode(Map.of("value", true))));
        byte[] bytes = PreparedMergedReadCacheIO.toBytes(cache);

        byte[] corrupt = bytes.clone();
        corrupt[corrupt.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedMergedReadCacheIO.fromBytes(corrupt));

        Path truncated = temporaryDirectory.resolve("truncated.spmr");
        Files.write(truncated, Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedMergedReadCacheIO.read(truncated));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedMergedReadCache("bad", Map.of(key, new byte[] {0})));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedMergedReadCache("b".repeat(64), Map.of("not-a-key", new byte[] {0})));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedMergedReadCache("b".repeat(64), Map.of(key, new byte[0])));
    }
}

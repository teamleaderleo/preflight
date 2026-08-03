package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedVariantJsonCacheIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsDeterministicallyAndAtomically() throws Exception {
        Map<String, byte[]> reversed = new LinkedHashMap<>();
        reversed.put("data/variants/z.variant", tree("variantId", "z"));
        reversed.put("data/variants/a.variant", tree("variantId", "a"));
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache("a".repeat(64), reversed);

        byte[] first = PreparedVariantJsonCacheIO.toBytes(cache);
        byte[] second = PreparedVariantJsonCacheIO.toBytes(new PreparedVariantJsonCache(
                "a".repeat(64), Map.of(
                        "data/variants/a.variant", tree("variantId", "a"),
                        "data/variants/z.variant", tree("variantId", "z"))));
        assertTrue(java.util.Arrays.equals(first, second));

        Path file = temporaryDirectory.resolve("profile.spvj");
        PreparedVariantJsonCacheIO.write(file, cache);
        PreparedVariantJsonCache read = PreparedVariantJsonCacheIO.read(file);
        assertEquals(cache.profileIdentitySha256(), read.profileIdentitySha256());
        assertArrayEquals(cache.entries().get("data/variants/a.variant"),
                read.entries().get("data/variants/a.variant"));
        assertArrayEquals(cache.entries().get("data/variants/z.variant"),
                read.entries().get("data/variants/z.variant"));
    }

    @Test
    void rejectsCorruptionWrongPathsAndWrongProfile() throws Exception {
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache(
                "b".repeat(64), Map.of("data/variants/a.variant", tree("id", "a")));
        byte[] bytes = PreparedVariantJsonCacheIO.toBytes(cache);
        bytes[bytes.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedVariantJsonCacheIO.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class, () -> new PreparedVariantJsonCache(
                "bad", Map.of("data/variants/a.variant", tree("id", "a"))));
        assertThrows(IllegalArgumentException.class, () -> new PreparedVariantJsonCache(
                "b".repeat(64), Map.of("data/weapons/a.wpn", tree("id", "a"))));
    }

    @Test
    void rejectsTruncation() throws Exception {
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache(
                "c".repeat(64), Map.of("data/variants/a.variant", tree("id", "a")));
        byte[] bytes = PreparedVariantJsonCacheIO.toBytes(cache);
        Path file = temporaryDirectory.resolve("truncated.spvj");
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedVariantJsonCacheIO.read(file));
    }

    @Test
    void rejectsTheTextBasedVersionOneFormatInsteadOfTreatingItAsATree() throws Exception {
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache(
                "d".repeat(64), Map.of("data/variants/a.variant", tree("id", "a")));
        byte[] bytes = PreparedVariantJsonCacheIO.toBytes(cache);
        // Magic occupies bytes 0..3 and the version is the following big-endian int.
        bytes[7] = 1;
        assertThrows(IOException.class, () -> PreparedVariantJsonCacheIO.fromBytes(bytes));
    }

    private static byte[] tree(String key, String value) {
        return JsonTree.encode(Map.of(key, value));
    }
}

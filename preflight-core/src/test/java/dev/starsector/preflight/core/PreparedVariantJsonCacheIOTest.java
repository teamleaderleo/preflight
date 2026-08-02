package dev.starsector.preflight.core;

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
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("data/variants/z.variant", "{\"variantId\":\"z\"}");
        reversed.put("data/variants/a.variant", "{\"variantId\":\"a\"}");
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache("a".repeat(64), reversed);

        byte[] first = PreparedVariantJsonCacheIO.toBytes(cache);
        byte[] second = PreparedVariantJsonCacheIO.toBytes(new PreparedVariantJsonCache(
                "a".repeat(64), Map.of(
                        "data/variants/a.variant", "{\"variantId\":\"a\"}",
                        "data/variants/z.variant", "{\"variantId\":\"z\"}")));
        assertTrue(java.util.Arrays.equals(first, second));

        Path file = temporaryDirectory.resolve("profile.spvj");
        PreparedVariantJsonCacheIO.write(file, cache);
        assertEquals(cache, PreparedVariantJsonCacheIO.read(file));
    }

    @Test
    void rejectsCorruptionWrongPathsAndWrongProfile() throws Exception {
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache(
                "b".repeat(64), Map.of("data/variants/a.variant", "{}"));
        byte[] bytes = PreparedVariantJsonCacheIO.toBytes(cache);
        bytes[bytes.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedVariantJsonCacheIO.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class, () -> new PreparedVariantJsonCache(
                "bad", Map.of("data/variants/a.variant", "{}")));
        assertThrows(IllegalArgumentException.class, () -> new PreparedVariantJsonCache(
                "b".repeat(64), Map.of("data/weapons/a.wpn", "{}")));
    }

    @Test
    void rejectsTruncation() throws Exception {
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache(
                "c".repeat(64), Map.of("data/variants/a.variant", "{}"));
        byte[] bytes = PreparedVariantJsonCacheIO.toBytes(cache);
        Path file = temporaryDirectory.resolve("truncated.spvj");
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedVariantJsonCacheIO.read(file));
    }
}

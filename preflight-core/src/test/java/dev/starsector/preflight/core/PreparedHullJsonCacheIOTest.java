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

class PreparedHullJsonCacheIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsHullJsonDeterministicallyAndAtomically() throws Exception {
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("data/hulls/z.ship", "{\"id\":\"z\"}");
        reversed.put("data/hulls/nested/a.ship", "{\"id\":\"a\"}");
        PreparedHullJsonCache cache = new PreparedHullJsonCache("a".repeat(64), reversed);

        byte[] first = PreparedHullJsonCacheIO.toBytes(cache);
        byte[] second = PreparedHullJsonCacheIO.toBytes(new PreparedHullJsonCache(
                "a".repeat(64), Map.of(
                        "data/hulls/nested/a.ship", "{\"id\":\"a\"}",
                        "data/hulls/z.ship", "{\"id\":\"z\"}")));
        assertTrue(java.util.Arrays.equals(first, second));

        Path file = temporaryDirectory.resolve("profile.sphj");
        PreparedHullJsonCacheIO.write(file, cache);
        assertEquals(cache, PreparedHullJsonCacheIO.read(file));
    }

    @Test
    void rejectsCorruptionWrongPathsAndWrongProfile() throws Exception {
        PreparedHullJsonCache cache = new PreparedHullJsonCache(
                "b".repeat(64), Map.of("data/hulls/a.ship", "{}"));
        byte[] bytes = PreparedHullJsonCacheIO.toBytes(cache);
        bytes[bytes.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedHullJsonCacheIO.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class, () -> new PreparedHullJsonCache(
                "bad", Map.of("data/hulls/a.ship", "{}")));
        assertThrows(IllegalArgumentException.class, () -> new PreparedHullJsonCache(
                "b".repeat(64), Map.of("data/variants/a.ship", "{}")));
        assertThrows(IllegalArgumentException.class, () -> new PreparedHullJsonCache(
                "b".repeat(64), Map.of("data/hulls/a.skin", "{}")));
        assertThrows(IllegalArgumentException.class, () -> new PreparedHullJsonCache(
                "b".repeat(64), Map.of("data/hulls.ship", "{}")));
    }

    @Test
    void rejectsTruncationAndOtherCacheArtifacts() throws Exception {
        PreparedHullJsonCache cache = new PreparedHullJsonCache(
                "c".repeat(64), Map.of("data/hulls/a.ship", "{}"));
        byte[] bytes = PreparedHullJsonCacheIO.toBytes(cache);
        Path file = temporaryDirectory.resolve("truncated.sphj");
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedHullJsonCacheIO.read(file));

        byte[] weapon = PreparedWeaponJsonCacheIO.toBytes(new PreparedWeaponJsonCache(
                "c".repeat(64), Map.of("data/weapons/a.wpn", "{}")));
        assertThrows(IOException.class, () -> PreparedHullJsonCacheIO.fromBytes(weapon));
        byte[] projectile = PreparedProjectileJsonCacheIO.toBytes(new PreparedProjectileJsonCache(
                "c".repeat(64), Map.of("data/weapons/proj/a.proj", "{}")));
        assertThrows(IOException.class, () -> PreparedHullJsonCacheIO.fromBytes(projectile));
        byte[] variant = PreparedVariantJsonCacheIO.toBytes(new PreparedVariantJsonCache(
                "c".repeat(64), Map.of("data/variants/a.variant", "{}")));
        assertThrows(IOException.class, () -> PreparedHullJsonCacheIO.fromBytes(variant));
    }
}

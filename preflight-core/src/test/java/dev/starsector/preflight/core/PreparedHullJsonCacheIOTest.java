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

class PreparedHullJsonCacheIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsHullJsonDeterministicallyAndAtomically() throws Exception {
        Map<String, byte[]> reversed = new LinkedHashMap<>();
        reversed.put("data/hulls/z.ship", tree("z"));
        reversed.put("data/hulls/nested/a.ship", tree("a"));
        PreparedHullJsonCache cache = new PreparedHullJsonCache("a".repeat(64), reversed);

        byte[] first = PreparedHullJsonCacheIO.toBytes(cache);
        byte[] second = PreparedHullJsonCacheIO.toBytes(new PreparedHullJsonCache(
                "a".repeat(64), Map.of(
                        "data/hulls/nested/a.ship", tree("a"),
                        "data/hulls/z.ship", tree("z"))));
        assertTrue(java.util.Arrays.equals(first, second));

        Path file = temporaryDirectory.resolve("profile.sphj");
        PreparedHullJsonCacheIO.write(file, cache);
        PreparedHullJsonCache read = PreparedHullJsonCacheIO.read(file);
        assertEquals(cache.profileIdentitySha256(), read.profileIdentitySha256());
        assertArrayEquals(cache.entries().get("data/hulls/nested/a.ship"),
                read.entries().get("data/hulls/nested/a.ship"));
        assertArrayEquals(cache.entries().get("data/hulls/z.ship"),
                read.entries().get("data/hulls/z.ship"));
    }

    @Test
    void rejectsCorruptionWrongPathsAndWrongProfile() throws Exception {
        PreparedHullJsonCache cache = new PreparedHullJsonCache(
                "b".repeat(64), Map.of("data/hulls/a.ship", tree("a")));
        byte[] bytes = PreparedHullJsonCacheIO.toBytes(cache);
        bytes[bytes.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedHullJsonCacheIO.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class, () -> new PreparedHullJsonCache(
                "bad", Map.of("data/hulls/a.ship", tree("a"))));
        assertThrows(IllegalArgumentException.class, () -> new PreparedHullJsonCache(
                "b".repeat(64), Map.of("data/variants/a.ship", tree("a"))));
        assertThrows(IllegalArgumentException.class, () -> new PreparedHullJsonCache(
                "b".repeat(64), Map.of("data/hulls/a.skin", tree("a"))));
        assertThrows(IllegalArgumentException.class, () -> new PreparedHullJsonCache(
                "b".repeat(64), Map.of("data/hulls.ship", tree("a"))));
    }

    @Test
    void rejectsTruncationAndOtherCacheArtifacts() throws Exception {
        PreparedHullJsonCache cache = new PreparedHullJsonCache(
                "c".repeat(64), Map.of("data/hulls/a.ship", tree("a")));
        byte[] bytes = PreparedHullJsonCacheIO.toBytes(cache);
        Path file = temporaryDirectory.resolve("truncated.sphj");
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedHullJsonCacheIO.read(file));

        byte[] weapon = PreparedWeaponJsonCacheIO.toBytes(new PreparedWeaponJsonCache(
                "c".repeat(64), Map.of("data/weapons/a.wpn", tree("a"))));
        assertThrows(IOException.class, () -> PreparedHullJsonCacheIO.fromBytes(weapon));
        byte[] projectile = PreparedProjectileJsonCacheIO.toBytes(new PreparedProjectileJsonCache(
                "c".repeat(64), Map.of("data/weapons/proj/a.proj", tree("a"))));
        assertThrows(IOException.class, () -> PreparedHullJsonCacheIO.fromBytes(projectile));
        byte[] variant = PreparedVariantJsonCacheIO.toBytes(new PreparedVariantJsonCache(
                "c".repeat(64), Map.of("data/variants/a.variant", tree("a"))));
        assertThrows(IOException.class, () -> PreparedHullJsonCacheIO.fromBytes(variant));
    }

    private static byte[] tree(String id) {
        return JsonTree.encode(Map.of("id", id));
    }
}

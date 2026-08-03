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

class PreparedProjectileJsonCacheIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsBothProjectileDomainsDeterministicallyAndAtomically() throws Exception {
        Map<String, byte[]> reversed = new LinkedHashMap<>();
        reversed.put("data/weapons/proj/z.proj", tree("z"));
        reversed.put("data/shipsystems/proj/a.proj", tree("a"));
        PreparedProjectileJsonCache cache = new PreparedProjectileJsonCache("a".repeat(64), reversed);

        byte[] first = PreparedProjectileJsonCacheIO.toBytes(cache);
        byte[] second = PreparedProjectileJsonCacheIO.toBytes(new PreparedProjectileJsonCache(
                "a".repeat(64), Map.of(
                        "data/shipsystems/proj/a.proj", tree("a"),
                        "data/weapons/proj/z.proj", tree("z"))));
        assertTrue(java.util.Arrays.equals(first, second));

        Path file = temporaryDirectory.resolve("profile.sppj");
        PreparedProjectileJsonCacheIO.write(file, cache);
        PreparedProjectileJsonCache read = PreparedProjectileJsonCacheIO.read(file);
        assertEquals(cache.profileIdentitySha256(), read.profileIdentitySha256());
        assertArrayEquals(cache.entries().get("data/shipsystems/proj/a.proj"),
                read.entries().get("data/shipsystems/proj/a.proj"));
        assertArrayEquals(cache.entries().get("data/weapons/proj/z.proj"),
                read.entries().get("data/weapons/proj/z.proj"));
    }

    @Test
    void rejectsCorruptionWrongPathsAndWrongProfile() throws Exception {
        PreparedProjectileJsonCache cache = new PreparedProjectileJsonCache(
                "b".repeat(64), Map.of("data/weapons/proj/a.proj", tree("a")));
        byte[] bytes = PreparedProjectileJsonCacheIO.toBytes(cache);
        bytes[bytes.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedProjectileJsonCacheIO.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class, () -> new PreparedProjectileJsonCache(
                "bad", Map.of("data/weapons/proj/a.proj", tree("a"))));
        assertThrows(IllegalArgumentException.class, () -> new PreparedProjectileJsonCache(
                "b".repeat(64), Map.of("data/weapons/a.proj", tree("a"))));
        assertThrows(IllegalArgumentException.class, () -> new PreparedProjectileJsonCache(
                "b".repeat(64), Map.of("data/shipsystems/wpn/a.proj", tree("a"))));
        assertThrows(IllegalArgumentException.class, () -> new PreparedProjectileJsonCache(
                "b".repeat(64), Map.of("data/weapons/proj/a.wpn", tree("a"))));
    }

    @Test
    void rejectsTruncationAndOtherCacheArtifacts() throws Exception {
        PreparedProjectileJsonCache cache = new PreparedProjectileJsonCache(
                "c".repeat(64), Map.of("data/weapons/proj/a.proj", tree("a")));
        byte[] bytes = PreparedProjectileJsonCacheIO.toBytes(cache);
        Path file = temporaryDirectory.resolve("truncated.sppj");
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedProjectileJsonCacheIO.read(file));

        byte[] weapon = PreparedWeaponJsonCacheIO.toBytes(new PreparedWeaponJsonCache(
                "c".repeat(64), Map.of("data/weapons/a.wpn", tree("a"))));
        assertThrows(IOException.class, () -> PreparedProjectileJsonCacheIO.fromBytes(weapon));
        byte[] variant = PreparedVariantJsonCacheIO.toBytes(new PreparedVariantJsonCache(
                "c".repeat(64), Map.of("data/variants/a.variant", tree("a"))));
        assertThrows(IOException.class, () -> PreparedProjectileJsonCacheIO.fromBytes(variant));
    }

    private static byte[] tree(String id) {
        return JsonTree.encode(Map.of("id", id));
    }
}

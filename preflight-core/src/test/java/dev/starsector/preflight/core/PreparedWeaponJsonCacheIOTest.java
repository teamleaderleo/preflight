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

class PreparedWeaponJsonCacheIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsBothWeaponDomainsDeterministicallyAndAtomically() throws Exception {
        Map<String, byte[]> reversed = new LinkedHashMap<>();
        reversed.put("data/weapons/z.wpn", tree("z"));
        reversed.put("data/shipsystems/wpn/a.wpn", tree("a"));
        PreparedWeaponJsonCache cache = new PreparedWeaponJsonCache("a".repeat(64), reversed);

        byte[] first = PreparedWeaponJsonCacheIO.toBytes(cache);
        byte[] second = PreparedWeaponJsonCacheIO.toBytes(new PreparedWeaponJsonCache(
                "a".repeat(64), Map.of(
                        "data/shipsystems/wpn/a.wpn", tree("a"),
                        "data/weapons/z.wpn", tree("z"))));
        assertTrue(java.util.Arrays.equals(first, second));

        Path file = temporaryDirectory.resolve("profile.spwj");
        PreparedWeaponJsonCacheIO.write(file, cache);
        PreparedWeaponJsonCache read = PreparedWeaponJsonCacheIO.read(file);
        assertEquals(cache.profileIdentitySha256(), read.profileIdentitySha256());
        assertArrayEquals(cache.entries().get("data/shipsystems/wpn/a.wpn"),
                read.entries().get("data/shipsystems/wpn/a.wpn"));
        assertArrayEquals(cache.entries().get("data/weapons/z.wpn"),
                read.entries().get("data/weapons/z.wpn"));
    }

    @Test
    void rejectsCorruptionWrongPathsAndWrongProfile() throws Exception {
        PreparedWeaponJsonCache cache = new PreparedWeaponJsonCache(
                "b".repeat(64), Map.of("data/weapons/a.wpn", tree("a")));
        byte[] bytes = PreparedWeaponJsonCacheIO.toBytes(cache);
        bytes[bytes.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedWeaponJsonCacheIO.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class, () -> new PreparedWeaponJsonCache(
                "bad", Map.of("data/weapons/a.wpn", tree("a"))));
        assertThrows(IllegalArgumentException.class, () -> new PreparedWeaponJsonCache(
                "b".repeat(64), Map.of("data/variants/a.variant", tree("a"))));
        assertThrows(IllegalArgumentException.class, () -> new PreparedWeaponJsonCache(
                "b".repeat(64), Map.of("data/shipsystems/proj/a.proj", tree("a"))));
    }

    @Test
    void rejectsTruncationAndVariantArtifacts() throws Exception {
        PreparedWeaponJsonCache cache = new PreparedWeaponJsonCache(
                "c".repeat(64), Map.of("data/weapons/a.wpn", tree("a")));
        byte[] bytes = PreparedWeaponJsonCacheIO.toBytes(cache);
        Path file = temporaryDirectory.resolve("truncated.spwj");
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedWeaponJsonCacheIO.read(file));

        byte[] variant = PreparedVariantJsonCacheIO.toBytes(new PreparedVariantJsonCache(
                "c".repeat(64), Map.of("data/variants/a.variant", tree("a"))));
        assertThrows(IOException.class, () -> PreparedWeaponJsonCacheIO.fromBytes(variant));
    }

    private static byte[] tree(String id) {
        return JsonTree.encode(Map.of("id", id));
    }
}

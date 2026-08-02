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

class PreparedWeaponJsonCacheIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsBothWeaponDomainsDeterministicallyAndAtomically() throws Exception {
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("data/weapons/z.wpn", "{\"id\":\"z\"}");
        reversed.put("data/shipsystems/wpn/a.wpn", "{\"id\":\"a\"}");
        PreparedWeaponJsonCache cache = new PreparedWeaponJsonCache("a".repeat(64), reversed);

        byte[] first = PreparedWeaponJsonCacheIO.toBytes(cache);
        byte[] second = PreparedWeaponJsonCacheIO.toBytes(new PreparedWeaponJsonCache(
                "a".repeat(64), Map.of(
                        "data/shipsystems/wpn/a.wpn", "{\"id\":\"a\"}",
                        "data/weapons/z.wpn", "{\"id\":\"z\"}")));
        assertTrue(java.util.Arrays.equals(first, second));

        Path file = temporaryDirectory.resolve("profile.spwj");
        PreparedWeaponJsonCacheIO.write(file, cache);
        assertEquals(cache, PreparedWeaponJsonCacheIO.read(file));
    }

    @Test
    void rejectsCorruptionWrongPathsAndWrongProfile() throws Exception {
        PreparedWeaponJsonCache cache = new PreparedWeaponJsonCache(
                "b".repeat(64), Map.of("data/weapons/a.wpn", "{}"));
        byte[] bytes = PreparedWeaponJsonCacheIO.toBytes(cache);
        bytes[bytes.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedWeaponJsonCacheIO.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class, () -> new PreparedWeaponJsonCache(
                "bad", Map.of("data/weapons/a.wpn", "{}")));
        assertThrows(IllegalArgumentException.class, () -> new PreparedWeaponJsonCache(
                "b".repeat(64), Map.of("data/variants/a.variant", "{}")));
        assertThrows(IllegalArgumentException.class, () -> new PreparedWeaponJsonCache(
                "b".repeat(64), Map.of("data/shipsystems/proj/a.proj", "{}")));
    }

    @Test
    void rejectsTruncationAndVariantArtifacts() throws Exception {
        PreparedWeaponJsonCache cache = new PreparedWeaponJsonCache(
                "c".repeat(64), Map.of("data/weapons/a.wpn", "{}"));
        byte[] bytes = PreparedWeaponJsonCacheIO.toBytes(cache);
        Path file = temporaryDirectory.resolve("truncated.spwj");
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedWeaponJsonCacheIO.read(file));

        byte[] variant = PreparedVariantJsonCacheIO.toBytes(new PreparedVariantJsonCache(
                "c".repeat(64), Map.of("data/variants/a.variant", "{}")));
        assertThrows(IOException.class, () -> PreparedWeaponJsonCacheIO.fromBytes(variant));
    }
}

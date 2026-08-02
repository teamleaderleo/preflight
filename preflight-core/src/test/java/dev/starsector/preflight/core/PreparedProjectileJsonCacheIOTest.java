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

class PreparedProjectileJsonCacheIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsBothProjectileDomainsDeterministicallyAndAtomically() throws Exception {
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("data/weapons/proj/z.proj", "{\"id\":\"z\"}");
        reversed.put("data/shipsystems/proj/a.proj", "{\"id\":\"a\"}");
        PreparedProjectileJsonCache cache = new PreparedProjectileJsonCache("a".repeat(64), reversed);

        byte[] first = PreparedProjectileJsonCacheIO.toBytes(cache);
        byte[] second = PreparedProjectileJsonCacheIO.toBytes(new PreparedProjectileJsonCache(
                "a".repeat(64), Map.of(
                        "data/shipsystems/proj/a.proj", "{\"id\":\"a\"}",
                        "data/weapons/proj/z.proj", "{\"id\":\"z\"}")));
        assertTrue(java.util.Arrays.equals(first, second));

        Path file = temporaryDirectory.resolve("profile.sppj");
        PreparedProjectileJsonCacheIO.write(file, cache);
        assertEquals(cache, PreparedProjectileJsonCacheIO.read(file));
    }

    @Test
    void rejectsCorruptionWrongPathsAndWrongProfile() throws Exception {
        PreparedProjectileJsonCache cache = new PreparedProjectileJsonCache(
                "b".repeat(64), Map.of("data/weapons/proj/a.proj", "{}"));
        byte[] bytes = PreparedProjectileJsonCacheIO.toBytes(cache);
        bytes[bytes.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedProjectileJsonCacheIO.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class, () -> new PreparedProjectileJsonCache(
                "bad", Map.of("data/weapons/proj/a.proj", "{}")));
        assertThrows(IllegalArgumentException.class, () -> new PreparedProjectileJsonCache(
                "b".repeat(64), Map.of("data/weapons/a.proj", "{}")));
        assertThrows(IllegalArgumentException.class, () -> new PreparedProjectileJsonCache(
                "b".repeat(64), Map.of("data/shipsystems/wpn/a.proj", "{}")));
        assertThrows(IllegalArgumentException.class, () -> new PreparedProjectileJsonCache(
                "b".repeat(64), Map.of("data/weapons/proj/a.wpn", "{}")));
    }

    @Test
    void rejectsTruncationAndOtherCacheArtifacts() throws Exception {
        PreparedProjectileJsonCache cache = new PreparedProjectileJsonCache(
                "c".repeat(64), Map.of("data/weapons/proj/a.proj", "{}"));
        byte[] bytes = PreparedProjectileJsonCacheIO.toBytes(cache);
        Path file = temporaryDirectory.resolve("truncated.sppj");
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedProjectileJsonCacheIO.read(file));

        byte[] weapon = PreparedWeaponJsonCacheIO.toBytes(new PreparedWeaponJsonCache(
                "c".repeat(64), Map.of("data/weapons/a.wpn", "{}")));
        assertThrows(IOException.class, () -> PreparedProjectileJsonCacheIO.fromBytes(weapon));
        byte[] variant = PreparedVariantJsonCacheIO.toBytes(new PreparedVariantJsonCache(
                "c".repeat(64), Map.of("data/variants/a.variant", "{}")));
        assertThrows(IOException.class, () -> PreparedProjectileJsonCacheIO.fromBytes(variant));
    }
}

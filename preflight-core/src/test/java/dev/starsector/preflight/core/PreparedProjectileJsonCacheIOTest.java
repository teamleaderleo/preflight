package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedProjectileJsonCacheIOTest {
    private static final String PROFILE = "a".repeat(64);
    private static final int PAYLOAD_LENGTH_OFFSET = 8;
    private static final int PAYLOAD_OFFSET = 12;
    private static final int PROFILE_BYTES = 32;
    private static final int CHECKSUM_BYTES = 32;
    private static final byte[] PROJECTILE_PREFIX = "data/weapons/proj/".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsBothProjectileDomainsDeterministicallyAndAtomically() throws Exception {
        Map<String, byte[]> reversed = new LinkedHashMap<>();
        reversed.put("data/weapons/proj/z.proj", tree("z"));
        reversed.put("data/shipsystems/proj/a.proj", tree("a"));
        PreparedProjectileJsonCache cache = new PreparedProjectileJsonCache(PROFILE, reversed);

        byte[] first = PreparedProjectileJsonCacheIO.toBytes(cache);
        byte[] second = PreparedProjectileJsonCacheIO.toBytes(new PreparedProjectileJsonCache(
                PROFILE, Map.of(
                        "data/shipsystems/proj/a.proj", tree("a"),
                        "data/weapons/proj/z.proj", tree("z"))));
        assertTrue(Arrays.equals(first, second));

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
    void roundTripsValidUnicodeProjectilePath() throws Exception {
        PreparedProjectileJsonCache cache = new PreparedProjectileJsonCache(
                PROFILE, Map.of("data/weapons/proj/世界.proj", tree("世界")));

        PreparedProjectileJsonCache decoded = PreparedProjectileJsonCacheIO.fromBytes(
                PreparedProjectileJsonCacheIO.toBytes(cache));

        assertEquals(cache.profileIdentitySha256(), decoded.profileIdentitySha256());
        assertArrayEquals(cache.entries().get("data/weapons/proj/世界.proj"),
                decoded.entries().get("data/weapons/proj/世界.proj"));
    }

    @Test
    void checksumValidMalformedUtf8IsRejectedAtThePathDecoder() throws Exception {
        PreparedProjectileJsonCache cache = new PreparedProjectileJsonCache(
                PROFILE, Map.of("data/weapons/proj/�.proj", tree("replacement")));
        byte[] bytes = PreparedProjectileJsonCacheIO.toBytes(cache);
        int malformedAt = firstPathBytesOffset(bytes) + PROJECTILE_PREFIX.length;
        assertEquals((byte) 0xef, bytes[malformedAt]);
        assertEquals((byte) 0xbf, bytes[malformedAt + 1]);
        assertEquals((byte) 0xbd, bytes[malformedAt + 2]);

        bytes[malformedAt] = (byte) 0xed;
        bytes[malformedAt + 1] = (byte) 0xa0;
        bytes[malformedAt + 2] = (byte) 0x80;
        resignPayload(bytes);

        IOException error = assertThrows(IOException.class, () -> PreparedProjectileJsonCacheIO.fromBytes(bytes));
        assertEquals("Prepared projectile cache string is not valid UTF-8", error.getMessage());
    }

    @Test
    void checksumValidNoncanonicalPathIsRejectedBeforeModelNormalization() throws Exception {
        PreparedProjectileJsonCache cache = new PreparedProjectileJsonCache(
                PROFILE, Map.of("data/weapons/proj/a.proj", tree("a")));
        byte[] bytes = PreparedProjectileJsonCacheIO.toBytes(cache);
        int pathAt = firstPathBytesOffset(bytes);
        int nameAt = pathAt + PROJECTILE_PREFIX.length;
        assertEquals((byte) 'a', bytes[nameAt]);

        bytes[nameAt] = (byte) 'A';
        resignPayload(bytes);

        IOException error = assertThrows(IOException.class, () -> PreparedProjectileJsonCacheIO.fromBytes(bytes));
        assertEquals("Prepared projectile cache path is not canonical: data/weapons/proj/A.proj", error.getMessage());
    }

    @Test
    void writerRejectsMalformedUtf16Path() {
        PreparedProjectileJsonCache cache = new PreparedProjectileJsonCache(
                PROFILE, Map.of("data/weapons/proj/\ud800.proj", tree("surrogate")));

        IOException error = assertThrows(IOException.class, () -> PreparedProjectileJsonCacheIO.toBytes(cache));
        assertEquals("Prepared projectile cache string cannot be encoded as UTF-8", error.getMessage());
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
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedProjectileJsonCacheIO.read(file));

        byte[] weapon = PreparedWeaponJsonCacheIO.toBytes(new PreparedWeaponJsonCache(
                "c".repeat(64), Map.of("data/weapons/a.wpn", tree("a"))));
        assertThrows(IOException.class, () -> PreparedProjectileJsonCacheIO.fromBytes(weapon));
        byte[] variant = PreparedVariantJsonCacheIO.toBytes(new PreparedVariantJsonCache(
                "c".repeat(64), Map.of("data/variants/a.variant", tree("a"))));
        assertThrows(IOException.class, () -> PreparedProjectileJsonCacheIO.fromBytes(variant));
    }

    private static int firstPathBytesOffset(byte[] bytes) {
        int payloadLength = intAt(bytes, PAYLOAD_LENGTH_OFFSET);
        if (payloadLength <= PROFILE_BYTES + Integer.BYTES * 2) {
            throw new IllegalStateException("fixture payload is too small");
        }
        int countOffset = PAYLOAD_OFFSET + PROFILE_BYTES;
        assertEquals(1, intAt(bytes, countOffset));
        int pathLengthOffset = countOffset + Integer.BYTES;
        int pathLength = intAt(bytes, pathLengthOffset);
        if (pathLength <= 0) {
            throw new IllegalStateException("fixture path is empty");
        }
        return pathLengthOffset + Integer.BYTES;
    }

    private static void resignPayload(byte[] bytes) {
        int payloadLength = intAt(bytes, PAYLOAD_LENGTH_OFFSET);
        byte[] payload = Arrays.copyOfRange(bytes, PAYLOAD_OFFSET, PAYLOAD_OFFSET + payloadLength);
        byte[] checksum = Hashes.sha256Bytes(payload);
        System.arraycopy(checksum, 0, bytes, PAYLOAD_OFFSET + payloadLength, CHECKSUM_BYTES);
    }

    private static int intAt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(offset);
    }

    private static byte[] tree(String id) {
        return JsonTree.encode(Map.of("id", id));
    }
}

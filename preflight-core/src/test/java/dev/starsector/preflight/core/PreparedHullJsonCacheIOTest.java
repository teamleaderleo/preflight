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

class PreparedHullJsonCacheIOTest {
    private static final String PROFILE = "a".repeat(64);
    private static final int PAYLOAD_LENGTH_OFFSET = 8;
    private static final int PAYLOAD_OFFSET = 12;
    private static final int PROFILE_BYTES = 32;
    private static final int CHECKSUM_BYTES = 32;
    private static final byte[] HULL_PREFIX = "data/hulls/".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsHullJsonDeterministicallyAndAtomically() throws Exception {
        Map<String, byte[]> reversed = new LinkedHashMap<>();
        reversed.put("data/hulls/z.ship", tree("z"));
        reversed.put("data/hulls/nested/a.ship", tree("a"));
        PreparedHullJsonCache cache = new PreparedHullJsonCache(PROFILE, reversed);

        byte[] first = PreparedHullJsonCacheIO.toBytes(cache);
        byte[] second = PreparedHullJsonCacheIO.toBytes(new PreparedHullJsonCache(
                PROFILE, Map.of(
                        "data/hulls/nested/a.ship", tree("a"),
                        "data/hulls/z.ship", tree("z"))));
        assertTrue(Arrays.equals(first, second));

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
    void roundTripsValidUnicodeHullPath() throws Exception {
        PreparedHullJsonCache cache = new PreparedHullJsonCache(
                PROFILE, Map.of("data/hulls/世界.ship", tree("世界")));

        PreparedHullJsonCache decoded = PreparedHullJsonCacheIO.fromBytes(
                PreparedHullJsonCacheIO.toBytes(cache));

        assertEquals(cache.profileIdentitySha256(), decoded.profileIdentitySha256());
        assertArrayEquals(cache.entries().get("data/hulls/世界.ship"),
                decoded.entries().get("data/hulls/世界.ship"));
    }

    @Test
    void checksumValidMalformedUtf8IsRejectedAtThePathDecoder() throws Exception {
        PreparedHullJsonCache cache = new PreparedHullJsonCache(
                PROFILE, Map.of("data/hulls/�.ship", tree("replacement")));
        byte[] bytes = PreparedHullJsonCacheIO.toBytes(cache);
        int malformedAt = firstPathBytesOffset(bytes) + HULL_PREFIX.length;
        assertEquals((byte) 0xef, bytes[malformedAt]);
        assertEquals((byte) 0xbf, bytes[malformedAt + 1]);
        assertEquals((byte) 0xbd, bytes[malformedAt + 2]);

        // ED A0 80 encodes a surrogate code point and is malformed UTF-8. Re-sign the payload so
        // rejection exercises the authenticated inner string decoder instead of the checksum gate.
        bytes[malformedAt] = (byte) 0xed;
        bytes[malformedAt + 1] = (byte) 0xa0;
        bytes[malformedAt + 2] = (byte) 0x80;
        resignPayload(bytes);

        IOException error = assertThrows(IOException.class, () -> PreparedHullJsonCacheIO.fromBytes(bytes));
        assertEquals("Prepared hull cache string is not valid UTF-8", error.getMessage());
    }

    @Test
    void checksumValidNoncanonicalPathIsRejectedBeforeModelNormalization() throws Exception {
        PreparedHullJsonCache cache = new PreparedHullJsonCache(
                PROFILE, Map.of("data/hulls/a.ship", tree("a")));
        byte[] bytes = PreparedHullJsonCacheIO.toBytes(cache);
        int pathAt = firstPathBytesOffset(bytes);
        int nameAt = pathAt + HULL_PREFIX.length;
        assertEquals((byte) 'a', bytes[nameAt]);

        bytes[nameAt] = (byte) 'A';
        resignPayload(bytes);

        IOException error = assertThrows(IOException.class, () -> PreparedHullJsonCacheIO.fromBytes(bytes));
        assertEquals("Prepared hull cache path is not canonical: data/hulls/A.ship", error.getMessage());
    }

    @Test
    void writerRejectsMalformedUtf16Path() {
        PreparedHullJsonCache cache = new PreparedHullJsonCache(
                PROFILE, Map.of("data/hulls/\ud800.ship", tree("surrogate")));

        IOException error = assertThrows(IOException.class, () -> PreparedHullJsonCacheIO.toBytes(cache));
        assertEquals("Prepared hull cache string cannot be encoded as UTF-8", error.getMessage());
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
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));
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

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

class PreparedWeaponJsonCacheIOTest {
    private static final String PROFILE = "a".repeat(64);
    private static final int PAYLOAD_LENGTH_OFFSET = 8;
    private static final int PAYLOAD_OFFSET = 12;
    private static final int PROFILE_BYTES = 32;
    private static final int CHECKSUM_BYTES = 32;
    private static final byte[] WEAPON_PREFIX = "data/weapons/".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsBothWeaponDomainsDeterministicallyAndAtomically() throws Exception {
        Map<String, byte[]> reversed = new LinkedHashMap<>();
        reversed.put("data/weapons/z.wpn", tree("z"));
        reversed.put("data/shipsystems/wpn/a.wpn", tree("a"));
        PreparedWeaponJsonCache cache = new PreparedWeaponJsonCache(PROFILE, reversed);

        byte[] first = PreparedWeaponJsonCacheIO.toBytes(cache);
        byte[] second = PreparedWeaponJsonCacheIO.toBytes(new PreparedWeaponJsonCache(
                PROFILE, Map.of(
                        "data/shipsystems/wpn/a.wpn", tree("a"),
                        "data/weapons/z.wpn", tree("z"))));
        assertTrue(Arrays.equals(first, second));

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
    void roundTripsValidUnicodeWeaponPath() throws Exception {
        PreparedWeaponJsonCache cache = new PreparedWeaponJsonCache(
                PROFILE, Map.of("data/weapons/世界.wpn", tree("世界")));

        PreparedWeaponJsonCache decoded = PreparedWeaponJsonCacheIO.fromBytes(
                PreparedWeaponJsonCacheIO.toBytes(cache));

        assertEquals(cache.profileIdentitySha256(), decoded.profileIdentitySha256());
        assertArrayEquals(cache.entries().get("data/weapons/世界.wpn"),
                decoded.entries().get("data/weapons/世界.wpn"));
    }

    @Test
    void checksumValidMalformedUtf8IsRejectedAtThePathDecoder() throws Exception {
        PreparedWeaponJsonCache cache = new PreparedWeaponJsonCache(
                PROFILE, Map.of("data/weapons/�.wpn", tree("replacement")));
        byte[] bytes = PreparedWeaponJsonCacheIO.toBytes(cache);
        int malformedAt = firstPathBytesOffset(bytes) + WEAPON_PREFIX.length;
        assertEquals((byte) 0xef, bytes[malformedAt]);
        assertEquals((byte) 0xbf, bytes[malformedAt + 1]);
        assertEquals((byte) 0xbd, bytes[malformedAt + 2]);

        bytes[malformedAt] = (byte) 0xed;
        bytes[malformedAt + 1] = (byte) 0xa0;
        bytes[malformedAt + 2] = (byte) 0x80;
        resignPayload(bytes);

        IOException error = assertThrows(IOException.class, () -> PreparedWeaponJsonCacheIO.fromBytes(bytes));
        assertEquals("Prepared weapon cache string is not valid UTF-8", error.getMessage());
    }

    @Test
    void checksumValidNoncanonicalPathIsRejectedBeforeModelNormalization() throws Exception {
        PreparedWeaponJsonCache cache = new PreparedWeaponJsonCache(
                PROFILE, Map.of("data/weapons/a.wpn", tree("a")));
        byte[] bytes = PreparedWeaponJsonCacheIO.toBytes(cache);
        int pathAt = firstPathBytesOffset(bytes);
        int nameAt = pathAt + WEAPON_PREFIX.length;
        assertEquals((byte) 'a', bytes[nameAt]);

        bytes[nameAt] = (byte) 'A';
        resignPayload(bytes);

        IOException error = assertThrows(IOException.class, () -> PreparedWeaponJsonCacheIO.fromBytes(bytes));
        assertEquals("Prepared weapon cache path is not canonical: data/weapons/A.wpn", error.getMessage());
    }

    @Test
    void writerRejectsMalformedUtf16Path() {
        PreparedWeaponJsonCache cache = new PreparedWeaponJsonCache(
                PROFILE, Map.of("data/weapons/\ud800.wpn", tree("surrogate")));

        IOException error = assertThrows(IOException.class, () -> PreparedWeaponJsonCacheIO.toBytes(cache));
        assertEquals("Prepared weapon cache string cannot be encoded as UTF-8", error.getMessage());
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
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedWeaponJsonCacheIO.read(file));

        byte[] variant = PreparedVariantJsonCacheIO.toBytes(new PreparedVariantJsonCache(
                "c".repeat(64), Map.of("data/variants/a.variant", tree("a"))));
        assertThrows(IOException.class, () -> PreparedWeaponJsonCacheIO.fromBytes(variant));
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

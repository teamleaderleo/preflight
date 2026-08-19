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

class PreparedVariantJsonCacheIOTest {
    private static final String PROFILE = "a".repeat(64);
    private static final int PAYLOAD_LENGTH_OFFSET = 8;
    private static final int PAYLOAD_OFFSET = 12;
    private static final int PROFILE_BYTES = 32;
    private static final int CHECKSUM_BYTES = 32;
    private static final byte[] VARIANT_PREFIX = "data/variants/".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsDeterministicallyAndAtomically() throws Exception {
        Map<String, byte[]> reversed = new LinkedHashMap<>();
        reversed.put("data/variants/z.variant", tree("variantId", "z"));
        reversed.put("data/variants/a.variant", tree("variantId", "a"));
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache(PROFILE, reversed);

        byte[] first = PreparedVariantJsonCacheIO.toBytes(cache);
        byte[] second = PreparedVariantJsonCacheIO.toBytes(new PreparedVariantJsonCache(
                PROFILE, Map.of(
                        "data/variants/a.variant", tree("variantId", "a"),
                        "data/variants/z.variant", tree("variantId", "z"))));
        assertTrue(Arrays.equals(first, second));

        Path file = temporaryDirectory.resolve("profile.spvj");
        PreparedVariantJsonCacheIO.write(file, cache);
        PreparedVariantJsonCache read = PreparedVariantJsonCacheIO.read(file);
        assertEquals(cache.profileIdentitySha256(), read.profileIdentitySha256());
        assertArrayEquals(cache.entries().get("data/variants/a.variant"),
                read.entries().get("data/variants/a.variant"));
        assertArrayEquals(cache.entries().get("data/variants/z.variant"),
                read.entries().get("data/variants/z.variant"));
    }

    @Test
    void roundTripsValidUnicodeVariantPath() throws Exception {
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache(
                PROFILE, Map.of("data/variants/世界.variant", tree("variantId", "世界")));

        PreparedVariantJsonCache decoded = PreparedVariantJsonCacheIO.fromBytes(
                PreparedVariantJsonCacheIO.toBytes(cache));

        assertEquals(cache.profileIdentitySha256(), decoded.profileIdentitySha256());
        assertArrayEquals(cache.entries().get("data/variants/世界.variant"),
                decoded.entries().get("data/variants/世界.variant"));
    }

    @Test
    void checksumValidMalformedUtf8IsRejectedAtThePathDecoder() throws Exception {
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache(
                PROFILE, Map.of("data/variants/�.variant", tree("id", "replacement")));
        byte[] bytes = PreparedVariantJsonCacheIO.toBytes(cache);
        int malformedAt = firstPathBytesOffset(bytes) + VARIANT_PREFIX.length;
        assertEquals((byte) 0xef, bytes[malformedAt]);
        assertEquals((byte) 0xbf, bytes[malformedAt + 1]);
        assertEquals((byte) 0xbd, bytes[malformedAt + 2]);

        bytes[malformedAt] = (byte) 0xed;
        bytes[malformedAt + 1] = (byte) 0xa0;
        bytes[malformedAt + 2] = (byte) 0x80;
        resignPayload(bytes);

        IOException error = assertThrows(IOException.class, () -> PreparedVariantJsonCacheIO.fromBytes(bytes));
        assertEquals("Prepared variant cache string is not valid UTF-8", error.getMessage());
    }

    @Test
    void checksumValidNoncanonicalPathIsRejectedBeforeModelNormalization() throws Exception {
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache(
                PROFILE, Map.of("data/variants/a.variant", tree("id", "a")));
        byte[] bytes = PreparedVariantJsonCacheIO.toBytes(cache);
        int pathAt = firstPathBytesOffset(bytes);
        int nameAt = pathAt + VARIANT_PREFIX.length;
        assertEquals((byte) 'a', bytes[nameAt]);

        bytes[nameAt] = (byte) 'A';
        resignPayload(bytes);

        IOException error = assertThrows(IOException.class, () -> PreparedVariantJsonCacheIO.fromBytes(bytes));
        assertEquals("Prepared variant cache path is not canonical: data/variants/A.variant", error.getMessage());
    }

    @Test
    void writerRejectsMalformedUtf16Path() {
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache(
                PROFILE, Map.of("data/variants/\ud800.variant", tree("id", "surrogate")));

        IOException error = assertThrows(IOException.class, () -> PreparedVariantJsonCacheIO.toBytes(cache));
        assertEquals("Prepared variant cache string cannot be encoded as UTF-8", error.getMessage());
    }

    @Test
    void rejectsCorruptionWrongPathsAndWrongProfile() throws Exception {
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache(
                "b".repeat(64), Map.of("data/variants/a.variant", tree("id", "a")));
        byte[] bytes = PreparedVariantJsonCacheIO.toBytes(cache);
        bytes[bytes.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedVariantJsonCacheIO.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class, () -> new PreparedVariantJsonCache(
                "bad", Map.of("data/variants/a.variant", tree("id", "a"))));
        assertThrows(IllegalArgumentException.class, () -> new PreparedVariantJsonCache(
                "b".repeat(64), Map.of("data/weapons/a.wpn", tree("id", "a"))));
    }

    @Test
    void rejectsTruncation() throws Exception {
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache(
                "c".repeat(64), Map.of("data/variants/a.variant", tree("id", "a")));
        byte[] bytes = PreparedVariantJsonCacheIO.toBytes(cache);
        Path file = temporaryDirectory.resolve("truncated.spvj");
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedVariantJsonCacheIO.read(file));
    }

    @Test
    void rejectsTheTextBasedVersionOneFormatInsteadOfTreatingItAsATree() throws Exception {
        PreparedVariantJsonCache cache = new PreparedVariantJsonCache(
                "d".repeat(64), Map.of("data/variants/a.variant", tree("id", "a")));
        byte[] bytes = PreparedVariantJsonCacheIO.toBytes(cache);
        // Magic occupies bytes 0..3 and the version is the following big-endian int.
        bytes[7] = 1;
        assertThrows(IOException.class, () -> PreparedVariantJsonCacheIO.fromBytes(bytes));
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

    private static byte[] tree(String key, String value) {
        return JsonTree.encode(Map.of(key, value));
    }
}

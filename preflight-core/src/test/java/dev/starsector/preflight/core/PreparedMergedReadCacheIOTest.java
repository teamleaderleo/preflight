package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedMergedReadCacheIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsDeterministicallyAndAtomically() throws Exception {
        String csv = MergedReadKey.csv("data/hulls/ship_data.csv", true, false, List.of("id"));
        String json = MergedReadKey.json("data/config/engine_styles.json", List.of());
        byte[] csvTree = JsonTree.encode(List.of(Map.of("id", "wolf")));
        byte[] jsonTree = JsonTree.encode(Map.of("volume", 0.8d));
        Map<String, byte[]> reversed = new LinkedHashMap<>();
        reversed.put(json, jsonTree);
        reversed.put(csv, csvTree);
        PreparedMergedReadCache cache = new PreparedMergedReadCache("a".repeat(64), reversed);

        byte[] first = PreparedMergedReadCacheIO.toBytes(cache);
        byte[] second = PreparedMergedReadCacheIO.toBytes(new PreparedMergedReadCache(
                "a".repeat(64), Map.of(csv, csvTree, json, jsonTree)));
        assertTrue(Arrays.equals(first, second));

        Path file = temporaryDirectory.resolve("profile.spmr");
        PreparedMergedReadCacheIO.write(file, cache);
        PreparedMergedReadCache restored = PreparedMergedReadCacheIO.read(file);
        assertEquals(cache.profileIdentitySha256(), restored.profileIdentitySha256());
        assertEquals(cache.entries().keySet(), restored.entries().keySet());
        for (String key : cache.entries().keySet()) {
            assertTrue(Arrays.equals(cache.entries().get(key), restored.entries().get(key)));
        }
    }

    @Test
    void rejectsGrowthDuringTheActualStreamRead() throws Exception {
        String key = MergedReadKey.json("data/config/engine_styles.json", List.of());
        byte[] bytes = PreparedMergedReadCacheIO.toBytes(new PreparedMergedReadCache(
                "e".repeat(64), Map.of(key, JsonTree.encode(Map.of("value", true)))));
        Path file = temporaryDirectory.resolve("growing.spmr");
        Files.write(file, bytes);
        boolean[] appended = {false};

        try (InputStream raw = Files.newInputStream(file);
             InputStream growing = new FilterInputStream(raw) {
                 @Override
                 public int read(byte[] buffer, int offset, int length) throws IOException {
                     int requested = appended[0] ? length : Math.min(1, length);
                     int read = super.read(buffer, offset, requested);
                     if (!appended[0] && read > 0) {
                         Files.write(file, new byte[] {0x55}, StandardOpenOption.APPEND);
                         appended[0] = true;
                     }
                     return read;
                 }
             }) {
            IOException error = assertThrows(
                    IOException.class,
                    () -> PreparedMergedReadCacheIO.read(growing, bytes.length, file.toString()));
            assertTrue(appended[0]);
            assertTrue(error.getMessage().contains("byte safety limit"), error.getMessage());
        }
        assertEquals(bytes.length + 1L, Files.size(file));
    }

    @Test
    void initialSizePrefilterRejectsBeforeTheBoundedRead() throws Exception {
        String key = MergedReadKey.json("data/config/engine_styles.json", List.of());
        byte[] bytes = PreparedMergedReadCacheIO.toBytes(new PreparedMergedReadCache(
                "f".repeat(64), Map.of(key, JsonTree.encode(Map.of("value", true)))));
        Path file = temporaryDirectory.resolve("already-too-large.spmr");
        Files.write(file, bytes);

        IOException error = assertThrows(
                IOException.class,
                () -> PreparedMergedReadCacheIO.read(file, bytes.length - 1));

        assertTrue(error.getMessage().contains("size is invalid"), error.getMessage());
    }

    @Test
    void rejectsChecksumValidMalformedUtf8Keys() throws Exception {
        String key = MergedReadKey.json("data/config/engine_styles.json", List.of());
        PreparedMergedReadCache cache = new PreparedMergedReadCache(
                "c".repeat(64), Map.of(key, JsonTree.encode(Map.of("value", true))));
        byte[] bytes = PreparedMergedReadCacheIO.toBytes(cache);
        byte[] encodedKey = key.getBytes(StandardCharsets.UTF_8);
        int keyOffset = indexOf(bytes, encodedKey);
        assertTrue(keyOffset >= 0);

        bytes[keyOffset + key.indexOf("engine_styles")] = (byte) 0x80;
        refreshChecksum(bytes);

        IOException error = assertThrows(IOException.class, () -> PreparedMergedReadCacheIO.fromBytes(bytes));
        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
    }

    @Test
    void refusesKeysThatCannotBeEncodedAsUtf8() throws Exception {
        String key = MergedReadKey.json("data/config/" + (char) 0xd800 + ".json", List.of());
        assertTrue(MergedReadKey.wellFormed(key));
        PreparedMergedReadCache cache = new PreparedMergedReadCache(
                "d".repeat(64), Map.of(key, JsonTree.encode(Map.of("value", true))));

        IOException error = assertThrows(IOException.class, () -> PreparedMergedReadCacheIO.toBytes(cache));
        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
    }

    @Test
    void rejectsCorruptionTruncationAndInvalidRecords() throws Exception {
        String key = MergedReadKey.json("data/config/engine_styles.json", List.of());
        PreparedMergedReadCache cache = new PreparedMergedReadCache(
                "b".repeat(64), Map.of(key, JsonTree.encode(Map.of("value", true))));
        byte[] bytes = PreparedMergedReadCacheIO.toBytes(cache);

        byte[] corrupt = bytes.clone();
        corrupt[corrupt.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedMergedReadCacheIO.fromBytes(corrupt));

        Path truncated = temporaryDirectory.resolve("truncated.spmr");
        Files.write(truncated, Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedMergedReadCacheIO.read(truncated));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedMergedReadCache("bad", Map.of(key, new byte[] {0})));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedMergedReadCache("b".repeat(64), Map.of("not-a-key", new byte[] {0})));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedMergedReadCache("b".repeat(64), Map.of(key, new byte[0])));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
            boolean match = true;
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return offset;
            }
        }
        return -1;
    }

    private static void refreshChecksum(byte[] bytes) {
        int payloadLength = ByteBuffer.wrap(bytes).getInt(8);
        byte[] checksum = Hashes.sha256Bytes(Arrays.copyOfRange(bytes, 12, 12 + payloadLength));
        System.arraycopy(checksum, 0, bytes, 12 + payloadLength, checksum.length);
    }
}

package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PreparedAudioManifestIOTest {
    private static final int FILE_HEADER_BYTES = 12;
    private static final int CHECKSUM_BYTES = 32;

    @Test
    void rejectsChecksumValidNoncanonicalPersistedPaths() throws Exception {
        PreparedAudioManifest.Entry entry = PreparedAudioManifest.Entry.ineligible(
                "sounds/property/effect.ogg",
                "1".repeat(64),
                123,
                456,
                PreparedAudio.Policy.STREAMED);
        PreparedAudioManifest manifest = new PreparedAudioManifest(
                "2".repeat(64),
                "3".repeat(64),
                "4".repeat(64),
                Map.of(entry.logicalPath(), entry));
        byte[] bytes = PreparedAudioManifestIO.toBytes(manifest);
        byte[] encodedPath = entry.logicalPath().getBytes(StandardCharsets.UTF_8);
        int pathOffset = indexOf(bytes, encodedPath);
        assertTrue(pathOffset >= 0);

        bytes[pathOffset] = (byte) 'S';
        refreshChecksum(bytes);

        IOException error = assertThrows(IOException.class, () -> PreparedAudioManifestIO.fromBytes(bytes));
        assertTrue(error.getMessage().contains("canonical"), error.getMessage());
    }

    @Test
    void refusesPathsThatCannotBeEncodedAsUtf8() {
        PreparedAudioManifest.Entry entry = PreparedAudioManifest.Entry.ineligible(
                "sounds/property/" + (char) 0xd800 + ".ogg",
                "1".repeat(64),
                123,
                456,
                PreparedAudio.Policy.STREAMED);
        PreparedAudioManifest manifest = new PreparedAudioManifest(
                "2".repeat(64),
                "3".repeat(64),
                "4".repeat(64),
                Map.of(entry.logicalPath(), entry));

        IOException error = assertThrows(IOException.class, () -> PreparedAudioManifestIO.toBytes(manifest));
        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
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

    private static void refreshChecksum(byte[] encoded) {
        int payloadLength = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).getInt(8);
        byte[] payload = Arrays.copyOfRange(encoded, FILE_HEADER_BYTES, FILE_HEADER_BYTES + payloadLength);
        byte[] checksum = Hashes.sha256Bytes(payload);
        System.arraycopy(checksum, 0, encoded, encoded.length - CHECKSUM_BYTES, CHECKSUM_BYTES);
    }
}

package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedTexturePackOrderUtf8CanonicalTest {
    private static final int PAYLOAD_LENGTH_OFFSET = 8;
    private static final int PAYLOAD_OFFSET = 12;
    private static final int CHECKSUM_BYTES = 32;

    @TempDir
    Path temporaryDirectory;

    @Test
    void checksumValidMalformedUtf8PathCannotBecomeAcceptedOrderEntry() throws Exception {
        String profile = "ab".repeat(32);
        String relative = "blobs/\ufffd.spft";
        Path valid = temporaryDirectory.resolve("valid-malformed-utf8.spfo");
        PreparedTexturePackOrderIO.write(valid, profile, List.of(relative));
        assertEquals(List.of(relative), PreparedTexturePackOrderIO.read(valid, profile));

        byte[] changed = Files.readAllBytes(valid);
        int pathOffset = firstPathBytesOffset(changed);
        byte[] encoded = relative.getBytes(StandardCharsets.UTF_8);
        int replacementOffset = indexOf(encoded, new byte[] {(byte) 0xef, (byte) 0xbf, (byte) 0xbd});
        assertEquals(true, replacementOffset >= 0);

        changed[pathOffset + replacementOffset] = (byte) 0xed;
        changed[pathOffset + replacementOffset + 1] = (byte) 0xa0;
        changed[pathOffset + replacementOffset + 2] = (byte) 0x80;
        resignPayload(changed);

        Path candidate = temporaryDirectory.resolve("malformed-utf8.spfo");
        Files.write(candidate, changed);
        assertThrows(IOException.class, () -> PreparedTexturePackOrderIO.read(candidate, profile));
    }

    @Test
    void checksumValidNoncanonicalRelativePathCannotNormalizeIntoAcceptedEntry() throws Exception {
        String profile = "cd".repeat(32);
        String relative = "blobs/aa.spft";
        Path valid = temporaryDirectory.resolve("valid-noncanonical.spfo");
        PreparedTexturePackOrderIO.write(valid, profile, List.of(relative));

        byte[] changed = Files.readAllBytes(valid);
        int pathOffset = firstPathBytesOffset(changed);
        byte[] encoded = relative.getBytes(StandardCharsets.UTF_8);
        int slash = indexOf(encoded, new byte[] {'/'});
        assertEquals(true, slash >= 0);

        changed[pathOffset + slash] = (byte) '\\';
        resignPayload(changed);

        Path candidate = temporaryDirectory.resolve("noncanonical.spfo");
        Files.write(candidate, changed);
        assertThrows(IOException.class, () -> PreparedTexturePackOrderIO.read(candidate, profile));
    }

    @Test
    void writerRejectsMalformedUtf16Strings() {
        assertThrows(
                IOException.class,
                () -> PreparedTexturePackOrderIO.write(
                        temporaryDirectory.resolve("malformed-profile.spfo"),
                        "profile-\ud800",
                        List.of("blobs/aa.spft")));
        assertThrows(
                IOException.class,
                () -> PreparedTexturePackOrderIO.write(
                        temporaryDirectory.resolve("malformed-path.spfo"),
                        "ef".repeat(32),
                        List.of("blobs/\ud800.spft")));
    }

    private static int firstPathBytesOffset(byte[] bytes) {
        int profileLength = intAt(bytes, PAYLOAD_OFFSET);
        int countOffset = PAYLOAD_OFFSET + Integer.BYTES + profileLength;
        assertEquals(1, intAt(bytes, countOffset));
        int pathLengthOffset = countOffset + Integer.BYTES;
        int pathLength = intAt(bytes, pathLengthOffset);
        if (pathLength <= 0) {
            throw new IllegalStateException("fixture path is empty");
        }
        return pathLengthOffset + Integer.BYTES;
    }

    private static int indexOf(byte[] bytes, byte[] needle) {
        for (int index = 0; index <= bytes.length - needle.length; index++) {
            if (Arrays.equals(Arrays.copyOfRange(bytes, index, index + needle.length), needle)) {
                return index;
            }
        }
        return -1;
    }

    private static void resignPayload(byte[] bytes) {
        int payloadLength = intAt(bytes, PAYLOAD_LENGTH_OFFSET);
        byte[] payload = Arrays.copyOfRange(bytes, PAYLOAD_OFFSET, PAYLOAD_OFFSET + payloadLength);
        byte[] checksum = Hashes.sha256Bytes(payload);
        System.arraycopy(
                checksum,
                0,
                bytes,
                PAYLOAD_OFFSET + payloadLength,
                CHECKSUM_BYTES);
    }

    private static int intAt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(offset);
    }
}

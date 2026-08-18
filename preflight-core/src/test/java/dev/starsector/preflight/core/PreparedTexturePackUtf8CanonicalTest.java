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

class PreparedTexturePackUtf8CanonicalTest {
    private static final int PACK_INDEX_LENGTH_OFFSET = 8;
    private static final int PACK_INDEX_CHECKSUM_OFFSET = 20;
    private static final int PACK_INDEX_OFFSET = 52;
    private static final int CHECKSUM_BYTES = 32;

    @TempDir
    Path temporaryDirectory;

    @Test
    void checksumValidMalformedUtf8PathCannotBecomeAcceptedEntry() throws Exception {
        String relative = "blobs/\ufffd.spft";
        PackFixture fixture = fixture("malformed-utf8", relative);
        byte[] changed = Files.readAllBytes(fixture.pack());
        int pathOffset = firstPathBytesOffset(changed);
        byte[] encoded = relative.getBytes(StandardCharsets.UTF_8);
        int replacementOffset = indexOf(encoded, new byte[] {(byte) 0xef, (byte) 0xbf, (byte) 0xbd});
        assertEquals(true, replacementOffset >= 0);

        // ED A0 80 is the UTF-8 encoding of a surrogate code point. Java's permissive UTF-8
        // replacement decoder turns the whole malformed sequence into one U+FFFD, so before the
        // strict decoder this authenticated corruption became the exact expected logical path.
        changed[pathOffset + replacementOffset] = (byte) 0xed;
        changed[pathOffset + replacementOffset + 1] = (byte) 0xa0;
        changed[pathOffset + replacementOffset + 2] = (byte) 0x80;
        resignPackIndex(changed);

        Path candidate = temporaryDirectory.resolve("malformed-utf8.spfp");
        Files.write(candidate, changed);
        assertThrows(
                IOException.class,
                () -> PreparedTexturePackIO.open(candidate, fixture.profile(), List.of(relative)));
    }

    @Test
    void checksumValidNoncanonicalRelativePathCannotNormalizeIntoAcceptedEntry() throws Exception {
        String relative = "blobs/aa.spft";
        PackFixture fixture = fixture("noncanonical-path", relative);
        byte[] changed = Files.readAllBytes(fixture.pack());
        int pathOffset = firstPathBytesOffset(changed);
        byte[] encoded = relative.getBytes(StandardCharsets.UTF_8);
        int slash = indexOf(encoded, new byte[] {'/'});
        assertEquals(true, slash >= 0);

        changed[pathOffset + slash] = '\\';
        resignPackIndex(changed);

        Path candidate = temporaryDirectory.resolve("noncanonical-path.spfp");
        Files.write(candidate, changed);
        assertThrows(
                IOException.class,
                () -> PreparedTexturePackIO.open(candidate, fixture.profile(), List.of(relative)));
    }

    @Test
    void writerRejectsMalformedUtf16ProfileIdentity() throws Exception {
        Path cache = temporaryDirectory.resolve("writer-cache");
        String relative = "blobs/aa.spft";
        writeTexture(cache.resolve(relative));

        assertThrows(
                IllegalArgumentException.class,
                () -> PreparedTexturePackIO.write(
                        cache.resolve("malformed-profile.spfp"),
                        "profile-\ud800",
                        cache,
                        List.of(relative)));
    }

    private PackFixture fixture(String name, String relative) throws Exception {
        Path cache = temporaryDirectory.resolve(name + "-cache");
        writeTexture(cache.resolve(relative));
        String profile = "ab".repeat(32);
        Path pack = cache.resolve(name + ".spfp");
        PreparedTexturePackIO.write(pack, profile, cache, List.of(relative));
        try (PreparedTexturePack ignored = PreparedTexturePackIO.open(pack, profile, List.of(relative))) {
            // Valid Unicode/canonical input remains accepted before the authenticated mutation.
        }
        return new PackFixture(pack, profile);
    }

    private static void writeTexture(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        PreparedTexture texture = new PreparedTexture(
                "cd".repeat(32),
                PreparedTexture.Transformation.IDENTITY,
                1,
                1,
                1,
                1,
                4,
                0,
                0,
                0,
                new byte[] {1, 2, 3, 4});
        PreparedTextureIO.write(path, texture, PreparedTextureIO.StorageCodec.RAW);
    }

    private static int firstPathBytesOffset(byte[] pack) {
        int profileLength = intAt(pack, PACK_INDEX_OFFSET);
        int countOffset = PACK_INDEX_OFFSET + Integer.BYTES + profileLength;
        assertEquals(1, intAt(pack, countOffset));
        int pathLengthOffset = countOffset + Integer.BYTES;
        int pathLength = intAt(pack, pathLengthOffset);
        if (pathLength <= 0) {
            throw new IllegalStateException("fixture path is empty");
        }
        return pathLengthOffset + Integer.BYTES;
    }

    private static int indexOf(byte[] bytes, byte[] needle) {
        for (int index = 0; index <= bytes.length - needle.length; index++) {
            if (Arrays.equals(
                    Arrays.copyOfRange(bytes, index, index + needle.length),
                    needle)) {
                return index;
            }
        }
        return -1;
    }

    private static void resignPackIndex(byte[] bytes) {
        int indexLength = intAt(bytes, PACK_INDEX_LENGTH_OFFSET);
        byte[] index = Arrays.copyOfRange(bytes, PACK_INDEX_OFFSET, PACK_INDEX_OFFSET + indexLength);
        byte[] checksum = Hashes.sha256Bytes(index);
        System.arraycopy(checksum, 0, bytes, PACK_INDEX_CHECKSUM_OFFSET, CHECKSUM_BYTES);
    }

    private static int intAt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(offset);
    }

    private record PackFixture(Path pack, String profile) {
    }
}

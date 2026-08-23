package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedTextureIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsAndWritesDeterministically() throws Exception {
        PreparedTexture texture = fixture();
        byte[] first = PreparedTextureIO.toBytes(texture);
        byte[] second = PreparedTextureIO.toBytes(fixture());
        assertArrayEquals(first, second);

        Path output = temporaryDirectory.resolve("nested/example.spft");
        PreparedTextureIO.write(output, texture);
        PreparedTexture restored = PreparedTextureIO.read(output);

        assertEquals(texture, restored);
        assertTrue(Files.isRegularFile(output));
    }

    @Test
    void packIntermediateIsCheckedAndNeverReplacesAnExistingBlob() throws Exception {
        PreparedTexture texture = fixture();
        Path output = temporaryDirectory.resolve("intermediate/example-lz4.spft");

        PreparedTextureIO.writePackIntermediate(
                output, texture, PreparedTextureIO.StorageCodec.LZ4);

        assertEquals(texture, PreparedTextureIO.read(output));
        assertThrows(
                IOException.class,
                () -> PreparedTextureIO.writePackIntermediate(
                        output, texture, PreparedTextureIO.StorageCodec.LZ4));
        assertEquals(texture, PreparedTextureIO.read(output));
    }

    @Test
    void verifiedReadRejectsGrowthDuringTheActualStreamRead() throws Exception {
        byte[] bytes = PreparedTextureIO.toBytes(fixture());
        Path file = temporaryDirectory.resolve("growing.spft");
        Files.write(file, bytes);
        boolean[] appended = {false};

        try (InputStream exact = Files.newInputStream(file)) {
            assertEquals(fixture(), PreparedTextureIO.read(exact, bytes.length, file.toString()));
        }

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
                    () -> PreparedTextureIO.read(growing, bytes.length, file.toString()));
            assertTrue(appended[0]);
            assertTrue(error.getMessage().contains("byte safety limit"), error.getMessage());
        }
        assertEquals(bytes.length + 1L, Files.size(file));
    }

    @Test
    void roundTripsLosslessLz4StorageAndTrustedRead() throws Exception {
        byte[] pixels = new byte[64 * 1024];
        Arrays.fill(pixels, (byte) 0x5a);
        PreparedTexture texture = new PreparedTexture(
                "cd".repeat(32),
                PreparedTexture.Transformation.IDENTITY,
                128,
                128,
                128,
                128,
                4,
                PreparedTexture.rgba(1, 2, 3, 255),
                PreparedTexture.rgba(4, 5, 6, 255),
                PreparedTexture.rgba(7, 8, 9, 255),
                pixels);

        byte[] raw = PreparedTextureIO.toBytes(texture);
        byte[] compressed = PreparedTextureIO.toBytes(texture, PreparedTextureIO.StorageCodec.LZ4);
        assertTrue(compressed.length < raw.length / 10);
        assertEquals(texture, PreparedTextureIO.fromBytes(compressed));

        Path output = temporaryDirectory.resolve("compressed-lz4.spft");
        PreparedTextureIO.write(output, texture, PreparedTextureIO.StorageCodec.LZ4);
        assertEquals(compressed.length - 120L, PreparedTextureIO.storedPixelBytes(output));
        assertEquals(texture, PreparedTextureIO.readTrusted(output));

        // Grow this thread's trusted-read scratch, then reuse it for the smaller blob above. The
        // decompressor must consume the encoded length, not stale bytes in the retained capacity.
        byte[] largerPixels = new byte[256 * 256 * 4];
        Arrays.fill(largerPixels, (byte) 0x37);
        PreparedTexture larger = new PreparedTexture(
                "ef".repeat(32),
                PreparedTexture.Transformation.IDENTITY,
                256,
                256,
                256,
                256,
                4,
                PreparedTexture.rgba(1, 2, 3, 255),
                PreparedTexture.rgba(4, 5, 6, 255),
                PreparedTexture.rgba(7, 8, 9, 255),
                largerPixels);
        Path largerOutput = temporaryDirectory.resolve("larger-compressed-lz4.spft");
        PreparedTextureIO.write(largerOutput, larger, PreparedTextureIO.StorageCodec.LZ4);
        assertEquals(larger, PreparedTextureIO.readTrusted(largerOutput));
        assertEquals(texture, PreparedTextureIO.readTrusted(output));

        Path wrongCodec = temporaryDirectory.resolve("raw-with-wrong-suffix-lz4.spft");
        PreparedTextureIO.write(wrongCodec, texture, PreparedTextureIO.StorageCodec.RAW);
        IOException rejected = assertThrows(
                IOException.class, () -> PreparedTextureIO.readTrusted(wrongCodec));
        assertTrue(rejected.getMessage().contains("LZ4 payload metadata"));
    }

    @Test
    void reportsAnExactRawAndSafeLz4WriteCeiling() throws Exception {
        PreparedTexture texture = fixture();

        assertEquals(
                PreparedTextureIO.toBytes(texture, PreparedTextureIO.StorageCodec.RAW).length,
                PreparedTextureIO.maximumFileBytes(
                        texture.pixelBytes(), PreparedTextureIO.StorageCodec.RAW));
        assertTrue(PreparedTextureIO.maximumFileBytes(
                        texture.pixelBytes(), PreparedTextureIO.StorageCodec.LZ4)
                >= PreparedTextureIO.toBytes(texture, PreparedTextureIO.StorageCodec.LZ4).length);
    }

    @Test
    void rejectsCorruptionAndTruncation() throws Exception {
        byte[] bytes = PreparedTextureIO.toBytes(fixture());
        byte[] corrupt = bytes.clone();
        corrupt[corrupt.length / 2] ^= 0x44;
        IOException checksum = assertThrows(IOException.class, () -> PreparedTextureIO.fromBytes(corrupt));
        assertTrue(checksum.getMessage().contains("checksum"));

        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 7);
        assertThrows(IOException.class, () -> PreparedTextureIO.fromBytes(truncated));
    }

    @Test
    void trustedReadStillRejectsMalformedStructureButSkipsPayloadChecksum() throws Exception {
        byte[] bytes = PreparedTextureIO.toBytes(fixture());
        // The checksum is the final 32 bytes, so this changes the last pixel while preserving every
        // structural field and the total file length.
        bytes[bytes.length - 32 - 1] ^= 0x44;
        Path sameLengthCorruption = temporaryDirectory.resolve("same-length-corruption.spft");
        Files.write(sameLengthCorruption, bytes);

        assertThrows(IOException.class, () -> PreparedTextureIO.read(sameLengthCorruption));
        PreparedTexture trusted = PreparedTextureIO.readTrusted(sameLengthCorruption);
        assertEquals(8, trusted.pixelBytes());

        Path truncated = temporaryDirectory.resolve("truncated.spft");
        Files.write(truncated, Arrays.copyOf(bytes, bytes.length - 7));
        assertThrows(IOException.class, () -> PreparedTextureIO.readTrusted(truncated));
    }

    private static PreparedTexture fixture() {
        return new PreparedTexture(
                "ab".repeat(32),
                PreparedTexture.Transformation.IDENTITY,
                2,
                1,
                2,
                1,
                4,
                PreparedTexture.rgba(1, 2, 3, 255),
                PreparedTexture.rgba(4, 5, 6, 255),
                PreparedTexture.rgba(7, 8, 9, 255),
                new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
    }
}

package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedTexturePackIntegrityTest {
    private static final int PACK_INDEX_LENGTH_OFFSET = 8;
    private static final int PACK_FIXED_HEADER_BYTES = 4 + Integer.BYTES * 2 + Long.BYTES + 32;
    private static final int SPFT_PREFIX_BYTES = 4 + Integer.BYTES * 2;
    private static final int SPFT_PAYLOAD_FIXED_BYTES = 32 + Integer.BYTES * 11;

    @TempDir
    Path temporaryDirectory;

    @Test
    void sameLengthPackedRawPixelMutationRejectsWhileLooseBlobRemainsValid() throws Exception {
        Fixture fixture = fixture("raw.spft", PreparedTextureIO.StorageCodec.RAW);
        byte[] packBytes = Files.readAllBytes(fixture.pack());
        int embedded = embeddedSpftOffset(packBytes);
        int firstStoredPixel = embedded + SPFT_PREFIX_BYTES + SPFT_PAYLOAD_FIXED_BYTES;
        packBytes[firstStoredPixel] ^= 0x01;
        Files.write(fixture.pack(), packBytes);

        try (PreparedTexturePack pack = PreparedTexturePackIO.open(
                fixture.pack(), fixture.profile(), List.of(fixture.relative()))) {
            assertThrows(IOException.class, () -> pack.readTrusted(fixture.relative()));
        }
        assertArrayEquals(
                fixture.texture().pixels(),
                PreparedTextureIO.read(fixture.cache().resolve(fixture.relative())).pixels());
    }

    @Test
    void checksumValidIndexCannotAuthorizeCorruptedEmbeddedMetadata() throws Exception {
        Fixture fixture = fixture("metadata-lz4.spft", PreparedTextureIO.StorageCodec.LZ4);
        byte[] packBytes = Files.readAllBytes(fixture.pack());
        int embedded = embeddedSpftOffset(packBytes);
        int color0 = embedded + SPFT_PREFIX_BYTES + 32 + Integer.BYTES * 6;
        packBytes[color0 + Integer.BYTES - 1] ^= 0x01;
        Files.write(fixture.pack(), packBytes);

        try (PreparedTexturePack pack = PreparedTexturePackIO.open(
                fixture.pack(), fixture.profile(), List.of(fixture.relative()))) {
            assertThrows(IOException.class, () -> pack.readTrusted(fixture.relative()));
        }
    }

    @Test
    void corruptedEmbeddedSpftChecksumRejectsEvenWhenPayloadIsUnchanged() throws Exception {
        Fixture fixture = fixture("checksum.spft", PreparedTextureIO.StorageCodec.RAW);
        byte[] packBytes = Files.readAllBytes(fixture.pack());
        packBytes[packBytes.length - 1] ^= 0x40;
        Files.write(fixture.pack(), packBytes);

        try (PreparedTexturePack pack = PreparedTexturePackIO.open(
                fixture.pack(), fixture.profile(), List.of(fixture.relative()))) {
            assertThrows(IOException.class, () -> pack.readTrusted(fixture.relative()));
        }
    }

    private Fixture fixture(String fileName, PreparedTextureIO.StorageCodec codec) throws Exception {
        Path cache = temporaryDirectory.resolve(fileName + "-cache");
        String relative = "blobs/01/" + fileName;
        Files.createDirectories(cache.resolve("blobs/01"));
        PreparedTexture texture = new PreparedTexture(
                "01".repeat(32),
                PreparedTexture.Transformation.IDENTITY,
                2,
                2,
                2,
                2,
                3,
                0x01020304,
                0x05060708,
                0x090a0b0c,
                new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        PreparedTextureIO.write(cache.resolve(relative), texture, codec);
        String profile = "ab".repeat(32);
        Path pack = PreparedTexturePackIO.path(cache, profile);
        PreparedTexturePackIO.write(pack, profile, cache, List.of(relative));
        return new Fixture(cache, profile, relative, pack, texture);
    }

    private static int embeddedSpftOffset(byte[] packBytes) {
        int indexLength = ByteBuffer.wrap(packBytes)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt(PACK_INDEX_LENGTH_OFFSET);
        return Math.addExact(PACK_FIXED_HEADER_BYTES, indexLength);
    }

    private record Fixture(
            Path cache,
            String profile,
            String relative,
            Path pack,
            PreparedTexture texture) {}
}

package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
        int firstStoredPixel = firstStoredPixelOffset(fixture.pack());
        flipByte(fixture.pack(), firstStoredPixel, 0x01);

        try (PreparedTexturePack pack = PreparedTexturePackIO.open(
                fixture.pack(), fixture.profile(), List.of(fixture.relative()))) {
            assertThrows(IOException.class, () -> pack.readTrusted(fixture.relative()));
        }
        assertArrayEquals(
                fixture.texture().pixels(),
                PreparedTextureIO.read(fixture.cache().resolve(fixture.relative())).pixels());
    }

    @Test
    void alreadyCorruptLooseBlobIsRefusedDuringPacking() throws Exception {
        Path cache = temporaryDirectory.resolve("prepack-corrupt-cache");
        String relative = "blobs/01/prepack-corrupt.spft";
        Path loose = cache.resolve(relative);
        Files.createDirectories(loose.getParent());
        PreparedTexture texture = texture();
        PreparedTextureIO.write(loose, texture, PreparedTextureIO.StorageCodec.RAW);

        flipByte(loose, SPFT_PREFIX_BYTES + SPFT_PAYLOAD_FIXED_BYTES, 0x01);
        assertThrows(IOException.class, () -> PreparedTextureIO.read(loose));

        String profile = "cd".repeat(32);
        Path packPath = PreparedTexturePackIO.path(cache, profile);
        assertThrows(
                IOException.class,
                () -> PreparedTexturePackIO.write(packPath, profile, cache, List.of(relative)));
    }

    @Test
    void mutationAfterPackOpenIsRejectedAtServingBoundary() throws Exception {
        Fixture fixture = fixture("post-open.spft", PreparedTextureIO.StorageCodec.RAW);
        int firstStoredPixel = firstStoredPixelOffset(fixture.pack());

        try (PreparedTexturePack pack = PreparedTexturePackIO.open(
                fixture.pack(), fixture.profile(), List.of(fixture.relative()))) {
            flipByte(fixture.pack(), firstStoredPixel, 0x04);
            assertThrows(IOException.class, () -> pack.readTrusted(fixture.relative()));
        }
    }

    @Test
    void corruptedEmbeddedLz4ChecksumRejects() throws Exception {
        Fixture fixture = fixture("checksum-lz4.spft", PreparedTextureIO.StorageCodec.LZ4);
        long finalByte = Files.size(fixture.pack()) - 1;
        flipByte(fixture.pack(), finalByte, 0x40);

        try (PreparedTexturePack pack = PreparedTexturePackIO.open(
                fixture.pack(), fixture.profile(), List.of(fixture.relative()))) {
            assertThrows(IOException.class, () -> pack.readTrusted(fixture.relative()));
        }
    }

    private Fixture fixture(String fileName, PreparedTextureIO.StorageCodec codec) throws Exception {
        Path cache = temporaryDirectory.resolve(fileName + "-cache");
        String relative = "blobs/01/" + fileName;
        Files.createDirectories(cache.resolve("blobs/01"));
        PreparedTexture texture = texture();
        PreparedTextureIO.write(cache.resolve(relative), texture, codec);
        String profile = "ab".repeat(32);
        Path pack = PreparedTexturePackIO.path(cache, profile);
        PreparedTexturePackIO.write(pack, profile, cache, List.of(relative));
        return new Fixture(cache, profile, relative, pack, texture);
    }

    private static PreparedTexture texture() {
        return new PreparedTexture(
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
    }

    private static int firstStoredPixelOffset(Path pack) throws IOException {
        byte[] bytes = Files.readAllBytes(pack);
        int indexLength = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt(PACK_INDEX_LENGTH_OFFSET);
        int embedded = Math.addExact(PACK_FIXED_HEADER_BYTES, indexLength);
        return Math.addExact(embedded, SPFT_PREFIX_BYTES + SPFT_PAYLOAD_FIXED_BYTES);
    }

    private static void flipByte(Path path, long offset, int bit) throws IOException {
        ByteBuffer one = ByteBuffer.allocate(1);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            if (channel.read(one, offset) != 1) {
                throw new IOException("fixture byte could not be read");
            }
            one.flip();
            byte changed = (byte) (one.get() ^ bit);
            one.clear();
            one.put(changed).flip();
            while (one.hasRemaining()) {
                if (channel.write(one, offset) <= 0) {
                    throw new IOException("fixture byte could not be written");
                }
            }
        }
    }

    private record Fixture(
            Path cache,
            String profile,
            String relative,
            Path pack,
            PreparedTexture texture) {}
}

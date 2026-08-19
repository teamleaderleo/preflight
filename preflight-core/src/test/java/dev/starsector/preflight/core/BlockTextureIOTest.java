package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockTextureIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsAndWritesDeterministically() throws Exception {
        BlockTexture texture = fixture(64, 64, true);
        assertArrayEquals(BlockTextureIO.toBytes(texture),
                BlockTextureIO.toBytes(fixture(64, 64, true)));

        Path output = temporaryDirectory.resolve("nested/example.spfb");
        BlockTextureIO.write(output, texture);

        assertEquals(texture, BlockTextureIO.read(output));
        assertTrue(Files.isRegularFile(output));
    }

    @Test
    void carriesTheBakeTimeFidelityMeasurementThroughTheBlob() throws Exception {
        // The measurement is the reason this cache is allowed to be lossy at all, so losing it in
        // serialisation would leave a blob nobody can defend after the fact.
        BlockTexture texture = fixture(16, 16, false);
        TextureFidelity.Report restored = BlockTextureIO.fromBytes(BlockTextureIO.toBytes(texture)).fidelity();

        assertEquals(texture.fidelity(), restored);
        assertEquals(texture.fidelity().meanDeltaE(), restored.meanDeltaE());
        assertEquals(texture.fidelity().maxAlphaError(), restored.maxAlphaError());
    }

    @Test
    void keepsAWholeMipChainAtTheRightSizePerLevel() throws Exception {
        BlockTexture texture = BlockTextureBaker.bake(
                Hashes.sha256(new byte[] {7}), gradient(32, 32, false), 32, 32, BlockTextureBaker.Mips.FULL_CHAIN);

        assertEquals(6, texture.levelCount());
        BlockTexture restored = BlockTextureIO.fromBytes(BlockTextureIO.toBytes(texture));
        for (int level = 0; level < texture.levelCount(); level++) {
            assertArrayEquals(texture.level(level), restored.level(level), "level " + level);
            assertEquals(32 >> level, restored.levelWidth(level));
        }
    }

    @Test
    void refusesTheOtherCachesBlobs() throws Exception {
        // SPFT and SPFB have the same shape and different guarantees: one is exactly what the game's
        // loader would have produced, the other is deliberately lossy. Reading one as the other has no
        // visible symptom beyond a game that looks slightly worse, so the magic has to catch it.
        PreparedTexture prepared = new PreparedTexture(
                Hashes.sha256(new byte[] {1}),
                PreparedTexture.Transformation.IDENTITY,
                4, 4, 4, 4, 4, 0, 0, 0,
                new byte[4 * 4 * 4]);
        byte[] otherCache = PreparedTextureIO.toBytes(prepared);

        IOException error = assertThrows(IOException.class, () -> BlockTextureIO.fromBytes(otherCache));
        assertTrue(error.getMessage().contains("magic"), error.getMessage());
    }

    @Test
    void rejectsCorruptionAndTruncation() throws Exception {
        byte[] bytes = BlockTextureIO.toBytes(fixture(16, 16, false));

        byte[] corrupt = bytes.clone();
        corrupt[corrupt.length / 2] ^= 0x44;
        IOException checksum = assertThrows(IOException.class, () -> BlockTextureIO.fromBytes(corrupt));
        assertTrue(checksum.getMessage().contains("checksum"), checksum.getMessage());

        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 9);
        assertThrows(IOException.class, () -> BlockTextureIO.fromBytes(truncated));

        byte[] extended = Arrays.copyOf(bytes, bytes.length + 5);
        assertThrows(IOException.class, () -> BlockTextureIO.fromBytes(extended));
    }

    @Test
    void readStopsAtConfiguredBoundBeforeParsing() throws Exception {
        BlockTexture texture = fixture(16, 16, false);
        byte[] bytes = BlockTextureIO.toBytes(texture);
        Path file = temporaryDirectory.resolve("bounded.spfb");
        Files.write(file, bytes);

        assertEquals(texture, BlockTextureIO.read(file, bytes.length));
        IOException oversized = assertThrows(IOException.class, () -> BlockTextureIO.read(file, bytes.length - 1));
        assertTrue(oversized.getMessage().contains((bytes.length - 1) + " byte safety limit"));
        assertThrows(IllegalArgumentException.class, () -> BlockTextureIO.read(file, 32));
    }

    @Test
    void refusesAStaleEncodersBlocksAsData() throws Exception {
        // A blob from an older encoder is not corrupt and cannot be recognised by inspection; the
        // codec version is the only thing that distinguishes it, so it has to survive the round trip.
        BlockTexture texture = fixture(16, 16, false);
        assertEquals(BlockCompressor.CODEC_VERSION,
                BlockTextureIO.fromBytes(BlockTextureIO.toBytes(texture)).codecVersion());
    }

    @Test
    void rejectsALevelCountThatCannotBeTrue() {
        assertThrows(IllegalArgumentException.class, () -> new BlockTexture(
                Hashes.sha256(new byte[] {2}),
                BlockTexture.Format.BC1,
                BlockCompressor.CODEC_VERSION,
                4, 4, 4, 4,
                TextureFidelity.compare(new int[16], new int[16]),
                List.of()));

        // A 4x4 texture has three levels: 4x4, 2x2, 1x1. A fourth cannot exist.
        List<byte[]> tooMany = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tooMany.add(new byte[BlockCompressor.BC1_BLOCK_BYTES]);
        }
        assertThrows(IllegalArgumentException.class, () -> new BlockTexture(
                Hashes.sha256(new byte[] {2}),
                BlockTexture.Format.BC1,
                BlockCompressor.CODEC_VERSION,
                4, 4, 4, 4,
                TextureFidelity.compare(new int[16], new int[16]),
                tooMany));
    }

    /** A baked level-0 texture; {@code alpha} decides whether the baker picks BC3 or BC1. */
    private static BlockTexture fixture(int width, int height, boolean alpha) {
        return BlockTextureBaker.bake(
                Hashes.sha256(new byte[] {(byte) (alpha ? 1 : 0)}),
                gradient(width, height, alpha),
                width,
                height,
                BlockTextureBaker.Mips.NONE);
    }

    private static int[] gradient(int width, int height, boolean alpha) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = (x * 255) / Math.max(1, width - 1);
                int a = alpha ? (y * 255) / Math.max(1, height - 1) : 255;
                pixels[y * width + x] = (a << 24) | (value << 16) | ((255 - value) << 8) | (y & 0xff);
            }
        }
        return pixels;
    }
}

package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockCacheManifestIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsAndWritesDeterministically() throws Exception {
        BlockCacheManifest manifest = fixture(BlockCompressor.CODEC_VERSION);
        assertArrayEquals(BlockCacheManifestIO.toBytes(manifest),
                BlockCacheManifestIO.toBytes(fixture(BlockCompressor.CODEC_VERSION)));

        Path output = temporaryDirectory.resolve("cache/blocks.spfc");
        BlockCacheManifestIO.write(output, manifest);
        BlockCacheManifest restored = BlockCacheManifestIO.read(output);

        assertEquals(manifest.entryCount(), restored.entryCount());
        assertEquals(manifest.profileFingerprint(), restored.profileFingerprint());
        assertEquals(manifest.entries(), restored.entries());
    }

    @Test
    void invalidatesAWholeCacheBakedByAnotherEncoder() throws Exception {
        // The cache is baked in one run by one encoder, so a version mismatch invalidates all of it
        // at once. The answer is "rebuild", not "corrupt": every texture simply takes the ordinary
        // path until it is rebuilt.
        assertTrue(fixture(BlockCompressor.CODEC_VERSION).matchesCurrentCodec());

        BlockCacheManifest stale = fixture(BlockCompressor.CODEC_VERSION + 1);
        assertFalse(BlockCacheManifestIO.fromBytes(BlockCacheManifestIO.toBytes(stale)).matchesCurrentCodec());
    }

    @Test
    void refusesAnEntryWhoseByteCountContradictsItsShape() {
        // The manifest is read without opening the blobs, so an entry claiming a size the format and
        // dimensions cannot produce would send a wrong-length upload to the driver.
        assertThrows(IllegalArgumentException.class, () -> new BlockCacheManifest.Entry(
                Hashes.sha256(new byte[] {1}), "blocks/a.spfb", BlockTexture.Format.BC1,
                64, 64, 1, 64L * 64, 0.1, 0.2));

        assertThrows(IllegalArgumentException.class, () -> new BlockCacheManifest.Entry(
                Hashes.sha256(new byte[] {1}), "blocks/a.spfb", BlockTexture.Format.BC1,
                64, 64, 99, 64L * 64 / 2, 0.1, 0.2));
    }

    @Test
    void describesABakedTextureWithoutRestatingIt() {
        BlockTexture texture = BlockTextureBaker.bake(
                Hashes.sha256(new byte[] {4}), opaque(32, 16), 32, 16, BlockTextureBaker.Mips.FULL_CHAIN);
        BlockCacheManifest.Entry entry = BlockCacheManifest.Entry.of(texture, "blocks/ab/cd.spfb");

        assertEquals(texture.blockBytes(), entry.blockBytes());
        assertEquals(texture.levelCount(), entry.levelCount());
        assertEquals(texture.uncompressedBytes(), entry.uncompressedBytes());
        assertEquals(texture.fidelity().meanDeltaE(), entry.meanDeltaE());
    }

    @Test
    void rejectsCorruptionAndTruncation() throws Exception {
        byte[] bytes = BlockCacheManifestIO.toBytes(fixture(BlockCompressor.CODEC_VERSION));

        byte[] corrupt = bytes.clone();
        corrupt[corrupt.length / 2] ^= 0x11;
        IOException checksum = assertThrows(IOException.class, () -> BlockCacheManifestIO.fromBytes(corrupt));
        assertTrue(checksum.getMessage().contains("checksum"), checksum.getMessage());

        assertThrows(IOException.class,
                () -> BlockCacheManifestIO.fromBytes(Arrays.copyOf(bytes, bytes.length - 7)));
    }

    @Test
    void readStopsAtConfiguredBoundBeforeParsing() throws Exception {
        BlockCacheManifest manifest = fixture(BlockCompressor.CODEC_VERSION);
        byte[] bytes = BlockCacheManifestIO.toBytes(manifest);
        Path file = temporaryDirectory.resolve("bounded.spfc");
        Files.write(file, bytes);

        assertEquals(manifest.entries(), BlockCacheManifestIO.read(file, bytes.length).entries());
        IOException oversized = assertThrows(
                IOException.class, () -> BlockCacheManifestIO.read(file, bytes.length - 1));
        assertTrue(oversized.getMessage().contains((bytes.length - 1) + " byte safety limit"));
        assertThrows(IllegalArgumentException.class, () -> BlockCacheManifestIO.read(file, 32));
    }

    @Test
    void refusesTheOtherCachesManifest() throws Exception {
        byte[] preparedPixels = TextureManifestIO.toBytes(new TextureManifest("fingerprint", Map.of()));

        IOException error = assertThrows(IOException.class,
                () -> BlockCacheManifestIO.fromBytes(preparedPixels));
        assertTrue(error.getMessage().contains("magic"), error.getMessage());
    }

    @Test
    void addsUpTheTradeItRepresents() {
        BlockCacheManifest manifest = fixture(BlockCompressor.CODEC_VERSION);

        assertTrue(manifest.uncompressedBytes() > manifest.blockBytes());
        assertEquals(manifest.entries().values().stream().mapToLong(BlockCacheManifest.Entry::blockBytes).sum(),
                manifest.blockBytes());
    }

    private static BlockCacheManifest fixture(int codecVersion) {
        Map<String, BlockCacheManifest.Entry> entries = new LinkedHashMap<>();
        entries.put("graphics/ships/hull.png", BlockCacheManifest.Entry.of(
                BlockTextureBaker.bake(Hashes.sha256(new byte[] {1}), opaque(64, 64), 64, 64,
                        BlockTextureBaker.Mips.NONE),
                "blocks/00/hull.spfb"));
        entries.put("graphics/ui/panel.png", BlockCacheManifest.Entry.of(
                BlockTextureBaker.bake(Hashes.sha256(new byte[] {2}), translucent(32, 32), 32, 32,
                        BlockTextureBaker.Mips.FULL_CHAIN),
                "blocks/01/panel.spfb"));
        return new BlockCacheManifest("profile-fingerprint", codecVersion, entries);
    }

    private static int[] opaque(int width, int height) {
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xff000000 | ((i * 37) & 0xffffff);
        }
        return pixels;
    }

    private static int[] translucent(int width, int height) {
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = ((i % 256) << 24) | ((i * 53) & 0xffffff);
        }
        return pixels;
    }
}

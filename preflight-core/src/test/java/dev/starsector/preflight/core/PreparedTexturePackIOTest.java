package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedTexturePackIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsRawAndCompressedBlobsThroughOneSharedChannel() throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        Files.createDirectories(cache);
        String profile = "ab".repeat(32);
        PreparedTexture first = texture("01".repeat(32), new byte[] {
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
        });
        PreparedTexture second = texture("02".repeat(32), new byte[] {
                12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1
        });
        String raw = "blobs/01/raw.spft";
        String lz4 = "blobs/02/example-lz4.spft";
        Files.createDirectories(cache.resolve("blobs/01"));
        Files.createDirectories(cache.resolve("blobs/02"));
        PreparedTextureIO.write(cache.resolve(raw), first, PreparedTextureIO.StorageCodec.RAW);
        PreparedTextureIO.write(cache.resolve(lz4), second, PreparedTextureIO.StorageCodec.LZ4);

        Path packPath = PreparedTexturePackIO.path(cache, profile);
        PreparedTexturePackIO.write(packPath, profile, cache, List.of(lz4, raw, raw));
        try (PreparedTexturePack pack =
                PreparedTexturePackIO.open(packPath, profile, List.of(raw, lz4))) {
            assertEquals(2, pack.entryCount());
            assertEquals(true, pack.hasEntryOrder(List.of(lz4, raw)));
            assertEquals(false, pack.hasEntryOrder(List.of(raw, lz4)));
            assertArrayEquals(first.pixels(), pack.readTrusted(raw).pixels());
            assertArrayEquals(second.pixels(), pack.readTrusted(lz4).pixels());
        }
    }

    @Test
    void reordersAnExactPackAfterLooseBlobsAreGone() throws Exception {
        Path cache = temporaryDirectory.resolve("reorder-cache");
        Files.createDirectories(cache.resolve("blobs"));
        String profile = "31".repeat(32);
        String firstPath = "blobs/first.spft";
        String secondPath = "blobs/second.spft";
        PreparedTexture first = texture("41".repeat(32), new byte[] {
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
        });
        PreparedTexture second = texture("42".repeat(32), new byte[] {
                12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1
        });
        PreparedTextureIO.write(cache.resolve(firstPath), first);
        PreparedTextureIO.write(cache.resolve(secondPath), second);
        Path pack = PreparedTexturePackIO.path(cache, profile);
        PreparedTexturePackIO.write(
                pack, profile, cache, List.of(firstPath, secondPath));
        Files.delete(cache.resolve(firstPath));
        Files.delete(cache.resolve(secondPath));

        assertEquals(true, PreparedTexturePackIO.reorder(
                pack, profile, List.of(secondPath, firstPath)));

        try (PreparedTexturePack opened = PreparedTexturePackIO.open(
                pack, profile, List.of(firstPath, secondPath))) {
            assertEquals(true, opened.hasEntryOrder(List.of(secondPath, firstPath)));
            assertArrayEquals(first.pixels(), opened.readTrusted(firstPath).pixels());
            assertArrayEquals(second.pixels(), opened.readTrusted(secondPath).pixels());
        }
        assertEquals(false, PreparedTexturePackIO.reorder(
                pack, profile, List.of(secondPath, firstPath)));
    }

    @Test
    void observedOrderRoundTripsDistinctPathsAndRejectsCorruption() throws Exception {
        String profile = "ef".repeat(32);
        Path order = PreparedTexturePackOrderIO.path(temporaryDirectory, profile);
        PreparedTexturePackOrderIO.write(
                order, profile, List.of("blobs/b.spft", "blobs/a.spft", "blobs/b.spft"));
        assertEquals(
                List.of("blobs/b.spft", "blobs/a.spft"),
                PreparedTexturePackOrderIO.read(order, profile));

        byte[] bytes = Files.readAllBytes(order);
        bytes[bytes.length / 2] ^= 0x20;
        Files.write(order, bytes);
        assertThrows(Exception.class, () -> PreparedTexturePackOrderIO.read(order, profile));
    }

    @Test
    void rejectsManifestDriftAndIndexCorruption() throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        Files.createDirectories(cache.resolve("blobs"));
        String profile = "cd".repeat(32);
        String relative = "blobs/one.spft";
        PreparedTextureIO.write(cache.resolve(relative),
                texture("03".repeat(32), new byte[12]), PreparedTextureIO.StorageCodec.LZ4);
        Path pack = PreparedTexturePackIO.path(cache, profile);
        PreparedTexturePackIO.write(pack, profile, cache, List.of(relative));

        assertThrows(Exception.class,
                () -> PreparedTexturePackIO.open(pack, profile, List.of("blobs/two.spft")));
        byte[] bytes = Files.readAllBytes(pack);
        bytes[bytes.length > 60 ? 55 : 0] ^= 0x40;
        Files.write(pack, bytes);
        assertThrows(Exception.class,
                () -> PreparedTexturePackIO.open(pack, profile, List.of(relative)));
    }

    @Test
    void prospectiveSizeMatchesTheWrittenPackExactly() throws Exception {
        Path cache = temporaryDirectory.resolve("estimated-cache");
        Files.createDirectories(cache.resolve("blobs"));
        String profile = "ad".repeat(32);
        String first = "blobs/first.spft";
        String second = "blobs/second.spft";
        PreparedTextureIO.write(cache.resolve(first),
                texture("04".repeat(32), new byte[12]), PreparedTextureIO.StorageCodec.RAW);
        PreparedTextureIO.write(cache.resolve(second),
                texture("05".repeat(32), new byte[12]), PreparedTextureIO.StorageCodec.LZ4);
        var sizes = new java.util.LinkedHashMap<String, Long>();
        sizes.put(first, Files.size(cache.resolve(first)));
        sizes.put(second, Files.size(cache.resolve(second)));

        Path pack = PreparedTexturePackIO.path(cache, profile);
        PreparedTexturePackIO.write(pack, profile, cache, sizes.keySet());

        assertEquals(Files.size(pack), PreparedTexturePackIO.estimatedFileBytes(profile, sizes));
    }

    private static PreparedTexture texture(String hash, byte[] pixels) {
        return new PreparedTexture(
                hash,
                PreparedTexture.Transformation.IDENTITY,
                2,
                2,
                2,
                2,
                3,
                0,
                0,
                0,
                pixels);
    }
}

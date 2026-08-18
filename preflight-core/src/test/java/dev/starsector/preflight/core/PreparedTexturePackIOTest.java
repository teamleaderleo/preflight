package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        bytes[bytes.length > 100 ? 90 : 0] ^= 0x40;
        Files.write(pack, bytes);
        assertThrows(Exception.class,
                () -> PreparedTexturePackIO.open(pack, profile, List.of(relative)));
    }

    @Test
    void rejectsSameLengthPackedPixelCorruptionBeforeServingTrustedData() throws Exception {
        Path cache = temporaryDirectory.resolve("pixel-corruption-cache");
        Files.createDirectories(cache.resolve("blobs"));
        String profile = "bc".repeat(32);
        String relative = "blobs/raw.spft";
        byte[] pixels = new byte[] {13, 27, 41, 55, 69, 83, 97, 111, 125, 7, 21, 35};
        PreparedTextureIO.write(
                cache.resolve(relative),
                texture("06".repeat(32), pixels),
                PreparedTextureIO.StorageCodec.RAW);
        Path pack = PreparedTexturePackIO.path(cache, profile);
        PreparedTexturePackIO.write(pack, profile, cache, List.of(relative));

        byte[] packed = Files.readAllBytes(pack);
        int pixelOffset = indexOf(packed, pixels);
        assertTrue(pixelOffset >= 0, "fixture pixels must appear verbatim in the raw packed SPFT");
        packed[pixelOffset + 5] ^= 0x01;
        Files.write(pack, packed);

        assertThrows(Exception.class,
                () -> PreparedTexturePackIO.open(pack, profile, List.of(relative)),
                "same-length packed pixel corruption must be rejected before readTrusted can serve it");
    }

    @Test
    void rejectsPackedEmbeddedChecksumCorruptionEvenWhenTheIndexStillMatches() throws Exception {
        Path cache = temporaryDirectory.resolve("embedded-checksum-cache");
        Files.createDirectories(cache.resolve("blobs"));
        String profile = "ce".repeat(32);
        String relative = "blobs/raw.spft";
        PreparedTextureIO.write(
                cache.resolve(relative),
                texture("07".repeat(32), new byte[] {1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23}),
                PreparedTextureIO.StorageCodec.RAW);
        Path pack = PreparedTexturePackIO.path(cache, profile);
        PreparedTexturePackIO.write(pack, profile, cache, List.of(relative));

        byte[] packed = Files.readAllBytes(pack);
        packed[packed.length - 1] ^= 0x20;
        Files.write(pack, packed);

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

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[start + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return start;
        }
        return -1;
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

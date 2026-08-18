package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedTexturePackPersistencePropertyTest {
    private static final long PACK_ROUND_TRIP_SEED = 0x53504650524f554eL;
    private static final long PACK_CORRUPTION_SEED = 0x53504650434f5252L;
    private static final long PACK_TRUNCATION_SEED = 0x535046505452554eL;
    private static final long ORDER_ROUND_TRIP_SEED = 0x5350464f524f554eL;
    private static final long ORDER_CORRUPTION_SEED = 0x5350464f434f5252L;
    private static final long ORDER_TRUNCATION_SEED = 0x5350464f5452554eL;

    private static final int PACK_INDEX_LENGTH_OFFSET = 8;
    private static final int PACK_PAYLOAD_LENGTH_OFFSET = 12;
    private static final int PACK_INDEX_CHECKSUM_OFFSET = 20;
    private static final int PACK_INDEX_OFFSET = 84;
    private static final int ORDER_PAYLOAD_LENGTH_OFFSET = 8;
    private static final int ORDER_PAYLOAD_OFFSET = 12;
    private static final int CHECKSUM_BYTES = 32;

    @TempDir
    Path temporaryDirectory;

    @Test
    void seededPacksRoundTripAndEncodeCanonically() throws Exception {
        Random random = new Random(PACK_ROUND_TRIP_SEED);
        for (int iteration = 0; iteration < 120; iteration++) {
            Path cache = temporaryDirectory.resolve("pack-roundtrip-" + iteration);
            Files.createDirectories(cache.resolve("blobs"));
            String profile = randomHash(random);
            int entryCount = 1 + random.nextInt(4);
            List<String> paths = new ArrayList<>();
            List<PreparedTexture> textures = new ArrayList<>();
            for (int index = 0; index < entryCount; index++) {
                PreparedTexture texture = randomTexture(random);
                PreparedTextureIO.StorageCodec codec = random.nextBoolean()
                        ? PreparedTextureIO.StorageCodec.RAW
                        : PreparedTextureIO.StorageCodec.LZ4;
                String path = "blobs/" + index + "/" + randomHash(random) + codec.suffixWithExtension();
                Files.createDirectories(cache.resolve(path).getParent());
                PreparedTextureIO.write(cache.resolve(path), texture, codec);
                paths.add(path);
                textures.add(texture);
            }

            Path pack = PreparedTexturePackIO.path(cache, profile);
            PreparedTexturePackIO.write(pack, profile, cache, paths);
            byte[] first = Files.readAllBytes(pack);
            PreparedTexturePackIO.write(pack, profile, cache, paths);
            byte[] second = Files.readAllBytes(pack);
            assertArrayEquals(first, second, "pack encoding iteration " + iteration);

            try (PreparedTexturePack opened = PreparedTexturePackIO.open(pack, profile, paths)) {
                assertEquals(entryCount, opened.entryCount());
                for (int index = 0; index < entryCount; index++) {
                    assertArrayEquals(
                            textures.get(index).pixels(),
                            opened.readTrusted(paths.get(index)).pixels(),
                            "pack round trip iteration " + iteration + " entry " + index);
                }
            }
        }
    }

    @Test
    void seededPackCorruptionsAlwaysReject() throws Exception {
        PackFixture fixture = packFixture("pack-corrupt");
        byte[] valid = Files.readAllBytes(fixture.pack());
        Random random = new Random(PACK_CORRUPTION_SEED);

        for (int iteration = 0; iteration < 300; iteration++) {
            byte[] corrupt = valid.clone();
            int byteIndex = random.nextInt(corrupt.length);
            corrupt[byteIndex] ^= (byte) (1 << random.nextInt(Byte.SIZE));
            Path candidate = temporaryDirectory.resolve("pack-corrupt-" + iteration + ".spfp");
            Files.write(candidate, corrupt);
            assertThrows(
                    IOException.class,
                    () -> PreparedTexturePackIO.open(candidate, fixture.profile(), fixture.paths()),
                    "pack corruption " + iteration + " at byte " + byteIndex);
        }
    }

    @Test
    void seededPackTruncationsAlwaysReject() throws Exception {
        PackFixture fixture = packFixture("pack-truncate");
        byte[] valid = Files.readAllBytes(fixture.pack());
        Random random = new Random(PACK_TRUNCATION_SEED);

        for (int iteration = 0; iteration < 300; iteration++) {
            int length = random.nextInt(valid.length);
            Path candidate = temporaryDirectory.resolve("pack-truncated-" + iteration + ".spfp");
            Files.write(candidate, Arrays.copyOf(valid, length));
            assertThrows(
                    IOException.class,
                    () -> PreparedTexturePackIO.open(candidate, fixture.profile(), fixture.paths()),
                    "pack truncation " + iteration + " at " + length);
        }
    }

    @Test
    void hostilePackLengthsCountsAndRangesRejectBeforeAcceptance() throws Exception {
        PackFixture fixture = packFixture("pack-hostile");
        byte[] valid = Files.readAllBytes(fixture.pack());

        assertPackRejected(fixture, withInt(valid, PACK_INDEX_LENGTH_OFFSET, -1));
        assertPackRejected(fixture, withInt(valid, PACK_INDEX_LENGTH_OFFSET, Integer.MAX_VALUE));
        assertPackRejected(fixture, withLong(valid, PACK_PAYLOAD_LENGTH_OFFSET, Long.MAX_VALUE));

        byte[] profileLength = valid.clone();
        putInt(profileLength, PACK_INDEX_OFFSET, Integer.MAX_VALUE);
        resignPackIndex(profileLength);
        assertPackRejected(fixture, profileLength);

        int profileBytes = intAt(valid, PACK_INDEX_OFFSET);
        int countOffset = PACK_INDEX_OFFSET + Integer.BYTES + profileBytes;
        byte[] count = valid.clone();
        putInt(count, countOffset, Integer.MAX_VALUE);
        resignPackIndex(count);
        assertPackRejected(fixture, count);

        int firstPathLengthOffset = countOffset + Integer.BYTES;
        int firstPathBytes = intAt(valid, firstPathLengthOffset);
        byte[] pathLength = valid.clone();
        putInt(pathLength, firstPathLengthOffset, Integer.MAX_VALUE);
        resignPackIndex(pathLength);
        assertPackRejected(fixture, pathLength);

        int firstRangeOffset = firstPathLengthOffset + Integer.BYTES + firstPathBytes;
        byte[] rangeOffset = valid.clone();
        putLong(rangeOffset, firstRangeOffset, Long.MAX_VALUE);
        resignPackIndex(rangeOffset);
        assertPackRejected(fixture, rangeOffset);

        byte[] rangeLength = valid.clone();
        putInt(rangeLength, firstRangeOffset + Long.BYTES, Integer.MAX_VALUE);
        resignPackIndex(rangeLength);
        assertPackRejected(fixture, rangeLength);
    }

    @Test
    void packWriterContainsTraversalAndSymlinkSources() throws Exception {
        Path cache = temporaryDirectory.resolve("pack-containment-cache");
        Files.createDirectories(cache.resolve("blobs"));
        Path outside = temporaryDirectory.resolve("outside.spft");
        PreparedTextureIO.write(outside, randomTexture(new Random(17)), PreparedTextureIO.StorageCodec.RAW);
        String profile = "cd".repeat(32);

        assertThrows(
                IllegalArgumentException.class,
                () -> PreparedTexturePackIO.write(
                        cache.resolve("escape.spfp"), profile, cache, List.of("../outside.spft")));

        Path link = cache.resolve("blobs/link.spft");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | SecurityException | IOException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable in this test environment: " + unavailable);
        }
        assertThrows(
                IOException.class,
                () -> PreparedTexturePackIO.write(
                        cache.resolve("linked.spfp"), profile, cache, List.of("blobs/link.spft")));
    }

    @Test
    void writerRejectsBlobReplacementBetweenContainmentAndOpen() throws Exception {
        Path cache = temporaryDirectory.resolve("pack-replace-cache");
        Files.createDirectories(cache.resolve("blobs"));
        String profile = "de".repeat(32);
        String relative = "blobs/source.spft";
        Path source = cache.resolve(relative);
        PreparedTexture first = randomTexture(new Random(31));
        PreparedTexture second = randomTexture(new Random(37));
        PreparedTextureIO.write(source, first, PreparedTextureIO.StorageCodec.RAW);
        byte[] secondBytes = PreparedTextureIO.toBytes(second, PreparedTextureIO.StorageCodec.RAW);
        if (secondBytes.length != Files.size(source)) {
            PreparedTexture replacement = new PreparedTexture(
                    second.sourceSha256(),
                    second.transformation(),
                    first.originalWidth(),
                    first.originalHeight(),
                    first.uploadWidth(),
                    first.uploadHeight(),
                    first.channels(),
                    first.color0(),
                    first.color1(),
                    first.color2(),
                    new byte[first.pixels().length]);
            secondBytes = PreparedTextureIO.toBytes(replacement, PreparedTextureIO.StorageCodec.RAW);
        }
        byte[] exactReplacement = secondBytes;
        PreparedTexturePackIO.setPackSourceOpenHookForTests((expected, selected) -> {
            if (selected.equals(source)) {
                Files.write(source, exactReplacement);
            }
        });
        try {
            assertThrows(
                    IOException.class,
                    () -> PreparedTexturePackIO.write(
                            cache.resolve("replace.spfp"), profile, cache, List.of(relative)));
        } finally {
            PreparedTexturePackIO.setPackSourceOpenHookForTests(null);
        }
    }

    @Test
    void packWriteIsAtomicAgainstReader() throws Exception {
        Path cache = temporaryDirectory.resolve("atomic-pack-cache");
        Files.createDirectories(cache.resolve("blobs"));
        String profile = "ef".repeat(32);
        String relative = "blobs/source.spft";
        PreparedTextureIO.write(
                cache.resolve(relative),
                randomTexture(new Random(43)),
                PreparedTextureIO.StorageCodec.RAW);
        Path pack = PreparedTexturePackIO.path(cache, profile);
        PreparedTexturePackIO.write(pack, profile, cache, List.of(relative));
        byte[] previous = Files.readAllBytes(pack);

        PreparedTexture replacement = randomTexture(new Random(47));
        PreparedTextureIO.write(cache.resolve(relative), replacement, PreparedTextureIO.StorageCodec.RAW);
        byte[] expectedNew = packedBytes(cache, profile, List.of(relative));
        Files.write(pack, previous);

        AtomicPublish.setBeforeMoveHookForTests((staged, target) -> {
            assertArrayEquals(previous, Files.readAllBytes(target));
        });
        try {
            PreparedTexturePackIO.write(pack, profile, cache, List.of(relative));
        } finally {
            AtomicPublish.setBeforeMoveHookForTests(null);
        }
        assertArrayEquals(expectedNew, Files.readAllBytes(pack));
    }

    @Test
    void packSizeEstimatorMatchesWrittenBytes() throws Exception {
        Path cache = temporaryDirectory.resolve("pack-estimator-cache");
        Files.createDirectories(cache.resolve("blobs"));
        String profile = "fa".repeat(32);
        List<String> paths = new ArrayList<>();
        java.util.LinkedHashMap<String, Long> lengths = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 3; index++) {
            String relative = "blobs/" + index + ".spft";
            PreparedTextureIO.write(
                    cache.resolve(relative),
                    randomTexture(new Random(53 + index)),
                    index % 2 == 0
                            ? PreparedTextureIO.StorageCodec.RAW
                            : PreparedTextureIO.StorageCodec.LZ4);
            paths.add(relative);
            lengths.put(relative, Files.size(cache.resolve(relative)));
        }
        Path pack = PreparedTexturePackIO.path(cache, profile);
        PreparedTexturePackIO.write(pack, profile, cache, paths);
        assertEquals(Files.size(pack), PreparedTexturePackIO.estimatedFileBytes(profile, lengths));
    }

    @Test
    void orderRoundTripCorruptionAndTruncationProperties() throws Exception {
        Random random = new Random(ORDER_ROUND_TRIP_SEED);
        String profile = "fe".repeat(32);
        for (int iteration = 0; iteration < 120; iteration++) {
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            int count = 1 + random.nextInt(8);
            for (int index = 0; index < count; index++) {
                unique.add("blobs/" + randomHash(random) + ".spft");
            }
            List<String> paths = new ArrayList<>(unique);
            Path target = temporaryDirectory.resolve("order-" + iteration + ".spfo");
            PreparedTexturePackOrderIO.write(target, profile, paths);
            assertEquals(paths, PreparedTexturePackOrderIO.read(target, profile));

            byte[] valid = Files.readAllBytes(target);
            for (int mutation = 0; mutation < 4; mutation++) {
                byte[] corrupt = valid.clone();
                int byteIndex = random.nextInt(corrupt.length);
                corrupt[byteIndex] ^= (byte) (1 << random.nextInt(Byte.SIZE));
                Files.write(target, corrupt);
                assertThrows(IOException.class, () -> PreparedTexturePackOrderIO.read(target, profile));
            }
            for (int truncation = 0; truncation < 4; truncation++) {
                Files.write(target, Arrays.copyOf(valid, random.nextInt(valid.length)));
                assertThrows(IOException.class, () -> PreparedTexturePackOrderIO.read(target, profile));
            }
        }
    }

    private PackFixture packFixture(String name) throws Exception {
        Path cache = temporaryDirectory.resolve(name);
        Files.createDirectories(cache.resolve("blobs"));
        String profile = "ab".repeat(32);
        List<String> paths = List.of("blobs/one.spft", "blobs/two.spft");
        PreparedTextureIO.write(
                cache.resolve(paths.get(0)),
                randomTexture(new Random(71)),
                PreparedTextureIO.StorageCodec.RAW);
        PreparedTextureIO.write(
                cache.resolve(paths.get(1)),
                randomTexture(new Random(73)),
                PreparedTextureIO.StorageCodec.LZ4);
        Path pack = PreparedTexturePackIO.path(cache, profile);
        PreparedTexturePackIO.write(pack, profile, cache, paths);
        return new PackFixture(profile, paths, pack);
    }

    private byte[] packedBytes(Path cache, String profile, List<String> paths) throws Exception {
        Path target = temporaryDirectory.resolve("expected-" + System.nanoTime() + ".spfp");
        PreparedTexturePackIO.write(target, profile, cache, paths);
        return Files.readAllBytes(target);
    }

    private static void assertPackRejected(PackFixture fixture, byte[] bytes) throws Exception {
        Path candidate = Files.createTempFile("preflight-pack-hostile-", ".spfp");
        try {
            Files.write(candidate, bytes);
            assertThrows(
                    IOException.class,
                    () -> PreparedTexturePackIO.open(candidate, fixture.profile(), fixture.paths()));
        } finally {
            Files.deleteIfExists(candidate);
        }
    }

    private static byte[] withInt(byte[] source, int offset, int value) {
        byte[] changed = source.clone();
        putInt(changed, offset, value);
        return changed;
    }

    private static byte[] withLong(byte[] source, int offset, long value) {
        byte[] changed = source.clone();
        putLong(changed, offset, value);
        return changed;
    }

    private static void resignPackIndex(byte[] bytes) {
        int indexLength = intAt(bytes, PACK_INDEX_LENGTH_OFFSET);
        byte[] index = Arrays.copyOfRange(bytes, PACK_INDEX_OFFSET, PACK_INDEX_OFFSET + indexLength);
        byte[] checksum = Hashes.sha256Bytes(index);
        System.arraycopy(checksum, 0, bytes, PACK_INDEX_CHECKSUM_OFFSET, CHECKSUM_BYTES);
    }

    private static void resignOrderPayload(byte[] bytes) {
        int payloadLength = intAt(bytes, ORDER_PAYLOAD_LENGTH_OFFSET);
        int checksumOffset = ORDER_PAYLOAD_OFFSET + payloadLength;
        byte[] payload = Arrays.copyOfRange(bytes, ORDER_PAYLOAD_OFFSET, checksumOffset);
        byte[] checksum = Hashes.sha256Bytes(payload);
        System.arraycopy(checksum, 0, bytes, checksumOffset, CHECKSUM_BYTES);
    }

    private static int intAt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(offset);
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(offset, value);
    }

    private static void putLong(byte[] bytes, int offset, long value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putLong(offset, value);
    }

    private static PreparedTexture randomTexture(Random random) {
        int width = 1 + random.nextInt(8);
        int height = 1 + random.nextInt(8);
        int channels = random.nextBoolean() ? 3 : 4;
        byte[] pixels = new byte[Math.multiplyExact(Math.multiplyExact(width, height), channels)];
        random.nextBytes(pixels);
        return new PreparedTexture(
                randomHash(random),
                random.nextBoolean()
                        ? PreparedTexture.Transformation.IDENTITY
                        : PreparedTexture.Transformation.DOWNSAMPLE_IF_OVERSIZED,
                width,
                height,
                width,
                height,
                channels,
                random.nextInt(256),
                random.nextInt(256),
                random.nextInt(256),
                pixels);
    }

    private static String randomHash(Random random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record PackFixture(String profile, List<String> paths, Path pack) {
    }
}

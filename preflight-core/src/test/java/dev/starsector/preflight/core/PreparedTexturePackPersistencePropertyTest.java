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
    private static final int PACK_INDEX_OFFSET = 52;
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
            for (int entry = 0; entry < entryCount; entry++) {
                String relative = "blobs/entry-" + entry + ".spft";
                PreparedTexture texture = randomTexture(random);
                PreparedTextureIO.StorageCodec codec = ((iteration + entry) & 1) == 0
                        ? PreparedTextureIO.StorageCodec.RAW
                        : PreparedTextureIO.StorageCodec.LZ4;
                PreparedTextureIO.write(cache.resolve(relative), texture, codec);
                paths.add(relative);
                textures.add(texture);
            }
            if (entryCount > 1) {
                java.util.Collections.rotate(paths, random.nextInt(entryCount));
            }

            Path first = cache.resolve("first.spfp");
            Path second = cache.resolve("second.spfp");
            PreparedTexturePackIO.write(first, profile, cache, paths);
            PreparedTexturePackIO.write(second, profile, cache, paths);
            assertArrayEquals(
                    Files.readAllBytes(first),
                    Files.readAllBytes(second),
                    "canonical pack encoding " + iteration);

            try (PreparedTexturePack pack = PreparedTexturePackIO.open(first, profile, paths)) {
                assertEquals(entryCount, pack.entryCount(), "entry count " + iteration);
                assertEquals(true, pack.hasEntryOrder(paths), "entry order " + iteration);
                for (int entry = 0; entry < entryCount; entry++) {
                    String relative = "blobs/entry-" + entry + ".spft";
                    PreparedTexture expected = textures.get(entry);
                    assertEquals(expected, pack.readTrusted(relative),
                            "packed texture " + iteration + "/" + entry);
                }
            }
        }
    }

    @Test
    void seededPackHeaderAndIndexCorruptionAlwaysRejects() throws Exception {
        PackFixture fixture = packFixture("pack-corrupt");
        byte[] valid = Files.readAllBytes(fixture.pack());
        int indexLength = intAt(valid, PACK_INDEX_LENGTH_OFFSET);
        int authenticatedPrefix = PACK_INDEX_OFFSET + indexLength;
        Random random = new Random(PACK_CORRUPTION_SEED);

        for (int iteration = 0; iteration < 600; iteration++) {
            byte[] corrupt = valid.clone();
            int byteIndex = random.nextInt(authenticatedPrefix);
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
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeTrue(false, "symbolic links unavailable on this runner");
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> PreparedTexturePackIO.write(
                        cache.resolve("symlink.spfp"), profile, cache, List.of("blobs/link.spft")));
    }

    @Test
    void seededOrderSidecarsRoundTripAndEncodeCanonically() throws Exception {
        Random random = new Random(ORDER_ROUND_TRIP_SEED);
        for (int iteration = 0; iteration < 300; iteration++) {
            String profile = "profile-" + iteration + "-π-" + random.nextInt();
            int entryCount = 1 + random.nextInt(12);
            List<String> source = new ArrayList<>();
            for (int entry = 0; entry < entryCount; entry++) {
                source.add("blobs/group-" + (entry % 4) + "/entry-" + entry + ".spft");
            }
            if (entryCount > 1) {
                source.add(source.get(random.nextInt(entryCount)));
            }
            List<String> expected = List.copyOf(new LinkedHashSet<>(source));
            Path first = temporaryDirectory.resolve("order-first-" + iteration + ".spfo");
            Path second = temporaryDirectory.resolve("order-second-" + iteration + ".spfo");

            PreparedTexturePackOrderIO.write(first, profile, source);
            PreparedTexturePackOrderIO.write(second, profile, source);

            assertArrayEquals(
                    Files.readAllBytes(first),
                    Files.readAllBytes(second),
                    "canonical order encoding " + iteration);
            assertEquals(expected, PreparedTexturePackOrderIO.read(first, profile),
                    "order round trip " + iteration);
        }
    }

    @Test
    void seededOrderSidecarCorruptionAndTruncationAlwaysReject() throws Exception {
        String profile = "ef".repeat(32);
        List<String> paths = List.of(
                "blobs/a.spft", "blobs/b.spft", "blobs/c.spft", "blobs/d.spft");
        Path validPath = temporaryDirectory.resolve("valid-order.spfo");
        PreparedTexturePackOrderIO.write(validPath, profile, paths);
        byte[] valid = Files.readAllBytes(validPath);
        Random corruption = new Random(ORDER_CORRUPTION_SEED);
        Random truncation = new Random(ORDER_TRUNCATION_SEED);

        for (int iteration = 0; iteration < 800; iteration++) {
            byte[] corrupt = valid.clone();
            int byteIndex = corruption.nextInt(corrupt.length);
            corrupt[byteIndex] ^= (byte) (1 << corruption.nextInt(Byte.SIZE));
            Path candidate = temporaryDirectory.resolve("order-corrupt-" + iteration + ".spfo");
            Files.write(candidate, corrupt);
            assertThrows(
                    IOException.class,
                    () -> PreparedTexturePackOrderIO.read(candidate, profile),
                    "order corruption " + iteration + " at byte " + byteIndex);
        }

        for (int iteration = 0; iteration < 300; iteration++) {
            int length = truncation.nextInt(valid.length);
            Path candidate = temporaryDirectory.resolve("order-truncated-" + iteration + ".spfo");
            Files.write(candidate, Arrays.copyOf(valid, length));
            assertThrows(
                    IOException.class,
                    () -> PreparedTexturePackOrderIO.read(candidate, profile),
                    "order truncation " + iteration + " at " + length);
        }
    }

    @Test
    void hostileOrderLengthsCountsTrailingDataAndFileSizeReject() throws Exception {
        String profile = "fa".repeat(32);
        List<String> paths = List.of("blobs/a.spft", "blobs/b.spft");
        Path validPath = temporaryDirectory.resolve("hostile-order.spfo");
        PreparedTexturePackOrderIO.write(validPath, profile, paths);
        byte[] valid = Files.readAllBytes(validPath);

        assertOrderRejected(profile, withInt(valid, ORDER_PAYLOAD_LENGTH_OFFSET, -1), "payload-negative");
        assertOrderRejected(
                profile,
                withInt(valid, ORDER_PAYLOAD_LENGTH_OFFSET, Integer.MAX_VALUE),
                "payload-max");

        byte[] profileLength = valid.clone();
        putInt(profileLength, ORDER_PAYLOAD_OFFSET, Integer.MAX_VALUE);
        resignOrderPayload(profileLength);
        assertOrderRejected(profile, profileLength, "profile-length");

        int profileBytes = intAt(valid, ORDER_PAYLOAD_OFFSET);
        int countOffset = ORDER_PAYLOAD_OFFSET + Integer.BYTES + profileBytes;
        byte[] count = valid.clone();
        putInt(count, countOffset, Integer.MAX_VALUE);
        resignOrderPayload(count);
        assertOrderRejected(profile, count, "entry-count");

        int firstPathLengthOffset = countOffset + Integer.BYTES;
        byte[] pathLength = valid.clone();
        putInt(pathLength, firstPathLengthOffset, Integer.MAX_VALUE);
        resignOrderPayload(pathLength);
        assertOrderRejected(profile, pathLength, "path-length");

        int payloadLength = intAt(valid, ORDER_PAYLOAD_LENGTH_OFFSET);
        byte[] payload = Arrays.copyOfRange(
                valid, ORDER_PAYLOAD_OFFSET, ORDER_PAYLOAD_OFFSET + payloadLength);
        byte[] trailingPayload = Arrays.copyOf(payload, payload.length + 1);
        trailingPayload[trailingPayload.length - 1] = 1;
        byte[] trailing = orderBytes(trailingPayload);
        assertOrderRejected(profile, trailing, "trailing-data");

        Path oversized = temporaryDirectory.resolve("oversized-order.spfo");
        try (FileChannel channel = FileChannel.open(
                oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(64L * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }
        assertThrows(IOException.class, () -> PreparedTexturePackOrderIO.read(oversized, profile));
    }

    private PackFixture packFixture(String name) throws Exception {
        Path cache = temporaryDirectory.resolve(name + "-cache");
        Files.createDirectories(cache.resolve("blobs"));
        String profile = "ab".repeat(32);
        List<String> paths = List.of("blobs/aa.spft", "blobs/bb.spft");
        PreparedTextureIO.write(
                cache.resolve(paths.get(0)),
                randomTexture(new Random(101)),
                PreparedTextureIO.StorageCodec.RAW);
        PreparedTextureIO.write(
                cache.resolve(paths.get(1)),
                randomTexture(new Random(202)),
                PreparedTextureIO.StorageCodec.LZ4);
        Path pack = cache.resolve(name + ".spfp");
        PreparedTexturePackIO.write(pack, profile, cache, paths);
        return new PackFixture(pack, profile, paths);
    }

    private void assertPackRejected(PackFixture fixture, byte[] bytes) throws Exception {
        Path candidate = temporaryDirectory.resolve("hostile-pack-" + System.nanoTime() + ".spfp");
        Files.write(candidate, bytes);
        assertThrows(
                IOException.class,
                () -> PreparedTexturePackIO.open(candidate, fixture.profile(), fixture.paths()));
    }

    private void assertOrderRejected(String profile, byte[] bytes, String name) throws Exception {
        Path candidate = temporaryDirectory.resolve("hostile-order-" + name + ".spfo");
        Files.write(candidate, bytes);
        assertThrows(IOException.class, () -> PreparedTexturePackOrderIO.read(candidate, profile));
    }

    private static byte[] orderBytes(byte[] payload) {
        ByteBuffer bytes = ByteBuffer.allocate(ORDER_PAYLOAD_OFFSET + payload.length + CHECKSUM_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        bytes.put(new byte[] {'S', 'P', 'F', 'O'});
        bytes.putInt(PreparedTexturePackOrderIO.FORMAT_VERSION);
        bytes.putInt(payload.length);
        bytes.put(payload);
        bytes.put(Hashes.sha256Bytes(payload));
        return bytes.array();
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
                        : PreparedTexture.Transformation.ALPHA_ADDER,
                width,
                height,
                width,
                height,
                channels,
                random.nextInt(),
                random.nextInt(),
                random.nextInt(),
                pixels);
    }

    private static String randomHash(Random random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record PackFixture(Path pack, String profile, List<String> paths) {
    }
}

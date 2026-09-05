package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockCacheManifestPersistencePropertyTest {
    private static final long ROUND_TRIP_SEED = 0x53504643424c4f43L;
    private static final long CORRUPTION_SEED = 0x53504643434f5252L;
    private static final long TRUNCATION_SEED = 0x535046435452554eL;

    private static final int PAYLOAD_LENGTH_OFFSET = 8;
    private static final int PAYLOAD_OFFSET = 12;
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_FILE_BYTES = 256 * 1024 * 1024;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000_000;

    @TempDir
    Path temporaryDirectory;

    @Test
    void seededRoundTripsRemainCanonical() throws Exception {
        Random random = new Random(ROUND_TRIP_SEED);
        for (int iteration = 0; iteration < 400; iteration++) {
            BlockCacheManifest manifest = randomManifest(random, iteration);
            byte[] encoded = BlockCacheManifestIO.toBytes(manifest);
            BlockCacheManifest restored = BlockCacheManifestIO.fromBytes(encoded);

            assertEquals(manifest.profileFingerprint(), restored.profileFingerprint(), "profile " + iteration);
            assertEquals(manifest.codecVersion(), restored.codecVersion(), "codec " + iteration);
            assertEquals(manifest.entries(), restored.entries(), "entries " + iteration);
            assertArrayEquals(encoded, BlockCacheManifestIO.toBytes(restored), "canonical bytes " + iteration);
        }
    }

    @Test
    void seededSingleBitCorruptionAlwaysRejects() throws Exception {
        byte[] valid = BlockCacheManifestIO.toBytes(fixtureManifest());
        Random random = new Random(CORRUPTION_SEED);

        for (int iteration = 0; iteration < 1_000; iteration++) {
            byte[] corrupt = valid.clone();
            int byteIndex = random.nextInt(corrupt.length);
            corrupt[byteIndex] ^= (byte) (1 << random.nextInt(Byte.SIZE));

            assertThrows(
                    IOException.class,
                    () -> BlockCacheManifestIO.fromBytes(corrupt),
                    "single-bit corruption " + iteration + " at byte " + byteIndex);
        }
    }

    @Test
    void seededAndBoundaryTruncationsAlwaysReject() throws Exception {
        byte[] valid = BlockCacheManifestIO.toBytes(fixtureManifest());
        Random random = new Random(TRUNCATION_SEED);

        for (int iteration = 0; iteration < 500; iteration++) {
            int length = random.nextInt(valid.length);
            assertThrows(
                    IOException.class,
                    () -> BlockCacheManifestIO.fromBytes(Arrays.copyOf(valid, length)),
                    "seeded truncation " + iteration + " at " + length);
        }

        int payloadLength = intAt(valid, PAYLOAD_LENGTH_OFFSET);
        int checksumOffset = PAYLOAD_OFFSET + payloadLength;
        int[] boundaries = {
            0, 1, 3, 4, 7, 8, 11, 12,
            checksumOffset - 1, checksumOffset, checksumOffset + CHECKSUM_BYTES - 1
        };
        for (int length : boundaries) {
            if (length >= 0 && length < valid.length) {
                assertThrows(
                        IOException.class,
                        () -> BlockCacheManifestIO.fromBytes(Arrays.copyOf(valid, length)),
                        "boundary truncation at " + length);
            }
        }
    }

    @Test
    void hostileAuthenticatedLengthsAndCountsRejectDeterministically() throws Exception {
        byte[] valid = BlockCacheManifestIO.toBytes(fixtureManifest());
        Layout layout = layout(valid);

        assertRejected(withInt(valid, PAYLOAD_LENGTH_OFFSET, -1, false));
        assertRejected(withInt(valid, PAYLOAD_LENGTH_OFFSET, Integer.MAX_VALUE, false));
        assertRejected(withInt(valid, layout.profileLengthOffset(), MAX_STRING_BYTES + 1, true));
        assertRejected(withInt(valid, layout.codecVersionOffset(), 0, true));
        assertRejected(withInt(valid, layout.entryCountOffset(), -1, true));
        assertRejected(withInt(valid, layout.entryCountOffset(), MAX_ENTRIES + 1, true));
        assertRejected(withInt(valid, layout.firstLogicalLengthOffset(), MAX_STRING_BYTES + 1, true));
        assertRejected(withInt(valid, layout.firstSourceLengthOffset(), MAX_STRING_BYTES + 1, true));
        assertRejected(withInt(valid, layout.firstBlobLengthOffset(), MAX_STRING_BYTES + 1, true));

        byte[] maxEntries = withInt(valid, layout.entryCountOffset(), MAX_ENTRIES, true);
        assertTimeout(Duration.ofSeconds(2), () -> assertRejected(maxEntries));
    }

    @Test
    void authenticatedNonCanonicalAndInvalidStateNeverBecomesAManifest() throws Exception {
        byte[] valid = BlockCacheManifestIO.toBytes(fixtureManifest());
        Layout layout = layout(valid);

        byte[] malformedUtf8 = valid.clone();
        malformedUtf8[layout.profileOffset()] = (byte) 0xc3;
        malformedUtf8[layout.profileOffset() + 1] = 0x28;
        resignPayload(malformedUtf8);
        assertRejected(malformedUtf8);

        byte[] blankProfile = valid.clone();
        replaceAsciiSameLength(
                blankProfile,
                layout.profileOffset(),
                layout.profileLength(),
                " ".repeat(layout.profileLength()));
        resignPayload(blankProfile);
        assertRejected(blankProfile);

        byte[] nonCanonicalLogicalPath = valid.clone();
        nonCanonicalLogicalPath[layout.firstLogicalOffset()] = (byte) 'A';
        resignPayload(nonCanonicalLogicalPath);
        assertRejected(nonCanonicalLogicalPath);

        byte[] logicalTraversal = valid.clone();
        replaceAsciiSameLength(
                logicalTraversal,
                layout.firstLogicalOffset(),
                layout.firstLogicalLength(),
                traversalOfLength(layout.firstLogicalLength()));
        resignPayload(logicalTraversal);
        assertRejected(logicalTraversal);

        byte[] nonCanonicalSourceHash = valid.clone();
        nonCanonicalSourceHash[layout.firstSourceOffset()] = (byte) 'A';
        resignPayload(nonCanonicalSourceHash);
        assertRejected(nonCanonicalSourceHash);

        byte[] nonCanonicalBlobPath = valid.clone();
        int slash = findByte(nonCanonicalBlobPath, layout.firstBlobOffset(), layout.firstBlobLength(), (byte) '/');
        nonCanonicalBlobPath[slash] = (byte) '\\';
        resignPayload(nonCanonicalBlobPath);
        assertRejected(nonCanonicalBlobPath);

        byte[] blobTraversal = valid.clone();
        replaceAsciiSameLength(
                blobTraversal,
                layout.firstBlobOffset(),
                layout.firstBlobLength(),
                traversalOfLength(layout.firstBlobLength()));
        resignPayload(blobTraversal);
        assertRejected(blobTraversal);

        assertRejected(withInt(valid, layout.firstFormatOffset(), Integer.MAX_VALUE, true));
        assertRejected(withInt(valid, layout.firstWidthOffset(), 0, true));
        assertRejected(withInt(valid, layout.firstHeightOffset(), 0, true));
        assertRejected(withInt(valid, layout.firstLevelCountOffset(), 0, true));
        assertRejected(withInt(valid, layout.firstLevelCountOffset(), 5, true));
        assertRejected(withLong(valid, layout.firstBlockBytesOffset(), 31, true));
        assertRejected(withDouble(valid, layout.firstMeanDeltaEOffset(), -0.1, true));
        assertRejected(withDouble(valid, layout.firstMeanDeltaEOffset(), Double.NaN, true));
        assertRejected(withDouble(valid, layout.firstMeanDeltaEOffset(), Double.POSITIVE_INFINITY, true));
        assertRejected(withDouble(valid, layout.firstP99DeltaEOffset(), Double.POSITIVE_INFINITY, true));

        byte[] duplicatePath = valid.clone();
        replaceAsciiSameLength(
                duplicatePath,
                layout.secondLogicalOffset(),
                layout.secondLogicalLength(),
                asciiAt(valid, layout.firstLogicalOffset(), layout.firstLogicalLength()));
        resignPayload(duplicatePath);
        assertRejected(duplicatePath);

        assertRejected(withTrailingPayloadByte(valid));
    }

    @Test
    void writerRejectsMalformedUtf16BeforePublication() {
        BlockCacheManifest manifest = new BlockCacheManifest(
                "profile-\uD800",
                BlockCompressor.CODEC_VERSION,
                Map.of());

        assertThrows(IOException.class, () -> BlockCacheManifestIO.toBytes(manifest));
    }

    @Test
    void blockAndClasspathSpfcFormatsRejectEachOther() throws Exception {
        byte[] block = BlockCacheManifestIO.toBytes(fixtureManifest());
        byte[] classpath = ClasspathProfileIndexIO.toBytes(classpathFixture());

        assertThrows(IOException.class, () -> ClasspathProfileIndexIO.fromBytes(block));
        assertThrows(IOException.class, () -> BlockCacheManifestIO.fromBytes(classpath));
    }

    @Test
    void diskReaderRejectsOversizedFileBeforeWholeFileRead() throws Exception {
        Path oversized = temporaryDirectory.resolve("oversized.spfc");
        try (FileChannel channel = FileChannel.open(
                oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            channel.position((long) MAX_FILE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }

        assertEquals((long) MAX_FILE_BYTES + 1, Files.size(oversized));
        // Exercise the same bounded reader without timing 256 MiB of legitimate disk I/O.
        // The large fixture still catches a regression to reading the whole file first.
        int readLimit = 4096;
        IOException error = assertTimeout(
                Duration.ofSeconds(2),
                () -> assertThrows(IOException.class, () -> BlockCacheManifestIO.read(oversized, readLimit)));
        assertEquals("Block cache manifest exceeds the " + readLimit + " byte safety limit: " + oversized,
                error.getMessage());
    }

    private static BlockCacheManifest randomManifest(Random random, int iteration) {
        int entryCount = 1 + random.nextInt(8);
        List<Integer> insertionOrder = new ArrayList<>();
        for (int entry = 0; entry < entryCount; entry++) {
            insertionOrder.add(entry);
        }
        Collections.shuffle(insertionOrder, random);

        Map<String, BlockCacheManifest.Entry> entries = new LinkedHashMap<>();
        BlockTexture.Format[] formats = BlockTexture.Format.values();
        for (int entry : insertionOrder) {
            String source = randomHash(random);
            BlockTexture.Format format = formats[random.nextInt(formats.length)];
            int width = 1 + random.nextInt(64);
            int height = 1 + random.nextInt(64);
            int maximumLevels = Math.min(5, BlockTexture.levelCount(width, height));
            int levelCount = 1 + random.nextInt(maximumLevels);
            long blockBytes = expectedBlockBytes(format, width, height, levelCount);
            double mean = random.nextDouble() * 5.0;
            double p99 = mean + random.nextDouble() * 10.0;
            String logicalPath = "graphics/group" + (entry % 3) + "/entry-" + iteration + "-" + entry + ".png";
            String blobPath = "blocks/" + source.substring(0, 2) + "/" + source + "-"
                    + format.name().toLowerCase(Locale.ROOT) + ".spfb";
            entries.put(logicalPath, new BlockCacheManifest.Entry(
                    source,
                    blobPath,
                    format,
                    width,
                    height,
                    levelCount,
                    blockBytes,
                    mean,
                    p99));
        }
        return new BlockCacheManifest(
                "profile-" + iteration + "-π-" + Integer.toUnsignedString(random.nextInt()),
                1 + random.nextInt(5),
                entries);
    }

    private static BlockCacheManifest fixtureManifest() {
        Map<String, BlockCacheManifest.Entry> entries = new LinkedHashMap<>();
        entries.put("a/a.png", new BlockCacheManifest.Entry(
                "aa".repeat(32),
                "blocks/aa/a.spfb",
                BlockTexture.Format.BC1,
                8,
                8,
                1,
                expectedBlockBytes(BlockTexture.Format.BC1, 8, 8, 1),
                0.1,
                0.2));
        entries.put("b/b.png", new BlockCacheManifest.Entry(
                "bb".repeat(32),
                "blocks/bb/b.spfb",
                BlockTexture.Format.BC3,
                4,
                4,
                1,
                expectedBlockBytes(BlockTexture.Format.BC3, 4, 4, 1),
                0.3,
                0.4));
        return new BlockCacheManifest("fixture-profile", BlockCompressor.CODEC_VERSION, entries);
    }

    private static ClasspathProfileIndex classpathFixture() {
        ClasspathProfileIndex.Archive archive = new ClasspathProfileIndex.Archive(
                "fixture-mod",
                "fixture.jar",
                Path.of("fixture-classpath", "fixture.jar"),
                "cc".repeat(32),
                100,
                10,
                "classpath/fixture.spfj",
                true);
        return new ClasspathProfileIndex(
                "dd".repeat(32),
                List.of(archive),
                Map.of("pkg/A.class", List.of(0)));
    }

    private static long expectedBlockBytes(BlockTexture.Format format, int width, int height, int levelCount) {
        long total = 0;
        for (int level = 0; level < levelCount; level++) {
            total = Math.addExact(total, format.blockBytesFor(
                    BlockTexture.levelWidth(width, level), BlockTexture.levelHeight(height, level)));
        }
        return total;
    }

    private static Layout layout(byte[] encoded) {
        int cursor = PAYLOAD_OFFSET;
        int profileLengthOffset = cursor;
        int profileLength = intAt(encoded, cursor);
        int profileOffset = cursor + Integer.BYTES;
        cursor = skipString(encoded, cursor);

        int codecVersionOffset = cursor;
        cursor += Integer.BYTES;
        int entryCountOffset = cursor;
        int entryCount = intAt(encoded, cursor);
        cursor += Integer.BYTES;
        if (entryCount < 2) {
            throw new IllegalStateException("fixture requires two entries");
        }

        int firstLogicalLengthOffset = cursor;
        int firstLogicalLength = intAt(encoded, cursor);
        int firstLogicalOffset = cursor + Integer.BYTES;
        cursor = skipString(encoded, cursor);

        int firstSourceLengthOffset = cursor;
        int firstSourceOffset = cursor + Integer.BYTES;
        cursor = skipString(encoded, cursor);

        int firstBlobLengthOffset = cursor;
        int firstBlobLength = intAt(encoded, cursor);
        int firstBlobOffset = cursor + Integer.BYTES;
        cursor = skipString(encoded, cursor);

        int firstFormatOffset = cursor;
        int firstWidthOffset = cursor + Integer.BYTES;
        int firstHeightOffset = cursor + Integer.BYTES * 2;
        int firstLevelCountOffset = cursor + Integer.BYTES * 3;
        int firstBlockBytesOffset = cursor + Integer.BYTES * 4;
        int firstMeanDeltaEOffset = firstBlockBytesOffset + Long.BYTES;
        int firstP99DeltaEOffset = firstMeanDeltaEOffset + Double.BYTES;
        cursor = firstP99DeltaEOffset + Double.BYTES;

        int secondLogicalLengthOffset = cursor;
        int secondLogicalLength = intAt(encoded, secondLogicalLengthOffset);
        int secondLogicalOffset = secondLogicalLengthOffset + Integer.BYTES;
        if (secondLogicalLength != firstLogicalLength) {
            throw new IllegalStateException("fixture logical paths must have equal encoded lengths");
        }

        return new Layout(
                profileLengthOffset,
                profileOffset,
                profileLength,
                codecVersionOffset,
                entryCountOffset,
                firstLogicalLengthOffset,
                firstLogicalOffset,
                firstLogicalLength,
                firstSourceLengthOffset,
                firstSourceOffset,
                firstBlobLengthOffset,
                firstBlobOffset,
                firstBlobLength,
                firstFormatOffset,
                firstWidthOffset,
                firstHeightOffset,
                firstLevelCountOffset,
                firstBlockBytesOffset,
                firstMeanDeltaEOffset,
                firstP99DeltaEOffset,
                secondLogicalOffset,
                secondLogicalLength);
    }

    private static int skipString(byte[] bytes, int lengthOffset) {
        int length = intAt(bytes, lengthOffset);
        return lengthOffset + Integer.BYTES + length;
    }

    private static byte[] withInt(byte[] source, int offset, int value, boolean resign) {
        byte[] changed = source.clone();
        putInt(changed, offset, value);
        if (resign) {
            resignPayload(changed);
        }
        return changed;
    }

    private static byte[] withLong(byte[] source, int offset, long value, boolean resign) {
        byte[] changed = source.clone();
        putLong(changed, offset, value);
        if (resign) {
            resignPayload(changed);
        }
        return changed;
    }

    private static byte[] withDouble(byte[] source, int offset, double value, boolean resign) {
        return withLong(source, offset, Double.doubleToRawLongBits(value), resign);
    }

    private static byte[] withTrailingPayloadByte(byte[] source) {
        int payloadLength = intAt(source, PAYLOAD_LENGTH_OFFSET);
        int checksumOffset = PAYLOAD_OFFSET + payloadLength;
        byte[] changed = new byte[source.length + 1];
        System.arraycopy(source, 0, changed, 0, checksumOffset);
        changed[checksumOffset] = 1;
        System.arraycopy(source, checksumOffset, changed, checksumOffset + 1, CHECKSUM_BYTES);
        putInt(changed, PAYLOAD_LENGTH_OFFSET, payloadLength + 1);
        resignPayload(changed);
        return changed;
    }

    private static void replaceAsciiSameLength(byte[] bytes, int offset, int expectedLength, String replacement) {
        byte[] replacementBytes = replacement.getBytes(StandardCharsets.US_ASCII);
        if (replacementBytes.length != expectedLength) {
            throw new IllegalArgumentException("replacement length differs from persisted field");
        }
        System.arraycopy(replacementBytes, 0, bytes, offset, replacementBytes.length);
    }

    private static String traversalOfLength(int length) {
        if (length < 3) {
            throw new IllegalArgumentException("traversal field must have room for ../");
        }
        return "../" + "x".repeat(length - 3);
    }

    private static String asciiAt(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private static int findByte(byte[] bytes, int offset, int length, byte target) {
        for (int index = offset; index < offset + length; index++) {
            if (bytes[index] == target) {
                return index;
            }
        }
        throw new IllegalStateException("fixture field does not contain requested byte");
    }

    private static String randomHash(Random random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void resignPayload(byte[] bytes) {
        int payloadLength = intAt(bytes, PAYLOAD_LENGTH_OFFSET);
        int checksumOffset = PAYLOAD_OFFSET + payloadLength;
        byte[] payload = Arrays.copyOfRange(bytes, PAYLOAD_OFFSET, checksumOffset);
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

    private static void assertRejected(byte[] bytes) {
        assertThrows(IOException.class, () -> BlockCacheManifestIO.fromBytes(bytes));
    }

    private record Layout(
            int profileLengthOffset,
            int profileOffset,
            int profileLength,
            int codecVersionOffset,
            int entryCountOffset,
            int firstLogicalLengthOffset,
            int firstLogicalOffset,
            int firstLogicalLength,
            int firstSourceLengthOffset,
            int firstSourceOffset,
            int firstBlobLengthOffset,
            int firstBlobOffset,
            int firstBlobLength,
            int firstFormatOffset,
            int firstWidthOffset,
            int firstHeightOffset,
            int firstLevelCountOffset,
            int firstBlockBytesOffset,
            int firstMeanDeltaEOffset,
            int firstP99DeltaEOffset,
            int secondLogicalOffset,
            int secondLogicalLength) {
    }
}

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

class TextureManifestPersistencePropertyTest {
    private static final long ROUND_TRIP_SEED = 0x5350464d524f554eL;
    private static final long CORRUPTION_SEED = 0x5350464d434f5252L;
    private static final long TRUNCATION_SEED = 0x5350464d5452554eL;

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
            TextureManifest manifest = randomManifest(random, iteration);
            byte[] encoded = TextureManifestIO.toBytes(manifest);
            TextureManifest restored = TextureManifestIO.fromBytes(encoded);

            assertEquals(manifest.profileFingerprint(), restored.profileFingerprint(), "profile " + iteration);
            assertEquals(manifest.entries(), restored.entries(), "entries " + iteration);
            assertArrayEquals(encoded, TextureManifestIO.toBytes(restored), "canonical bytes " + iteration);
        }
    }

    @Test
    void seededSingleBitCorruptionAlwaysRejects() throws Exception {
        byte[] valid = TextureManifestIO.toBytes(fixtureManifest());
        Random random = new Random(CORRUPTION_SEED);

        for (int iteration = 0; iteration < 1_000; iteration++) {
            byte[] corrupt = valid.clone();
            int byteIndex = random.nextInt(corrupt.length);
            corrupt[byteIndex] ^= (byte) (1 << random.nextInt(Byte.SIZE));

            assertThrows(
                    IOException.class,
                    () -> TextureManifestIO.fromBytes(corrupt),
                    "single-bit corruption " + iteration + " at byte " + byteIndex);
        }
    }

    @Test
    void seededAndBoundaryTruncationsAlwaysReject() throws Exception {
        byte[] valid = TextureManifestIO.toBytes(fixtureManifest());
        Random random = new Random(TRUNCATION_SEED);

        for (int iteration = 0; iteration < 500; iteration++) {
            int length = random.nextInt(valid.length);
            assertThrows(
                    IOException.class,
                    () -> TextureManifestIO.fromBytes(Arrays.copyOf(valid, length)),
                    "seeded truncation " + iteration + " at " + length);
        }

        int payloadLength = intAt(valid, PAYLOAD_LENGTH_OFFSET);
        int checksumOffset = PAYLOAD_OFFSET + payloadLength;
        int[] boundaries = {0, 1, 3, 4, 7, 8, 11, 12, checksumOffset - 1, checksumOffset,
                checksumOffset + CHECKSUM_BYTES - 1};
        for (int length : boundaries) {
            if (length >= 0 && length < valid.length) {
                assertThrows(
                        IOException.class,
                        () -> TextureManifestIO.fromBytes(Arrays.copyOf(valid, length)),
                        "boundary truncation at " + length);
            }
        }
    }

    @Test
    void hostileAuthenticatedLengthsAndCountsRejectDeterministically() throws Exception {
        byte[] valid = TextureManifestIO.toBytes(fixtureManifest());
        Layout layout = layout(valid);

        assertRejected(withInt(valid, PAYLOAD_LENGTH_OFFSET, -1, false));
        assertRejected(withInt(valid, PAYLOAD_LENGTH_OFFSET, Integer.MAX_VALUE, false));
        assertRejected(withInt(valid, layout.profileLengthOffset(), MAX_STRING_BYTES + 1, true));
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
        byte[] valid = TextureManifestIO.toBytes(fixtureManifest());
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

        byte[] invalidTransformation = withInt(valid, layout.firstTransformationOffset(), Integer.MAX_VALUE, true);
        assertRejected(invalidTransformation);

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

        assertRejected(withInt(valid, layout.firstWidthOffset(), 0, true));
        assertRejected(withInt(valid, layout.firstHeightOffset(), 0, true));
        assertRejected(withInt(valid, layout.firstChannelsOffset(), 2, true));
        assertRejected(withInt(valid, layout.firstPixelBytesOffset(), 15, true));

        byte[] overflow = valid.clone();
        putInt(overflow, layout.firstWidthOffset(), Integer.MAX_VALUE);
        putInt(overflow, layout.firstHeightOffset(), Integer.MAX_VALUE);
        putInt(overflow, layout.firstChannelsOffset(), 4);
        resignPayload(overflow);
        assertRejected(overflow);

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
    void diskReaderRejectsOversizedFileBeforeWholeFileRead() throws Exception {
        Path oversized = temporaryDirectory.resolve("oversized.spfm");
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
                () -> assertThrows(IOException.class, () -> TextureManifestIO.read(oversized, readLimit)));
        assertEquals("Texture manifest exceeds the " + readLimit + " byte safety limit: " + oversized,
                error.getMessage());
    }

    private static TextureManifest randomManifest(Random random, int iteration) {
        int entryCount = 1 + random.nextInt(8);
        List<Integer> insertionOrder = new ArrayList<>();
        for (int entry = 0; entry < entryCount; entry++) {
            insertionOrder.add(entry);
        }
        Collections.shuffle(insertionOrder, random);

        Map<String, TextureManifest.Entry> entries = new LinkedHashMap<>();
        PreparedTexture.Transformation[] transformations = PreparedTexture.Transformation.values();
        for (int entry : insertionOrder) {
            String source = randomHash(random);
            PreparedTexture.Transformation transformation = transformations[random.nextInt(transformations.length)];
            int width = 1 + random.nextInt(64);
            int height = 1 + random.nextInt(64);
            int channels = random.nextBoolean() ? 3 : 4;
            int pixels = Math.multiplyExact(Math.multiplyExact(width, height), channels);
            String logicalPath = "graphics/group" + (entry % 3) + "/entry-" + iteration + "-" + entry + ".png";
            String blobPath = "blobs/" + source.substring(0, 2) + "/" + source + "-"
                    + transformation.name().toLowerCase(Locale.ROOT) + ".spft";
            entries.put(logicalPath, new TextureManifest.Entry(
                    source,
                    transformation,
                    blobPath,
                    width,
                    height,
                    channels,
                    pixels));
        }
        return new TextureManifest(
                "profile-" + iteration + "-π-" + Integer.toUnsignedString(random.nextInt()),
                entries);
    }

    private static TextureManifest fixtureManifest() {
        Map<String, TextureManifest.Entry> entries = new LinkedHashMap<>();
        entries.put("a/a.png", new TextureManifest.Entry(
                "aa".repeat(32),
                PreparedTexture.Transformation.IDENTITY,
                "blobs/aa/a.spft",
                2,
                2,
                4,
                16));
        entries.put("b/b.png", new TextureManifest.Entry(
                "bb".repeat(32),
                PreparedTexture.Transformation.ALPHA_ADDER,
                "blobs/bb/b.spft",
                1,
                1,
                3,
                3));
        return new TextureManifest("fixture-profile", entries);
    }

    private static Layout layout(byte[] encoded) {
        int cursor = PAYLOAD_OFFSET;
        int profileLengthOffset = cursor;
        int profileLength = intAt(encoded, cursor);
        int profileOffset = cursor + Integer.BYTES;
        cursor = skipString(encoded, cursor);

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
        int firstSourceLength = intAt(encoded, cursor);
        int firstSourceOffset = cursor + Integer.BYTES;
        cursor = skipString(encoded, cursor);

        int firstTransformationOffset = cursor;
        cursor += Integer.BYTES;

        int firstBlobLengthOffset = cursor;
        int firstBlobLength = intAt(encoded, cursor);
        int firstBlobOffset = cursor + Integer.BYTES;
        cursor = skipString(encoded, cursor);

        int firstWidthOffset = cursor;
        int firstHeightOffset = cursor + Integer.BYTES;
        int firstChannelsOffset = cursor + Integer.BYTES * 2;
        int firstPixelBytesOffset = cursor + Integer.BYTES * 3;
        cursor += Integer.BYTES * 4;

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
                entryCountOffset,
                firstLogicalLengthOffset,
                firstLogicalOffset,
                firstLogicalLength,
                firstSourceLengthOffset,
                firstSourceOffset,
                firstSourceLength,
                firstTransformationOffset,
                firstBlobLengthOffset,
                firstBlobOffset,
                firstBlobLength,
                firstWidthOffset,
                firstHeightOffset,
                firstChannelsOffset,
                firstPixelBytesOffset,
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

    private static void assertRejected(byte[] bytes) {
        assertThrows(IOException.class, () -> TextureManifestIO.fromBytes(bytes));
    }

    private record Layout(
            int profileLengthOffset,
            int profileOffset,
            int profileLength,
            int entryCountOffset,
            int firstLogicalLengthOffset,
            int firstLogicalOffset,
            int firstLogicalLength,
            int firstSourceLengthOffset,
            int firstSourceOffset,
            int firstSourceLength,
            int firstTransformationOffset,
            int firstBlobLengthOffset,
            int firstBlobOffset,
            int firstBlobLength,
            int firstWidthOffset,
            int firstHeightOffset,
            int firstChannelsOffset,
            int firstPixelBytesOffset,
            int secondLogicalOffset,
            int secondLogicalLength) {
    }
}

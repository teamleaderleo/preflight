package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
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
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarArchiveIndexPersistencePropertyTest {
    private static final long ROUND_TRIP_SEED = 0x5350464a524f554eL;
    private static final long CORRUPTION_SEED = 0x5350464a434f5252L;
    private static final long TRUNCATION_SEED = 0x5350464a5452554eL;

    private static final int PAYLOAD_LENGTH_OFFSET = 8;
    private static final int PAYLOAD_OFFSET = 12;
    private static final int SHA256_BYTES = 32;
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;
    private static final int MAX_ENTRY_COUNT = 10_000_000;
    private static final int MAX_NAME_BYTES = 4 * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void seededRoundTripsRemainCanonical() throws Exception {
        Random random = new Random(ROUND_TRIP_SEED);
        for (int iteration = 0; iteration < 500; iteration++) {
            JarArchiveIndex index = randomIndex(random, iteration);
            byte[] encoded = JarArchiveIndexIO.toBytes(index);
            JarArchiveIndex restored = JarArchiveIndexIO.fromBytes(encoded);

            assertEquals(index.sourceSha256(), restored.sourceSha256(), "source hash " + iteration);
            assertEquals(index.sourceBytes(), restored.sourceBytes(), "source bytes " + iteration);
            assertEquals(index.entries(), restored.entries(), "entries " + iteration);
            assertArrayEquals(encoded, JarArchiveIndexIO.toBytes(restored), "canonical bytes " + iteration);
        }
    }

    @Test
    void seededSingleBitCorruptionAlwaysRejects() throws Exception {
        byte[] valid = JarArchiveIndexIO.toBytes(fixtureIndex());
        Random random = new Random(CORRUPTION_SEED);

        for (int iteration = 0; iteration < 1_000; iteration++) {
            byte[] corrupt = valid.clone();
            int byteIndex = random.nextInt(corrupt.length);
            corrupt[byteIndex] ^= (byte) (1 << random.nextInt(Byte.SIZE));

            assertThrows(
                    IOException.class,
                    () -> JarArchiveIndexIO.fromBytes(corrupt),
                    "single-bit corruption " + iteration + " at byte " + byteIndex);
        }
    }

    @Test
    void seededAndBoundaryTruncationsAlwaysReject() throws Exception {
        byte[] valid = JarArchiveIndexIO.toBytes(fixtureIndex());
        Random random = new Random(TRUNCATION_SEED);

        for (int iteration = 0; iteration < 500; iteration++) {
            int length = random.nextInt(valid.length);
            assertThrows(
                    IOException.class,
                    () -> JarArchiveIndexIO.fromBytes(Arrays.copyOf(valid, length)),
                    "seeded truncation " + iteration + " at " + length);
        }

        int payloadLength = intAt(valid, PAYLOAD_LENGTH_OFFSET);
        int checksumOffset = PAYLOAD_OFFSET + payloadLength;
        int[] boundaries = {
            0,
            1,
            3,
            4,
            7,
            8,
            11,
            12,
            PAYLOAD_OFFSET + SHA256_BYTES - 1,
            PAYLOAD_OFFSET + SHA256_BYTES,
            checksumOffset - 1,
            checksumOffset,
            checksumOffset + CHECKSUM_BYTES - 1
        };
        for (int length : boundaries) {
            if (length >= 0 && length < valid.length) {
                assertThrows(
                        IOException.class,
                        () -> JarArchiveIndexIO.fromBytes(Arrays.copyOf(valid, length)),
                        "boundary truncation at " + length);
            }
        }
    }

    @Test
    void hostileAuthenticatedLengthsAndCountsRejectDeterministically() throws Exception {
        byte[] valid = JarArchiveIndexIO.toBytes(fixtureIndex());
        Layout layout = layout(valid);

        assertRejected(withInt(valid, PAYLOAD_LENGTH_OFFSET, -1, false));
        assertRejected(withInt(valid, PAYLOAD_LENGTH_OFFSET, Integer.MAX_VALUE, false));
        assertRejected(withInt(valid, layout.entryCountOffset(), Integer.MAX_VALUE, true));
        assertRejected(withInt(valid, layout.firstNameLengthOffset(), MAX_NAME_BYTES + 1, true));

        byte[] maxEntries = withInt(valid, layout.entryCountOffset(), MAX_ENTRY_COUNT, true);
        assertTimeout(Duration.ofSeconds(2), () -> assertRejected(maxEntries));
    }

    @Test
    void hostileAuthenticatedEntryStateNeverBecomesAnIndex() throws Exception {
        byte[] valid = JarArchiveIndexIO.toBytes(fixtureIndex());
        Layout layout = layout(valid);

        assertRejected(withLong(valid, layout.sourceBytesOffset(), -1L, true));
        assertRejected(withLong(valid, layout.firstUncompressedOffset(), -1L, true));
        assertRejected(withLong(valid, layout.firstCompressedOffset(), -1L, true));
        assertRejected(withLong(valid, layout.firstCrcOffset(), -2L, true));
        assertRejected(withLong(valid, layout.firstCrcOffset(), 0x1_0000_0000L, true));
        assertRejected(withInt(valid, layout.firstMethodOffset(), -2, true));

        byte[] traversal = valid.clone();
        replaceAsciiSameLength(
                traversal,
                layout.firstNameOffset(),
                layout.firstNameLength(),
                traversalOfLength(layout.firstNameLength()));
        resignPayload(traversal);
        assertRejected(traversal);

        byte[] duplicate = valid.clone();
        replaceAsciiSameLength(
                duplicate,
                layout.secondNameOffset(),
                layout.secondNameLength(),
                "a/A.class");
        resignPayload(duplicate);
        assertRejected(duplicate);

        byte[] malformedUtf8 = valid.clone();
        malformedUtf8[layout.firstNameOffset()] = (byte) 0xc3;
        malformedUtf8[layout.firstNameOffset() + 1] = 0x28;
        resignPayload(malformedUtf8);
        assertRejected(malformedUtf8);
    }

    @Test
    void aggregateOverflowAndTrailingPayloadReject() throws Exception {
        byte[] valid = JarArchiveIndexIO.toBytes(fixtureIndex());
        Layout layout = layout(valid);

        byte[] uncompressedOverflow = valid.clone();
        putLong(uncompressedOverflow, layout.firstUncompressedOffset(), Long.MAX_VALUE);
        putLong(uncompressedOverflow, layout.secondUncompressedOffset(), Long.MAX_VALUE);
        resignPayload(uncompressedOverflow);
        assertRejected(uncompressedOverflow);

        byte[] compressedOverflow = valid.clone();
        putLong(compressedOverflow, layout.firstCompressedOffset(), Long.MAX_VALUE);
        putLong(compressedOverflow, layout.secondCompressedOffset(), Long.MAX_VALUE);
        resignPayload(compressedOverflow);
        assertRejected(compressedOverflow);

        assertRejected(withTrailingPayloadByte(valid));
    }

    @Test
    void diskReaderRejectsOversizedFileBeforeWholeFileRead() throws Exception {
        Path oversized = temporaryDirectory.resolve("oversized.spfj");
        try (FileChannel channel = FileChannel.open(
                oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position((long) MAX_FILE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }

        assertTimeout(
                Duration.ofSeconds(2),
                () -> assertThrows(IOException.class, () -> JarArchiveIndexIO.read(oversized)));
    }

    private static JarArchiveIndex randomIndex(Random random, int iteration) {
        int entryCount = 1 + random.nextInt(16);
        List<String> names = new ArrayList<>();
        for (int entry = 0; entry < entryCount; entry++) {
            String suffix = (entry & 1) == 0 ? ".class" : ".json";
            names.add("pkg/group" + (entry % 5) + "/Entry" + iteration + "_" + entry + suffix);
        }
        Collections.shuffle(names, random);

        Map<String, JarArchiveIndex.Entry> entries = new LinkedHashMap<>();
        for (String name : names) {
            long uncompressed = random.nextInt(1_000_000);
            long compressed = random.nextInt(1_000_000);
            long crc = random.nextBoolean() ? -1L : Integer.toUnsignedLong(random.nextInt());
            int method = switch (random.nextInt(3)) {
                case 0 -> -1;
                case 1 -> 0;
                default -> 8;
            };
            entries.put(name, new JarArchiveIndex.Entry(name, uncompressed, compressed, crc, method));
        }
        return new JarArchiveIndex(randomHash(random), random.nextInt(20_000_000), entries);
    }

    private static JarArchiveIndex fixtureIndex() {
        Map<String, JarArchiveIndex.Entry> entries = new LinkedHashMap<>();
        entries.put("a/A.class", new JarArchiveIndex.Entry("a/A.class", 100, 80, 1, 8));
        entries.put("b/B.class", new JarArchiveIndex.Entry("b/B.class", 200, 150, 2, 8));
        return new JarArchiveIndex("ab".repeat(32), 4096, entries);
    }

    private static Layout layout(byte[] encoded) {
        int cursor = PAYLOAD_OFFSET + SHA256_BYTES;
        int sourceBytesOffset = cursor;
        cursor += Long.BYTES;
        int entryCountOffset = cursor;
        int count = intAt(encoded, cursor);
        cursor += Integer.BYTES;
        if (count < 2) {
            throw new IllegalStateException("fixture requires two entries");
        }

        int firstNameLengthOffset = cursor;
        int firstNameLength = intAt(encoded, cursor);
        int firstNameOffset = cursor + Integer.BYTES;
        cursor += Integer.BYTES + firstNameLength;
        int firstUncompressedOffset = cursor;
        cursor += Long.BYTES;
        int firstCompressedOffset = cursor;
        cursor += Long.BYTES;
        int firstCrcOffset = cursor;
        cursor += Long.BYTES;
        int firstMethodOffset = cursor;
        cursor += Integer.BYTES;

        int secondNameLengthOffset = cursor;
        int secondNameLength = intAt(encoded, secondNameLengthOffset);
        int secondNameOffset = secondNameLengthOffset + Integer.BYTES;
        cursor += Integer.BYTES + secondNameLength;
        int secondUncompressedOffset = cursor;
        cursor += Long.BYTES;
        int secondCompressedOffset = cursor;

        if (firstNameLength != "a/A.class".length()
                || secondNameLength != "a/A.class".length()) {
            throw new IllegalStateException("fixture names must match replacement length");
        }
        return new Layout(
                sourceBytesOffset,
                entryCountOffset,
                firstNameLengthOffset,
                firstNameOffset,
                firstNameLength,
                firstUncompressedOffset,
                firstCompressedOffset,
                firstCrcOffset,
                firstMethodOffset,
                secondNameOffset,
                secondNameLength,
                secondUncompressedOffset,
                secondCompressedOffset);
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

    private static byte[] withTrailingPayloadByte(byte[] source) {
        int payloadLength = intAt(source, PAYLOAD_LENGTH_OFFSET);
        byte[] payload = Arrays.copyOfRange(source, PAYLOAD_OFFSET, PAYLOAD_OFFSET + payloadLength);
        byte[] expandedPayload = Arrays.copyOf(payload, payload.length + 1);
        expandedPayload[expandedPayload.length - 1] = 1;
        ByteBuffer bytes = ByteBuffer.allocate(PAYLOAD_OFFSET + expandedPayload.length + CHECKSUM_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        bytes.put(new byte[] {'S', 'P', 'F', 'J'});
        bytes.putInt(JarArchiveIndex.FORMAT_VERSION);
        bytes.putInt(expandedPayload.length);
        bytes.put(expandedPayload);
        bytes.put(Hashes.sha256Bytes(expandedPayload));
        return bytes.array();
    }

    private static void replaceAsciiSameLength(
            byte[] bytes,
            int offset,
            int expectedLength,
            String replacement) {
        byte[] replacementBytes = replacement.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (replacementBytes.length != expectedLength) {
            throw new IllegalArgumentException("replacement length differs from persisted field");
        }
        System.arraycopy(replacementBytes, 0, bytes, offset, replacementBytes.length);
    }

    private static String traversalOfLength(int length) {
        if (length < 3) {
            throw new IllegalArgumentException("entry name must have room for ../");
        }
        return "../" + "x".repeat(length - 3);
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

    private static String randomHash(Random random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void assertRejected(byte[] bytes) {
        assertThrows(IOException.class, () -> JarArchiveIndexIO.fromBytes(bytes));
    }

    private record Layout(
            int sourceBytesOffset,
            int entryCountOffset,
            int firstNameLengthOffset,
            int firstNameOffset,
            int firstNameLength,
            int firstUncompressedOffset,
            int firstCompressedOffset,
            int firstCrcOffset,
            int firstMethodOffset,
            int secondNameOffset,
            int secondNameLength,
            int secondUncompressedOffset,
            int secondCompressedOffset) {
    }
}

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
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathProfileIndexPersistencePropertyTest {
    private static final long ROUND_TRIP_SEED = 0x53504643524f554eL;
    private static final long CORRUPTION_SEED = 0x53504643434f5252L;
    private static final long TRUNCATION_SEED = 0x535046435452554eL;

    private static final int PAYLOAD_LENGTH_OFFSET = 8;
    private static final int PAYLOAD_OFFSET = 12;
    private static final int PROFILE_HASH_BYTES = 32;
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;
    private static final int MAX_ARCHIVES = 1_000_000;
    private static final int MAX_ENTRIES = 20_000_000;
    private static final int MAX_PROVIDERS_PER_ENTRY = 100_000;
    private static final int MAX_STRING_BYTES = 4 * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void seededRoundTripsRemainCanonical() throws Exception {
        Random random = new Random(ROUND_TRIP_SEED);
        for (int iteration = 0; iteration < 400; iteration++) {
            ClasspathProfileIndex index = randomIndex(random, iteration);
            byte[] encoded = ClasspathProfileIndexIO.toBytes(index);
            ClasspathProfileIndex restored = ClasspathProfileIndexIO.fromBytes(encoded);

            assertEquals(index.profileFingerprint(), restored.profileFingerprint(), "profile " + iteration);
            assertEquals(index.archives(), restored.archives(), "archives " + iteration);
            assertEquals(index.providers(), restored.providers(), "providers " + iteration);
            assertArrayEquals(encoded, ClasspathProfileIndexIO.toBytes(restored), "canonical bytes " + iteration);
        }
    }

    @Test
    void seededSingleBitCorruptionAlwaysRejects() throws Exception {
        byte[] valid = ClasspathProfileIndexIO.toBytes(fixtureIndex());
        Random random = new Random(CORRUPTION_SEED);

        for (int iteration = 0; iteration < 1_000; iteration++) {
            byte[] corrupt = valid.clone();
            int byteIndex = random.nextInt(corrupt.length);
            corrupt[byteIndex] ^= (byte) (1 << random.nextInt(Byte.SIZE));

            assertThrows(
                    IOException.class,
                    () -> ClasspathProfileIndexIO.fromBytes(corrupt),
                    "single-bit corruption " + iteration + " at byte " + byteIndex);
        }
    }

    @Test
    void seededAndBoundaryTruncationsAlwaysReject() throws Exception {
        byte[] valid = ClasspathProfileIndexIO.toBytes(fixtureIndex());
        Random random = new Random(TRUNCATION_SEED);

        for (int iteration = 0; iteration < 500; iteration++) {
            int length = random.nextInt(valid.length);
            assertThrows(
                    IOException.class,
                    () -> ClasspathProfileIndexIO.fromBytes(Arrays.copyOf(valid, length)),
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
            PAYLOAD_OFFSET + PROFILE_HASH_BYTES - 1,
            PAYLOAD_OFFSET + PROFILE_HASH_BYTES,
            checksumOffset - 1,
            checksumOffset,
            checksumOffset + CHECKSUM_BYTES - 1
        };
        for (int length : boundaries) {
            if (length >= 0 && length < valid.length) {
                assertThrows(
                        IOException.class,
                        () -> ClasspathProfileIndexIO.fromBytes(Arrays.copyOf(valid, length)),
                        "boundary truncation at " + length);
            }
        }
    }

    @Test
    void hostileAuthenticatedLengthsAndCountsRejectDeterministically() throws Exception {
        byte[] valid = ClasspathProfileIndexIO.toBytes(fixtureIndex());
        Layout layout = layout(valid);

        assertRejected(withInt(valid, PAYLOAD_LENGTH_OFFSET, -1, false));
        assertRejected(withInt(valid, PAYLOAD_LENGTH_OFFSET, Integer.MAX_VALUE, false));
        assertRejected(withInt(valid, layout.archiveCountOffset(), Integer.MAX_VALUE, true));
        assertRejected(withInt(valid, layout.firstModIdLengthOffset(), MAX_STRING_BYTES + 1, true));
        assertRejected(withInt(valid, layout.firstRelativePathLengthOffset(), MAX_STRING_BYTES + 1, true));
        assertRejected(withInt(valid, layout.firstPhysicalPathLengthOffset(), MAX_STRING_BYTES + 1, true));
        assertRejected(withInt(valid, layout.firstArchiveIndexPathLengthOffset(), MAX_STRING_BYTES + 1, true));
        assertRejected(withInt(valid, layout.entryCountOffset(), Integer.MAX_VALUE, true));
        assertRejected(withInt(valid, layout.firstProviderCountOffset(), 0, true));
        assertRejected(withInt(
                valid,
                layout.firstProviderCountOffset(),
                MAX_PROVIDERS_PER_ENTRY + 1,
                true));

        byte[] maxArchives = withInt(valid, layout.archiveCountOffset(), MAX_ARCHIVES, true);
        assertTimeout(Duration.ofSeconds(2), () -> assertRejected(maxArchives));

        byte[] maxEntries = withInt(valid, layout.entryCountOffset(), MAX_ENTRIES, true);
        assertTimeout(Duration.ofSeconds(2), () -> assertRejected(maxEntries));
    }

    @Test
    void hostileAuthenticatedArchiveAndProviderStateNeverBecomesAnIndex() throws Exception {
        byte[] valid = ClasspathProfileIndexIO.toBytes(fixtureIndex());
        Layout layout = layout(valid);

        assertRejected(withLong(valid, layout.firstSourceBytesOffset(), -1L, true));
        assertRejected(withLong(valid, layout.firstModifiedMillisOffset(), -1L, true));
        assertRejected(withInt(valid, layout.firstProviderIndexOffset(), Integer.MAX_VALUE, true));

        byte[] blankModId = valid.clone();
        replaceAsciiSameLength(
                blankModId,
                layout.firstModIdOffset(),
                layout.firstModIdLength(),
                " ".repeat(layout.firstModIdLength()));
        resignPayload(blankModId);
        assertRejected(blankModId);

        byte[] relativeTraversal = valid.clone();
        replaceAsciiSameLength(
                relativeTraversal,
                layout.firstRelativePathOffset(),
                layout.firstRelativePathLength(),
                traversalOfLength(layout.firstRelativePathLength()));
        resignPayload(relativeTraversal);
        assertRejected(relativeTraversal);

        byte[] archiveIndexTraversal = valid.clone();
        replaceAsciiSameLength(
                archiveIndexTraversal,
                layout.firstArchiveIndexPathOffset(),
                layout.firstArchiveIndexPathLength(),
                traversalOfLength(layout.firstArchiveIndexPathLength()));
        resignPayload(archiveIndexTraversal);
        assertRejected(archiveIndexTraversal);

        byte[] duplicateEntry = valid.clone();
        replaceAsciiSameLength(
                duplicateEntry,
                layout.secondEntryNameOffset(),
                layout.secondEntryNameLength(),
                "a/A.class");
        resignPayload(duplicateEntry);
        assertRejected(duplicateEntry);

        byte[] outOfOrderProviders = valid.clone();
        putInt(outOfOrderProviders, layout.firstProviderIndexOffset(), 1);
        putInt(outOfOrderProviders, layout.secondProviderIndexOffset(), 0);
        resignPayload(outOfOrderProviders);
        assertRejected(outOfOrderProviders);

        byte[] duplicateProviders = valid.clone();
        putInt(duplicateProviders, layout.secondProviderIndexOffset(), 0);
        resignPayload(duplicateProviders);
        assertRejected(duplicateProviders);
    }

    @Test
    void diskReaderRejectsOversizedFileBeforeWholeFileRead() throws Exception {
        Path oversized = temporaryDirectory.resolve("oversized.spfc");
        try (FileChannel channel = FileChannel.open(
                oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position((long) MAX_FILE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }

        assertTimeout(
                Duration.ofSeconds(2),
                () -> assertThrows(IOException.class, () -> ClasspathProfileIndexIO.read(oversized)));
    }

    private static ClasspathProfileIndex randomIndex(Random random, int iteration) {
        int archiveCount = 1 + random.nextInt(5);
        List<ClasspathProfileIndex.Archive> archives = new ArrayList<>();
        for (int archive = 0; archive < archiveCount; archive++) {
            archives.add(new ClasspathProfileIndex.Archive(
                    "mod-" + iteration + "-" + archive + "-π",
                    "jars/archive-" + archive + ".jar",
                    Path.of("property-classpath", "iteration-" + iteration, "archive-" + archive + ".jar"),
                    randomHash(random),
                    random.nextInt(10_000_000),
                    Integer.toUnsignedLong(random.nextInt()),
                    "classpath/archives/archive-" + archive + ".spfj",
                    random.nextBoolean()));
        }

        int entryCount = 1 + random.nextInt(12);
        List<String> entryNames = new ArrayList<>();
        for (int entry = 0; entry < entryCount; entry++) {
            entryNames.add("pkg/group" + (entry % 4) + "/Entry" + entry + ".class");
        }
        Collections.shuffle(entryNames, random);

        Map<String, List<Integer>> providers = new LinkedHashMap<>();
        for (String name : entryNames) {
            List<Integer> candidates = new ArrayList<>();
            for (int archive = 0; archive < archiveCount; archive++) {
                candidates.add(archive);
            }
            Collections.shuffle(candidates, random);
            int providerCount = 1 + random.nextInt(archiveCount);
            List<Integer> selected = new ArrayList<>(candidates.subList(0, providerCount));
            Collections.sort(selected);
            providers.put(name, selected);
        }
        return new ClasspathProfileIndex(randomHash(random), archives, providers);
    }

    private static ClasspathProfileIndex fixtureIndex() {
        List<ClasspathProfileIndex.Archive> archives = List.of(
                new ClasspathProfileIndex.Archive(
                        "alpha",
                        "a.jar",
                        Path.of("fixture-classpath", "alpha", "a.jar"),
                        "aa".repeat(32),
                        100,
                        10,
                        "classpath/a.spfj",
                        true),
                new ClasspathProfileIndex.Archive(
                        "bravo",
                        "b.jar",
                        Path.of("fixture-classpath", "bravo", "b.jar"),
                        "bb".repeat(32),
                        200,
                        20,
                        "classpath/b.spfj",
                        false));
        Map<String, List<Integer>> providers = new LinkedHashMap<>();
        providers.put("a/A.class", List.of(0, 1));
        providers.put("b/B.class", List.of(1));
        return new ClasspathProfileIndex("cc".repeat(32), archives, providers);
    }

    private static Layout layout(byte[] encoded) {
        int cursor = PAYLOAD_OFFSET + PROFILE_HASH_BYTES;
        int archiveCountOffset = cursor;
        int archiveCount = intAt(encoded, cursor);
        cursor += Integer.BYTES;
        if (archiveCount < 2) {
            throw new IllegalStateException("fixture requires two archives");
        }

        int firstModIdLengthOffset = cursor;
        int firstModIdLength = intAt(encoded, cursor);
        int firstModIdOffset = cursor + Integer.BYTES;
        cursor = skipString(encoded, cursor);

        int firstRelativePathLengthOffset = cursor;
        int firstRelativePathLength = intAt(encoded, cursor);
        int firstRelativePathOffset = cursor + Integer.BYTES;
        cursor = skipString(encoded, cursor);

        int firstPhysicalPathLengthOffset = cursor;
        cursor = skipString(encoded, cursor);
        cursor += PROFILE_HASH_BYTES;
        int firstSourceBytesOffset = cursor;
        cursor += Long.BYTES;
        int firstModifiedMillisOffset = cursor;
        cursor += Long.BYTES;

        int firstArchiveIndexPathLengthOffset = cursor;
        int firstArchiveIndexPathLength = intAt(encoded, cursor);
        int firstArchiveIndexPathOffset = cursor + Integer.BYTES;
        cursor = skipString(encoded, cursor);
        cursor += 1;

        for (int archive = 1; archive < archiveCount; archive++) {
            cursor = skipString(encoded, cursor);
            cursor = skipString(encoded, cursor);
            cursor = skipString(encoded, cursor);
            cursor += PROFILE_HASH_BYTES + Long.BYTES * 2;
            cursor = skipString(encoded, cursor);
            cursor += 1;
        }

        int entryCountOffset = cursor;
        int entryCount = intAt(encoded, cursor);
        cursor += Integer.BYTES;
        if (entryCount < 2) {
            throw new IllegalStateException("fixture requires two entries");
        }

        cursor = skipString(encoded, cursor);
        int firstProviderCountOffset = cursor;
        int firstProviderCount = intAt(encoded, cursor);
        cursor += Integer.BYTES;
        if (firstProviderCount < 2) {
            throw new IllegalStateException("fixture requires two providers on the first entry");
        }
        int firstProviderIndexOffset = cursor;
        int secondProviderIndexOffset = cursor + Integer.BYTES;
        cursor += Integer.BYTES * firstProviderCount;

        int secondEntryNameLengthOffset = cursor;
        int secondEntryNameLength = intAt(encoded, secondEntryNameLengthOffset);
        int secondEntryNameOffset = secondEntryNameLengthOffset + Integer.BYTES;
        if (secondEntryNameLength != "a/A.class".length()) {
            throw new IllegalStateException("fixture second entry must match duplicate replacement length");
        }

        return new Layout(
                archiveCountOffset,
                firstModIdLengthOffset,
                firstModIdOffset,
                firstModIdLength,
                firstRelativePathLengthOffset,
                firstRelativePathOffset,
                firstRelativePathLength,
                firstPhysicalPathLengthOffset,
                firstSourceBytesOffset,
                firstModifiedMillisOffset,
                firstArchiveIndexPathLengthOffset,
                firstArchiveIndexPathOffset,
                firstArchiveIndexPathLength,
                entryCountOffset,
                firstProviderCountOffset,
                firstProviderIndexOffset,
                secondProviderIndexOffset,
                secondEntryNameOffset,
                secondEntryNameLength);
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
        ByteBuffer.wrap(changed).order(ByteOrder.BIG_ENDIAN).putLong(offset, value);
        if (resign) {
            resignPayload(changed);
        }
        return changed;
    }

    private static void replaceAsciiSameLength(
            byte[] bytes,
            int offset,
            int expectedLength,
            String replacement) {
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

    private static String randomHash(Random random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void assertRejected(byte[] bytes) {
        assertThrows(IOException.class, () -> ClasspathProfileIndexIO.fromBytes(bytes));
    }

    private record Layout(
            int archiveCountOffset,
            int firstModIdLengthOffset,
            int firstModIdOffset,
            int firstModIdLength,
            int firstRelativePathLengthOffset,
            int firstRelativePathOffset,
            int firstRelativePathLength,
            int firstPhysicalPathLengthOffset,
            int firstSourceBytesOffset,
            int firstModifiedMillisOffset,
            int firstArchiveIndexPathLengthOffset,
            int firstArchiveIndexPathOffset,
            int firstArchiveIndexPathLength,
            int entryCountOffset,
            int firstProviderCountOffset,
            int firstProviderIndexOffset,
            int secondProviderIndexOffset,
            int secondEntryNameOffset,
            int secondEntryNameLength) {
    }
}

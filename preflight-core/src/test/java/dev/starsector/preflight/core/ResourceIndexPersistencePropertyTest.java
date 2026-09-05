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

class ResourceIndexPersistencePropertyTest {
    private static final long ROUND_TRIP_SEED = 0x53504649524f554eL;
    private static final long CORRUPTION_SEED = 0x53504649434f5252L;
    private static final long TRUNCATION_SEED = 0x535046495452554eL;

    private static final int PAYLOAD_LENGTH_OFFSET = 8;
    private static final int PAYLOAD_OFFSET = 12;
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;
    private static final int MAX_ROOTS = 100_000;
    private static final int MAX_ENTRIES = 10_000_000;
    private static final int MAX_PROVIDERS_PER_ENTRY = 100_000;

    @TempDir
    Path temporaryDirectory;

    @Test
    void seededRoundTripsRemainCanonical() throws Exception {
        Random random = new Random(ROUND_TRIP_SEED);
        for (int iteration = 0; iteration < 500; iteration++) {
            ResourceIndex index = randomIndex(random, iteration);
            byte[] encoded = ResourceIndexIO.toBytes(index);
            ResourceIndex restored = ResourceIndexIO.fromBytes(encoded);

            assertEquals(index.profileFingerprint(), restored.profileFingerprint(), "profile " + iteration);
            assertEquals(index.roots(), restored.roots(), "roots " + iteration);
            assertEquals(index.entries(), restored.entries(), "entries " + iteration);
            assertArrayEquals(encoded, ResourceIndexIO.toBytes(restored), "canonical bytes " + iteration);
        }
    }

    @Test
    void seededSingleBitCorruptionAlwaysRejects() throws Exception {
        byte[] valid = ResourceIndexIO.toBytes(fixtureIndex());
        Random random = new Random(CORRUPTION_SEED);

        for (int iteration = 0; iteration < 1_000; iteration++) {
            byte[] corrupt = valid.clone();
            int byteIndex = random.nextInt(corrupt.length);
            corrupt[byteIndex] ^= (byte) (1 << random.nextInt(Byte.SIZE));

            assertThrows(
                    IOException.class,
                    () -> ResourceIndexIO.fromBytes(corrupt),
                    "single-bit corruption " + iteration + " at byte " + byteIndex);
        }
    }

    @Test
    void seededAndBoundaryTruncationsAlwaysReject() throws Exception {
        byte[] valid = ResourceIndexIO.toBytes(fixtureIndex());
        Random random = new Random(TRUNCATION_SEED);

        for (int iteration = 0; iteration < 500; iteration++) {
            int length = random.nextInt(valid.length);
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(
                    IOException.class,
                    () -> ResourceIndexIO.fromBytes(truncated),
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
            PAYLOAD_OFFSET + 1,
            checksumOffset - 1,
            checksumOffset,
            checksumOffset + CHECKSUM_BYTES - 1
        };
        for (int length : boundaries) {
            if (length >= 0 && length < valid.length) {
                assertThrows(
                        IOException.class,
                        () -> ResourceIndexIO.fromBytes(Arrays.copyOf(valid, length)),
                        "boundary truncation at " + length);
            }
        }
    }

    @Test
    void hostileAuthenticatedLengthsAndCountsRejectDeterministically() throws Exception {
        byte[] valid = ResourceIndexIO.toBytes(fixtureIndex());
        Layout layout = layout(valid);

        assertRejected(withInt(valid, PAYLOAD_LENGTH_OFFSET, -1, false));
        assertRejected(withInt(valid, PAYLOAD_LENGTH_OFFSET, Integer.MAX_VALUE, false));
        assertRejected(withInt(valid, layout.fingerprintLengthOffset(), Integer.MAX_VALUE, true));
        assertRejected(withInt(valid, layout.rootCountOffset(), Integer.MAX_VALUE, true));
        assertRejected(withInt(valid, layout.firstRootIdLengthOffset(), Integer.MAX_VALUE, true));
        assertRejected(withInt(valid, layout.firstRootPathLengthOffset(), Integer.MAX_VALUE, true));
        assertRejected(withInt(valid, layout.entryCountOffset(), Integer.MAX_VALUE, true));
        assertRejected(withInt(valid, layout.firstProviderCountOffset(), 0, true));
        assertRejected(withInt(
                valid,
                layout.firstProviderCountOffset(),
                MAX_PROVIDERS_PER_ENTRY + 1,
                true));

        byte[] maxRoots = withInt(valid, layout.rootCountOffset(), MAX_ROOTS, true);
        assertTimeout(Duration.ofSeconds(2), () -> assertRejected(maxRoots));

        byte[] maxEntries = withInt(valid, layout.entryCountOffset(), MAX_ENTRIES, true);
        assertTimeout(Duration.ofSeconds(2), () -> assertRejected(maxEntries));
    }

    @Test
    void hostileAuthenticatedProviderAndPathStateNeverBecomesAnIndex() throws Exception {
        byte[] valid = ResourceIndexIO.toBytes(fixtureIndex());
        Layout layout = layout(valid);

        assertRejected(withInt(valid, layout.firstProviderRootOffset(), Integer.MAX_VALUE, true));
        assertRejected(withLong(valid, layout.firstProviderSizeOffset(), -1L, true));
        assertRejected(withLong(valid, layout.firstProviderModifiedOffset(), -1L, true));

        byte[] traversal = valid.clone();
        replaceAsciiSameLength(traversal, layout.firstProviderPathOffset(), "../x/y");
        resignPayload(traversal);
        assertRejected(traversal);

        byte[] duplicateNormalizedEntry = valid.clone();
        replaceAsciiSameLength(duplicateNormalizedEntry, layout.secondEntryPathOffset(), "AA.TXT");
        resignPayload(duplicateNormalizedEntry);
        assertRejected(duplicateNormalizedEntry);

        byte[] outOfOrderProviders = valid.clone();
        putInt(outOfOrderProviders, layout.firstProviderRootOffset(), 1);
        putInt(outOfOrderProviders, layout.secondProviderRootOffset(), 0);
        resignPayload(outOfOrderProviders);
        assertRejected(outOfOrderProviders);
    }

    @Test
    void authenticatedMalformedUtf8NeverBecomesAnIndex() throws Exception {
        byte[] valid = ResourceIndexIO.toBytes(fixtureIndex());
        Layout layout = layout(valid);
        byte[] malformed = valid.clone();
        int firstRootIdOffset = layout.firstRootIdLengthOffset() + Integer.BYTES;
        malformed[firstRootIdOffset] = (byte) 0xc3;
        malformed[firstRootIdOffset + 1] = 0x28;
        resignPayload(malformed);

        assertRejected(malformed);
    }

    @Test
    void diskReaderRejectsOversizedFileBeforeWholeFileRead() throws Exception {
        Path oversized = temporaryDirectory.resolve("oversized.spfi");
        try (FileChannel channel = FileChannel.open(
                oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            channel.position((long) MAX_FILE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }

        assertEquals((long) MAX_FILE_BYTES + 1, Files.size(oversized));
        // Exercise the same bounded reader without timing 512 MiB of legitimate disk I/O.
        // The large fixture still catches a regression to reading the whole file first.
        int readLimit = 4096;
        IOException error = assertTimeout(
                Duration.ofSeconds(2),
                () -> assertThrows(IOException.class, () -> ResourceIndexIO.read(oversized, readLimit)));
        assertEquals("Resource index exceeds the " + readLimit + " byte safety limit: " + oversized,
                error.getMessage());
    }

    private static ResourceIndex randomIndex(Random random, int iteration) {
        int rootCount = 1 + random.nextInt(4);
        List<ResourceIndex.Root> roots = new ArrayList<>();
        for (int root = 0; root < rootCount; root++) {
            roots.add(new ResourceIndex.Root(
                    "root-" + iteration + "-" + root + "-π",
                    Path.of("property-roots", "iteration-" + iteration, "root-" + root),
                    root == 0));
        }

        int entryCount = 1 + random.nextInt(10);
        List<String> logicalPaths = new ArrayList<>();
        for (int entry = 0; entry < entryCount; entry++) {
            logicalPaths.add("assets/group-" + (entry % 3) + "/resource-" + entry + ".bin");
        }
        Collections.shuffle(logicalPaths, random);

        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        for (String logicalPath : logicalPaths) {
            int providerCount = 1 + random.nextInt(Math.min(rootCount, 3));
            List<Integer> rootIndexes = new ArrayList<>();
            for (int provider = 0; provider < providerCount; provider++) {
                rootIndexes.add(random.nextInt(rootCount));
            }
            Collections.sort(rootIndexes);

            List<ResourceIndex.Provider> providers = new ArrayList<>();
            for (int provider = 0; provider < providerCount; provider++) {
                int rootIndex = rootIndexes.get(provider);
                providers.add(new ResourceIndex.Provider(
                        rootIndex,
                        "physical/r" + rootIndex + "/" + logicalPath,
                        random.nextInt(1_000_000),
                        Integer.toUnsignedLong(random.nextInt())));
            }
            entries.put(logicalPath, providers);
        }
        return new ResourceIndex(randomHash(random), roots, entries);
    }

    private static ResourceIndex fixtureIndex() {
        List<ResourceIndex.Root> roots = List.of(
                new ResourceIndex.Root("core", Path.of("fixture-core"), true),
                new ResourceIndex.Root("mod1", Path.of("fixture-mod1"), false));
        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        entries.put("aa.txt", List.of(
                new ResourceIndex.Provider(0, "aa.txt", 11, 101),
                new ResourceIndex.Provider(1, "aa.txt", 12, 102)));
        entries.put("bb.txt", List.of(new ResourceIndex.Provider(0, "bb.txt", 21, 201)));
        return new ResourceIndex("ab".repeat(32), roots, entries);
    }

    private static Layout layout(byte[] encoded) {
        int cursor = PAYLOAD_OFFSET;
        int fingerprintLengthOffset = cursor;
        cursor = skipString(encoded, cursor);
        int rootCountOffset = cursor;
        int rootCount = intAt(encoded, cursor);
        cursor += Integer.BYTES;

        int firstRootIdLengthOffset = cursor;
        cursor = skipString(encoded, cursor);
        int firstRootPathLengthOffset = cursor;
        cursor = skipString(encoded, cursor);
        cursor += 1;
        for (int root = 1; root < rootCount; root++) {
            cursor = skipString(encoded, cursor);
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
        int providerCount = intAt(encoded, cursor);
        cursor += Integer.BYTES;
        if (providerCount < 2) {
            throw new IllegalStateException("fixture requires two providers on the first entry");
        }

        int firstProviderRootOffset = cursor;
        cursor += Integer.BYTES;
        int firstProviderPathLengthOffset = cursor;
        int firstProviderPathLength = intAt(encoded, cursor);
        int firstProviderPathOffset = cursor + Integer.BYTES;
        cursor += Integer.BYTES + firstProviderPathLength;
        int firstProviderSizeOffset = cursor;
        cursor += Long.BYTES;
        int firstProviderModifiedOffset = cursor;
        cursor += Long.BYTES;

        int secondProviderRootOffset = cursor;
        cursor += Integer.BYTES;
        cursor = skipString(encoded, cursor);
        cursor += Long.BYTES * 2;
        for (int provider = 2; provider < providerCount; provider++) {
            cursor += Integer.BYTES;
            cursor = skipString(encoded, cursor);
            cursor += Long.BYTES * 2;
        }

        int secondEntryPathLengthOffset = cursor;
        int secondEntryPathLength = intAt(encoded, secondEntryPathLengthOffset);
        int secondEntryPathOffset = secondEntryPathLengthOffset + Integer.BYTES;
        if (secondEntryPathLength != "AA.TXT".length()) {
            throw new IllegalStateException("fixture second entry must match replacement length");
        }

        return new Layout(
                fingerprintLengthOffset,
                rootCountOffset,
                firstRootIdLengthOffset,
                firstRootPathLengthOffset,
                entryCountOffset,
                firstProviderCountOffset,
                firstProviderRootOffset,
                firstProviderPathLengthOffset,
                firstProviderPathOffset,
                firstProviderSizeOffset,
                firstProviderModifiedOffset,
                secondProviderRootOffset,
                secondEntryPathOffset);
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

    private static void replaceAsciiSameLength(byte[] bytes, int offset, String replacement) {
        byte[] replacementBytes = replacement.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(replacementBytes, 0, bytes, offset, replacementBytes.length);
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
        assertThrows(IOException.class, () -> ResourceIndexIO.fromBytes(bytes));
    }

    private record Layout(
            int fingerprintLengthOffset,
            int rootCountOffset,
            int firstRootIdLengthOffset,
            int firstRootPathLengthOffset,
            int entryCountOffset,
            int firstProviderCountOffset,
            int firstProviderRootOffset,
            int firstProviderPathLengthOffset,
            int firstProviderPathOffset,
            int firstProviderSizeOffset,
            int firstProviderModifiedOffset,
            int secondProviderRootOffset,
            int secondEntryPathOffset) {
    }
}

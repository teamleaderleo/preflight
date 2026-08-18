package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedAudioPersistencePropertyTest {
    static final long SEED = 0x5350_4155_4449_4f50L;
    static final int AUDIO_ROUND_TRIPS = 500;
    static final int MANIFEST_ROUND_TRIPS = 300;
    static final int SINGLE_BIT_CASES = 1_000;
    static final int TRUNCATION_CASES = 500;
    static final int CACHE_CORRUPTION_CASES = 256;

    private static final int FILE_HEADER_BYTES = 12;
    private static final int CHECKSUM_BYTES = 32;
    private static final int AUDIO_FRAME_COUNT_OFFSET = 100;
    private static final int AUDIO_SAMPLE_COUNT_OFFSET = 108;
    private static final int AUDIO_PCM_LENGTH_OFFSET = 116;
    private static final int MANIFEST_COUNT_OFFSET = 108;
    private static final int MANIFEST_FIRST_PATH_LENGTH_OFFSET = 112;
    private static final int MANIFEST_MAX_STRING_BYTES = 16 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void seededAudioRoundTripsAreExactAndCanonical() throws Exception {
        Random random = new Random(SEED);
        for (int iteration = 0; iteration < AUDIO_ROUND_TRIPS; iteration++) {
            PreparedAudio audio = randomAudio(random, randomSha256(random));
            byte[] encoded = PreparedAudioIO.toBytes(audio);
            PreparedAudio decoded = PreparedAudioIO.fromBytes(encoded);

            assertEquals(audio, decoded, "audio round trip iteration " + iteration);
            assertArrayEquals(
                    encoded,
                    PreparedAudioIO.toBytes(decoded),
                    "audio canonical re-encode iteration " + iteration);

            PreparedAudio equivalent = new PreparedAudio(
                    audio.sourceSha256().toUpperCase(Locale.ROOT),
                    audio.decoderPolicyIdentitySha256().toUpperCase(Locale.ROOT),
                    audio.policy(),
                    audio.encoding(),
                    audio.bitsPerSample(),
                    audio.byteOrder(),
                    audio.sampleRateHz(),
                    audio.channels(),
                    audio.frameCount(),
                    audio.pcmBytes());
            assertArrayEquals(
                    encoded,
                    PreparedAudioIO.toBytes(equivalent),
                    "audio canonical hash case iteration " + iteration);
        }
    }

    @Test
    void seededManifestOrdersRoundTripToCanonicalBytes() throws Exception {
        Random random = new Random(SEED ^ 0x4d41_4e49_4645_5354L);
        for (int iteration = 0; iteration < MANIFEST_ROUND_TRIPS; iteration++) {
            String profile = randomSha256(random);
            String build = randomSha256(random);
            String decoder = randomSha256(random);
            List<PreparedAudioManifest.Entry> entries = randomEntries(random, decoder);

            LinkedHashMap<String, PreparedAudioManifest.Entry> forward = new LinkedHashMap<>();
            for (PreparedAudioManifest.Entry entry : entries) {
                forward.put(entry.logicalPath(), entry);
            }
            LinkedHashMap<String, PreparedAudioManifest.Entry> reverse = new LinkedHashMap<>();
            for (int index = entries.size() - 1; index >= 0; index--) {
                PreparedAudioManifest.Entry entry = entries.get(index);
                reverse.put(entry.logicalPath(), entry);
            }

            PreparedAudioManifest left = new PreparedAudioManifest(
                    profile.toUpperCase(Locale.ROOT),
                    build,
                    decoder.toUpperCase(Locale.ROOT),
                    forward);
            PreparedAudioManifest right = new PreparedAudioManifest(profile, build, decoder, reverse);
            byte[] leftBytes = PreparedAudioManifestIO.toBytes(left);
            byte[] rightBytes = PreparedAudioManifestIO.toBytes(right);

            assertEquals(left.manifestSha256(), right.manifestSha256(), "manifest identity iteration " + iteration);
            assertArrayEquals(leftBytes, rightBytes, "manifest canonical order iteration " + iteration);

            PreparedAudioManifest decoded = PreparedAudioManifestIO.fromBytes(leftBytes);
            assertEquals(left.manifestSha256(), decoded.manifestSha256(), "manifest round trip iteration " + iteration);
            assertEquals(left.entries(), decoded.entries(), "manifest entries iteration " + iteration);
            assertArrayEquals(
                    leftBytes,
                    PreparedAudioManifestIO.toBytes(decoded),
                    "manifest canonical re-encode iteration " + iteration);
        }
    }

    @Test
    void seededSingleBitCorruptionRejectsDeterministically() throws Exception {
        PreparedAudio audio = fixtureAudio();
        byte[] audioBytes = PreparedAudioIO.toBytes(audio);
        byte[] manifestBytes = PreparedAudioManifestIO.toBytes(fixtureManifest(audio));
        Random audioRandom = new Random(SEED ^ 0x4155_4449_4f43_4f52L);
        Random manifestRandom = new Random(SEED ^ 0x4d41_4e49_434f_5252L);

        for (int iteration = 0; iteration < SINGLE_BIT_CASES; iteration++) {
            byte[] corruptAudio = flipOneBit(audioBytes, audioRandom);
            assertDeterministicReject(
                    () -> PreparedAudioIO.fromBytes(corruptAudio),
                    "audio single-bit corruption iteration " + iteration);

            byte[] corruptManifest = flipOneBit(manifestBytes, manifestRandom);
            assertDeterministicReject(
                    () -> PreparedAudioManifestIO.fromBytes(corruptManifest),
                    "manifest single-bit corruption iteration " + iteration);
        }
    }

    @Test
    void seededTruncationsAlwaysReject() throws Exception {
        PreparedAudio audio = fixtureAudio();
        byte[] audioBytes = PreparedAudioIO.toBytes(audio);
        byte[] manifestBytes = PreparedAudioManifestIO.toBytes(fixtureManifest(audio));
        Random audioRandom = new Random(SEED ^ 0x4155_4449_4f54_5255L);
        Random manifestRandom = new Random(SEED ^ 0x4d41_4e49_5452_554eL);

        for (int iteration = 0; iteration < TRUNCATION_CASES; iteration++) {
            int audioLength = audioRandom.nextInt(audioBytes.length);
            byte[] truncatedAudio = Arrays.copyOf(audioBytes, audioLength);
            assertDeterministicReject(
                    () -> PreparedAudioIO.fromBytes(truncatedAudio),
                    "audio truncation iteration " + iteration + " at " + audioLength);

            int manifestLength = manifestRandom.nextInt(manifestBytes.length);
            byte[] truncatedManifest = Arrays.copyOf(manifestBytes, manifestLength);
            assertDeterministicReject(
                    () -> PreparedAudioManifestIO.fromBytes(truncatedManifest),
                    "manifest truncation iteration " + iteration + " at " + manifestLength);
        }

        int[] audioBoundaries = {0, 3, 4, 7, 8, 11, 12, 43, 44, 75, 76, 99, 100, 107, 108, 115, 116, 119, 120,
                151, 152, audioBytes.length - CHECKSUM_BYTES, audioBytes.length - 1};
        for (int boundary : audioBoundaries) {
            if (boundary >= 0 && boundary < audioBytes.length) {
                assertDeterministicReject(
                        () -> PreparedAudioIO.fromBytes(Arrays.copyOf(audioBytes, boundary)),
                        "audio meaningful truncation boundary " + boundary);
            }
        }

        int firstPathEnd = MANIFEST_FIRST_PATH_LENGTH_OFFSET
                + Integer.BYTES
                + fixtureFirstManifestPath().getBytes(StandardCharsets.UTF_8).length;
        int[] manifestBoundaries = {0, 3, 4, 7, 8, 11, 12, 43, 44, 75, 76, 107, 108, 111, 112,
                firstPathEnd, manifestBytes.length - CHECKSUM_BYTES, manifestBytes.length - 1};
        for (int boundary : manifestBoundaries) {
            if (boundary >= 0 && boundary < manifestBytes.length) {
                assertDeterministicReject(
                        () -> PreparedAudioManifestIO.fromBytes(Arrays.copyOf(manifestBytes, boundary)),
                        "manifest meaningful truncation boundary " + boundary);
            }
        }
    }

    @Test
    void hostileDeclaredLengthsAndCountsRejectBeforeLargeAllocation() throws Exception {
        byte[] audioBytes = PreparedAudioIO.toBytes(fixtureAudio());
        for (int declared : new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
            byte[] corrupt = audioBytes.clone();
            putInt(corrupt, 8, declared);
            assertDeterministicReject(
                    () -> PreparedAudioIO.fromBytes(corrupt),
                    "audio payload length " + declared);
        }
        for (int declared : new int[]{Integer.MIN_VALUE, -1, PreparedAudio.MAX_PCM_BYTES + 1, Integer.MAX_VALUE}) {
            byte[] corrupt = audioBytes.clone();
            putInt(corrupt, AUDIO_PCM_LENGTH_OFFSET, declared);
            refreshChecksum(corrupt);
            assertDeterministicReject(
                    () -> PreparedAudioIO.fromBytes(corrupt),
                    "audio PCM length " + declared);
        }
        byte[] hugeFrames = audioBytes.clone();
        putLong(hugeFrames, AUDIO_FRAME_COUNT_OFFSET, Long.MAX_VALUE);
        refreshChecksum(hugeFrames);
        assertDeterministicReject(() -> PreparedAudioIO.fromBytes(hugeFrames), "audio frame count overflow");

        byte[] badSampleCount = audioBytes.clone();
        putLong(badSampleCount, AUDIO_SAMPLE_COUNT_OFFSET, Long.MAX_VALUE);
        refreshChecksum(badSampleCount);
        assertDeterministicReject(() -> PreparedAudioIO.fromBytes(badSampleCount), "audio sample count mismatch");

        byte[] manifestBytes = PreparedAudioManifestIO.toBytes(fixtureManifest(fixtureAudio()));
        for (int declared : new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
            byte[] corrupt = manifestBytes.clone();
            putInt(corrupt, 8, declared);
            assertDeterministicReject(
                    () -> PreparedAudioManifestIO.fromBytes(corrupt),
                    "manifest payload length " + declared);
        }
        for (int declared : new int[]{Integer.MIN_VALUE, -1, PreparedAudioManifest.MAX_ENTRIES + 1, Integer.MAX_VALUE}) {
            byte[] corrupt = manifestBytes.clone();
            putInt(corrupt, MANIFEST_COUNT_OFFSET, declared);
            refreshChecksum(corrupt);
            assertDeterministicReject(
                    () -> PreparedAudioManifestIO.fromBytes(corrupt),
                    "manifest entry count " + declared);
        }
        for (int declared : new int[]{Integer.MIN_VALUE, -1, 0, MANIFEST_MAX_STRING_BYTES + 1, Integer.MAX_VALUE}) {
            byte[] corrupt = manifestBytes.clone();
            putInt(corrupt, MANIFEST_FIRST_PATH_LENGTH_OFFSET, declared);
            refreshChecksum(corrupt);
            assertDeterministicReject(
                    () -> PreparedAudioManifestIO.fromBytes(corrupt),
                    "manifest string length " + declared);
        }
    }

    @Test
    void malformedHeadersVersionsChecksumsAndManifestIdentityRejectDeterministically() throws Exception {
        byte[] audioBytes = PreparedAudioIO.toBytes(fixtureAudio());
        byte[] manifestBytes = PreparedAudioManifestIO.toBytes(fixtureManifest(fixtureAudio()));

        assertHeaderMutationsReject(audioBytes, PreparedAudio.FORMAT_VERSION, PreparedAudioIO::fromBytes, "audio");
        assertHeaderMutationsReject(
                manifestBytes,
                PreparedAudioManifest.FORMAT_VERSION,
                PreparedAudioManifestIO::fromBytes,
                "manifest");

        byte[] badAudioChecksum = audioBytes.clone();
        badAudioChecksum[badAudioChecksum.length - 1] ^= 1;
        assertDeterministicReject(() -> PreparedAudioIO.fromBytes(badAudioChecksum), "audio checksum");

        byte[] badManifestChecksum = manifestBytes.clone();
        badManifestChecksum[badManifestChecksum.length - 1] ^= 1;
        assertDeterministicReject(() -> PreparedAudioManifestIO.fromBytes(badManifestChecksum), "manifest checksum");

        byte[] badManifestIdentity = manifestBytes.clone();
        int payloadLength = getInt(badManifestIdentity, 8);
        int identityOffset = FILE_HEADER_BYTES + payloadLength - 32;
        badManifestIdentity[identityOffset] ^= 1;
        refreshChecksum(badManifestIdentity);
        assertDeterministicReject(
                () -> PreparedAudioManifestIO.fromBytes(badManifestIdentity),
                "manifest embedded identity");
    }

    @Test
    void corruptedPersistedBlobNeverBecomesCacheHitAndRebuildRestoresHit() throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        PreparedAudio audio = fixtureAudio();
        PreparedAudioCache.write(cache, audio);
        Path blob = PreparedAudioCache.blobPath(
                cache,
                audio.sourceSha256(),
                audio.decoderPolicyIdentitySha256(),
                audio.policy());
        byte[] valid = Files.readAllBytes(blob);
        Random random = new Random(SEED ^ 0x4341_4348_4543_4f52L);

        for (int iteration = 0; iteration < CACHE_CORRUPTION_CASES; iteration++) {
            byte[] corrupt = flipOneBit(valid, random);
            Files.write(blob, corrupt);
            PreparedAudioCache.Lookup lookup = PreparedAudioCache.lookup(
                    cache,
                    audio.sourceSha256(),
                    audio.decoderPolicyIdentitySha256(),
                    audio.policy());
            assertEquals(
                    PreparedAudioCache.Status.CORRUPT,
                    lookup.status(),
                    "cache corruption iteration " + iteration);
            assertNull(lookup.audio(), "cache corruption iteration " + iteration);
        }

        PreparedAudioCache.write(cache, audio);
        PreparedAudioCache.Lookup rebuilt = PreparedAudioCache.lookup(
                cache,
                audio.sourceSha256(),
                audio.decoderPolicyIdentitySha256(),
                audio.policy());
        assertEquals(PreparedAudioCache.Status.HIT, rebuilt.status());
        assertEquals(audio, rebuilt.audio());
    }

    private static List<PreparedAudioManifest.Entry> randomEntries(Random random, String decoder) {
        int count = random.nextInt(10);
        List<PreparedAudioManifest.Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String logicalPath = String.format(
                    Locale.ROOT,
                    "sounds/property/%02d-%08x.ogg",
                    index,
                    random.nextInt());
            long sourceBytes = random.nextInt(Integer.MAX_VALUE);
            long sourceModifiedMillis = Integer.toUnsignedLong(random.nextInt());
            PreparedAudio.Policy policy = PreparedAudio.Policy.values()[random.nextInt(PreparedAudio.Policy.values().length)];
            if (policy.cacheEligible()) {
                PreparedAudio audio = randomAudio(random, decoder);
                entries.add(PreparedAudioManifest.Entry.prepared(
                        logicalPath,
                        sourceBytes,
                        sourceModifiedMillis,
                        audio));
            } else {
                entries.add(PreparedAudioManifest.Entry.ineligible(
                        logicalPath,
                        randomSha256(random),
                        sourceBytes,
                        sourceModifiedMillis,
                        policy));
            }
        }
        return entries;
    }

    private static PreparedAudio randomAudio(Random random, String decoder) {
        PreparedAudio.PcmEncoding encoding = PreparedAudio.PcmEncoding.values()[
                random.nextInt(PreparedAudio.PcmEncoding.values().length)];
        int[] bitDepths = switch (encoding) {
            case PCM_SIGNED -> new int[]{8, 16, 24, 32};
            case PCM_UNSIGNED -> new int[]{8};
            case PCM_FLOAT -> new int[]{32, 64};
        };
        int bits = bitDepths[random.nextInt(bitDepths.length)];
        int channels = 1 + random.nextInt(PreparedAudio.MAX_CHANNELS);
        long frames = random.nextInt(33);
        int pcmLength = Math.toIntExact(frames * channels * (bits / Byte.SIZE));
        byte[] pcm = new byte[pcmLength];
        random.nextBytes(pcm);
        return new PreparedAudio(
                randomSha256(random),
                decoder,
                PreparedAudio.Policy.FULLY_DECODED_EFFECT,
                encoding,
                bits,
                PreparedAudio.ByteOrder.values()[random.nextInt(PreparedAudio.ByteOrder.values().length)],
                1 + random.nextInt(PreparedAudio.MAX_SAMPLE_RATE_HZ),
                channels,
                frames,
                pcm);
    }

    private static PreparedAudio fixtureAudio() {
        byte[] pcm = new byte[32];
        for (int index = 0; index < pcm.length; index++) {
            pcm[index] = (byte) (index * 29 + 7);
        }
        return new PreparedAudio(
                "1".repeat(64),
                "2".repeat(64),
                PreparedAudio.Policy.FULLY_DECODED_EFFECT,
                PreparedAudio.PcmEncoding.PCM_SIGNED,
                16,
                PreparedAudio.ByteOrder.LITTLE_ENDIAN,
                48_000,
                2,
                8,
                pcm);
    }

    private static PreparedAudioManifest fixtureManifest(PreparedAudio audio) {
        PreparedAudioManifest.Entry prepared = PreparedAudioManifest.Entry.prepared(
                fixtureManifestPath(),
                123_456,
                1_700_000_000_000L,
                audio);
        PreparedAudioManifest.Entry streamed = PreparedAudioManifest.Entry.ineligible(
                "music/property/theme.ogg",
                "3".repeat(64),
                654_321,
                1_700_000_000_001L,
                PreparedAudio.Policy.STREAMED);
        Map<String, PreparedAudioManifest.Entry> entries = new LinkedHashMap<>();
        entries.put(streamed.logicalPath(), streamed);
        entries.put(prepared.logicalPath(), prepared);
        return new PreparedAudioManifest(
                "4".repeat(64),
                "5".repeat(64),
                audio.decoderPolicyIdentitySha256(),
                entries);
    }

    private static String fixtureManifestPath() {
        return "sounds/property/effect.ogg";
    }

    private static String fixtureFirstManifestPath() {
        return "music/property/theme.ogg";
    }

    private static byte[] flipOneBit(byte[] source, Random random) {
        byte[] corrupt = source.clone();
        int index = random.nextInt(corrupt.length);
        corrupt[index] ^= (byte) (1 << random.nextInt(Byte.SIZE));
        return corrupt;
    }

    private static void assertHeaderMutationsReject(
            byte[] encoded,
            int version,
            Decoder decoder,
            String label) throws Exception {
        byte[] magic = encoded.clone();
        magic[0] ^= 1;
        assertDeterministicReject(() -> decoder.decode(magic), label + " magic");

        for (int malformedVersion : new int[]{Integer.MIN_VALUE, -1, 0, version + 1, Integer.MAX_VALUE}) {
            if (malformedVersion == version) continue;
            byte[] corrupt = encoded.clone();
            putInt(corrupt, 4, malformedVersion);
            assertDeterministicReject(
                    () -> decoder.decode(corrupt),
                    label + " version " + malformedVersion);
        }
    }

    private static void refreshChecksum(byte[] encoded) {
        int payloadLength = getInt(encoded, 8);
        byte[] payload = Arrays.copyOfRange(encoded, FILE_HEADER_BYTES, FILE_HEADER_BYTES + payloadLength);
        byte[] checksum = Hashes.sha256Bytes(payload);
        System.arraycopy(checksum, 0, encoded, encoded.length - CHECKSUM_BYTES, CHECKSUM_BYTES);
    }

    private static int getInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(offset);
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(offset, value);
    }

    private static void putLong(byte[] bytes, int offset, long value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putLong(offset, value);
    }

    private static String randomSha256(Random random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static void assertDeterministicReject(IoAction action, String label) {
        IOException first = assertThrows(IOException.class, action::run, label);
        IOException second = assertThrows(IOException.class, action::run, label + " repeat");
        assertEquals(first.getClass(), second.getClass(), label + " exception type");
        assertEquals(first.getMessage(), second.getMessage(), label + " exception message");
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    @FunctionalInterface
    private interface Decoder {
        Object decode(byte[] bytes) throws IOException;
    }
}

package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedAudioCacheTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void modelAndBlobRoundTripAreExactAndDefensive() throws Exception {
        byte[] pcm = pcm(16, 3);
        PreparedAudio audio = audio("A", "B", pcm);
        assertEquals("a".repeat(64), audio.sourceSha256());
        assertEquals("b".repeat(64), audio.decoderPolicyIdentitySha256());
        assertEquals(4, audio.frameCount());
        assertEquals(8, audio.sampleCount());
        assertEquals(4, audio.frameSizeBytes());
        assertEquals(16, audio.pcmByteCount());

        byte[] encoded = PreparedAudioIO.toBytes(audio);
        PreparedAudio decoded = PreparedAudioIO.fromBytes(encoded);
        assertEquals(audio, decoded);
        assertArrayEquals(pcm, decoded.pcmBytes());

        pcm[0] = 99;
        assertNotEquals(pcm[0], decoded.pcmBytes()[0]);
        byte[] returned = decoded.pcmBytes();
        returned[1] = 88;
        assertNotEquals(returned[1], decoded.pcmBytes()[1]);
        ByteBuffer direct = ByteBuffer.allocateDirect(decoded.pcmByteCount());
        decoded.copyPcmTo(direct);
        direct.flip();
        byte[] directCopy = new byte[direct.remaining()];
        direct.get(directCopy);
        assertArrayEquals(decoded.pcmBytes(), directCopy);
        assertThrows(java.nio.BufferOverflowException.class,
                () -> decoded.copyPcmTo(ByteBuffer.allocate(decoded.pcmByteCount() - 1)));

        assertThrows(IllegalArgumentException.class, () -> new PreparedAudio(
                "a".repeat(64),
                "b".repeat(64),
                PreparedAudio.Policy.STREAMED,
                PreparedAudio.PcmEncoding.PCM_SIGNED,
                16,
                PreparedAudio.ByteOrder.LITTLE_ENDIAN,
                44_100,
                2,
                4,
                new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> new PreparedAudio(
                "a".repeat(64),
                "b".repeat(64),
                PreparedAudio.Policy.FULLY_DECODED_EFFECT,
                PreparedAudio.PcmEncoding.PCM_FLOAT,
                16,
                PreparedAudio.ByteOrder.LITTLE_ENDIAN,
                44_100,
                2,
                4,
                new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> new PreparedAudio(
                "a".repeat(64),
                "b".repeat(64),
                PreparedAudio.Policy.FULLY_DECODED_EFFECT,
                PreparedAudio.PcmEncoding.PCM_SIGNED,
                16,
                PreparedAudio.ByteOrder.LITTLE_ENDIAN,
                44_100,
                2,
                4,
                new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> new PreparedAudioManifest.Metadata(
                44_100,
                2,
                4,
                8,
                PreparedAudio.PcmEncoding.PCM_FLOAT,
                16,
                PreparedAudio.ByteOrder.LITTLE_ENDIAN,
                16,
                "a".repeat(64)));
    }

    @Test
    void blobAndManifestWritesUseTheSharedAtomicPersistencePath() throws Exception {
        Path directory = temporaryDirectory.resolve("nested/audio-cache");
        Path blob = directory.resolve("effect.spau");
        Path manifestFile = directory.resolve("profile.spam");
        PreparedAudio audio = audio("1", "2", pcm(16, 3));
        PreparedAudioManifest.Entry entry = PreparedAudioManifest.Entry.prepared(
                "sounds/effect.ogg",
                123,
                456,
                audio);
        PreparedAudioManifest manifest = manifest(
                "3",
                "4",
                audio.decoderPolicyIdentitySha256(),
                Map.of(entry.logicalPath(), entry));

        PreparedAudioIO.write(blob, audio);
        PreparedAudioManifestIO.write(manifestFile, manifest);

        assertEquals(audio, PreparedAudioIO.read(blob));
        PreparedAudioManifest restoredManifest = PreparedAudioManifestIO.read(manifestFile);
        assertEquals(manifest.manifestSha256(), restoredManifest.manifestSha256());
        assertEquals(manifest.entries(), restoredManifest.entries());
        try (var files = Files.list(directory)) {
            assertEquals(
                    List.of("effect.spau", "profile.spam"),
                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void blobRejectsCorruptionTruncationAndMetadataMutation() throws Exception {
        PreparedAudio audio = audio("c", "d", pcm(16, 9));
        byte[] encoded = PreparedAudioIO.toBytes(audio);

        byte[] corrupt = encoded.clone();
        corrupt[corrupt.length / 2] ^= 1;
        IOException checksum = assertThrows(IOException.class, () -> PreparedAudioIO.fromBytes(corrupt));
        assertTrue(checksum.getMessage().contains("checksum"), checksum.getMessage());

        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 5);
        assertThrows(IOException.class, () -> PreparedAudioIO.fromBytes(truncated));

        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IOException.class, () -> PreparedAudioIO.fromBytes(trailing));
    }

    @Test
    void trustedBlobReadSkipsOnlyThePayloadChecksum() throws Exception {
        PreparedAudio audio = audio("c", "d", pcm(16, 9));
        Path blob = temporaryDirectory.resolve("trusted.spau");
        PreparedAudioIO.write(blob, audio);

        byte[] sameLengthCorruption = Files.readAllBytes(blob);
        // The final PCM byte is immediately before the 32-byte outer checksum. Changing it keeps
        // every structural and dimensional invariant valid while making the checksum stale.
        sameLengthCorruption[sameLengthCorruption.length - 33] ^= 1;
        Files.write(blob, sameLengthCorruption);
        PreparedAudio trusted = PreparedAudioIO.readTrusted(blob);
        assertEquals(audio.pcmByteCount(), trusted.pcmByteCount());
        assertThrows(IOException.class, () -> PreparedAudioIO.read(blob));

        Files.write(blob, java.util.Arrays.copyOf(sameLengthCorruption, sameLengthCorruption.length - 1));
        assertThrows(IOException.class, () -> PreparedAudioIO.readTrusted(blob));
    }

    @Test
    void trustedBlobReadStillRejectsMalformedStructure() throws Exception {
        PreparedAudio audio = audio("e", "f", pcm(16, 7));
        Path blob = temporaryDirectory.resolve("trusted-structure.spau");
        byte[] encoded = PreparedAudioIO.toBytes(audio);

        // File header: magic, version, payload length. The PCM length follows two hashes, six
        // ints, two longs, and is therefore at byte 116 in format v1.
        byte[] badPayloadLength = encoded.clone();
        ByteBuffer.wrap(badPayloadLength).order(ByteOrder.BIG_ENDIAN).putInt(8, 1);
        Files.write(blob, badPayloadLength);
        assertThrows(IOException.class, () -> PreparedAudioIO.readTrusted(blob));

        byte[] badPcmLength = encoded.clone();
        ByteBuffer.wrap(badPcmLength).order(ByteOrder.BIG_ENDIAN).putInt(116, audio.pcmByteCount() - 1);
        Files.write(blob, badPcmLength);
        assertThrows(IOException.class, () -> PreparedAudioIO.readTrusted(blob));

        byte[] badSampleCount = encoded.clone();
        ByteBuffer.wrap(badSampleCount).order(ByteOrder.BIG_ENDIAN).putLong(108, audio.sampleCount() + 1);
        Files.write(blob, badSampleCount);
        assertThrows(IOException.class, () -> PreparedAudioIO.readTrusted(blob));
    }

    @Test
    void cacheIsContentAddressedFailOpenAndRejectsCrossKeySubstitution() throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        PreparedAudio first = audio("1", "2", pcm(16, 1));
        PreparedAudio second = audio("3", "4", pcm(16, 2));

        PreparedAudioCache.Lookup miss = PreparedAudioCache.lookup(
                cache,
                first.sourceSha256(),
                first.decoderPolicyIdentitySha256(),
                first.policy());
        assertEquals(PreparedAudioCache.Status.MISS, miss.status());

        PreparedAudioCache.Lookup streamed = PreparedAudioCache.lookup(
                cache,
                first.sourceSha256(),
                first.decoderPolicyIdentitySha256(),
                PreparedAudio.Policy.STREAMED);
        assertEquals(PreparedAudioCache.Status.INELIGIBLE, streamed.status());

        PreparedAudioCache.write(cache, first);
        PreparedAudioCache.write(cache, second);
        PreparedAudioCache.Lookup hit = PreparedAudioCache.lookup(
                cache,
                first.sourceSha256(),
                first.decoderPolicyIdentitySha256(),
                first.policy());
        assertEquals(PreparedAudioCache.Status.HIT, hit.status());
        assertEquals(first, hit.audio());
        assertTrue(hit.path().startsWith(cache.toAbsolutePath().normalize()));

        Path firstPath = PreparedAudioCache.blobPath(
                cache,
                first.sourceSha256(),
                first.decoderPolicyIdentitySha256(),
                first.policy());
        Path secondPath = PreparedAudioCache.blobPath(
                cache,
                second.sourceSha256(),
                second.decoderPolicyIdentitySha256(),
                second.policy());
        Files.copy(secondPath, firstPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        PreparedAudioCache.Lookup substituted = PreparedAudioCache.lookup(
                cache,
                first.sourceSha256(),
                first.decoderPolicyIdentitySha256(),
                first.policy());
        assertEquals(PreparedAudioCache.Status.CORRUPT, substituted.status());
        assertTrue(substituted.detail().contains("identity"), substituted.detail());

        PreparedAudioCache.Lookup invalid = PreparedAudioCache.lookup(
                cache,
                "bad",
                first.decoderPolicyIdentitySha256(),
                first.policy());
        assertEquals(PreparedAudioCache.Status.ERROR, invalid.status());
    }

    @Test
    void concurrentReadersAndNonFileTargetsFailSafely() throws Exception {
        Path cache = temporaryDirectory.resolve("concurrent-cache");
        PreparedAudio audio = audio("a", "b", pcm(16, 4));
        PreparedAudioCache.write(cache, audio);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<PreparedAudioCache.Status>> futures = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                futures.add(executor.submit(() -> PreparedAudioCache.lookup(
                        cache,
                        audio.sourceSha256(),
                        audio.decoderPolicyIdentitySha256(),
                        audio.policy()).status()));
            }
            for (Future<PreparedAudioCache.Status> future : futures) {
                assertEquals(PreparedAudioCache.Status.HIT, future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        PreparedAudio directoryAudio = audio("c", "d", pcm(16, 5));
        Path directoryTarget = PreparedAudioCache.blobPath(
                cache,
                directoryAudio.sourceSha256(),
                directoryAudio.decoderPolicyIdentitySha256(),
                directoryAudio.policy());
        Files.createDirectories(directoryTarget);
        PreparedAudioCache.Lookup directory = PreparedAudioCache.lookup(
                cache,
                directoryAudio.sourceSha256(),
                directoryAudio.decoderPolicyIdentitySha256(),
                directoryAudio.policy());
        assertEquals(PreparedAudioCache.Status.ERROR, directory.status());

        Path blockedRoot = temporaryDirectory.resolve("cache-root-is-a-file");
        Files.writeString(blockedRoot, "blocked");
        assertThrows(IOException.class, () -> PreparedAudioCache.write(blockedRoot, audio));
    }

    @Test
    void manifestIsDeterministicRoundTripsAndTracksPolicies() throws Exception {
        PreparedAudio prepared = audio("5", "6", pcm(16, 7));
        PreparedAudioManifest.Entry effect = PreparedAudioManifest.Entry.prepared(
                "Sounds/Effects/Zap.ogg",
                123,
                456,
                prepared);
        PreparedAudioManifest.Entry music = PreparedAudioManifest.Entry.ineligible(
                "music/theme.ogg",
                "7".repeat(64),
                999,
                1_000,
                PreparedAudio.Policy.STREAMED);
        PreparedAudioManifest.Entry unsupported = PreparedAudioManifest.Entry.ineligible(
                "sounds/unsupported.xyz",
                "8".repeat(64),
                10,
                11,
                PreparedAudio.Policy.UNSUPPORTED);

        LinkedHashMap<String, PreparedAudioManifest.Entry> reversed = new LinkedHashMap<>();
        reversed.put(unsupported.logicalPath(), unsupported);
        reversed.put(music.logicalPath(), music);
        reversed.put(effect.logicalPath(), effect);
        PreparedAudioManifest left = manifest("9", "a", prepared.decoderPolicyIdentitySha256(), reversed);

        LinkedHashMap<String, PreparedAudioManifest.Entry> ordered = new LinkedHashMap<>();
        ordered.put(effect.logicalPath(), effect);
        ordered.put(music.logicalPath(), music);
        ordered.put(unsupported.logicalPath(), unsupported);
        PreparedAudioManifest right = manifest("9", "a", prepared.decoderPolicyIdentitySha256(), ordered);

        assertEquals(left.manifestSha256(), right.manifestSha256());
        assertArrayEquals(PreparedAudioManifestIO.toBytes(left), PreparedAudioManifestIO.toBytes(right));
        PreparedAudioManifest decoded = PreparedAudioManifestIO.fromBytes(PreparedAudioManifestIO.toBytes(left));
        assertEquals(left.manifestSha256(), decoded.manifestSha256());
        assertEquals(3, decoded.entryCount());
        assertEquals(1, decoded.preparedEntryCount());
        assertEquals(1, decoded.streamedEntryCount());
        assertEquals(1, decoded.unsupportedEntryCount());
        assertEquals("music/theme.ogg", decoded.entries().firstKey());
        assertEquals(prepared.pcmSha256(), decoded.entries().get(effect.logicalPath()).metadata().pcmSha256());

        PreparedAudioManifest changedBuild = manifest(
                "9", "b", prepared.decoderPolicyIdentitySha256(), ordered);
        assertNotEquals(left.manifestSha256(), changedBuild.manifestSha256());
        PreparedAudioManifest changedDecoder = manifest(
                "9", "a", "c".repeat(64), Map.of(music.logicalPath(), music));
        assertNotEquals(left.manifestSha256(), changedDecoder.manifestSha256());
        PreparedAudioManifest.Entry changedEffect = PreparedAudioManifest.Entry.prepared(
                effect.logicalPath(),
                effect.sourceBytes() + 1,
                effect.sourceModifiedMillis(),
                prepared);
        LinkedHashMap<String, PreparedAudioManifest.Entry> changedEntries = new LinkedHashMap<>(ordered);
        changedEntries.put(changedEffect.logicalPath(), changedEffect);
        PreparedAudioManifest changedSourceMetadata = manifest(
                "9", "a", prepared.decoderPolicyIdentitySha256(), changedEntries);
        assertNotEquals(left.manifestSha256(), changedSourceMetadata.manifestSha256());

        byte[] corrupt = PreparedAudioManifestIO.toBytes(left);
        corrupt[corrupt.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedAudioManifestIO.fromBytes(corrupt));
        assertThrows(IOException.class, () -> PreparedAudioManifestIO.fromBytes(
                java.util.Arrays.copyOf(PreparedAudioManifestIO.toBytes(left), 20)));
    }

    private static PreparedAudio audio(String sourceDigit, String decoderDigit, byte[] pcm) {
        return new PreparedAudio(
                sourceDigit.repeat(64),
                decoderDigit.repeat(64),
                PreparedAudio.Policy.FULLY_DECODED_EFFECT,
                PreparedAudio.PcmEncoding.PCM_SIGNED,
                16,
                PreparedAudio.ByteOrder.LITTLE_ENDIAN,
                44_100,
                2,
                4,
                pcm);
    }

    private static PreparedAudioManifest manifest(
            String profileDigit,
            String buildDigit,
            String decoder,
            Map<String, PreparedAudioManifest.Entry> entries) {
        return new PreparedAudioManifest(
                profileDigit.repeat(64),
                buildDigit.repeat(64),
                decoder,
                entries);
    }

    private static byte[] pcm(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (seed + i * 13);
        return bytes;
    }
}

package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedTexturePersistencePropertyTest {
    private static final long ROUND_TRIP_SEED = 0x5350465450524f50L;
    private static final long CORRUPTION_SEED = 0x53504654434f5252L;
    private static final long TRUNCATION_SEED = 0x535046545452554eL;
    private static final long CACHE_CORRUPTION_SEED = 0x5350465443414348L;

    private static final int VERSION_OFFSET = 4;
    private static final int PAYLOAD_LENGTH_OFFSET = 8;
    private static final int PAYLOAD_OFFSET = 12;
    private static final int TRANSFORMATION_OFFSET = 44;
    private static final int UPLOAD_WIDTH_OFFSET = 56;
    private static final int UPLOAD_HEIGHT_OFFSET = 60;
    private static final int CHANNELS_OFFSET = 64;
    private static final int CODEC_OFFSET = 80;
    private static final int PIXEL_LENGTH_OFFSET = 84;
    private static final int CHECKSUM_BYTES = 32;

    @TempDir
    Path temporaryDirectory;

    @Test
    void seededRoundTripsRemainCanonicalAcrossRawAndLz4() throws Exception {
        Random random = new Random(ROUND_TRIP_SEED);
        PreparedTextureIO.StorageCodec[] codecs = PreparedTextureIO.StorageCodec.values();

        for (int index = 0; index < 500; index++) {
            PreparedTexture texture = randomTexture(random);
            PreparedTextureIO.StorageCodec codec = codecs[index % codecs.length];

            byte[] encoded = PreparedTextureIO.toBytes(texture, codec);
            PreparedTexture restored = PreparedTextureIO.fromBytes(encoded);

            assertEquals(texture, restored, "round trip " + index + " with " + codec);
            assertArrayEquals(
                    encoded,
                    PreparedTextureIO.toBytes(restored, codec),
                    "canonical re-encoding " + index + " with " + codec);
        }
    }

    @Test
    void seededSingleBitCorruptionAlwaysRejectsVerifiedParser() throws Exception {
        Random random = new Random(CORRUPTION_SEED);
        byte[][] corpus = {
            PreparedTextureIO.toBytes(fixture(), PreparedTextureIO.StorageCodec.RAW),
            PreparedTextureIO.toBytes(fixture(), PreparedTextureIO.StorageCodec.LZ4)
        };

        for (int index = 0; index < 1_000; index++) {
            byte[] original = corpus[index % corpus.length];
            byte[] corrupt = original.clone();
            int byteIndex = random.nextInt(corrupt.length);
            corrupt[byteIndex] ^= (byte) (1 << random.nextInt(Byte.SIZE));

            assertThrows(
                    IOException.class,
                    () -> PreparedTextureIO.fromBytes(corrupt),
                    "single-bit corruption " + index + " at byte " + byteIndex);
        }
    }

    @Test
    void seededAndBoundaryTruncationsAlwaysReject() throws Exception {
        Random random = new Random(TRUNCATION_SEED);
        byte[][] corpus = {
            PreparedTextureIO.toBytes(fixture(), PreparedTextureIO.StorageCodec.RAW),
            PreparedTextureIO.toBytes(fixture(), PreparedTextureIO.StorageCodec.LZ4)
        };

        for (int index = 0; index < 500; index++) {
            byte[] original = corpus[index % corpus.length];
            int length = random.nextInt(original.length);
            byte[] truncated = Arrays.copyOf(original, length);
            assertThrows(
                    IOException.class,
                    () -> PreparedTextureIO.fromBytes(truncated),
                    "seeded truncation " + index + " at " + length);
        }

        byte[] raw = corpus[0];
        int[] boundaries = {
            0,
            1,
            3,
            4,
            7,
            8,
            11,
            12,
            43,
            44,
            47,
            48,
            55,
            56,
            63,
            64,
            79,
            80,
            83,
            84,
            87,
            88,
            raw.length - CHECKSUM_BYTES - 1,
            raw.length - CHECKSUM_BYTES,
            raw.length - 1
        };
        for (int length : boundaries) {
            if (length >= 0 && length < raw.length) {
                assertThrows(
                        IOException.class,
                        () -> PreparedTextureIO.fromBytes(Arrays.copyOf(raw, length)),
                        "boundary truncation at " + length);
            }
        }
    }

    @Test
    void hostileHeaderAndMetadataLengthsRejectBeforeAcceptance() throws Exception {
        byte[] raw = PreparedTextureIO.toBytes(fixture(), PreparedTextureIO.StorageCodec.RAW);
        byte[] lz4 = PreparedTextureIO.toBytes(fixture(), PreparedTextureIO.StorageCodec.LZ4);

        assertRejected(withInt(raw, VERSION_OFFSET, Integer.MAX_VALUE, false));
        assertRejected(withInt(raw, PAYLOAD_LENGTH_OFFSET, -1, false));
        assertRejected(withInt(raw, PAYLOAD_LENGTH_OFFSET, Integer.MAX_VALUE, false));
        assertRejected(withInt(raw, TRANSFORMATION_OFFSET, Integer.MAX_VALUE, true));
        assertRejected(withInt(raw, UPLOAD_WIDTH_OFFSET, -1, true));
        assertRejected(withInt(raw, CHANNELS_OFFSET, 5, true));
        assertRejected(withInt(raw, CODEC_OFFSET, Integer.MAX_VALUE, true));
        assertRejected(withInt(raw, PIXEL_LENGTH_OFFSET, Integer.MAX_VALUE, true));

        byte[] overflowingDimensions = raw.clone();
        putInt(overflowingDimensions, UPLOAD_WIDTH_OFFSET, Integer.MAX_VALUE);
        putInt(overflowingDimensions, UPLOAD_HEIGHT_OFFSET, Integer.MAX_VALUE);
        putInt(overflowingDimensions, CHANNELS_OFFSET, 4);
        rewritePayloadChecksum(overflowingDimensions);
        assertRejected(overflowingDimensions);

        // A tiny compressed input must be rejected from metadata before it can request an output
        // allocation beyond the parser's declared 512 MiB safety ceiling.
        byte[] oversizedExpansion = lz4.clone();
        int width = 134_217_729;
        int pixels = Math.multiplyExact(width, 4);
        putInt(oversizedExpansion, UPLOAD_WIDTH_OFFSET, width);
        putInt(oversizedExpansion, UPLOAD_HEIGHT_OFFSET, 1);
        putInt(oversizedExpansion, CHANNELS_OFFSET, 4);
        putInt(oversizedExpansion, PIXEL_LENGTH_OFFSET, pixels);
        rewritePayloadChecksum(oversizedExpansion);
        assertRejected(oversizedExpansion);
    }

    @Test
    void seededPersistedCorruptionNeverBecomesVerifiedCacheHit() throws Exception {
        PreparedTexture texture = fixture();
        Path relative = Path.of("blobs", "ab", "fixture.spft");
        Path blob = temporaryDirectory.resolve(relative);
        Files.createDirectories(blob.getParent());
        byte[] valid = PreparedTextureIO.toBytes(texture, PreparedTextureIO.StorageCodec.LZ4);
        TextureManifest manifest = manifest(texture, relative);
        ManifestTextureCacheLookup lookup = new ManifestTextureCacheLookup(temporaryDirectory, manifest);
        Random random = new Random(CACHE_CORRUPTION_SEED);

        for (int index = 0; index < 256; index++) {
            byte[] corrupt = valid.clone();
            int byteIndex = random.nextInt(corrupt.length);
            corrupt[byteIndex] ^= (byte) (1 << random.nextInt(Byte.SIZE));
            Files.write(blob, corrupt);

            assertEquals(
                    TextureCacheLookup.Status.CORRUPT,
                    lookup.lookup("graphics/ships/example.png").status(),
                    "cache corruption " + index + " at byte " + byteIndex);
        }

        Files.write(blob, valid);
        assertEquals(
                TextureCacheLookup.Status.HIT,
                lookup.lookup("graphics/ships/example.png").status());
    }

    private static PreparedTexture randomTexture(Random random) {
        int width = 1 + random.nextInt(64);
        int height = 1 + random.nextInt(64);
        int channels = random.nextBoolean() ? 3 : 4;
        byte[] pixels = new byte[Math.multiplyExact(Math.multiplyExact(width, height), channels)];
        random.nextBytes(pixels);
        byte[] hash = new byte[32];
        random.nextBytes(hash);
        String sourceHash = HexFormat.of().formatHex(hash);
        if (random.nextBoolean()) {
            sourceHash = sourceHash.toUpperCase(java.util.Locale.ROOT);
        }
        PreparedTexture.Transformation transformation = random.nextBoolean()
                ? PreparedTexture.Transformation.IDENTITY
                : PreparedTexture.Transformation.ALPHA_ADDER;
        return new PreparedTexture(
                sourceHash,
                transformation,
                1 + random.nextInt(128),
                1 + random.nextInt(128),
                width,
                height,
                channels,
                random.nextInt(),
                random.nextInt(),
                random.nextInt(),
                pixels);
    }

    private static PreparedTexture fixture() {
        byte[] pixels = new byte[8 * 8 * 4];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = (byte) ((index / 16) & 0xff);
        }
        return new PreparedTexture(
                "ab".repeat(32),
                PreparedTexture.Transformation.IDENTITY,
                8,
                8,
                8,
                8,
                4,
                PreparedTexture.rgba(1, 2, 3, 255),
                PreparedTexture.rgba(4, 5, 6, 255),
                PreparedTexture.rgba(7, 8, 9, 255),
                pixels);
    }

    private static TextureManifest manifest(PreparedTexture texture, Path relative) {
        return new TextureManifest("property-profile", Map.of(
                "graphics/ships/example.png",
                new TextureManifest.Entry(
                        texture.sourceSha256(),
                        texture.transformation(),
                        relative.toString(),
                        texture.uploadWidth(),
                        texture.uploadHeight(),
                        texture.channels(),
                        texture.pixelBytes())));
    }

    private static byte[] withInt(byte[] source, int offset, int value, boolean payloadField) {
        byte[] changed = source.clone();
        putInt(changed, offset, value);
        if (payloadField) {
            rewritePayloadChecksum(changed);
        }
        return changed;
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(offset, value);
    }

    private static void rewritePayloadChecksum(byte[] bytes) {
        int payloadLength = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt(PAYLOAD_LENGTH_OFFSET);
        int checksumOffset = PAYLOAD_OFFSET + payloadLength;
        byte[] payload = Arrays.copyOfRange(bytes, PAYLOAD_OFFSET, checksumOffset);
        byte[] checksum = Hashes.sha256Bytes(payload);
        System.arraycopy(checksum, 0, bytes, checksumOffset, CHECKSUM_BYTES);
    }

    private static void assertRejected(byte[] bytes) {
        assertThrows(IOException.class, () -> PreparedTextureIO.fromBytes(bytes));
    }
}

package dev.starsector.preflight.core;

import io.airlift.compress.MalformedInputException;
import io.airlift.compress.lz4.Lz4Compressor;
import io.airlift.compress.lz4.Lz4Decompressor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/** Versioned raw blob persistence for upload-ready texture data. */
public final class PreparedTextureIO {
    public static final String SINGLE_READ_LZ4_PROPERTY = "preflight.texture.singleReadLz4";
    private static final int ESTABLISHED_FORMAT_VERSION = 1;
    private static final byte[] MAGIC = {'S', 'P', 'F', 'T'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int SHA256_BYTES = 32;
    private static final int CODEC_RAW = 0;
    private static final int CODEC_LZ4 = 1;
    private static final int PAYLOAD_FIXED_BYTES = SHA256_BYTES + Integer.BYTES * 11;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;
    private static final int MAX_TRUSTED_LZ4_SCRATCH_BYTES = 16 * 1024 * 1024;
    private static final boolean SINGLE_READ_LZ4 = Boolean.parseBoolean(
            System.getProperty(SINGLE_READ_LZ4_PROPERTY, "true"));
    private static final Lz4Decompressor LZ4_DECOMPRESSOR = new Lz4Decompressor();
    private static final ThreadLocal<byte[]> TRUSTED_LZ4_SCRATCH =
            ThreadLocal.withInitial(() -> new byte[0]);

    private PreparedTextureIO() {
    }

    public static String cacheDirectoryName() {
        return CacheFormatNamespace.name(
                "blobs", PreparedTexture.FORMAT_VERSION, ESTABLISHED_FORMAT_VERSION);
    }

    public static Path cacheDirectory(Path cacheRoot) {
        return CacheFormatNamespace.directory(
                cacheRoot, "blobs", PreparedTexture.FORMAT_VERSION, ESTABLISHED_FORMAT_VERSION);
    }

    public static void write(Path target, PreparedTexture texture) throws IOException {
        AtomicBlobs.write(target, toBytes(texture));
    }

    public static void write(Path target, PreparedTexture texture, StorageCodec codec) throws IOException {
        AtomicBlobs.write(target, toBytes(texture, codec));
    }

    public static PreparedTexture read(Path source) throws IOException {
        return read(source, true);
    }

    /**
     * Reads a blob from a trusted local cache without recomputing its payload checksum.
     *
     * <p>All format, length, dimension, channel, codec, and trailing-data checks still run. The
     * only check omitted is the SHA-256 pass over the payload. This is intended for a latency-
     * sensitive consumer of blobs written atomically by Preflight and referenced by a verified
     * manifest. Tools that build, inspect, or validate a cache must use {@link #read(Path)}.
     */
    public static PreparedTexture readTrusted(Path source) throws IOException {
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
            return readTrusted(channel, 0, channel.size(), source.toString());
        }
    }

    public static boolean singleReadLz4Enabled() {
        return SINGLE_READ_LZ4;
    }

    /** Encoded pixel bytes inside a complete SPFT file, excluding metadata and checksum. */
    public static long storedPixelBytes(Path source) throws IOException {
        long stored = Files.size(source) - minimumFileBytes() - PAYLOAD_FIXED_BYTES;
        if (stored < 0 || stored > MAX_FILE_BYTES) {
            throw new IOException("Prepared texture stored pixel length is invalid: " + source);
        }
        return stored;
    }

    /** Exact SPFT file size for an uncompressed upload-ready pixel array. */
    public static long rawFileBytes(long pixelBytes) {
        long fileBytes = Math.addExact(minimumFileBytes() + PAYLOAD_FIXED_BYTES, pixelBytes);
        if (pixelBytes < 0 || fileBytes > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Prepared texture pixel length is invalid: " + pixelBytes);
        }
        return fileBytes;
    }

    /** Maximum complete SPFT file size for a prospective upload-ready pixel array. */
    public static long maximumFileBytes(long pixelBytes, StorageCodec codec) {
        Objects.requireNonNull(codec, "codec");
        long rawBytes = rawFileBytes(pixelBytes);
        if (codec == StorageCodec.RAW) {
            return rawBytes;
        }
        int maximumStored = new Lz4Compressor().maxCompressedLength(Math.toIntExact(pixelBytes));
        long fileBytes = Math.addExact(minimumFileBytes() + PAYLOAD_FIXED_BYTES, maximumStored);
        if (fileBytes > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Prepared texture file length is invalid: " + fileBytes);
        }
        return fileBytes;
    }

    /** Reads one complete SPFT blob stored at an indexed range in a shared pack channel. */
    public static PreparedTexture readTrusted(
            FileChannel channel, long offset, long size, String sourceLabel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        String label = sourceLabel == null ? "prepared texture range" : sourceLabel;
        if (offset < 0 || size < minimumFileBytes()) {
            throw new IOException("Prepared texture blob is too small: " + label);
        }
        if (size > MAX_FILE_BYTES || offset > Long.MAX_VALUE - size) {
            throw new IOException(
                    "Prepared texture blob range exceeds the safety limit: " + label);
        }
        if (SINGLE_READ_LZ4 && label.endsWith("-lz4.spft")) {
            return readTrustedLz4Range(channel, offset, size, label);
        }
        long[] position = {offset};
        try {
            // The trusted serving path does not need the payload checksum. Read fixed metadata
            // separately; raw pixels go directly into their final adopted array, while LZ4 input
            // uses bounded scratch before decompression into its final array.
            ByteBuffer metadata = ByteBuffer.allocate(
                    MAGIC.length + Integer.BYTES * 2 + PAYLOAD_FIXED_BYTES).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, position, metadata, "Prepared texture ended inside its metadata");
            metadata.flip();

            byte[] magic = new byte[MAGIC.length];
            metadata.get(magic);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("Prepared texture magic header is invalid");
            }
            int version = metadata.getInt();
            if (version != PreparedTexture.FORMAT_VERSION) {
                throw new IOException("Unsupported prepared texture version: " + version);
            }
            int payloadLength = metadata.getInt();
            byte[] sourceHash = new byte[SHA256_BYTES];
            metadata.get(sourceHash);
            PreparedTexture.Transformation transformation =
                    PreparedTexture.Transformation.fromId(metadata.getInt());
            int originalWidth = metadata.getInt();
            int originalHeight = metadata.getInt();
            int uploadWidth = metadata.getInt();
            int uploadHeight = metadata.getInt();
            int channels = metadata.getInt();
            int color0 = metadata.getInt();
            int color1 = metadata.getInt();
            int color2 = metadata.getInt();
            int codec = metadata.getInt();
            int pixelLength = metadata.getInt();

            long expectedLength = minimumFileBytes() + (long) payloadLength;
            if (payloadLength < PAYLOAD_FIXED_BYTES || expectedLength != size) {
                throw new IOException("Prepared texture payload length is invalid");
            }
            StorageCodec storageCodec = StorageCodec.fromId(codec);
            if (uploadWidth <= 0 || uploadHeight <= 0 || (channels != 3 && channels != 4)) {
                throw new IOException("Prepared texture dimensions or channel count are invalid");
            }
            long expectedPixels = Math.multiplyExact(
                    Math.multiplyExact((long) uploadWidth, uploadHeight), channels);
            int storedLength = payloadLength - PAYLOAD_FIXED_BYTES;
            if (pixelLength < 0 || expectedPixels != pixelLength || storedLength < 0
                    || pixelLength > MAX_FILE_BYTES) {
                throw new IOException(
                        "Prepared texture pixel length is " + pixelLength + "; expected " + expectedPixels);
            }

            byte[] storedPixels = storageCodec == StorageCodec.LZ4
                    ? trustedLz4Scratch(storedLength)
                    : new byte[storedLength];
            readFully(channel, position, ByteBuffer.wrap(storedPixels, 0, storedLength),
                    "Prepared texture ended inside its pixels");
            if (position[0] != offset + size - CHECKSUM_BYTES) {
                throw new IOException("Prepared texture payload contains trailing data");
            }
            byte[] pixels = decodePixels(storageCodec, storedPixels, storedLength, pixelLength);
            return PreparedTexture.adopting(
                    java.util.HexFormat.of().formatHex(sourceHash),
                    transformation,
                    originalWidth,
                    originalHeight,
                    uploadWidth,
                    uploadHeight,
                    channels,
                    color0,
                    color1,
                    color2,
                    pixels);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Prepared texture contains invalid data: " + error.getMessage(), error);
        }
    }

    /** Reads a known-LZ4 pack range with one positioned read into reusable heap scratch. */
    private static PreparedTexture readTrustedLz4Range(
            FileChannel channel, long offset, long size, String label) throws IOException {
        int contentLength = Math.toIntExact(size - CHECKSUM_BYTES);
        byte[] content = trustedLz4Scratch(contentLength);
        long[] position = {offset};
        readFully(
                channel,
                position,
                ByteBuffer.wrap(content, 0, contentLength),
                "Prepared texture ended inside its payload");
        try {
            ByteBuffer input = ByteBuffer.wrap(content, 0, contentLength).order(ByteOrder.BIG_ENDIAN);
            byte[] magic = new byte[MAGIC.length];
            input.get(magic);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("Prepared texture magic header is invalid");
            }
            int version = input.getInt();
            if (version != PreparedTexture.FORMAT_VERSION) {
                throw new IOException("Unsupported prepared texture version: " + version);
            }
            int payloadLength = input.getInt();
            byte[] sourceHash = new byte[SHA256_BYTES];
            input.get(sourceHash);
            PreparedTexture.Transformation transformation =
                    PreparedTexture.Transformation.fromId(input.getInt());
            int originalWidth = input.getInt();
            int originalHeight = input.getInt();
            int uploadWidth = input.getInt();
            int uploadHeight = input.getInt();
            int channels = input.getInt();
            int color0 = input.getInt();
            int color1 = input.getInt();
            int color2 = input.getInt();
            StorageCodec storageCodec = StorageCodec.fromId(input.getInt());
            int pixelLength = input.getInt();

            long expectedLength = minimumFileBytes() + (long) payloadLength;
            int storedLength = payloadLength - PAYLOAD_FIXED_BYTES;
            if (payloadLength < PAYLOAD_FIXED_BYTES || expectedLength != size
                    || storageCodec != StorageCodec.LZ4) {
                throw new IOException("Prepared texture LZ4 payload metadata is invalid");
            }
            if (uploadWidth <= 0 || uploadHeight <= 0 || (channels != 3 && channels != 4)) {
                throw new IOException("Prepared texture dimensions or channel count are invalid");
            }
            long expectedPixels = Math.multiplyExact(
                    Math.multiplyExact((long) uploadWidth, uploadHeight), channels);
            if (pixelLength < 0 || expectedPixels != pixelLength || storedLength < 0
                    || input.position() + storedLength != contentLength
                    || pixelLength > MAX_FILE_BYTES) {
                throw new IOException(
                        "Prepared texture pixel length is " + pixelLength + "; expected " + expectedPixels);
            }
            byte[] pixels = decodeLz4(content, input.position(), storedLength, pixelLength);
            return PreparedTexture.adopting(
                    java.util.HexFormat.of().formatHex(sourceHash),
                    transformation,
                    originalWidth,
                    originalHeight,
                    uploadWidth,
                    uploadHeight,
                    channels,
                    color0,
                    color1,
                    color2,
                    pixels);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Prepared texture contains invalid data: " + error.getMessage(), error);
        }
    }

    private static PreparedTexture read(Path source, boolean verifyChecksum) throws IOException {
        long size = Files.size(source);
        if (size < minimumFileBytes()) {
            throw new IOException("Prepared texture blob is too small: " + source);
        }
        if (size > MAX_FILE_BYTES) {
            throw new IOException("Prepared texture blob exceeds the " + MAX_FILE_BYTES + " byte safety limit: " + source);
        }
        try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ)) {
            return read(input, MAX_FILE_BYTES, source.toString(), verifyChecksum);
        }
    }

    static PreparedTexture read(InputStream input, int maximumBytes, String sourceLabel) throws IOException {
        return read(input, maximumBytes, sourceLabel, true);
    }

    private static PreparedTexture read(
            InputStream input, int maximumBytes, String sourceLabel, boolean verifyChecksum) throws IOException {
        Objects.requireNonNull(input, "input");
        if (maximumBytes < minimumFileBytes() || maximumBytes > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Invalid prepared texture read limit: " + maximumBytes);
        }
        byte[] bytes = input.readNBytes(Math.addExact(maximumBytes, 1));
        if (bytes.length > maximumBytes) {
            throw new IOException(
                    "Prepared texture blob exceeds the " + maximumBytes + " byte safety limit: " + sourceLabel);
        }
        if (bytes.length < minimumFileBytes()) {
            throw new IOException("Prepared texture blob is too small: " + sourceLabel);
        }
        return fromBytes(bytes, verifyChecksum);
    }

    public static byte[] toBytes(PreparedTexture texture) throws IOException {
        return toBytes(texture, StorageCodec.RAW);
    }

    public static byte[] toBytes(PreparedTexture texture, StorageCodec codec) throws IOException {
        Objects.requireNonNull(codec, "codec");
        byte[] payload = encodePayload(texture, codec);
        long total = minimumFileBytes() + payload.length;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Prepared texture blob exceeds the " + MAX_FILE_BYTES + " byte safety limit");
        }
        byte[] checksum = Hashes.sha256Bytes(payload);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) total);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(PreparedTexture.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(checksum);
        }
        return bytes.toByteArray();
    }

    public static PreparedTexture fromBytes(byte[] bytes) throws IOException {
        return fromBytes(bytes, true);
    }

    private static PreparedTexture fromBytes(byte[] bytes, boolean verifyChecksum) throws IOException {
        if (bytes.length < minimumFileBytes()) {
            throw new IOException("Prepared texture blob is too small");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Prepared texture blob exceeds the " + MAX_FILE_BYTES + " byte safety limit");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("Prepared texture magic header is invalid");
            }
            int version = input.readInt();
            if (version != PreparedTexture.FORMAT_VERSION) {
                throw new IOException("Unsupported prepared texture version: " + version);
            }
            int payloadLength = input.readInt();
            long expectedLength = minimumFileBytes() + (long) payloadLength;
            if (payloadLength < PAYLOAD_FIXED_BYTES || expectedLength != bytes.length) {
                throw new IOException("Prepared texture payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Prepared texture ended before its checksum");
            }
            if (verifyChecksum && !MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Prepared texture checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Prepared texture contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] encodePayload(PreparedTexture texture, StorageCodec codec) throws IOException {
        byte[] pixels = texture.pixels();
        byte[] storedPixels = encodePixels(codec, pixels);
        int payloadSize = Math.addExact(PAYLOAD_FIXED_BYTES, storedPixels.length);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(payloadSize);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(Hashes.decodeSha256(texture.sourceSha256()));
            output.writeInt(texture.transformation().id());
            output.writeInt(texture.originalWidth());
            output.writeInt(texture.originalHeight());
            output.writeInt(texture.uploadWidth());
            output.writeInt(texture.uploadHeight());
            output.writeInt(texture.channels());
            output.writeInt(texture.color0Rgba());
            output.writeInt(texture.color1Rgba());
            output.writeInt(texture.color2Rgba());
            output.writeInt(codec.id());
            output.writeInt(texture.pixelBytes());
            output.write(storedPixels);
        }
        return bytes.toByteArray();
    }

    private static PreparedTexture decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] sourceHash = input.readNBytes(SHA256_BYTES);
            if (sourceHash.length != SHA256_BYTES) {
                throw new EOFException("Prepared texture ended inside its source hash");
            }
            PreparedTexture.Transformation transformation = PreparedTexture.Transformation.fromId(input.readInt());
            int originalWidth = input.readInt();
            int originalHeight = input.readInt();
            int uploadWidth = input.readInt();
            int uploadHeight = input.readInt();
            int channels = input.readInt();
            int color0 = input.readInt();
            int color1 = input.readInt();
            int color2 = input.readInt();
            StorageCodec codec = StorageCodec.fromId(input.readInt());
            int pixelLength = input.readInt();
            if (uploadWidth <= 0 || uploadHeight <= 0 || (channels != 3 && channels != 4)) {
                throw new IOException("Prepared texture dimensions or channel count are invalid");
            }
            long expectedPixels = Math.multiplyExact(
                    Math.multiplyExact((long) uploadWidth, uploadHeight),
                    channels);
            if (pixelLength < 0 || expectedPixels != pixelLength || pixelLength > MAX_FILE_BYTES) {
                throw new IOException(
                        "Prepared texture pixel length is " + pixelLength + "; expected " + expectedPixels);
            }
            byte[] storedPixels = input.readAllBytes();
            if (storedPixels.length == 0 && pixelLength != 0) {
                throw new EOFException("Prepared texture ended inside its pixels");
            }
            byte[] pixels = decodePixels(codec, storedPixels, pixelLength);
            // The array was just read from this stream and no other reference to it exists, so
            // the constructor's defensive copy would only duplicate the pixels the blob was read
            // for -- 2.53 GB of them across one launch of the reviewed profile.
            return PreparedTexture.adopting(
                    java.util.HexFormat.of().formatHex(sourceHash),
                    transformation,
                    originalWidth,
                    originalHeight,
                    uploadWidth,
                    uploadHeight,
                    channels,
                    color0,
                    color1,
                    color2,
                    pixels);
        }
    }

    private static void readFully(
            FileChannel channel, long[] position, ByteBuffer target, String eofMessage) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target, position[0]);
            if (read < 0) {
                throw new EOFException(eofMessage);
            }
            if (read == 0) {
                throw new IOException("Prepared texture range read made no progress");
            }
            position[0] += read;
        }
    }

    private static byte[] encodePixels(StorageCodec codec, byte[] pixels) throws IOException {
        if (codec == StorageCodec.RAW) {
            return pixels;
        }
        Lz4Compressor compressor = new Lz4Compressor();
        byte[] compressed = new byte[compressor.maxCompressedLength(pixels.length)];
        int length = compressor.compress(pixels, 0, pixels.length, compressed, 0, compressed.length);
        return Arrays.copyOf(compressed, length);
    }

    private static byte[] decodePixels(StorageCodec codec, byte[] stored, int pixelLength) throws IOException {
        return decodePixels(codec, stored, stored.length, pixelLength);
    }

    private static byte[] decodePixels(
            StorageCodec codec, byte[] stored, int storedLength, int pixelLength) throws IOException {
        if (codec == StorageCodec.RAW) {
            if (storedLength != pixelLength || stored.length != storedLength) {
                throw new IOException(
                        "Prepared texture raw pixel length is " + storedLength + "; expected " + pixelLength);
            }
            return stored;
        }
        return decodeLz4(stored, 0, storedLength, pixelLength);
    }

    private static byte[] decodeLz4(
            byte[] stored, int offset, int storedLength, int pixelLength) throws IOException {
        byte[] pixels = new byte[pixelLength];
        try {
            int restored = LZ4_DECOMPRESSOR.decompress(
                    stored, offset, storedLength, pixels, 0, pixels.length);
            if (restored != pixelLength) {
                throw new IOException(
                        "Prepared texture decompressed to " + restored + " bytes; expected " + pixelLength);
            }
            return pixels;
        } catch (MalformedInputException error) {
            throw new IOException("Prepared texture LZ4 payload is malformed", error);
        }
    }

    private static byte[] trustedLz4Scratch(int required) {
        if (required > MAX_TRUSTED_LZ4_SCRATCH_BYTES) {
            return new byte[required];
        }
        byte[] current = TRUSTED_LZ4_SCRATCH.get();
        if (current.length >= required) {
            return current;
        }
        int capacity = Integer.highestOneBit(Math.max(1, required - 1)) << 1;
        if (capacity <= 0 || capacity > MAX_TRUSTED_LZ4_SCRATCH_BYTES) {
            capacity = MAX_TRUSTED_LZ4_SCRATCH_BYTES;
        }
        byte[] replacement = new byte[capacity];
        TRUSTED_LZ4_SCRATCH.set(replacement);
        return replacement;
    }

    public enum StorageCodec {
        RAW(CODEC_RAW, "raw"),
        LZ4(CODEC_LZ4, "lz4");

        private final int id;
        private final String suffix;

        StorageCodec(int id, String suffix) {
            this.id = id;
            this.suffix = suffix;
        }

        public int id() {
            return id;
        }

        public String suffix() {
            return suffix;
        }

        private static StorageCodec fromId(int id) throws IOException {
            for (StorageCodec codec : values()) {
                if (codec.id == id) {
                    return codec;
                }
            }
            throw new IOException("Unsupported prepared texture codec: " + id);
        }
    }

    private static long minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2L + CHECKSUM_BYTES;
    }
}

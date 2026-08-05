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
    private static final byte[] MAGIC = {'S', 'P', 'F', 'T'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int SHA256_BYTES = 32;
    private static final int CODEC_RAW = 0;
    private static final int CODEC_LZ4 = 1;
    private static final int PAYLOAD_FIXED_BYTES = SHA256_BYTES + Integer.BYTES * 11;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;
    private static final Lz4Decompressor LZ4_DECOMPRESSOR = new Lz4Decompressor();

    private PreparedTextureIO() {
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
            long size = channel.size();
            if (size < minimumFileBytes()) {
                throw new IOException("Prepared texture blob is too small: " + source);
            }
            if (size > MAX_FILE_BYTES) {
                throw new IOException(
                        "Prepared texture blob exceeds the " + MAX_FILE_BYTES + " byte safety limit: " + source);
            }

            // The trusted serving path does not need the payload checksum. Read the fixed metadata
            // and pixels directly into their final array instead of Files.readAllBytes() followed
            // by DataInputStream.readNBytes(), which copied every served texture a second time.
            ByteBuffer metadata = ByteBuffer.allocate(
                    MAGIC.length + Integer.BYTES * 2 + PAYLOAD_FIXED_BYTES).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, metadata, "Prepared texture ended inside its metadata");
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
                    Math.multiplyExact((long) uploadWidth, uploadHeight),
                    channels);
            int storedLength = payloadLength - PAYLOAD_FIXED_BYTES;
            if (pixelLength < 0 || expectedPixels != pixelLength || storedLength < 0 || pixelLength > MAX_FILE_BYTES) {
                throw new IOException(
                        "Prepared texture pixel length is " + pixelLength + "; expected " + expectedPixels);
            }

            byte[] storedPixels = new byte[storedLength];
            readFully(channel, ByteBuffer.wrap(storedPixels), "Prepared texture ended inside its pixels");
            if (channel.position() != size - CHECKSUM_BYTES) {
                throw new IOException("Prepared texture payload contains trailing data");
            }
            byte[] pixels = decodePixels(storageCodec, storedPixels, pixelLength);
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
        return fromBytes(Files.readAllBytes(source), verifyChecksum);
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

    private static void readFully(FileChannel channel, ByteBuffer target, String eofMessage) throws IOException {
        while (target.hasRemaining()) {
            if (channel.read(target) < 0) {
                throw new EOFException(eofMessage);
            }
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
        if (codec == StorageCodec.RAW) {
            if (stored.length != pixelLength) {
                throw new IOException(
                        "Prepared texture raw pixel length is " + stored.length + "; expected " + pixelLength);
            }
            return stored;
        }
        byte[] pixels = new byte[pixelLength];
        try {
            int restored = LZ4_DECOMPRESSOR.decompress(
                    stored, 0, stored.length, pixels, 0, pixels.length);
            if (restored != pixelLength) {
                throw new IOException(
                        "Prepared texture decompressed to " + restored + " bytes; expected " + pixelLength);
            }
            return pixels;
        } catch (MalformedInputException error) {
            throw new IOException("Prepared texture LZ4 payload is malformed", error);
        }
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

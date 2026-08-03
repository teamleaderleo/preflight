package dev.starsector.preflight.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

/** Versioned raw blob persistence for upload-ready texture data. */
public final class PreparedTextureIO {
    private static final byte[] MAGIC = {'S', 'P', 'F', 'T'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int SHA256_BYTES = 32;
    private static final int CODEC_RAW = 0;
    private static final int PAYLOAD_FIXED_BYTES = SHA256_BYTES + Integer.BYTES * 11;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;

    private PreparedTextureIO() {
    }

    public static void write(Path target, PreparedTexture texture) throws IOException {
        AtomicBlobs.write(target, toBytes(texture));
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
        return read(source, false);
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
        long payloadSize = PAYLOAD_FIXED_BYTES + (long) texture.pixelBytes();
        long total = minimumFileBytes() + payloadSize;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Prepared texture blob exceeds the " + MAX_FILE_BYTES + " byte safety limit");
        }
        byte[] payload = encodePayload(texture, (int) payloadSize);
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

    private static byte[] encodePayload(PreparedTexture texture, int payloadSize) throws IOException {
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
            output.writeInt(CODEC_RAW);
            output.writeInt(texture.pixelBytes());
            output.write(texture.pixels());
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
            int codec = input.readInt();
            if (codec != CODEC_RAW) {
                throw new IOException("Unsupported prepared texture codec: " + codec);
            }
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
            byte[] pixels = input.readNBytes(pixelLength);
            if (pixels.length != pixelLength) {
                throw new EOFException("Prepared texture ended inside its pixels");
            }
            if (input.available() != 0) {
                throw new IOException("Prepared texture payload contains trailing data");
            }
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

    private static long minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2L + CHECKSUM_BYTES;
    }
}

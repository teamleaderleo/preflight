package dev.starsector.preflight.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Versioned blob persistence for {@link BlockTexture}.
 *
 * <p>The magic is {@code SPFB}, distinct from the prepared-pixel cache's {@code SPFT}, so the two
 * caches cannot be read into each other even if a path is wrong or a directory is shared. That is
 * cheap insurance against the one mistake with no visible symptom: exact and lossy data have the same
 * shape, and a cache that mixed them would produce a game that merely looks slightly worse.
 *
 * <p>Everything is checksummed. Block data is unusually intolerant of corruption — there is no
 * structure a decoder could use to notice that an endpoint pair is wrong, so a flipped bit becomes a
 * wrong colour rather than an error — and the whole point of a cache is that nobody looks at it.
 */
public final class BlockTextureIO {
    private static final byte[] MAGIC = {'S', 'P', 'F', 'B'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int SHA256_BYTES = 32;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;

    private BlockTextureIO() {
    }

    public static void write(Path target, BlockTexture texture) throws IOException {
        AtomicBlobs.write(target, toBytes(texture));
    }

    public static BlockTexture read(Path source) throws IOException {
        return read(source, MAX_FILE_BYTES);
    }

    static BlockTexture read(Path source, int maximumBytes) throws IOException {
        if (maximumBytes < minimumFileBytes() || maximumBytes > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Invalid block texture read limit: " + maximumBytes);
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ)) {
            bytes = input.readNBytes(Math.addExact(maximumBytes, 1));
        }
        if (bytes.length < minimumFileBytes()) {
            throw new IOException("Block texture blob is too small: " + source);
        }
        if (bytes.length > maximumBytes) {
            throw new IOException(
                    "Block texture blob exceeds the " + maximumBytes + " byte safety limit: " + source);
        }
        return fromBytes(bytes);
    }

    public static byte[] toBytes(BlockTexture texture) throws IOException {
        byte[] payload = encodePayload(texture);
        if (minimumFileBytes() + (long) payload.length > MAX_FILE_BYTES) {
            throw new IOException("Block texture blob exceeds the " + MAX_FILE_BYTES + " byte safety limit");
        }
        byte[] checksum = Hashes.sha256Bytes(payload);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + (int) minimumFileBytes());
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(BlockTexture.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(checksum);
        }
        return bytes.toByteArray();
    }

    public static BlockTexture fromBytes(byte[] bytes) throws IOException {
        if (bytes.length < minimumFileBytes()) {
            throw new IOException("Block texture blob is too small");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Block texture blob exceeds the " + MAX_FILE_BYTES + " byte safety limit");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("Block texture magic header is invalid");
            }
            int version = input.readInt();
            if (version != BlockTexture.FORMAT_VERSION) {
                throw new IOException("Unsupported block texture version: " + version);
            }
            int payloadLength = input.readInt();
            if (payloadLength < 0 || minimumFileBytes() + (long) payloadLength != bytes.length) {
                throw new IOException("Block texture payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Block texture ended before its checksum");
            }
            if (!MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Block texture checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | IndexOutOfBoundsException | ArithmeticException error) {
            throw new IOException("Block texture contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] encodePayload(BlockTexture texture) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(Hashes.decodeSha256(texture.sourceSha256()));
            output.writeInt(texture.format().id());
            output.writeInt(texture.codecVersion());
            output.writeInt(texture.originalWidth());
            output.writeInt(texture.originalHeight());
            output.writeInt(texture.width());
            output.writeInt(texture.height());
            writeFidelity(output, texture.fidelity());
            output.writeInt(texture.levelCount());
            for (int level = 0; level < texture.levelCount(); level++) {
                byte[] blocks = texture.level(level);
                output.writeInt(blocks.length);
                output.write(blocks);
            }
        }
        return bytes.toByteArray();
    }

    private static BlockTexture decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] sourceHash = input.readNBytes(SHA256_BYTES);
            if (sourceHash.length != SHA256_BYTES) {
                throw new EOFException("Block texture ended inside its source hash");
            }
            BlockTexture.Format format = BlockTexture.Format.fromId(input.readInt());
            int codecVersion = input.readInt();
            int originalWidth = input.readInt();
            int originalHeight = input.readInt();
            int width = input.readInt();
            int height = input.readInt();
            TextureFidelity.Report fidelity = readFidelity(input);
            int levelCount = input.readInt();
            if (levelCount <= 0 || levelCount > BlockTexture.MAX_LEVELS) {
                throw new IOException("Block texture level count is invalid: " + levelCount);
            }
            List<byte[]> levels = new ArrayList<>(levelCount);
            for (int level = 0; level < levelCount; level++) {
                int length = input.readInt();
                // Checked before allocating: the length is attacker-visible in the sense that matters
                // here -- a corrupted cache entry should fail, not ask for a two-gigabyte array.
                if (length < 0 || length > payload.length) {
                    throw new IOException("Block texture level " + level + " length is invalid: " + length);
                }
                byte[] blocks = input.readNBytes(length);
                if (blocks.length != length) {
                    throw new EOFException("Block texture ended inside level " + level);
                }
                levels.add(blocks);
            }
            if (input.available() != 0) {
                throw new IOException("Block texture payload contains trailing data");
            }
            return new BlockTexture(
                    java.util.HexFormat.of().formatHex(sourceHash),
                    format,
                    codecVersion,
                    originalWidth,
                    originalHeight,
                    width,
                    height,
                    fidelity,
                    levels);
        }
    }

    private static void writeFidelity(DataOutputStream output, TextureFidelity.Report report) throws IOException {
        output.writeLong(report.visiblePixels());
        output.writeDouble(report.meanDeltaE());
        output.writeDouble(report.p99DeltaE());
        output.writeDouble(report.maxDeltaE());
        output.writeDouble(report.imperceptibleFraction());
        output.writeDouble(report.obviousFraction());
        output.writeInt(report.maxAlphaError());
    }

    private static TextureFidelity.Report readFidelity(DataInputStream input) throws IOException {
        return new TextureFidelity.Report(
                input.readLong(),
                input.readDouble(),
                input.readDouble(),
                input.readDouble(),
                input.readDouble(),
                input.readDouble(),
                input.readInt());
    }

    private static long minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2L + CHECKSUM_BYTES;
    }
}

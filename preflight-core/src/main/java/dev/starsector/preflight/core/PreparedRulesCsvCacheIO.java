package dev.starsector.preflight.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;

/** Checksummed, bounded, transactional persistence for merged campaign-rules rows. */
public final class PreparedRulesCsvCacheIO {
    private static final byte[] MAGIC = {'S', 'P', 'R', 'C'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int SHA256_BYTES = 32;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;
    private static final int MAX_TREE_BYTES = 256 * 1024 * 1024;

    private PreparedRulesCsvCacheIO() {
    }

    public static void write(Path target, PreparedRulesCsvCache cache) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = toBytes(cache);
        Path temporary = absolute.resolveSibling(
                absolute.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            AtomicPublish.replace(temporary, absolute);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public static PreparedRulesCsvCache read(Path source) throws IOException {
        return read(source, MAX_FILE_BYTES);
    }

    static PreparedRulesCsvCache read(Path source, int maximumBytes) throws IOException {
        if (maximumBytes < minimumFileBytes() || maximumBytes > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Invalid prepared rules cache read limit: " + maximumBytes);
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ)) {
            bytes = input.readNBytes(Math.addExact(maximumBytes, 1));
        }
        if (bytes.length < minimumFileBytes()) {
            throw new IOException("Prepared rules cache is too small: " + source);
        }
        if (bytes.length > maximumBytes) {
            throw new IOException(
                    "Prepared rules cache exceeds the " + maximumBytes + " byte safety limit: " + source);
        }
        return fromBytes(bytes);
    }

    public static byte[] toBytes(PreparedRulesCsvCache cache) throws IOException {
        byte[] payload = payload(cache);
        long total = minimumFileBytes() + (long) payload.length;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Prepared rules cache exceeds the safety limit");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) total);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(PreparedRulesCsvCache.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(Hashes.sha256Bytes(payload));
        }
        return bytes.toByteArray();
    }

    public static PreparedRulesCsvCache fromBytes(byte[] bytes) throws IOException {
        if (bytes.length < minimumFileBytes() || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Prepared rules cache size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) {
                throw new IOException("Prepared rules cache magic header is invalid");
            }
            int version = input.readInt();
            if (version != PreparedRulesCsvCache.FORMAT_VERSION) {
                throw new IOException("Unsupported prepared rules cache version: " + version);
            }
            int payloadLength = input.readInt();
            if (payloadLength < 0 || minimumFileBytes() + (long) payloadLength != bytes.length) {
                throw new IOException("Prepared rules cache payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Prepared rules cache ended before its checksum");
            }
            if (!MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Prepared rules cache checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Prepared rules cache contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] payload(PreparedRulesCsvCache cache) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(Hashes.decodeSha256(cache.profileIdentitySha256()));
            writeTree(output, cache.mergedTree());
        }
        return bytes.toByteArray();
    }

    private static PreparedRulesCsvCache decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] profile = input.readNBytes(SHA256_BYTES);
            if (profile.length != SHA256_BYTES) {
                throw new EOFException("Prepared rules cache ended inside its profile identity");
            }
            byte[] tree = readTree(input);
            if (input.read() != -1) {
                throw new IOException("Prepared rules cache payload has trailing bytes");
            }
            return new PreparedRulesCsvCache(java.util.HexFormat.of().formatHex(profile), tree);
        }
    }

    private static void writeTree(DataOutputStream output, byte[] tree) throws IOException {
        if (tree.length > MAX_TREE_BYTES) {
            throw new IOException("Prepared rules tree exceeds the safety limit");
        }
        output.writeInt(tree.length);
        output.write(tree);
    }

    private static byte[] readTree(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_TREE_BYTES) {
            throw new IOException("Prepared rules tree length is invalid: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Prepared rules cache ended inside its tree");
        }
        return bytes;
    }

    private static int minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2 + CHECKSUM_BYTES;
    }
}

package dev.starsector.preflight.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Checksummed, bounded, transactional persistence for prepared merged reads. */
public final class PreparedMergedReadCacheIO {
    private static final byte[] MAGIC = {'S', 'P', 'M', 'R'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int SHA256_BYTES = 32;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;
    private static final int MAX_ENTRIES = 200_000;
    private static final int MAX_KEY_BYTES = 64 * 1024;
    private static final int MAX_VALUE_BYTES = 64 * 1024 * 1024;

    private PreparedMergedReadCacheIO() {
    }

    public static void write(Path target, PreparedMergedReadCache cache) throws IOException {
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
            try {
                Files.move(temporary, absolute,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public static PreparedMergedReadCache read(Path source) throws IOException {
        long size = Files.size(source);
        if (size < minimumFileBytes() || size > MAX_FILE_BYTES) {
            throw new IOException("Prepared merged read cache size is invalid: " + source);
        }
        return fromBytes(Files.readAllBytes(source));
    }

    public static byte[] toBytes(PreparedMergedReadCache cache) throws IOException {
        byte[] payload = payload(cache);
        long total = minimumFileBytes() + (long) payload.length;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Prepared merged read cache exceeds the safety limit");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) total);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(PreparedMergedReadCache.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(Hashes.sha256Bytes(payload));
        }
        return bytes.toByteArray();
    }

    public static PreparedMergedReadCache fromBytes(byte[] bytes) throws IOException {
        if (bytes.length < minimumFileBytes() || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Prepared merged read cache size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) {
                throw new IOException("Prepared merged read cache magic header is invalid");
            }
            int version = input.readInt();
            if (version != PreparedMergedReadCache.FORMAT_VERSION) {
                throw new IOException("Unsupported prepared merged read cache version: " + version);
            }
            int payloadLength = input.readInt();
            if (payloadLength < 0 || minimumFileBytes() + (long) payloadLength != bytes.length) {
                throw new IOException("Prepared merged read cache payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Prepared merged read cache ended before its checksum");
            }
            if (!MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Prepared merged read cache checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException(
                    "Prepared merged read cache contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] payload(PreparedMergedReadCache cache) throws IOException {
        if (cache.entries().size() > MAX_ENTRIES) {
            throw new IOException("Prepared merged read cache has too many entries");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(Hashes.decodeSha256(cache.profileIdentitySha256()));
            output.writeInt(cache.entries().size());
            // Sorted so an artifact is a function of what it holds and not of the order the launch
            // happened to learn things in; two runs that learn the same reads write the same bytes.
            for (Map.Entry<String, byte[]> item : new TreeMap<>(cache.entries()).entrySet()) {
                writeBytes(output, item.getKey().getBytes(StandardCharsets.UTF_8), MAX_KEY_BYTES);
                writeBytes(output, item.getValue(), MAX_VALUE_BYTES);
            }
        }
        return bytes.toByteArray();
    }

    private static PreparedMergedReadCache decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] profile = input.readNBytes(SHA256_BYTES);
            if (profile.length != SHA256_BYTES) {
                throw new EOFException("Prepared merged read cache ended inside its profile identity");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) {
                throw new IOException("Prepared merged read cache entry count is invalid: " + count);
            }
            Map<String, byte[]> entries = new LinkedHashMap<>(Math.max(16, count * 2));
            for (int index = 0; index < count; index++) {
                String key = new String(readBytes(input, MAX_KEY_BYTES), StandardCharsets.UTF_8);
                byte[] value = readBytes(input, MAX_VALUE_BYTES);
                if (entries.put(key, value) != null) {
                    throw new IOException("Duplicate prepared merged read key");
                }
            }
            if (input.read() != -1) {
                throw new IOException("Prepared merged read cache payload has trailing bytes");
            }
            return new PreparedMergedReadCache(
                    java.util.HexFormat.of().formatHex(profile), entries);
        }
    }

    private static void writeBytes(DataOutputStream output, byte[] value, int limit) throws IOException {
        if (value.length > limit) {
            throw new IOException("Prepared merged read cache field exceeds the safety limit");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input, int limit) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > limit) {
            throw new IOException("Prepared merged read cache field length is invalid: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Prepared merged read cache ended inside a field");
        }
        return bytes;
    }

    private static int minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2 + CHECKSUM_BYTES;
    }
}

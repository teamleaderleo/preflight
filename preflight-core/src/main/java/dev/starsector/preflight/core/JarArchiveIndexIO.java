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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned binary persistence for {@link JarArchiveIndex}. */
public final class JarArchiveIndexIO {
    private static final byte[] MAGIC = {'S', 'P', 'F', 'J'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int SHA256_BYTES = 32;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;
    private static final int MAX_ENTRY_COUNT = 10_000_000;
    private static final int MAX_NAME_BYTES = 4 * 1024 * 1024;

    private JarArchiveIndexIO() {
    }

    public static void write(Path target, JarArchiveIndex index) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = toBytes(index);
        Path temporary = absolute.resolveSibling(
                absolute.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
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

    public static JarArchiveIndex read(Path source) throws IOException {
        long size = Files.size(source);
        if (size < minimumFileBytes()) {
            throw new IOException("JAR archive index is too small: " + source);
        }
        if (size > MAX_FILE_BYTES) {
            throw new IOException("JAR archive index exceeds the safety limit: " + source);
        }
        return fromBytes(Files.readAllBytes(source));
    }

    public static byte[] toBytes(JarArchiveIndex index) throws IOException {
        byte[] payload = encodePayload(index);
        long total = minimumFileBytes() + payload.length;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("JAR archive index exceeds the safety limit");
        }
        byte[] checksum = Hashes.sha256Bytes(payload);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) total);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(JarArchiveIndex.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(checksum);
        }
        return bytes.toByteArray();
    }

    public static JarArchiveIndex fromBytes(byte[] bytes) throws IOException {
        if (bytes.length < minimumFileBytes()) {
            throw new IOException("JAR archive index is too small");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("JAR archive index exceeds the safety limit");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("JAR archive index magic header is invalid");
            }
            int version = input.readInt();
            if (version != JarArchiveIndex.FORMAT_VERSION) {
                throw new IOException("Unsupported JAR archive index version: " + version);
            }
            int payloadLength = input.readInt();
            long expectedLength = minimumFileBytes() + (long) payloadLength;
            if (payloadLength < 0 || expectedLength != bytes.length) {
                throw new IOException("JAR archive index payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] expectedChecksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || expectedChecksum.length != CHECKSUM_BYTES) {
                throw new EOFException("JAR archive index ended before its checksum");
            }
            if (!MessageDigest.isEqual(expectedChecksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("JAR archive index checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("JAR archive index contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] encodePayload(JarArchiveIndex index) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(Hashes.decodeSha256(index.sourceSha256()));
            output.writeLong(index.sourceBytes());
            output.writeInt(index.entryCount());
            for (JarArchiveIndex.Entry entry : index.entries().values()) {
                writeString(output, entry.name());
                output.writeLong(entry.uncompressedBytes());
                output.writeLong(entry.compressedBytes());
                output.writeLong(entry.crc32());
                output.writeInt(entry.compressionMethod());
            }
        }
        return bytes.toByteArray();
    }

    private static JarArchiveIndex decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] hash = input.readNBytes(SHA256_BYTES);
            if (hash.length != SHA256_BYTES) {
                throw new EOFException("JAR archive index ended inside its source hash");
            }
            String sourceSha256 = java.util.HexFormat.of().formatHex(hash);
            long sourceBytes = input.readLong();
            int count = readCount(input);
            Map<String, JarArchiveIndex.Entry> entries = new LinkedHashMap<>(Math.min(count, 65_536));
            for (int i = 0; i < count; i++) {
                String name = readString(input);
                JarArchiveIndex.Entry entry = new JarArchiveIndex.Entry(
                        name,
                        input.readLong(),
                        input.readLong(),
                        input.readLong(),
                        input.readInt());
                if (entries.put(name, entry) != null) {
                    throw new IOException("Duplicate JAR entry in archive index: " + name);
                }
            }
            if (input.available() != 0) {
                throw new IOException("JAR archive index payload contains trailing data");
            }
            return new JarArchiveIndex(sourceSha256, sourceBytes, entries);
        }
    }

    private static int readCount(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_ENTRY_COUNT) {
            throw new IOException("Invalid JAR archive index entry count: " + count);
        }
        return count;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_NAME_BYTES) {
            throw new IOException("JAR entry name exceeds the safety limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_NAME_BYTES) {
            throw new IOException("Invalid JAR entry name length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("JAR archive index ended inside an entry name");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static long minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2L + CHECKSUM_BYTES;
    }
}

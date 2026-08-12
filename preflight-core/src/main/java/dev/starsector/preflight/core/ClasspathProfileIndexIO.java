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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned binary persistence for {@link ClasspathProfileIndex}. */
public final class ClasspathProfileIndexIO {
    private static final byte[] MAGIC = {'S', 'P', 'F', 'C'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int SHA256_BYTES = 32;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;
    private static final int MAX_ARCHIVES = 1_000_000;
    private static final int MAX_ENTRIES = 20_000_000;
    private static final int MAX_PROVIDERS_PER_ENTRY = 100_000;
    private static final int MAX_STRING_BYTES = 4 * 1024 * 1024;

    private ClasspathProfileIndexIO() {
    }

    public static void write(Path target, ClasspathProfileIndex index) throws IOException {
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

    public static ClasspathProfileIndex read(Path source) throws IOException {
        long size = Files.size(source);
        if (size < minimumFileBytes()) {
            throw new IOException("Classpath profile index is too small: " + source);
        }
        if (size > MAX_FILE_BYTES) {
            throw new IOException("Classpath profile index exceeds the safety limit: " + source);
        }
        return fromBytes(Files.readAllBytes(source));
    }

    public static byte[] toBytes(ClasspathProfileIndex index) throws IOException {
        byte[] payload = encodePayload(index);
        long total = minimumFileBytes() + payload.length;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Classpath profile index exceeds the safety limit");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) total);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(ClasspathProfileIndex.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(Hashes.sha256Bytes(payload));
        }
        return bytes.toByteArray();
    }

    public static ClasspathProfileIndex fromBytes(byte[] bytes) throws IOException {
        if (bytes.length < minimumFileBytes()) {
            throw new IOException("Classpath profile index is too small");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Classpath profile index exceeds the safety limit");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("Classpath profile index magic header is invalid");
            }
            int version = input.readInt();
            if (version != ClasspathProfileIndex.FORMAT_VERSION) {
                throw new IOException("Unsupported classpath profile index version: " + version);
            }
            int payloadLength = input.readInt();
            long expectedLength = minimumFileBytes() + (long) payloadLength;
            if (payloadLength < 0 || expectedLength != bytes.length) {
                throw new IOException("Classpath profile index payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] expectedChecksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || expectedChecksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Classpath profile index ended before its checksum");
            }
            if (!MessageDigest.isEqual(expectedChecksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Classpath profile index checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Classpath profile index contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] encodePayload(ClasspathProfileIndex index) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(Hashes.decodeSha256(index.profileFingerprint()));
            output.writeInt(index.archives().size());
            for (ClasspathProfileIndex.Archive archive : index.archives()) {
                writeString(output, archive.modId());
                writeString(output, archive.relativePath());
                writeString(output, archive.physicalPath().toString());
                output.write(Hashes.decodeSha256(archive.sourceSha256()));
                output.writeLong(archive.sourceBytes());
                output.writeLong(archive.modifiedMillis());
                writeString(output, archive.archiveIndexRelativePath());
                output.writeBoolean(archive.declared());
            }
            output.writeInt(index.entryCount());
            for (Map.Entry<String, List<Integer>> item : index.providers().entrySet()) {
                writeString(output, item.getKey());
                output.writeInt(item.getValue().size());
                for (int provider : item.getValue()) {
                    output.writeInt(provider);
                }
            }
        }
        return bytes.toByteArray();
    }

    private static ClasspathProfileIndex decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] profileHash = input.readNBytes(SHA256_BYTES);
            if (profileHash.length != SHA256_BYTES) {
                throw new EOFException("Classpath profile index ended inside its fingerprint");
            }
            String fingerprint = java.util.HexFormat.of().formatHex(profileHash);
            int archiveCount = readCount(input, "archive", MAX_ARCHIVES);
            List<ClasspathProfileIndex.Archive> archives = new ArrayList<>(Math.min(archiveCount, 65_536));
            for (int i = 0; i < archiveCount; i++) {
                String modId = readString(input);
                String relativePath = readString(input);
                Path physicalPath = Path.of(readString(input));
                byte[] sourceHash = input.readNBytes(SHA256_BYTES);
                if (sourceHash.length != SHA256_BYTES) {
                    throw new EOFException("Classpath profile index ended inside an archive hash");
                }
                archives.add(new ClasspathProfileIndex.Archive(
                        modId,
                        relativePath,
                        physicalPath,
                        java.util.HexFormat.of().formatHex(sourceHash),
                        input.readLong(),
                        input.readLong(),
                        readString(input),
                        input.readBoolean()));
            }

            int entryCount = readCount(input, "entry", MAX_ENTRIES);
            Map<String, List<Integer>> providers = new LinkedHashMap<>(Math.min(entryCount, 65_536));
            for (int i = 0; i < entryCount; i++) {
                String name = readString(input);
                int providerCount = readCount(input, "provider", MAX_PROVIDERS_PER_ENTRY);
                if (providerCount == 0) {
                    throw new IOException("Classpath entry has no providers: " + name);
                }
                List<Integer> values = new ArrayList<>(Math.min(providerCount, 4_096));
                for (int j = 0; j < providerCount; j++) {
                    values.add(input.readInt());
                }
                if (providers.put(name, values) != null) {
                    throw new IOException("Duplicate classpath entry in profile index: " + name);
                }
            }
            if (input.available() != 0) {
                throw new IOException("Classpath profile index payload contains trailing data");
            }
            return new ClasspathProfileIndex(fingerprint, archives, providers);
        }
    }

    private static int readCount(DataInputStream input, String kind, int maximum) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw new IOException("Invalid classpath " + kind + " count: " + count);
        }
        return count;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("Classpath profile index string exceeds the safety limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid classpath profile index string length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Classpath profile index ended inside a string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static long minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2L + CHECKSUM_BYTES;
    }
}

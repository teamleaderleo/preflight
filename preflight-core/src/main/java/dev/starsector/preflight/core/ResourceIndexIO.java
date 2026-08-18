package dev.starsector.preflight.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Binary persistence for {@link ResourceIndex}. */
public final class ResourceIndexIO {
    private static final int ESTABLISHED_FORMAT_VERSION = 1;
    private static final byte[] MAGIC = {'S', 'P', 'F', 'I'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ROOTS = 100_000;
    private static final int MAX_ENTRIES = 10_000_000;
    private static final int MAX_PROVIDERS_PER_ENTRY = 100_000;
    private static final int MAX_EAGER_COLLECTION_CAPACITY = 65_536;

    private ResourceIndexIO() {
    }

    public static Path directory(Path cacheRoot) {
        return CacheFormatNamespace.directory(
                cacheRoot, "resource-indexes", ResourceIndex.FORMAT_VERSION, ESTABLISHED_FORMAT_VERSION);
    }

    public static void write(Path target, ResourceIndex index) throws IOException {
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

    public static ResourceIndex read(Path source) throws IOException {
        long size = Files.size(source);
        if (size < minimumFileBytes()) {
            throw new IOException("Resource index is too small: " + source);
        }
        if (size > MAX_FILE_BYTES) {
            throw new IOException("Resource index exceeds the " + MAX_FILE_BYTES + " byte safety limit: " + source);
        }
        return fromBytes(Files.readAllBytes(source));
    }

    public static byte[] toBytes(ResourceIndex index) throws IOException {
        byte[] payload = encodePayload(index);
        long outputSize = minimumFileBytes() + payload.length;
        if (outputSize > MAX_FILE_BYTES) {
            throw new IOException("Resource index exceeds the " + MAX_FILE_BYTES + " byte safety limit");
        }
        byte[] checksum = sha256(payload);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) outputSize);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(ResourceIndex.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(checksum);
        }
        return bytes.toByteArray();
    }

    public static ResourceIndex fromBytes(byte[] bytes) throws IOException {
        if (bytes.length < minimumFileBytes()) {
            throw new IOException("Resource index is too small");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Resource index exceeds the " + MAX_FILE_BYTES + " byte safety limit");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("Resource index magic header is invalid");
            }
            int version = input.readInt();
            if (version != ResourceIndex.FORMAT_VERSION) {
                throw new IOException("Unsupported resource index version: " + version);
            }
            int payloadLength = input.readInt();
            long expectedLength = minimumFileBytes() + (long) payloadLength;
            if (payloadLength < 0 || expectedLength != bytes.length) {
                throw new IOException("Resource index payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] expectedChecksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || expectedChecksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Resource index ended before the payload checksum");
            }
            byte[] actualChecksum = sha256(payload);
            if (!MessageDigest.isEqual(expectedChecksum, actualChecksum)) {
                throw new IOException("Resource index checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException error) {
            throw new IOException("Resource index contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] encodePayload(ResourceIndex index) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeString(output, index.profileFingerprint());
            output.writeInt(index.roots().size());
            for (ResourceIndex.Root root : index.roots()) {
                writeString(output, root.id());
                writeString(output, root.path().toString());
                output.writeBoolean(root.core());
            }
            output.writeInt(index.entryCount());
            for (Map.Entry<String, List<ResourceIndex.Provider>> entry : index.entries().entrySet()) {
                writeString(output, entry.getKey());
                output.writeInt(entry.getValue().size());
                for (ResourceIndex.Provider provider : entry.getValue()) {
                    output.writeInt(provider.rootIndex());
                    writeString(output, provider.relativePath());
                    output.writeLong(provider.size());
                    output.writeLong(provider.modifiedMillis());
                }
            }
        }
        return bytes.toByteArray();
    }

    private static ResourceIndex decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String fingerprint = readString(input);
            int rootCount = readCount(input, "root", MAX_ROOTS);
            List<ResourceIndex.Root> roots = new ArrayList<>(initialCapacity(rootCount));
            for (int i = 0; i < rootCount; i++) {
                roots.add(new ResourceIndex.Root(readString(input), Path.of(readString(input)), input.readBoolean()));
            }

            int entryCount = readCount(input, "entry", MAX_ENTRIES);
            Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>(initialCapacity(entryCount));
            for (int i = 0; i < entryCount; i++) {
                String path = readString(input);
                int providerCount = readCount(input, "provider", MAX_PROVIDERS_PER_ENTRY);
                if (providerCount == 0) {
                    throw new IOException("Resource entry has no providers: " + path);
                }
                List<ResourceIndex.Provider> providers = new ArrayList<>(initialCapacity(providerCount));
                for (int j = 0; j < providerCount; j++) {
                    providers.add(new ResourceIndex.Provider(
                            input.readInt(),
                            readString(input),
                            input.readLong(),
                            input.readLong()));
                }
                if (entries.put(path, providers) != null) {
                    throw new IOException("Duplicate resource path in index: " + path);
                }
            }
            if (input.available() != 0) {
                throw new IOException("Resource index payload contains trailing data");
            }
            return new ResourceIndex(fingerprint, roots, entries);
        }
    }

    private static int initialCapacity(int expectedSize) {
        return Math.max(16, Math.min(expectedSize, MAX_EAGER_COLLECTION_CAPACITY));
    }

    private static int readCount(DataInputStream input, String kind, int maximum) throws IOException {
        int value = input.readInt();
        if (value < 0 || value > maximum) {
            throw new IOException("Invalid " + kind + " count: " + value);
        }
        return value;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("String exceeds resource index safety limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid resource index string length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Resource index ended inside a string");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Resource index string is not valid UTF-8", error);
        }
    }

    private static long minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2L + CHECKSUM_BYTES;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}

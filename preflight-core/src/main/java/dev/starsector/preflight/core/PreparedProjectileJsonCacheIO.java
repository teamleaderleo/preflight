package dev.starsector.preflight.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** Checksummed, bounded, transactional persistence for tagged projectile JSON trees. */
public final class PreparedProjectileJsonCacheIO {
    private static final byte[] MAGIC = {'S', 'P', 'P', 'J'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int SHA256_BYTES = 32;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_PATH_BYTES = 1024 * 1024;
    private static final int MAX_TREE_BYTES = 32 * 1024 * 1024;

    private PreparedProjectileJsonCacheIO() {
    }

    public static void write(Path target, PreparedProjectileJsonCache cache) throws IOException {
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

    public static PreparedProjectileJsonCache read(Path source) throws IOException {
        long size = Files.size(source);
        if (size < minimumFileBytes() || size > MAX_FILE_BYTES) {
            throw new IOException("Prepared projectile cache size is invalid: " + source);
        }
        return fromBytes(Files.readAllBytes(source));
    }

    public static byte[] toBytes(PreparedProjectileJsonCache cache) throws IOException {
        byte[] payload = payload(cache);
        long total = minimumFileBytes() + (long) payload.length;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Prepared projectile cache exceeds the safety limit");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) total);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(PreparedProjectileJsonCache.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(Hashes.sha256Bytes(payload));
        }
        return bytes.toByteArray();
    }

    public static PreparedProjectileJsonCache fromBytes(byte[] bytes) throws IOException {
        if (bytes.length < minimumFileBytes() || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Prepared projectile cache size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) {
                throw new IOException("Prepared projectile cache magic header is invalid");
            }
            int version = input.readInt();
            if (version != PreparedProjectileJsonCache.FORMAT_VERSION) {
                throw new IOException("Unsupported prepared projectile cache version: " + version);
            }
            int payloadLength = input.readInt();
            if (payloadLength < 0 || minimumFileBytes() + (long) payloadLength != bytes.length) {
                throw new IOException("Prepared projectile cache payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Prepared projectile cache ended before its checksum");
            }
            if (!MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Prepared projectile cache checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Prepared projectile cache contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] payload(PreparedProjectileJsonCache cache) throws IOException {
        if (cache.entries().size() > MAX_ENTRIES) {
            throw new IOException("Prepared projectile cache has too many entries");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(Hashes.decodeSha256(cache.profileIdentitySha256()));
            output.writeInt(cache.entries().size());
            for (Map.Entry<String, byte[]> item : cache.entries().entrySet()) {
                writeString(output, item.getKey(), MAX_PATH_BYTES);
                writeBytes(output, item.getValue(), MAX_TREE_BYTES);
            }
        }
        return bytes.toByteArray();
    }

    private static PreparedProjectileJsonCache decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] profile = input.readNBytes(SHA256_BYTES);
            if (profile.length != SHA256_BYTES) {
                throw new EOFException("Prepared projectile cache ended inside its profile identity");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) {
                throw new IOException("Prepared projectile cache entry count is invalid: " + count);
            }
            TreeMap<String, byte[]> entries = new TreeMap<>();
            for (int index = 0; index < count; index++) {
                String path = readString(input, MAX_PATH_BYTES);
                String canonicalPath = SpecCachePaths.normalizeKey(path);
                if (!canonicalPath.equals(path)) {
                    throw new IOException("Prepared projectile cache path is not canonical: " + path);
                }
                byte[] tree = readBytes(input, MAX_TREE_BYTES);
                if (entries.put(path, tree) != null) {
                    throw new IOException("Duplicate prepared projectile path: " + path);
                }
            }
            if (input.read() != -1) {
                throw new IOException("Prepared projectile cache payload has trailing bytes");
            }
            return new PreparedProjectileJsonCache(
                    java.util.HexFormat.of().formatHex(profile), entries);
        }
    }

    private static void writeString(DataOutputStream output, String value, int limit) throws IOException {
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException error) {
            throw new IOException("Prepared projectile cache string cannot be encoded as UTF-8", error);
        }
        writeBytes(output, bytes, limit);
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes, int limit) throws IOException {
        if (bytes.length > limit) {
            throw new IOException("Prepared projectile cache field exceeds the safety limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int limit) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > limit) {
            throw new IOException("Prepared projectile cache string length is invalid: " + length);
        }
        byte[] bytes = readBytes(input, length, limit);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Prepared projectile cache string is not valid UTF-8", error);
        }
    }

    private static byte[] readBytes(DataInputStream input, int limit) throws IOException {
        int length = input.readInt();
        return readBytes(input, length, limit);
    }

    private static byte[] readBytes(DataInputStream input, int length, int limit) throws IOException {
        if (length < 0 || length > limit) {
            throw new IOException("Prepared projectile cache field length is invalid: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Prepared projectile cache ended inside a field");
        }
        return bytes;
    }

    private static int minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2 + CHECKSUM_BYTES;
    }
}

package dev.starsector.preflight.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Binary persistence for {@link BlockCacheManifest}, magic {@code SPFC}.
 *
 * <p>Binary rather than JSON because this file is read on the startup path the whole cache exists to
 * shorten. A profile can hold ten thousand textures, and parsing that as text would spend a
 * measurable part of the saving on reading the description of the saving.
 */
public final class BlockCacheManifestIO {
    private static final byte[] MAGIC = {'S', 'P', 'F', 'C'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_FILE_BYTES = 256 * 1024 * 1024;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000_000;
    private static final int MAX_EAGER_CAPACITY = 65_536;

    private BlockCacheManifestIO() {
    }

    public static void write(Path target, BlockCacheManifest manifest) throws IOException {
        AtomicBlobs.write(target, toBytes(manifest));
    }

    public static BlockCacheManifest read(Path source) throws IOException {
        long size = Files.size(source);
        if (size < minimumFileBytes() || size > MAX_FILE_BYTES) {
            throw new IOException("Block cache manifest size is invalid: " + source);
        }
        return fromBytes(Files.readAllBytes(source));
    }

    public static byte[] toBytes(BlockCacheManifest manifest) throws IOException {
        byte[] payload = encodePayload(manifest);
        long total = minimumFileBytes() + payload.length;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Block cache manifest exceeds the safety limit");
        }
        byte[] checksum = Hashes.sha256Bytes(payload);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) total);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(BlockCacheManifest.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(checksum);
        }
        return bytes.toByteArray();
    }

    public static BlockCacheManifest fromBytes(byte[] bytes) throws IOException {
        if (bytes.length < minimumFileBytes() || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Block cache manifest size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) {
                throw new IOException("Block cache manifest magic header is invalid");
            }
            int version = input.readInt();
            if (version != BlockCacheManifest.FORMAT_VERSION) {
                throw new IOException("Unsupported block cache manifest version: " + version);
            }
            int payloadLength = input.readInt();
            if (payloadLength < 0 || minimumFileBytes() + (long) payloadLength != bytes.length) {
                throw new IOException("Block cache manifest payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Block cache manifest ended before its checksum");
            }
            if (!MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Block cache manifest checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Block cache manifest contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] encodePayload(BlockCacheManifest manifest) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeString(output, manifest.profileFingerprint());
            output.writeInt(manifest.codecVersion());
            output.writeInt(manifest.entryCount());
            for (Map.Entry<String, BlockCacheManifest.Entry> entry : manifest.entries().entrySet()) {
                BlockCacheManifest.Entry value = entry.getValue();
                writeString(output, entry.getKey());
                writeString(output, value.sourceSha256());
                writeString(output, value.blobRelativePath());
                output.writeInt(value.format().id());
                output.writeInt(value.width());
                output.writeInt(value.height());
                output.writeInt(value.levelCount());
                output.writeLong(value.blockBytes());
                output.writeDouble(value.meanDeltaE());
                output.writeDouble(value.p99DeltaE());
            }
        }
        return bytes.toByteArray();
    }

    private static BlockCacheManifest decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String fingerprint = readString(input);
            int codecVersion = input.readInt();
            int entryCount = readCount(input);
            Map<String, BlockCacheManifest.Entry> entries = new LinkedHashMap<>(
                    Math.max(16, Math.min(entryCount, MAX_EAGER_CAPACITY)));
            for (int i = 0; i < entryCount; i++) {
                String logicalPath = readCanonicalLogicalPath(input);
                BlockCacheManifest.Entry entry = new BlockCacheManifest.Entry(
                        readCanonicalSha256(input),
                        readCanonicalRelativePath(input),
                        BlockTexture.Format.fromId(input.readInt()),
                        input.readInt(),
                        input.readInt(),
                        input.readInt(),
                        input.readLong(),
                        input.readDouble(),
                        input.readDouble());
                if (entries.put(logicalPath, entry) != null) {
                    throw new IOException("Duplicate block cache manifest path: " + logicalPath);
                }
            }
            if (input.available() != 0) {
                throw new IOException("Block cache manifest contains trailing data");
            }
            return new BlockCacheManifest(fingerprint, codecVersion, entries);
        }
    }

    private static int readCount(DataInputStream input) throws IOException {
        int value = input.readInt();
        if (value < 0 || value > MAX_ENTRIES) {
            throw new IOException("Invalid block cache manifest entry count: " + value);
        }
        return value;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException error) {
            throw new IOException("Block cache manifest string cannot be encoded as UTF-8", error);
        }
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("Block cache manifest string exceeds the safety limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readCanonicalLogicalPath(DataInputStream input) throws IOException {
        String value = readString(input);
        String canonical = ResourceIndex.normalizeLogicalPath(value);
        if (!value.equals(canonical)) {
            throw new IOException("Block cache manifest logical path is not canonical: " + value);
        }
        return value;
    }

    private static String readCanonicalRelativePath(DataInputStream input) throws IOException {
        String value = readString(input);
        String canonical = ResourceIndex.normalizeRelativePath(value);
        if (!value.equals(canonical)) {
            throw new IOException("Block cache manifest blob path is not canonical: " + value);
        }
        return value;
    }

    private static String readCanonicalSha256(DataInputStream input) throws IOException {
        String value = readString(input);
        if (!value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IOException("Block cache manifest source SHA-256 is not canonical lowercase hex");
        }
        Hashes.decodeSha256(value);
        return value;
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid block cache manifest string length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Block cache manifest ended inside a string");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Block cache manifest string is not valid UTF-8", error);
        }
    }

    private static long minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2L + CHECKSUM_BYTES;
    }
}

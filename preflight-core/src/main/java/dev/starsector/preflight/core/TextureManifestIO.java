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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Binary persistence for {@link TextureManifest}. */
public final class TextureManifestIO {
    private static final byte[] MAGIC = {'S', 'P', 'F', 'M'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_FILE_BYTES = 256 * 1024 * 1024;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000_000;
    private static final int MAX_EAGER_CAPACITY = 65_536;

    private TextureManifestIO() {
    }

    public static Path directory(Path cacheRoot) {
        if (TextureManifest.FORMAT_VERSION == 1 && PreparedTexture.FORMAT_VERSION == 1) {
            return cacheRoot.resolve("manifests");
        }
        return cacheRoot.resolve("manifests-v" + TextureManifest.FORMAT_VERSION
                + "-blobs-v" + PreparedTexture.FORMAT_VERSION);
    }

    public static void write(Path target, TextureManifest manifest) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        byte[] bytes = toBytes(manifest);
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

        // A prepared manifest becomes eligible for the zero-source-byte launch path only when its
        // current sources can be tied back to their manifest hashes with a platform generation
        // token. Sealing is advisory to preparation: unsupported volumes retain a valid manifest,
        // while launch declines prepared textures until it has exact generation evidence.
        TextureSourceGenerationAuthority.sealIfPossible(absolute, manifest);
    }

    public static TextureManifest read(Path source) throws IOException {
        long size = Files.size(source);
        if (size < minimumFileBytes() || size > MAX_FILE_BYTES) {
            throw new IOException("Texture manifest size is invalid: " + source);
        }
        return fromBytes(Files.readAllBytes(source));
    }

    public static byte[] toBytes(TextureManifest manifest) throws IOException {
        byte[] payload = encodePayload(manifest);
        long total = minimumFileBytes() + payload.length;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Texture manifest exceeds the safety limit");
        }
        byte[] checksum = Hashes.sha256Bytes(payload);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) total);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(TextureManifest.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(checksum);
        }
        return bytes.toByteArray();
    }

    public static TextureManifest fromBytes(byte[] bytes) throws IOException {
        if (bytes.length < minimumFileBytes() || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Texture manifest size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) {
                throw new IOException("Texture manifest magic header is invalid");
            }
            int version = input.readInt();
            if (version != TextureManifest.FORMAT_VERSION) {
                throw new IOException("Unsupported texture manifest version: " + version);
            }
            int payloadLength = input.readInt();
            if (payloadLength < 0 || minimumFileBytes() + (long) payloadLength != bytes.length) {
                throw new IOException("Texture manifest payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Texture manifest ended before its checksum");
            }
            if (!MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Texture manifest checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Texture manifest contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] encodePayload(TextureManifest manifest) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeString(output, manifest.profileFingerprint());
            output.writeInt(manifest.entryCount());
            for (Map.Entry<String, TextureManifest.Entry> entry : manifest.entries().entrySet()) {
                TextureManifest.Entry value = entry.getValue();
                writeString(output, entry.getKey());
                writeString(output, value.sourceSha256());
                output.writeInt(value.transformation().id());
                writeString(output, value.blobRelativePath());
                output.writeInt(value.width());
                output.writeInt(value.height());
                output.writeInt(value.channels());
                output.writeInt(value.pixelBytes());
            }
        }
        return bytes.toByteArray();
    }

    private static TextureManifest decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String fingerprint = readString(input);
            int entryCount = readCount(input, MAX_ENTRIES);
            Map<String, TextureManifest.Entry> entries = new LinkedHashMap<>(
                    Math.max(16, Math.min(entryCount, MAX_EAGER_CAPACITY)));
            for (int i = 0; i < entryCount; i++) {
                String logicalPath = readCanonicalLogicalPath(input);
                TextureManifest.Entry entry = new TextureManifest.Entry(
                        readCanonicalSha256(input),
                        PreparedTexture.Transformation.fromId(input.readInt()),
                        readCanonicalRelativePath(input),
                        input.readInt(),
                        input.readInt(),
                        input.readInt(),
                        input.readInt());
                if (entries.put(logicalPath, entry) != null) {
                    throw new IOException("Duplicate texture manifest path: " + logicalPath);
                }
            }
            if (input.available() != 0) {
                throw new IOException("Texture manifest contains trailing data");
            }
            return new TextureManifest(fingerprint, entries);
        }
    }

    private static int readCount(DataInputStream input, int maximum) throws IOException {
        int value = input.readInt();
        if (value < 0 || value > maximum) {
            throw new IOException("Invalid texture manifest entry count: " + value);
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
            throw new IOException("Texture manifest string cannot be encoded as UTF-8", error);
        }
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("Texture manifest string exceeds the safety limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readCanonicalLogicalPath(DataInputStream input) throws IOException {
        String value = readString(input);
        String canonical = ResourceIndex.normalizeLogicalPath(value);
        if (!value.equals(canonical)) {
            throw new IOException("Texture manifest logical path is not canonical: " + value);
        }
        return value;
    }

    private static String readCanonicalRelativePath(DataInputStream input) throws IOException {
        String value = readString(input);
        String canonical = ResourceIndex.normalizeRelativePath(value);
        if (!value.equals(canonical)) {
            throw new IOException("Texture manifest blob path is not canonical: " + value);
        }
        return value;
    }

    private static String readCanonicalSha256(DataInputStream input) throws IOException {
        String value = readString(input);
        if (!value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IOException("Texture manifest source SHA-256 is not canonical lowercase hex");
        }
        Hashes.decodeSha256(value);
        return value;
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid texture manifest string length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Texture manifest ended inside a string");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Texture manifest string is not valid UTF-8", error);
        }
    }

    private static long minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2L + CHECKSUM_BYTES;
    }
}

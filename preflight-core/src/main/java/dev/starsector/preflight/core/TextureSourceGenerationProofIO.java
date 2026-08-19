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

/** Strict checksummed persistence for {@link TextureSourceGenerationProof}. */
public final class TextureSourceGenerationProofIO {
    private static final byte[] MAGIC = {'S', 'P', 'T', 'G'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PROVIDER_BYTES = 4 * 1024;
    private static final int MAX_TOKEN_BYTES = 64 * 1024;
    private static final int MAX_PROFILE_FILE_NAME_BYTES = 240;
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_EAGER_CAPACITY = 65_536;

    private TextureSourceGenerationProofIO() {
    }

    public static Path directory(Path cacheRoot) {
        return cacheRoot.resolve("texture-source-generations-v" + TextureSourceGenerationProof.FORMAT_VERSION);
    }

    public static Path path(Path cacheRoot, String profileFingerprint) {
        return directory(cacheRoot).resolve(canonicalProfileFileName(profileFingerprint) + ".sptg");
    }

    public static void write(Path target, TextureSourceGenerationProof proof) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        byte[] bytes = toBytes(proof);
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

    public static TextureSourceGenerationProof read(Path source) throws IOException {
        long size = Files.size(source);
        if (size < minimumFileBytes() || size > MAX_FILE_BYTES) {
            throw new IOException("Texture source generation proof size is invalid: " + source);
        }
        return fromBytes(Files.readAllBytes(source));
    }

    public static byte[] toBytes(TextureSourceGenerationProof proof) throws IOException {
        byte[] payload = encodePayload(proof);
        long total = minimumFileBytes() + payload.length;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Texture source generation proof exceeds the safety limit");
        }
        byte[] checksum = Hashes.sha256Bytes(payload);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) total);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(TextureSourceGenerationProof.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(checksum);
        }
        return bytes.toByteArray();
    }

    public static TextureSourceGenerationProof fromBytes(byte[] bytes) throws IOException {
        if (bytes.length < minimumFileBytes() || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Texture source generation proof size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) {
                throw new IOException("Texture source generation proof magic header is invalid");
            }
            int version = input.readInt();
            if (version != TextureSourceGenerationProof.FORMAT_VERSION) {
                throw new IOException("Unsupported texture source generation proof version: " + version);
            }
            int payloadLength = input.readInt();
            if (payloadLength < 0 || minimumFileBytes() + (long) payloadLength != bytes.length) {
                throw new IOException("Texture source generation proof payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Texture source generation proof ended before its checksum");
            }
            if (!MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Texture source generation proof checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException(
                    "Texture source generation proof contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] encodePayload(TextureSourceGenerationProof proof) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeString(
                    output,
                    canonicalProfileFileName(proof.profileFingerprint()),
                    MAX_STRING_BYTES,
                    "profile fingerprint");
            writeString(output, proof.manifestSha256(), MAX_STRING_BYTES, "manifest SHA-256");
            writeString(output, proof.provider(), MAX_PROVIDER_BYTES, "generation provider");
            output.writeInt(proof.entryCount());
            for (Map.Entry<String, String> entry : proof.entries().entrySet()) {
                writeString(output, entry.getKey(), MAX_STRING_BYTES, "logical path");
                writeString(output, entry.getValue(), MAX_TOKEN_BYTES, "generation token");
            }
        }
        return bytes.toByteArray();
    }

    private static TextureSourceGenerationProof decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String fingerprint = canonicalProfileFileName(
                    readString(input, MAX_STRING_BYTES, "profile fingerprint"));
            String manifestSha256 = readCanonicalSha256(input);
            String provider = readString(input, MAX_PROVIDER_BYTES, "generation provider");
            int entryCount = readCount(input, MAX_ENTRIES);
            Map<String, String> entries = new LinkedHashMap<>(
                    Math.max(16, Math.min(entryCount, MAX_EAGER_CAPACITY)));
            for (int index = 0; index < entryCount; index++) {
                String logicalPath = readCanonicalLogicalPath(input);
                String token = readString(input, MAX_TOKEN_BYTES, "generation token");
                if (token.isBlank()) {
                    throw new IOException("Texture source generation token is blank: " + logicalPath);
                }
                if (entries.put(logicalPath, token) != null) {
                    throw new IOException("Duplicate texture generation proof path: " + logicalPath);
                }
            }
            if (input.available() != 0) {
                throw new IOException("Texture source generation proof contains trailing data");
            }
            return new TextureSourceGenerationProof(fingerprint, manifestSha256, provider, entries);
        }
    }

    private static int readCount(DataInputStream input, int maximum) throws IOException {
        int value = input.readInt();
        if (value < 0 || value > maximum) {
            throw new IOException("Invalid texture source generation proof entry count: " + value);
        }
        return value;
    }

    private static void writeString(
            DataOutputStream output, String value, int maximum, String label) throws IOException {
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException error) {
            throw new IOException("Texture source generation " + label + " cannot be encoded as UTF-8", error);
        }
        if (bytes.length > maximum) {
            throw new IOException("Texture source generation " + label + " exceeds the safety limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readCanonicalLogicalPath(DataInputStream input) throws IOException {
        String value = readString(input, MAX_STRING_BYTES, "logical path");
        String canonical = ResourceIndex.normalizeLogicalPath(value);
        if (!value.equals(canonical)) {
            throw new IOException("Texture generation proof logical path is not canonical: " + value);
        }
        return value;
    }

    private static String readCanonicalSha256(DataInputStream input) throws IOException {
        String value = readString(input, MAX_STRING_BYTES, "manifest SHA-256");
        if (!value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IOException("Texture generation proof SHA-256 is not canonical lowercase hex");
        }
        Hashes.decodeSha256(value);
        return value;
    }

    private static String canonicalProfileFileName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Texture source generation profile fingerprint is blank");
        }
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_PROFILE_FILE_NAME_BYTES) {
            throw new IllegalArgumentException(
                    "Texture source generation profile fingerprint exceeds the filename safety limit");
        }
        if (".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException(
                    "Texture source generation profile fingerprint is not a file name: " + value);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean portable = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '-'
                    || character == '_'
                    || character == '.';
            if (!portable) {
                throw new IllegalArgumentException(
                        "Texture source generation profile fingerprint is not a portable file name: " + value);
            }
        }
        return value;
    }

    private static String readString(DataInputStream input, int maximum, String label) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum) {
            throw new IOException("Invalid texture source generation " + label + " length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Texture source generation proof ended inside " + label);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Texture source generation " + label + " is not valid UTF-8", error);
        }
    }

    private static long minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2L + CHECKSUM_BYTES;
    }
}

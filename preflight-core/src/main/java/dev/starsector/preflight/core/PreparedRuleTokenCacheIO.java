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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Checksummed, bounded, transactional persistence for learned rule-token shapes. */
public final class PreparedRuleTokenCacheIO {
    private static final byte[] MAGIC = {'S', 'P', 'R', 'T'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int SHA256_BYTES = 32;
    private static final int MAX_FILE_BYTES = 128 * 1024 * 1024;

    private PreparedRuleTokenCacheIO() {
    }

    public static void write(Path target, PreparedRuleTokenCache cache) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = toBytes(cache);
        Path temporary = absolute.resolveSibling(
                absolute.getFileName() + ".tmp-" + ProcessHandle.current().pid()
                        + "-" + System.nanoTime());
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

    public static PreparedRuleTokenCache read(Path source) throws IOException {
        long size = Files.size(source);
        if (size < minimumFileBytes() || size > MAX_FILE_BYTES) {
            throw new IOException("Prepared rule-token cache size is invalid: " + source);
        }
        return fromBytes(Files.readAllBytes(source));
    }

    public static byte[] toBytes(PreparedRuleTokenCache cache) throws IOException {
        byte[] payload = payload(cache);
        long total = minimumFileBytes() + (long) payload.length;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Prepared rule-token cache exceeds the safety limit");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) total);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(PreparedRuleTokenCache.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(Hashes.sha256Bytes(payload));
        }
        return bytes.toByteArray();
    }

    public static PreparedRuleTokenCache fromBytes(byte[] bytes) throws IOException {
        if (bytes.length < minimumFileBytes() || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Prepared rule-token cache size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) {
                throw new IOException("Prepared rule-token cache magic header is invalid");
            }
            int version = input.readInt();
            if (version != PreparedRuleTokenCache.FORMAT_VERSION) {
                throw new IOException("Unsupported prepared rule-token cache version: " + version);
            }
            int payloadLength = input.readInt();
            if (payloadLength < 0 || minimumFileBytes() + (long) payloadLength != bytes.length) {
                throw new IOException("Prepared rule-token cache payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Prepared rule-token cache ended before its checksum");
            }
            if (!MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Prepared rule-token cache checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException(
                    "Prepared rule-token cache contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] payload(PreparedRuleTokenCache cache) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(Hashes.decodeSha256(cache.profileIdentitySha256()));
            Map<String, List<PreparedRuleTokenCache.Token>> sorted =
                    new TreeMap<>(cache.entries());
            output.writeInt(sorted.size());
            for (Map.Entry<String, List<PreparedRuleTokenCache.Token>> entry : sorted.entrySet()) {
                writeString(output, entry.getKey(), PreparedRuleTokenCache.MAX_STRING_BYTES);
                output.writeInt(entry.getValue().size());
                for (PreparedRuleTokenCache.Token token : entry.getValue()) {
                    output.writeBoolean(token.string() != null);
                    if (token.string() != null) {
                        writeString(output, token.string(), PreparedRuleTokenCache.MAX_STRING_BYTES);
                    }
                    writeString(output, token.type(), PreparedRuleTokenCache.MAX_TYPE_BYTES);
                }
            }
        }
        return bytes.toByteArray();
    }

    private static PreparedRuleTokenCache decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] profile = input.readNBytes(SHA256_BYTES);
            if (profile.length != SHA256_BYTES) {
                throw new EOFException("Prepared rule-token cache ended inside its profile identity");
            }
            int count = input.readInt();
            if (count < 0 || count > PreparedRuleTokenCache.MAX_ENTRIES) {
                throw new IOException("Prepared rule-token cache entry count is invalid: " + count);
            }
            Map<String, List<PreparedRuleTokenCache.Token>> entries = new LinkedHashMap<>();
            long totalTokens = 0L;
            for (int index = 0; index < count; index++) {
                String expression = readString(input, PreparedRuleTokenCache.MAX_STRING_BYTES);
                int tokenCount = input.readInt();
                if (tokenCount < 0 || tokenCount > PreparedRuleTokenCache.MAX_TOKENS_PER_EXPRESSION) {
                    throw new IOException("Prepared rule-token count is invalid: " + tokenCount);
                }
                totalTokens = Math.addExact(totalTokens, tokenCount);
                if (totalTokens > PreparedRuleTokenCache.MAX_TOTAL_TOKENS) {
                    throw new IOException("Prepared rule-token total exceeds the safety limit");
                }
                List<PreparedRuleTokenCache.Token> tokens = new ArrayList<>(tokenCount);
                for (int token = 0; token < tokenCount; token++) {
                    String string = input.readBoolean()
                            ? readString(input, PreparedRuleTokenCache.MAX_STRING_BYTES) : null;
                    String type = readString(input, PreparedRuleTokenCache.MAX_TYPE_BYTES);
                    tokens.add(new PreparedRuleTokenCache.Token(string, type));
                }
                if (entries.put(expression, List.copyOf(tokens)) != null) {
                    throw new IOException("Prepared rule-token cache repeats an expression");
                }
            }
            if (input.read() != -1) {
                throw new IOException("Prepared rule-token cache payload has trailing bytes");
            }
            return new PreparedRuleTokenCache(HexFormat.of().formatHex(profile), entries);
        }
    }

    private static void writeString(DataOutputStream output, String value, int maximum)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) {
            throw new IOException("Prepared rule-token string exceeds the safety limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum) {
            throw new IOException("Prepared rule-token string length is invalid: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Prepared rule-token cache ended inside a string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2 + CHECKSUM_BYTES;
    }
}

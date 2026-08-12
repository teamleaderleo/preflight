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

/** Checksummed, bounded, transactional persistence for learned rule command packages. */
public final class PreparedRuleCommandClassCacheIO {
    private static final byte[] MAGIC = {'S', 'P', 'R', 'K'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int SHA256_BYTES = 32;
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;

    private PreparedRuleCommandClassCacheIO() {
    }

    public static void write(Path target, PreparedRuleCommandClassCache cache) throws IOException {
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

    public static PreparedRuleCommandClassCache read(Path source) throws IOException {
        long size = Files.size(source);
        if (size < minimumFileBytes() || size > MAX_FILE_BYTES) {
            throw new IOException("Prepared rule command cache size is invalid: " + source);
        }
        return fromBytes(Files.readAllBytes(source));
    }

    public static byte[] toBytes(PreparedRuleCommandClassCache cache) throws IOException {
        byte[] payload = payload(cache);
        long total = minimumFileBytes() + (long) payload.length;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Prepared rule command cache exceeds the safety limit");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) total);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(PreparedRuleCommandClassCache.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(Hashes.sha256Bytes(payload));
        }
        return bytes.toByteArray();
    }

    public static PreparedRuleCommandClassCache fromBytes(byte[] bytes) throws IOException {
        if (bytes.length < minimumFileBytes() || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Prepared rule command cache size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) {
                throw new IOException("Prepared rule command cache magic header is invalid");
            }
            int version = input.readInt();
            if (version != PreparedRuleCommandClassCache.FORMAT_VERSION) {
                throw new IOException("Unsupported prepared rule command cache version: " + version);
            }
            int payloadLength = input.readInt();
            if (payloadLength < 0 || minimumFileBytes() + (long) payloadLength != bytes.length) {
                throw new IOException("Prepared rule command cache payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Prepared rule command cache ended before its checksum");
            }
            if (!MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Prepared rule command cache checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException(
                    "Prepared rule command cache contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] payload(PreparedRuleCommandClassCache cache) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(Hashes.decodeSha256(cache.profileIdentitySha256()));
            List<String> packages = cache.commandPackages();
            output.writeInt(packages.size());
            for (String declared : packages) {
                writeString(output, declared);
            }
            Map<String, String> winners = cache.winningPackages();
            output.writeInt(winners.size());
            // Written as an index into the declared list rather than a repeated string: the winner is
            // always a member, and the record's constructor has already refused any artifact where it
            // is not.
            for (Map.Entry<String, String> winner : winners.entrySet()) {
                writeString(output, winner.getKey());
                output.writeInt(packages.indexOf(winner.getValue()));
            }
        }
        return bytes.toByteArray();
    }

    private static PreparedRuleCommandClassCache decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] profile = input.readNBytes(SHA256_BYTES);
            if (profile.length != SHA256_BYTES) {
                throw new EOFException("Prepared rule command cache ended inside its profile identity");
            }

            int packageCount = input.readInt();
            if (packageCount < 0 || packageCount > PreparedRuleCommandClassCache.MAX_PACKAGES) {
                throw new IOException("Prepared rule command package count is invalid: " + packageCount);
            }
            List<String> packages = new ArrayList<>(packageCount);
            for (int index = 0; index < packageCount; index++) {
                packages.add(readString(input));
            }

            int entryCount = input.readInt();
            if (entryCount < 0 || entryCount > PreparedRuleCommandClassCache.MAX_ENTRIES) {
                throw new IOException("Prepared rule command entry count is invalid: " + entryCount);
            }
            Map<String, String> winners = new LinkedHashMap<>();
            for (int index = 0; index < entryCount; index++) {
                String name = readString(input);
                int winner = input.readInt();
                if (winner < 0 || winner >= packages.size()) {
                    throw new IOException("Prepared rule command package index is out of range: " + winner);
                }
                if (winners.put(name, packages.get(winner)) != null) {
                    throw new IOException("Prepared rule command cache repeats [" + name + "]");
                }
            }

            if (input.read() != -1) {
                throw new IOException("Prepared rule command cache payload has trailing bytes");
            }
            return new PreparedRuleCommandClassCache(
                    java.util.HexFormat.of().formatHex(profile), packages, winners);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > PreparedRuleCommandClassCache.MAX_NAME_LENGTH) {
            throw new IOException("Prepared rule command string exceeds the safety limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > PreparedRuleCommandClassCache.MAX_NAME_LENGTH) {
            throw new IOException("Prepared rule command string length is invalid: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Prepared rule command cache ended inside a string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2 + CHECKSUM_BYTES;
    }
}

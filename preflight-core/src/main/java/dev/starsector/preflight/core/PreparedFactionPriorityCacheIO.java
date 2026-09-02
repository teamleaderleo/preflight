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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Checksummed, bounded, transactional persistence for learned faction priority tables. */
public final class PreparedFactionPriorityCacheIO {
    private static final byte[] MAGIC = {'S', 'P', 'F', 'C'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int PROFILE_BYTES = 32;
    private static final int HEADER_BYTES = MAGIC.length + Integer.BYTES * 2;
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;

    private PreparedFactionPriorityCacheIO() {
    }

    public static Path path(Path cacheRoot, String profileFingerprint) {
        Path pack = PreparedTexturePackIO.path(cacheRoot, profileFingerprint);
        String name = pack.getFileName().toString();
        return pack.resolveSibling(name.substring(0, name.length() - ".spfp".length()) + ".spfc");
    }

    public static void write(Path target, PreparedFactionPriorityCache cache) throws IOException {
        byte[] payload = payload(cache);
        long total = HEADER_BYTES + (long) payload.length + CHECKSUM_BYTES;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Prepared faction-priority cache exceeds its safety limit");
        }
        ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(total));
        bytes.put(MAGIC).putInt(PreparedFactionPriorityCache.FORMAT_VERSION).putInt(payload.length);
        bytes.put(payload).put(Hashes.sha256Bytes(payload)).flip();

        Path absolute = target.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = absolute.resolveSibling(
                absolute.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) {
                    if (channel.write(bytes) <= 0) {
                        throw new IOException("Prepared faction-priority cache write made no progress");
                    }
                }
                channel.force(true);
            }
            AtomicPublish.replace(temporary, absolute);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    public static PreparedFactionPriorityCache read(Path source) throws IOException {
        long size = Files.size(source);
        if (size < HEADER_BYTES + CHECKSUM_BYTES + PROFILE_BYTES + Integer.BYTES
                || size > MAX_FILE_BYTES) {
            throw new IOException("Prepared faction-priority cache size is invalid: " + source);
        }
        byte[] bytes = Files.readAllBytes(source);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!java.util.Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) {
                throw new IOException("Prepared faction-priority cache magic header is invalid");
            }
            if (input.readInt() != PreparedFactionPriorityCache.FORMAT_VERSION) {
                throw new IOException("Unsupported prepared faction-priority cache version");
            }
            int payloadLength = input.readInt();
            if (payloadLength <= 0 || HEADER_BYTES + (long) payloadLength + CHECKSUM_BYTES != bytes.length) {
                throw new IOException("Prepared faction-priority cache payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES
                    || !MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Prepared faction-priority cache checksum mismatch");
            }
            return decode(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Prepared faction-priority cache contains invalid data", error);
        }
    }

    private static byte[] payload(PreparedFactionPriorityCache cache) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(Hashes.decodeSha256(cache.profileIdentitySha256()));
            output.writeInt(cache.entries().size());
            for (Map.Entry<String, List<String>> entry : cache.entries().entrySet()) {
                writeString(output, entry.getKey());
                output.writeInt(entry.getValue().size());
                for (String id : entry.getValue()) writeString(output, id);
            }
        }
        return bytes.toByteArray();
    }

    private static PreparedFactionPriorityCache decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] profile = input.readNBytes(PROFILE_BYTES);
            if (profile.length != PROFILE_BYTES) throw new EOFException("Missing profile identity");
            int entryCount = input.readInt();
            if (entryCount < 0 || entryCount > PreparedFactionPriorityCache.MAX_ENTRIES) {
                throw new IOException("Prepared faction-priority entry count is invalid");
            }
            int totalIds = 0;
            Map<String, List<String>> entries = new LinkedHashMap<>();
            for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
                String key = readString(input);
                int idCount = input.readInt();
                totalIds = Math.addExact(totalIds, idCount);
                if (idCount < 0 || totalIds > PreparedFactionPriorityCache.MAX_TOTAL_IDS) {
                    throw new IOException("Prepared faction-priority id count is invalid");
                }
                List<String> ids = new ArrayList<>(idCount);
                for (int idIndex = 0; idIndex < idCount; idIndex++) ids.add(readString(input));
                if (entries.put(key, ids) != null) {
                    throw new IOException("Prepared faction-priority cache repeats a key");
                }
            }
            if (input.read() != -1) {
                throw new IOException("Prepared faction-priority cache payload has trailing bytes");
            }
            return new PreparedFactionPriorityCache(
                    java.util.HexFormat.of().formatHex(profile), entries);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > PreparedFactionPriorityCache.MAX_STRING_BYTES) {
            throw new IOException("Prepared faction-priority string is invalid");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > PreparedFactionPriorityCache.MAX_STRING_BYTES) {
            throw new IOException("Prepared faction-priority string length is invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Prepared faction-priority string is truncated");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

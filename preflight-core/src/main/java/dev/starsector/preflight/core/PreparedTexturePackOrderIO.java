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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Checksummed observed access order used to lay out a profile's next texture pack. */
public final class PreparedTexturePackOrderIO {
    private static final byte[] MAGIC = {'S', 'P', 'F', 'O'};
    private static final int FORMAT_VERSION = 1;
    private static final int CHECKSUM_BYTES = 32;
    private static final int HEADER_BYTES = MAGIC.length + Integer.BYTES * 2;
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;

    private PreparedTexturePackOrderIO() {
    }

    public static Path path(Path cacheRoot, String profileFingerprint) {
        Path pack = PreparedTexturePackIO.path(cacheRoot, profileFingerprint);
        String name = pack.getFileName().toString();
        return pack.resolveSibling(name.substring(0, name.length() - ".spfp".length()) + ".spfo");
    }

    public static void write(
            Path target, String profileFingerprint, Collection<String> blobRelativePaths)
            throws IOException {
        byte[] payload = encode(profileFingerprint, blobRelativePaths);
        long total = HEADER_BYTES + (long) payload.length + CHECKSUM_BYTES;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Prepared texture pack order exceeds its safety limit");
        }
        ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(total));
        bytes.put(MAGIC).putInt(FORMAT_VERSION).putInt(payload.length);
        bytes.put(payload).put(Hashes.sha256Bytes(payload)).flip();
        Path absolute = target.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = absolute.resolveSibling(
                absolute.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) {
                    if (channel.write(bytes) <= 0) {
                        throw new IOException("Prepared texture pack order write made no progress");
                    }
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

    public static List<String> read(Path source, String expectedProfile) throws IOException {
        long size = Files.size(source);
        if (size < HEADER_BYTES + CHECKSUM_BYTES || size > MAX_FILE_BYTES) {
            throw new IOException("Prepared texture pack order size is invalid");
        }
        byte[] bytes = Files.readAllBytes(source);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!java.util.Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) {
                throw new IOException("Prepared texture pack order magic header is invalid");
            }
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported prepared texture pack order version");
            }
            int payloadLength = input.readInt();
            if (payloadLength <= 0 || HEADER_BYTES + (long) payloadLength + CHECKSUM_BYTES != size) {
                throw new IOException("Prepared texture pack order payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES
                    || !MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Prepared texture pack order checksum mismatch");
            }
            return decode(payload, expectedProfile);
        }
    }

    private static byte[] encode(String profile, Collection<String> paths) throws IOException {
        if (profile == null || profile.isEmpty() || paths == null || paths.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Prepared texture pack order is invalid");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : paths) {
            normalized.add(ResourceIndex.normalizeRelativePath(path));
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Prepared texture pack order cannot be empty");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeString(output, profile);
            output.writeInt(normalized.size());
            for (String path : normalized) {
                writeString(output, path);
            }
        }
        return bytes.toByteArray();
    }

    private static List<String> decode(byte[] payload, String expectedProfile) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (!readString(input).equals(expectedProfile)) {
                throw new IOException("Prepared texture pack order profile does not match");
            }
            int count = input.readInt();
            if (count <= 0 || count > MAX_ENTRIES) {
                throw new IOException("Prepared texture pack order entry count is invalid");
            }
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            for (int index = 0; index < count; index++) {
                if (!paths.add(ResourceIndex.normalizeRelativePath(readString(input)))) {
                    throw new IOException("Prepared texture pack order contains a duplicate path");
                }
            }
            if (input.available() != 0) {
                throw new IOException("Prepared texture pack order contains trailing data");
            }
            return List.copyOf(paths);
        } catch (IllegalArgumentException error) {
            throw new IOException("Prepared texture pack order is invalid", error);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("Prepared texture pack order string exceeds its safety limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Prepared texture pack order string length is invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Prepared texture pack order ended inside a string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

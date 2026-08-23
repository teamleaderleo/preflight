package dev.starsector.preflight.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;

/** Checksummed, bounded, transactional persistence for a MagicLib paintjob catalog payload. */
public final class PreparedMagicPaintjobCacheIO {
    private static final byte[] MAGIC = {'S', 'P', 'M', 'P'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int SHA256_BYTES = 32;
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 32 * 1024 * 1024;

    private PreparedMagicPaintjobCacheIO() {
    }

    public static void write(Path target, PreparedMagicPaintjobCache cache) throws IOException {
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

    public static PreparedMagicPaintjobCache read(Path source) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (bytes.length < minimumFileBytes()) {
            throw new IOException("Prepared MagicLib paintjob cache is too small: " + source);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Prepared MagicLib paintjob cache exceeds its safety limit: " + source);
        }
        return fromBytes(bytes);
    }

    public static byte[] toBytes(PreparedMagicPaintjobCache cache) throws IOException {
        byte[] payload = payload(cache);
        long total = minimumFileBytes() + (long) payload.length;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Prepared MagicLib paintjob cache exceeds its safety limit");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) total);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(PreparedMagicPaintjobCache.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(Hashes.sha256Bytes(payload));
        }
        return bytes.toByteArray();
    }

    public static PreparedMagicPaintjobCache fromBytes(byte[] bytes) throws IOException {
        if (bytes.length < minimumFileBytes() || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Prepared MagicLib paintjob cache size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) {
                throw new IOException("Prepared MagicLib paintjob cache magic header is invalid");
            }
            int version = input.readInt();
            if (version != PreparedMagicPaintjobCache.FORMAT_VERSION) {
                throw new IOException("Unsupported prepared MagicLib paintjob cache version: " + version);
            }
            int payloadLength = input.readInt();
            if (payloadLength < SHA256_BYTES + Integer.BYTES
                    || payloadLength > MAX_PAYLOAD_BYTES + SHA256_BYTES + Integer.BYTES
                    || minimumFileBytes() + (long) payloadLength != bytes.length) {
                throw new IOException("Prepared MagicLib paintjob cache payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Prepared MagicLib paintjob cache ended before its checksum");
            }
            if (!MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Prepared MagicLib paintjob cache checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException(
                    "Prepared MagicLib paintjob cache contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] payload(PreparedMagicPaintjobCache cache) throws IOException {
        byte[] catalog = cache.payload();
        if (catalog.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("MagicLib paintjob catalog payload exceeds its safety limit");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(SHA256_BYTES + Integer.BYTES + catalog.length);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(Hashes.decodeSha256(cache.profileIdentitySha256()));
            output.writeInt(catalog.length);
            output.write(catalog);
        }
        return bytes.toByteArray();
    }

    private static PreparedMagicPaintjobCache decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] profile = input.readNBytes(SHA256_BYTES);
            if (profile.length != SHA256_BYTES) {
                throw new EOFException("Prepared MagicLib paintjob cache ended inside its profile identity");
            }
            int length = input.readInt();
            if (length < 1 || length > MAX_PAYLOAD_BYTES) {
                throw new IOException("MagicLib paintjob catalog payload length is invalid: " + length);
            }
            byte[] catalog = input.readNBytes(length);
            if (catalog.length != length) {
                throw new EOFException("Prepared MagicLib paintjob cache ended inside its catalog");
            }
            if (input.read() != -1) {
                throw new IOException("Prepared MagicLib paintjob cache payload has trailing bytes");
            }
            return new PreparedMagicPaintjobCache(
                    java.util.HexFormat.of().formatHex(profile), catalog);
        }
    }

    private static int minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2 + CHECKSUM_BYTES;
    }
}

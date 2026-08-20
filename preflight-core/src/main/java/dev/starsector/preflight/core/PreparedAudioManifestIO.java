package dev.starsector.preflight.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic persistence for prepared-audio profile manifests. */
public final class PreparedAudioManifestIO {
    private static final byte[] MAGIC = {'S', 'P', 'A', 'M'};
    private static final int SHA256_BYTES = 32;
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_STRING_BYTES = 16 * 1024;
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    private static final int PAYLOAD_BASE_BYTES = SHA256_BYTES * 4 + Integer.BYTES;
    private static final int ENTRY_FIXED_BYTES = SHA256_BYTES + Long.BYTES * 2 + Integer.BYTES + 2;
    private static final int PREPARED_METADATA_BYTES = Integer.BYTES * 6 + Long.BYTES * 2 + SHA256_BYTES;

    private PreparedAudioManifestIO() {
    }

    public static void write(Path target, PreparedAudioManifest manifest) throws IOException {
        AtomicBlobs.write(target, toBytes(manifest));
    }

    public static PreparedAudioManifest read(Path source) throws IOException {
        Path absolute = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Prepared audio manifest path is not a regular file: " + absolute);
        }
        long size = Files.size(absolute);
        if (size < minimumFileBytes() || size > MAX_FILE_BYTES) {
            throw new IOException("Prepared audio manifest file size is invalid: " + size);
        }
        return fromBytes(readBounded(absolute));
    }

    public static byte[] toBytes(PreparedAudioManifest manifest) throws IOException {
        byte[] payload = encodePayload(manifest);
        long total = minimumFileBytes() + (long) payload.length;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Prepared audio manifest exceeds the file safety limit");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.toIntExact(total));
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(PreparedAudioManifest.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(Hashes.sha256Bytes(payload));
        }
        return bytes.toByteArray();
    }

    public static PreparedAudioManifest fromBytes(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < minimumFileBytes()) {
            throw new IOException("Prepared audio manifest is too small");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Prepared audio manifest exceeds the file safety limit");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) {
                throw new IOException("Prepared audio manifest magic header is invalid");
            }
            int version = input.readInt();
            if (version != PreparedAudioManifest.FORMAT_VERSION) {
                throw new IOException("Unsupported prepared audio manifest version: " + version);
            }
            int payloadLength = input.readInt();
            if (payloadLength < PAYLOAD_BASE_BYTES
                    || minimumFileBytes() + (long) payloadLength != bytes.length) {
                throw new IOException("Prepared audio manifest payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Prepared audio manifest ended before its checksum");
            }
            if (!MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Prepared audio manifest checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Prepared audio manifest contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] encodePayload(PreparedAudioManifest manifest) throws IOException {
        int estimated = estimatedPayloadBytes(manifest);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(estimated);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(Hashes.decodeSha256(manifest.profileFingerprintSha256()));
            output.write(Hashes.decodeSha256(manifest.starsectorBuildSha256()));
            output.write(Hashes.decodeSha256(manifest.decoderPolicyIdentitySha256()));
            output.writeInt(manifest.entryCount());
            for (PreparedAudioManifest.Entry entry : manifest.entries().values()) {
                writeString(output, entry.logicalPath());
                output.write(Hashes.decodeSha256(entry.sourceSha256()));
                output.writeLong(entry.sourceBytes());
                output.writeLong(entry.sourceModifiedMillis());
                output.writeInt(entry.policy().id());
                output.writeBoolean(!entry.cacheKeySha256().isEmpty());
                if (!entry.cacheKeySha256().isEmpty()) {
                    output.write(Hashes.decodeSha256(entry.cacheKeySha256()));
                }
                PreparedAudioManifest.Metadata metadata = entry.metadata();
                output.writeBoolean(metadata != null);
                if (metadata != null) {
                    output.writeInt(metadata.sampleRateHz());
                    output.writeInt(metadata.channels());
                    output.writeLong(metadata.frameCount());
                    output.writeLong(metadata.sampleCount());
                    output.writeInt(metadata.encoding().id());
                    output.writeInt(metadata.bitsPerSample());
                    output.writeInt(metadata.byteOrder().id());
                    output.writeInt(metadata.pcmBytes());
                    output.write(Hashes.decodeSha256(metadata.pcmSha256()));
                }
            }
            output.write(Hashes.decodeSha256(manifest.manifestSha256()));
        }
        if (bytes.size() != estimated) {
            throw new IOException("Prepared audio manifest size estimate differs from encoded payload");
        }
        return bytes.toByteArray();
    }

    private static int estimatedPayloadBytes(PreparedAudioManifest manifest) throws IOException {
        long bytes = PAYLOAD_BASE_BYTES;
        long maximum = MAX_FILE_BYTES - (long) minimumFileBytes();
        for (PreparedAudioManifest.Entry entry : manifest.entries().values()) {
            int pathBytes = encodeString(entry.logicalPath()).length;
            bytes = Math.addExact(bytes, Integer.BYTES + (long) pathBytes + ENTRY_FIXED_BYTES);
            if (!entry.cacheKeySha256().isEmpty()) {
                bytes = Math.addExact(bytes, SHA256_BYTES);
            }
            if (entry.metadata() != null) {
                bytes = Math.addExact(bytes, PREPARED_METADATA_BYTES);
            }
            if (bytes > maximum) {
                throw new IOException("Prepared audio manifest payload exceeds its safety limit");
            }
        }
        return Math.toIntExact(bytes);
    }

    private static PreparedAudioManifest decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String profile = readHash(input, "profile fingerprint");
            String build = readHash(input, "Starsector build identity");
            String decoder = readHash(input, "decoder-policy identity");
            int count = input.readInt();
            if (count < 0 || count > PreparedAudioManifest.MAX_ENTRIES) {
                throw new IOException("Prepared audio manifest entry count is invalid: " + count);
            }
            Map<String, PreparedAudioManifest.Entry> entries = new LinkedHashMap<>();
            String previous = null;
            for (int i = 0; i < count; i++) {
                String persistedPath = readString(input);
                String logicalPath = ResourceIndex.normalizeLogicalPath(persistedPath);
                if (!persistedPath.equals(logicalPath)) {
                    throw new IOException("Prepared audio manifest path is not canonical: " + persistedPath);
                }
                if (previous != null && previous.compareTo(logicalPath) >= 0) {
                    throw new IOException("Prepared audio manifest entries are not in strict order");
                }
                previous = logicalPath;
                String source = readHash(input, "source hash");
                long sourceBytes = input.readLong();
                long sourceModifiedMillis = input.readLong();
                PreparedAudio.Policy policy = PreparedAudio.Policy.fromId(input.readInt());
                String cacheKey = input.readBoolean() ? readHash(input, "cache key") : "";
                PreparedAudioManifest.Metadata metadata = null;
                if (input.readBoolean()) {
                    metadata = new PreparedAudioManifest.Metadata(
                            input.readInt(),
                            input.readInt(),
                            input.readLong(),
                            input.readLong(),
                            PreparedAudio.PcmEncoding.fromId(input.readInt()),
                            input.readInt(),
                            PreparedAudio.ByteOrder.fromId(input.readInt()),
                            input.readInt(),
                            readHash(input, "PCM hash"));
                }
                PreparedAudioManifest.Entry entry = new PreparedAudioManifest.Entry(
                        logicalPath,
                        source,
                        sourceBytes,
                        sourceModifiedMillis,
                        policy,
                        cacheKey,
                        metadata);
                if (entries.put(logicalPath, entry) != null) {
                    throw new IOException("Prepared audio manifest contains a duplicate logical path");
                }
            }
            String expectedManifestHash = readHash(input, "manifest identity");
            if (input.available() != 0) {
                throw new IOException("Prepared audio manifest payload contains trailing data");
            }
            PreparedAudioManifest manifest = new PreparedAudioManifest(profile, build, decoder, entries);
            if (!manifest.manifestSha256().equals(expectedManifestHash)) {
                throw new IOException("Prepared audio manifest identity mismatch");
            }
            return manifest;
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = encodeString(value);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static byte[] encodeString(String value) throws IOException {
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IOException("Prepared audio manifest string cannot be encoded as UTF-8");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > MAX_STRING_BYTES) {
            throw new IOException("Prepared audio manifest string length is invalid");
        }
        return bytes;
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_STRING_BYTES) {
            throw new IOException("Prepared audio manifest string length is invalid: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Prepared audio manifest ended inside a string");
        }
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("Prepared audio manifest string is invalid UTF-8");
        }
        return value;
    }

    private static String readHash(DataInputStream input, String name) throws IOException {
        byte[] bytes = input.readNBytes(SHA256_BYTES);
        if (bytes.length != SHA256_BYTES) {
            throw new EOFException("Prepared audio manifest ended inside its " + name);
        }
        return HexFormat.of().formatHex(bytes);
    }

    private static byte[] readBounded(Path source) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IOException("Prepared audio manifest grew beyond its file safety limit");
            }
            return bytes;
        }
    }

    private static int minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2 + CHECKSUM_BYTES;
    }
}

package dev.starsector.preflight.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/** Deterministic checksummed persistence for decoded PCM payloads. */
public final class PreparedAudioIO {
    private static final byte[] MAGIC = {'S', 'P', 'A', 'U'};
    private static final int SHA256_BYTES = 32;
    private static final int CHECKSUM_BYTES = 32;
    private static final int PAYLOAD_FIXED_BYTES = SHA256_BYTES * 3 + Integer.BYTES * 7 + Long.BYTES * 2;
    private static final int MAX_FILE_BYTES = PreparedAudio.MAX_PCM_BYTES + PAYLOAD_FIXED_BYTES + 64;

    private PreparedAudioIO() {
    }

    public static void write(Path target, PreparedAudio audio) throws IOException {
        AtomicBlobs.write(target, toBytes(audio));
    }

    public static PreparedAudio read(Path source) throws IOException {
        Path absolute = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Prepared audio path is not a regular file: " + absolute);
        }
        long size = Files.size(absolute);
        if (size < minimumFileBytes() || size > MAX_FILE_BYTES) {
            throw new IOException("Prepared audio file size is invalid: " + size);
        }
        return fromBytes(readBounded(absolute));
    }

    public static byte[] toBytes(PreparedAudio audio) throws IOException {
        long payloadSize = PAYLOAD_FIXED_BYTES + (long) audio.pcmByteCount();
        long total = minimumFileBytes() + payloadSize;
        if (total > MAX_FILE_BYTES) {
            throw new IOException("Prepared audio exceeds the " + MAX_FILE_BYTES + " byte file limit");
        }
        byte[] payload = encodePayload(audio, Math.toIntExact(payloadSize));
        byte[] checksum = Hashes.sha256Bytes(payload);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.toIntExact(total));
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(PreparedAudio.FORMAT_VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(checksum);
        }
        return bytes.toByteArray();
    }

    public static PreparedAudio fromBytes(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < minimumFileBytes()) {
            throw new IOException("Prepared audio is too small");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Prepared audio exceeds the file safety limit");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) {
                throw new IOException("Prepared audio magic header is invalid");
            }
            int version = input.readInt();
            if (version != PreparedAudio.FORMAT_VERSION) {
                throw new IOException("Unsupported prepared audio version: " + version);
            }
            int payloadLength = input.readInt();
            long expectedLength = minimumFileBytes() + (long) payloadLength;
            if (payloadLength < PAYLOAD_FIXED_BYTES || expectedLength != bytes.length) {
                throw new IOException("Prepared audio payload length is invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (payload.length != payloadLength || checksum.length != CHECKSUM_BYTES) {
                throw new EOFException("Prepared audio ended before its checksum");
            }
            if (!MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
                throw new IOException("Prepared audio checksum mismatch");
            }
            return decodePayload(payload);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Prepared audio contains invalid data: " + error.getMessage(), error);
        }
    }

    private static byte[] encodePayload(PreparedAudio audio, int payloadSize) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(payloadSize);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(Hashes.decodeSha256(audio.sourceSha256()));
            output.write(Hashes.decodeSha256(audio.decoderPolicyIdentitySha256()));
            output.writeInt(audio.policy().id());
            output.writeInt(audio.encoding().id());
            output.writeInt(audio.bitsPerSample());
            output.writeInt(audio.byteOrder().id());
            output.writeInt(audio.sampleRateHz());
            output.writeInt(audio.channels());
            output.writeLong(audio.frameCount());
            output.writeLong(audio.sampleCount());
            output.writeInt(audio.pcmByteCount());
            output.write(Hashes.decodeSha256(audio.pcmSha256()));
            output.write(audio.internalPcmBytes());
        }
        return bytes.toByteArray();
    }

    private static PreparedAudio decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] sourceHash = readHash(input, "source hash");
            byte[] decoderHash = readHash(input, "decoder-policy identity");
            PreparedAudio.Policy policy = PreparedAudio.Policy.fromId(input.readInt());
            PreparedAudio.PcmEncoding encoding = PreparedAudio.PcmEncoding.fromId(input.readInt());
            int bitsPerSample = input.readInt();
            PreparedAudio.ByteOrder byteOrder = PreparedAudio.ByteOrder.fromId(input.readInt());
            int sampleRate = input.readInt();
            int channels = input.readInt();
            long frameCount = input.readLong();
            long sampleCount = input.readLong();
            int pcmLength = input.readInt();
            byte[] expectedPcmHash = readHash(input, "PCM checksum");
            if (pcmLength < 0 || pcmLength > PreparedAudio.MAX_PCM_BYTES) {
                throw new IOException("Prepared audio PCM length is invalid: " + pcmLength);
            }
            byte[] pcm = input.readNBytes(pcmLength);
            if (pcm.length != pcmLength) {
                throw new EOFException("Prepared audio ended inside its PCM payload");
            }
            if (input.available() != 0) {
                throw new IOException("Prepared audio payload contains trailing data");
            }
            if (!MessageDigest.isEqual(expectedPcmHash, Hashes.sha256Bytes(pcm))) {
                throw new IOException("Prepared audio PCM checksum mismatch");
            }
            PreparedAudio audio = new PreparedAudio(
                    HexFormat.of().formatHex(sourceHash),
                    HexFormat.of().formatHex(decoderHash),
                    policy,
                    encoding,
                    bitsPerSample,
                    byteOrder,
                    sampleRate,
                    channels,
                    frameCount,
                    pcm);
            if (audio.sampleCount() != sampleCount) {
                throw new IOException("Prepared audio sample count is invalid: " + sampleCount);
            }
            return audio;
        }
    }

    private static byte[] readHash(DataInputStream input, String name) throws IOException {
        byte[] bytes = input.readNBytes(SHA256_BYTES);
        if (bytes.length != SHA256_BYTES) {
            throw new EOFException("Prepared audio ended inside its " + name);
        }
        return bytes;
    }

    private static byte[] readBounded(Path source) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IOException("Prepared audio grew beyond its file safety limit");
            }
            return bytes;
        }
    }

    private static int minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2 + CHECKSUM_BYTES;
    }
}

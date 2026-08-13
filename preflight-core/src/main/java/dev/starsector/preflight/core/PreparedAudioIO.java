package dev.starsector.preflight.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
        return read(source, true);
    }

    /**
     * Reads an atomically-written local cache blob without recomputing its payload checksum.
     *
     * <p>Magic, version, file and payload lengths, enum values, audio dimensions, PCM length,
     * sample count, EOF, and trailing-data checks still run. Builders and cache inspection tools
     * must use {@link #read(Path)}; this entrypoint is for the latency-sensitive game runtime after
     * it has selected the blob by the exact source and decoder identities encoded in its path.
     */
    public static PreparedAudio readTrusted(Path source) throws IOException {
        Path absolute = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)
                || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Prepared audio path is not a regular file: " + absolute);
        }
        try (FileChannel channel = FileChannel.open(absolute, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < minimumFileBytes() || size > MAX_FILE_BYTES) {
                throw new IOException("Prepared audio file size is invalid: " + size);
            }

            // Read fixed metadata and then PCM straight into the array the model adopts. The old
            // trusted path materialized the whole file, copied its payload, copied PCM out of that,
            // and cloned PCM into the model before the runtime cloned it once more for OpenAL.
            ByteBuffer metadata = ByteBuffer.allocate(
                    MAGIC.length + Integer.BYTES * 2 + PAYLOAD_FIXED_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
            readFully(channel, metadata, "Prepared audio ended inside its metadata");
            metadata.flip();

            byte[] magic = new byte[MAGIC.length];
            metadata.get(magic);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("Prepared audio magic header is invalid");
            }
            int version = metadata.getInt();
            if (version != PreparedAudio.FORMAT_VERSION) {
                throw new IOException("Unsupported prepared audio version: " + version);
            }
            int payloadLength = metadata.getInt();
            long expectedLength = minimumFileBytes() + (long) payloadLength;
            if (payloadLength < PAYLOAD_FIXED_BYTES || expectedLength != size) {
                throw new IOException("Prepared audio payload length is invalid");
            }

            byte[] sourceHash = new byte[SHA256_BYTES];
            metadata.get(sourceHash);
            byte[] decoderHash = new byte[SHA256_BYTES];
            metadata.get(decoderHash);
            PreparedAudio.Policy policy = PreparedAudio.Policy.fromId(metadata.getInt());
            PreparedAudio.PcmEncoding encoding = PreparedAudio.PcmEncoding.fromId(metadata.getInt());
            int bitsPerSample = metadata.getInt();
            PreparedAudio.ByteOrder byteOrder = PreparedAudio.ByteOrder.fromId(metadata.getInt());
            int sampleRate = metadata.getInt();
            int channels = metadata.getInt();
            long frameCount = metadata.getLong();
            long sampleCount = metadata.getLong();
            int pcmLength = metadata.getInt();
            // Trusted mode deliberately skips both stored checksums. Advance over the inner PCM
            // hash without allocating or hashing; the content-addressed lookup and embedded source
            // and decoder identities are checked by the runtime after this structural read.
            metadata.position(metadata.position() + SHA256_BYTES);
            if (pcmLength < 0 || pcmLength > PreparedAudio.MAX_PCM_BYTES
                    || payloadLength - PAYLOAD_FIXED_BYTES != pcmLength) {
                throw new IOException("Prepared audio PCM length is invalid: " + pcmLength);
            }

            byte[] pcm = new byte[pcmLength];
            readFully(channel, ByteBuffer.wrap(pcm), "Prepared audio ended inside its PCM payload");
            if (channel.position() != size - CHECKSUM_BYTES) {
                throw new IOException("Prepared audio payload contains trailing data");
            }
            readFully(channel, ByteBuffer.allocate(CHECKSUM_BYTES),
                    "Prepared audio ended inside its checksum");
            if (channel.position() != size || channel.size() != size) {
                throw new IOException("Prepared audio file changed while it was read");
            }
            PreparedAudio audio = PreparedAudio.adopting(
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
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Prepared audio contains invalid data: " + error.getMessage(), error);
        }
    }

    private static PreparedAudio read(Path source, boolean verifyChecksum) throws IOException {
        Path absolute = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Prepared audio path is not a regular file: " + absolute);
        }
        long size = Files.size(absolute);
        if (size < minimumFileBytes() || size > MAX_FILE_BYTES) {
            throw new IOException("Prepared audio file size is invalid: " + size);
        }
        return fromBytes(readBounded(absolute), verifyChecksum);
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
        return fromBytes(bytes, true);
    }

    private static PreparedAudio fromBytes(byte[] bytes, boolean verifyChecksum) throws IOException {
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
            if (verifyChecksum && !MessageDigest.isEqual(checksum, Hashes.sha256Bytes(payload))) {
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
            // The PCM hash is not verified here, and deliberately. It is recorded so a blob can say
            // what it holds, but every byte it covers is already inside the payload this method only
            // reached by verifying. Checking it again is hashing the same bytes a second time -- on
            // a launch that serves 1.23 GB of PCM that second pass measured 4.4 s, which is most of
            // what preparing the audio was supposed to save. Corruption anywhere in the PCM still
            // fails, on the payload checksum, before this point.
            if (expectedPcmHash.length != SHA256_BYTES) {
                throw new IOException("Prepared audio PCM checksum is malformed");
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

    private static void readFully(FileChannel channel, ByteBuffer destination, String message)
            throws IOException {
        while (destination.hasRemaining()) {
            if (channel.read(destination) < 0) {
                throw new EOFException(message);
            }
        }
    }

    private static int minimumFileBytes() {
        return MAGIC.length + Integer.BYTES * 2 + CHECKSUM_BYTES;
    }
}

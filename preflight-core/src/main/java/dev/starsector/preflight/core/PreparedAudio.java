package dev.starsector.preflight.core;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Immutable decoded PCM payload for one exact eligible audio source and decoder policy. */
public final class PreparedAudio {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_CHANNELS = 8;
    public static final int MAX_SAMPLE_RATE_HZ = 384_000;
    public static final int MAX_PCM_BYTES = 64 * 1024 * 1024;

    private final String sourceSha256;
    private final String decoderPolicyIdentitySha256;
    private final Policy policy;
    private final PcmEncoding encoding;
    private final int bitsPerSample;
    private final ByteOrder byteOrder;
    private final int sampleRateHz;
    private final int channels;
    private final long frameCount;
    private final long sampleCount;
    private final byte[] pcmBytes;

    private PreparedAudio(
            String sourceSha256,
            String decoderPolicyIdentitySha256,
            Policy policy,
            PcmEncoding encoding,
            int bitsPerSample,
            ByteOrder byteOrder,
            int sampleRateHz,
            int channels,
            long frameCount,
            byte[] pcmBytes,
            boolean adopt) {
        Hashes.decodeSha256(sourceSha256);
        Hashes.decodeSha256(decoderPolicyIdentitySha256);
        this.sourceSha256 = sourceSha256.toLowerCase(Locale.ROOT);
        this.decoderPolicyIdentitySha256 = decoderPolicyIdentitySha256.toLowerCase(Locale.ROOT);
        this.policy = Objects.requireNonNull(policy, "policy");
        if (policy != Policy.FULLY_DECODED_EFFECT) {
            throw new IllegalArgumentException("Only fully decoded effects may contain a prepared PCM payload");
        }
        this.encoding = Objects.requireNonNull(encoding, "encoding");
        this.bitsPerSample = validateBits(encoding, bitsPerSample);
        this.byteOrder = Objects.requireNonNull(byteOrder, "byteOrder");
        if (sampleRateHz < 1 || sampleRateHz > MAX_SAMPLE_RATE_HZ) {
            throw new IllegalArgumentException("sampleRateHz is outside 1.." + MAX_SAMPLE_RATE_HZ);
        }
        this.sampleRateHz = sampleRateHz;
        if (channels < 1 || channels > MAX_CHANNELS) {
            throw new IllegalArgumentException("channels is outside 1.." + MAX_CHANNELS);
        }
        this.channels = channels;
        if (frameCount < 0) {
            throw new IllegalArgumentException("frameCount must be nonnegative");
        }
        this.frameCount = frameCount;
        this.sampleCount = Math.multiplyExact(frameCount, channels);
        Objects.requireNonNull(pcmBytes, "pcmBytes");
        long expected = Math.multiplyExact(frameCount, frameSizeBytes());
        if (expected > MAX_PCM_BYTES) {
            throw new IllegalArgumentException("Prepared audio exceeds the " + MAX_PCM_BYTES + " byte PCM limit");
        }
        if (pcmBytes.length != expected) {
            throw new IllegalArgumentException(
                    "PCM payload length is " + pcmBytes.length + "; expected " + expected);
        }
        this.pcmBytes = adopt ? pcmBytes : pcmBytes.clone();
    }

    public PreparedAudio(
            String sourceSha256,
            String decoderPolicyIdentitySha256,
            Policy policy,
            PcmEncoding encoding,
            int bitsPerSample,
            ByteOrder byteOrder,
            int sampleRateHz,
            int channels,
            long frameCount,
            byte[] pcmBytes) {
        this(sourceSha256, decoderPolicyIdentitySha256, policy, encoding, bitsPerSample, byteOrder,
                sampleRateHz, channels, frameCount, pcmBytes, false);
    }

    /** Takes ownership of a freshly read PCM array that no other caller can reach. */
    static PreparedAudio adopting(
            String sourceSha256,
            String decoderPolicyIdentitySha256,
            Policy policy,
            PcmEncoding encoding,
            int bitsPerSample,
            ByteOrder byteOrder,
            int sampleRateHz,
            int channels,
            long frameCount,
            byte[] pcmBytes) {
        return new PreparedAudio(sourceSha256, decoderPolicyIdentitySha256, policy, encoding,
                bitsPerSample, byteOrder, sampleRateHz, channels, frameCount, pcmBytes, true);
    }

    public String sourceSha256() {
        return sourceSha256;
    }

    public String decoderPolicyIdentitySha256() {
        return decoderPolicyIdentitySha256;
    }

    public Policy policy() {
        return policy;
    }

    public PcmEncoding encoding() {
        return encoding;
    }

    public int bitsPerSample() {
        return bitsPerSample;
    }

    public ByteOrder byteOrder() {
        return byteOrder;
    }

    public int sampleRateHz() {
        return sampleRateHz;
    }

    public int channels() {
        return channels;
    }

    public long frameCount() {
        return frameCount;
    }

    public long sampleCount() {
        return sampleCount;
    }

    public int bytesPerSample() {
        return bitsPerSample / Byte.SIZE;
    }

    public int frameSizeBytes() {
        return Math.multiplyExact(channels, bytesPerSample());
    }

    public int pcmByteCount() {
        return pcmBytes.length;
    }

    public String pcmSha256() {
        return Hashes.sha256(pcmBytes);
    }

    public byte[] pcmBytes() {
        return pcmBytes.clone();
    }

    public void copyPcmTo(byte[] destination, int offset) {
        Objects.requireNonNull(destination, "destination");
        if (offset < 0 || offset > destination.length - pcmBytes.length) {
            throw new IndexOutOfBoundsException("PCM destination is too small");
        }
        System.arraycopy(pcmBytes, 0, destination, offset, pcmBytes.length);
    }

    /** Copies the immutable PCM payload directly into its final native-facing buffer. */
    public void copyPcmTo(ByteBuffer destination) {
        Objects.requireNonNull(destination, "destination");
        if (destination.remaining() < pcmBytes.length) {
            throw new java.nio.BufferOverflowException();
        }
        destination.put(pcmBytes);
    }

    byte[] internalPcmBytes() {
        return pcmBytes;
    }

    public static boolean supportsFormat(PcmEncoding encoding, int bits) {
        Objects.requireNonNull(encoding, "encoding");
        return switch (encoding) {
            case PCM_SIGNED -> bits == 8 || bits == 16 || bits == 24 || bits == 32;
            case PCM_UNSIGNED -> bits == 8;
            case PCM_FLOAT -> bits == 32 || bits == 64;
        };
    }

    private static int validateBits(PcmEncoding encoding, int bits) {
        if (!supportsFormat(encoding, bits)) {
            throw new IllegalArgumentException("Unsupported " + encoding + " bit depth: " + bits);
        }
        return bits;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof PreparedAudio other)) return false;
        return bitsPerSample == other.bitsPerSample
                && sampleRateHz == other.sampleRateHz
                && channels == other.channels
                && frameCount == other.frameCount
                && sampleCount == other.sampleCount
                && sourceSha256.equals(other.sourceSha256)
                && decoderPolicyIdentitySha256.equals(other.decoderPolicyIdentitySha256)
                && policy == other.policy
                && encoding == other.encoding
                && byteOrder == other.byteOrder
                && Arrays.equals(pcmBytes, other.pcmBytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                sourceSha256,
                decoderPolicyIdentitySha256,
                policy,
                encoding,
                bitsPerSample,
                byteOrder,
                sampleRateHz,
                channels,
                frameCount,
                sampleCount);
        return 31 * result + Arrays.hashCode(pcmBytes);
    }

    public enum Policy {
        FULLY_DECODED_EFFECT(0, true),
        STREAMED(1, false),
        UNSUPPORTED(2, false);

        private final int id;
        private final boolean cacheEligible;

        Policy(int id, boolean cacheEligible) {
            this.id = id;
            this.cacheEligible = cacheEligible;
        }

        public int id() {
            return id;
        }

        public boolean cacheEligible() {
            return cacheEligible;
        }

        public static Policy fromId(int id) {
            for (Policy value : values()) {
                if (value.id == id) return value;
            }
            throw new IllegalArgumentException("Unknown prepared-audio policy id: " + id);
        }
    }

    public enum PcmEncoding {
        PCM_SIGNED(0),
        PCM_UNSIGNED(1),
        PCM_FLOAT(2);

        private final int id;

        PcmEncoding(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static PcmEncoding fromId(int id) {
            for (PcmEncoding value : values()) {
                if (value.id == id) return value;
            }
            throw new IllegalArgumentException("Unknown PCM encoding id: " + id);
        }
    }

    public enum ByteOrder {
        LITTLE_ENDIAN(0),
        BIG_ENDIAN(1);

        private final int id;

        ByteOrder(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static ByteOrder fromId(int id) {
            for (ByteOrder value : values()) {
                if (value.id == id) return value;
            }
            throw new IllegalArgumentException("Unknown PCM byte-order id: " + id);
        }
    }
}

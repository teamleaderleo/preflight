package dev.starsector.preflight.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Bounded parser for the first Ogg page and Vorbis identification packet.
 * It identifies format metadata only; it does not decode audio samples.
 */
public final class OggVorbisIdentification {
    private static final byte[] OGG_CAPTURE = {'O', 'g', 'g', 'S'};
    private static final byte[] VORBIS_SIGNATURE = {'v', 'o', 'r', 'b', 'i', 's'};
    private static final byte[] OPUS_SIGNATURE = {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'};
    private static final int FIXED_PAGE_HEADER_BYTES = 27;
    private static final int MAX_PAGE_SEGMENTS = 255;
    private static final int MAX_PAGE_BODY_BYTES = 255 * 255;
    private static final int VORBIS_IDENTIFICATION_BYTES = 30;
    private static final int MAX_DETAIL_CHARS = 1024;
    private static final int OGG_CRC_POLYNOMIAL = 0x04c11db7;

    private OggVorbisIdentification() {
    }

    public enum Status {
        SUPPORTED,
        UNSUPPORTED,
        MALFORMED,
        ERROR
    }

    public record Result(
            Status status,
            String codec,
            int channels,
            int sampleRate,
            int smallBlockSize,
            int largeBlockSize,
            String detail) {
        public Result {
            Objects.requireNonNull(status, "status");
            codec = codec == null ? "unknown" : codec;
            detail = boundedDetail(detail == null ? "" : detail);
            if (status == Status.SUPPORTED) {
                if (!"vorbis".equals(codec)) throw new IllegalArgumentException("Supported codec must be vorbis");
                if (channels <= 0 || sampleRate <= 0 || smallBlockSize <= 0 || largeBlockSize <= 0) {
                    throw new IllegalArgumentException("Supported Vorbis metadata must be positive");
                }
            } else if (channels != 0 || sampleRate != 0 || smallBlockSize != 0 || largeBlockSize != 0) {
                throw new IllegalArgumentException("Non-supported results must not expose audio metadata");
            }
        }

        public boolean supported() {
            return status == Status.SUPPORTED;
        }
    }

    public static Result inspect(Path source) {
        if (source == null) return error("Source path is null");
        try {
            Path absolute = source.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(absolute)
                    || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
                return error("Source is not a non-symlink regular file: " + absolute);
            }
            try (InputStream input = Files.newInputStream(absolute)) {
                return inspect(input);
            }
        } catch (IOException | RuntimeException failure) {
            return error(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    public static Result inspect(byte[] source) {
        if (source == null) return error("Source bytes are null");
        try (InputStream input = new ByteArrayInputStream(source)) {
            return inspect(input);
        } catch (IOException impossible) {
            return error(impossible.getMessage());
        }
    }

    private static Result inspect(InputStream input) {
        try {
            byte[] header = input.readNBytes(FIXED_PAGE_HEADER_BYTES);
            if (header.length < OGG_CAPTURE.length) {
                return startsWith(header, OGG_CAPTURE)
                        ? malformed("Truncated Ogg capture pattern")
                        : unsupported("unknown", "Input is not an Ogg stream");
            }
            if (!startsWith(header, OGG_CAPTURE)) {
                return unsupported("unknown", "Input is not an Ogg stream");
            }
            if (header.length < FIXED_PAGE_HEADER_BYTES) {
                return malformed("Truncated Ogg page header");
            }
            if (Byte.toUnsignedInt(header[4]) != 0) {
                return unsupported("ogg", "Unsupported Ogg bitstream version " + Byte.toUnsignedInt(header[4]));
            }
            int headerType = Byte.toUnsignedInt(header[5]);
            if ((headerType & 0x01) != 0) {
                return malformed("First Ogg page starts with a continued packet");
            }
            if ((headerType & 0x02) == 0) {
                return malformed("First Ogg page is missing the beginning-of-stream flag");
            }

            int segmentCount = Byte.toUnsignedInt(header[26]);
            if (segmentCount <= 0 || segmentCount > MAX_PAGE_SEGMENTS) {
                return malformed("First Ogg page has no packet segments");
            }
            byte[] lacing = input.readNBytes(segmentCount);
            if (lacing.length != segmentCount) {
                return malformed("Truncated Ogg segment table");
            }

            int bodyLength = 0;
            int firstPacketLength = 0;
            boolean firstPacketComplete = false;
            for (byte value : lacing) {
                int length = Byte.toUnsignedInt(value);
                bodyLength = Math.addExact(bodyLength, length);
                if (!firstPacketComplete) {
                    firstPacketLength = Math.addExact(firstPacketLength, length);
                    if (length < 255) firstPacketComplete = true;
                }
            }
            if (bodyLength < 0 || bodyLength > MAX_PAGE_BODY_BYTES) {
                return malformed("Ogg page body exceeds its byte limit");
            }
            if (!firstPacketComplete) {
                return malformed("Identification packet continues beyond the bounded first page");
            }

            byte[] body = input.readNBytes(bodyLength);
            if (body.length != bodyLength) {
                return malformed("Truncated Ogg page body");
            }
            if (!checksumMatches(header, lacing, body)) {
                return malformed("Ogg page checksum mismatch");
            }
            if (firstPacketLength <= 0 || firstPacketLength > body.length) {
                return malformed("Invalid first Ogg packet length");
            }
            byte[] packet = Arrays.copyOf(body, firstPacketLength);
            return inspectIdentificationPacket(packet);
        } catch (ArithmeticException failure) {
            return malformed("Ogg page length overflow");
        } catch (IOException failure) {
            return error(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } catch (RuntimeException failure) {
            return error(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private static Result inspectIdentificationPacket(byte[] packet) {
        if (startsWith(packet, OPUS_SIGNATURE)) {
            return unsupported("opus", "Ogg Opus is not eligible for the Vorbis prepared-audio path");
        }
        if (packet.length < 7 || Byte.toUnsignedInt(packet[0]) != 1
                || !matches(packet, 1, VORBIS_SIGNATURE)) {
            return unsupported("ogg", "First Ogg packet is not a Vorbis identification header");
        }
        if (packet.length != VORBIS_IDENTIFICATION_BYTES) {
            return malformed("Vorbis identification header must contain exactly 30 bytes");
        }
        long version = unsignedIntLittleEndian(packet, 7);
        if (version != 0) {
            return unsupported("vorbis", "Unsupported Vorbis version " + version);
        }
        int channels = Byte.toUnsignedInt(packet[11]);
        long sampleRateLong = unsignedIntLittleEndian(packet, 12);
        if (channels <= 0) return malformed("Vorbis channel count is zero");
        if (sampleRateLong <= 0 || sampleRateLong > Integer.MAX_VALUE) {
            return malformed("Vorbis sample rate is outside the supported integer range");
        }

        int blockSizeByte = Byte.toUnsignedInt(packet[28]);
        int smallExponent = blockSizeByte & 0x0f;
        int largeExponent = (blockSizeByte >>> 4) & 0x0f;
        if (smallExponent < 6 || smallExponent > 13
                || largeExponent < 6 || largeExponent > 13
                || smallExponent > largeExponent) {
            return malformed("Vorbis block-size exponents are invalid");
        }
        if (Byte.toUnsignedInt(packet[29]) != 1) {
            return malformed("Vorbis identification framing bit is invalid");
        }

        return new Result(
                Status.SUPPORTED,
                "vorbis",
                channels,
                (int) sampleRateLong,
                1 << smallExponent,
                1 << largeExponent,
                "Validated the first Ogg page and Vorbis identification header");
    }

    private static boolean checksumMatches(byte[] header, byte[] lacing, byte[] body) {
        int stored = littleEndianInt(header, 22);
        byte[] page = new byte[header.length + lacing.length + body.length];
        System.arraycopy(header, 0, page, 0, header.length);
        System.arraycopy(lacing, 0, page, header.length, lacing.length);
        System.arraycopy(body, 0, page, header.length + lacing.length, body.length);
        page[22] = 0;
        page[23] = 0;
        page[24] = 0;
        page[25] = 0;
        return stored == oggChecksum(page);
    }

    /** Package-private so {@link OggVorbisStreamLength} can validate a page it found by scanning. */
    static int oggChecksum(byte[] page) {
        int checksum = 0;
        for (byte value : page) {
            checksum ^= Byte.toUnsignedInt(value) << 24;
            for (int bit = 0; bit < 8; bit++) {
                checksum = (checksum & 0x80000000) != 0
                        ? (checksum << 1) ^ OGG_CRC_POLYNOMIAL
                        : checksum << 1;
            }
        }
        return checksum;
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | (Byte.toUnsignedInt(bytes[offset + 1]) << 8)
                | (Byte.toUnsignedInt(bytes[offset + 2]) << 16)
                | (Byte.toUnsignedInt(bytes[offset + 3]) << 24);
    }

    private static long unsignedIntLittleEndian(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(littleEndianInt(bytes, offset));
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length <= prefix.length && matches(prefix, 0, bytes)
                || bytes.length >= prefix.length && matches(bytes, 0, prefix);
    }

    private static boolean matches(byte[] bytes, int offset, byte[] expected) {
        if (offset < 0 || bytes.length - offset < expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (bytes[offset + i] != expected[i]) return false;
        }
        return true;
    }

    private static Result unsupported(String codec, String detail) {
        return new Result(Status.UNSUPPORTED, codec, 0, 0, 0, 0, detail);
    }

    private static Result malformed(String detail) {
        return new Result(Status.MALFORMED, "ogg", 0, 0, 0, 0, detail);
    }

    private static Result error(String detail) {
        return new Result(Status.ERROR, "unknown", 0, 0, 0, 0, detail);
    }

    private static String boundedDetail(String value) {
        String normalized = value.replace('\u0000', '?').strip();
        if (normalized.length() <= MAX_DETAIL_CHARS) return normalized;
        return normalized.substring(0, MAX_DETAIL_CHARS) + "...";
    }
}

package dev.starsector.preflight.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Reads a Vorbis stream's total frame count from its final Ogg page, without decoding.
 *
 * <p>The last page of a stream carries the granule position of the last sample it completes, which
 * for Vorbis is the running frame count. Reading it costs two seeks, so the decoded size of a whole
 * profile can be measured in the time it takes to decode one file.</p>
 *
 * <p>This is the frame count <em>libvorbis</em> would emit. JOrbis does not trim its final block
 * against the granule position, so the installed decoder produces up to one extra block — at most
 * 8,192 frames, measured at 256 mono / 128 stereo. Treat the result as a tight floor on decoded
 * size, not an exact prediction of what {@code sound/void} returns.</p>
 *
 * @see OggVorbisIdentification for channel count, sample rate, and codec validation
 */
public final class OggVorbisStreamLength {
    /**
     * A maximal Ogg page is 27 header bytes, a 255-entry segment table, and 255*255 body bytes.
     * Reading at least this much guarantees the window contains the whole final page.
     */
    private static final int MAX_PAGE_BYTES = 27 + 255 + 255 * 255;
    private static final int TAIL_WINDOW_BYTES = 2 * MAX_PAGE_BYTES;
    private static final int FIXED_PAGE_HEADER_BYTES = 27;
    private static final byte[] OGG_CAPTURE = {'O', 'g', 'g', 'S'};
    private static final int END_OF_STREAM_FLAG = 0x04;

    private OggVorbisStreamLength() {
    }

    public enum Status {
        MEASURED,
        /** The file is well-formed Ogg but carries no usable final granule position. */
        UNMEASURED,
        MALFORMED,
        ERROR
    }

    /**
     * @param totalFrames per-channel sample frames, or 0 unless {@code status} is {@code MEASURED}
     * @param serialNumber the bitstream serial the first and last page agreed on
     */
    public record Measurement(Status status, long totalFrames, int serialNumber, String detail) {
        public Measurement {
            Objects.requireNonNull(status, "status");
            detail = detail == null ? "" : detail;
            if (status == Status.MEASURED) {
                if (totalFrames <= 0) {
                    throw new IllegalArgumentException("A measured stream must have a positive frame count");
                }
            } else if (totalFrames != 0) {
                throw new IllegalArgumentException("Only a measured stream may report a frame count");
            }
        }

        public boolean measured() {
            return status == Status.MEASURED;
        }

        /** Decoded size of this stream as signed 16-bit little-endian PCM. */
        public long decodedBytes(int channels) {
            if (channels <= 0) {
                throw new IllegalArgumentException("channels must be positive: " + channels);
            }
            return Math.multiplyExact(Math.multiplyExact(totalFrames, channels), 2L);
        }
    }

    public static Measurement measure(Path source) {
        if (source == null) {
            return new Measurement(Status.ERROR, 0, 0, "Source path is null");
        }
        try {
            Path absolute = source.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(absolute) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
                return new Measurement(Status.ERROR, 0, 0, "Source is not a non-symlink regular file: " + absolute);
            }
            try (SeekableByteChannel channel = Files.newByteChannel(absolute, StandardOpenOption.READ)) {
                long size = channel.size();
                if (size < FIXED_PAGE_HEADER_BYTES) {
                    return new Measurement(Status.MALFORMED, 0, 0, "File is shorter than one Ogg page header");
                }
                byte[] head = read(channel, 0, FIXED_PAGE_HEADER_BYTES);
                if (!startsWith(head, OGG_CAPTURE)) {
                    return new Measurement(Status.MALFORMED, 0, 0, "Input is not an Ogg stream");
                }
                int serial = littleEndianInt(head, 14);

                int window = (int) Math.min(size, TAIL_WINDOW_BYTES);
                byte[] tail = read(channel, size - window, window);
                return scanBackwards(tail, serial);
            }
        } catch (IOException | RuntimeException failure) {
            return new Measurement(
                    Status.ERROR, 0, 0, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    /**
     * Finds the last page belonging to {@code serial} and takes its granule position.
     *
     * <p>A four-byte capture pattern occurs inside page bodies by chance, so a candidate only counts
     * once its version, serial, end-of-stream flag, and CRC all agree. The CRC is what makes this
     * safe rather than merely likely.
     */
    private static Measurement scanBackwards(byte[] tail, int serial) {
        for (int at = tail.length - FIXED_PAGE_HEADER_BYTES; at >= 0; at--) {
            if (!matches(tail, at, OGG_CAPTURE)) {
                continue;
            }
            if (Byte.toUnsignedInt(tail[at + 4]) != 0 || littleEndianInt(tail, at + 14) != serial) {
                continue;
            }
            if ((Byte.toUnsignedInt(tail[at + 5]) & END_OF_STREAM_FLAG) == 0) {
                continue;
            }
            int segments = Byte.toUnsignedInt(tail[at + 26]);
            int lacingEnd = at + FIXED_PAGE_HEADER_BYTES + segments;
            if (lacingEnd > tail.length) {
                continue;
            }
            int body = 0;
            for (int i = at + FIXED_PAGE_HEADER_BYTES; i < lacingEnd; i++) {
                body += Byte.toUnsignedInt(tail[i]);
            }
            int pageEnd = lacingEnd + body;
            if (pageEnd > tail.length || !checksumMatches(tail, at, pageEnd)) {
                continue;
            }
            long granule = littleEndianLong(tail, at + 6);
            if (granule < 0) {
                return new Measurement(
                        Status.UNMEASURED, 0, serial, "Final Ogg page completes no packet");
            }
            if (granule == 0) {
                return new Measurement(
                        Status.UNMEASURED, 0, serial, "Final Ogg page reports a zero granule position");
            }
            return new Measurement(Status.MEASURED, granule, serial, "Read the final Ogg page granule position");
        }
        return new Measurement(
                Status.MALFORMED, 0, serial, "No end-of-stream Ogg page for serial " + Integer.toUnsignedString(serial));
    }

    private static boolean checksumMatches(byte[] tail, int at, int pageEnd) {
        byte[] page = new byte[pageEnd - at];
        System.arraycopy(tail, at, page, 0, page.length);
        int stored = littleEndianInt(page, 22);
        page[22] = 0;
        page[23] = 0;
        page[24] = 0;
        page[25] = 0;
        return stored == OggVorbisIdentification.oggChecksum(page);
    }

    private static byte[] read(SeekableByteChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(position);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                break;
            }
        }
        byte[] bytes = new byte[buffer.position()];
        buffer.flip();
        buffer.get(bytes);
        return bytes;
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | (Byte.toUnsignedInt(bytes[offset + 1]) << 8)
                | (Byte.toUnsignedInt(bytes[offset + 2]) << 16)
                | (Byte.toUnsignedInt(bytes[offset + 3]) << 24);
    }

    private static long littleEndianLong(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(littleEndianInt(bytes, offset))
                | ((long) littleEndianInt(bytes, offset + 4) << 32);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return matches(bytes, 0, prefix);
    }

    private static boolean matches(byte[] bytes, int offset, byte[] expected) {
        if (offset < 0 || bytes.length - offset < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (bytes[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}

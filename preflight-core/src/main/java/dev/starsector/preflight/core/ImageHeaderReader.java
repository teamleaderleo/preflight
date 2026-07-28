package dev.starsector.preflight.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads image pixel dimensions and channel count from a file's header only &mdash; no full decode.
 *
 * <p>This is the exact-dimension source for decoded-texture (VRAM) working-set estimates: a
 * texture's resident base size is {@code width * height * channels}, and that is derivable from a
 * few header bytes without allocating the decoded image. Supports the two formats that cover
 * essentially all Starsector art (PNG, JPEG); anything else returns {@link Optional#empty()} so
 * callers can count it as unmeasured rather than guess.
 *
 * <p>Channel counts are the natural decoded channels per format: PNG grayscale=1, gray+alpha=2,
 * truecolor=3, truecolor+alpha=4, and indexed/palette is treated as 4 (the conservative RGBA
 * expansion, since a palette may carry transparency); JPEG grayscale=1, YCbCr=3, CMYK=4.
 */
public final class ImageHeaderReader {

    /** Upper bound on bytes read while hunting for a JPEG start-of-frame marker. */
    private static final int MAX_SCAN = 64 * 1024;

    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private ImageHeaderReader() {
    }

    /** Exact resident-size inputs for one image. */
    public record ImageDimensions(int width, int height, int channels) {
        public ImageDimensions {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("image dimensions must be positive");
            }
            if (channels < 1 || channels > 4) {
                throw new IllegalArgumentException("channels must be 1..4");
            }
        }

        /** Exact decoded base-level size in bytes ({@code width * height * channels}). */
        public long decodedBytes() {
            return (long) width * (long) height * (long) channels;
        }
    }

    /** The container an image is actually stored in, as distinct from what its name claims. */
    public enum Format {
        PNG,
        JPEG
    }

    /**
     * Encoding properties that cost decode time without changing the decoded result.
     *
     * @param progressive PNG Adam7 interlacing or JPEG progressive mode. Both store the image as
     *     successive refinement passes so a partial download can be displayed early. A game reads its
     *     art from local disk and shows nothing until the decode finishes, so the passes buy nothing
     *     and the decoder does materially more work than for the same image stored normally.
     */
    public record ImageEncoding(Format format, int bitDepth, boolean progressive) {
        public ImageEncoding {
            if (format == null) {
                throw new IllegalArgumentException("format is required");
            }
            if (bitDepth <= 0) {
                throw new IllegalArgumentException("bitDepth must be positive: " + bitDepth);
            }
        }
    }

    /** Reads the container format and encoding flags, header only. */
    public static Optional<ImageEncoding> encoding(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = readAtMost(in, 33);
            if (isPng(head)) {
                return parsePngEncoding(head);
            }
            if (isJpeg(head)) {
                byte[] rest = readAtMost(in, MAX_SCAN - head.length);
                byte[] buffer = new byte[head.length + rest.length];
                System.arraycopy(head, 0, buffer, 0, head.length);
                System.arraycopy(rest, 0, buffer, head.length, rest.length);
                return parseJpegEncoding(buffer);
            }
            return Optional.empty();
        }
    }

    private static Optional<ImageEncoding> parsePngEncoding(byte[] data) {
        // IHDR: [sig(8)][len(4)]["IHDR"(4)][width(4)][height(4)][bitDepth(1)][colorType(1)]
        // [compression(1)][filter(1)][interlace(1)] — interlace is offset 28, 1 means Adam7.
        if (data.length < 29) {
            return Optional.empty();
        }
        int bitDepth = data[24] & 0xFF;
        if (bitDepth <= 0) {
            return Optional.empty();
        }
        return Optional.of(new ImageEncoding(Format.PNG, bitDepth, (data[28] & 0xFF) != 0));
    }

    private static Optional<ImageEncoding> parseJpegEncoding(byte[] data) {
        int pos = 2;
        while (pos + 1 < data.length) {
            if ((data[pos] & 0xFF) != 0xFF) {
                pos++;
                continue;
            }
            int marker = data[pos + 1] & 0xFF;
            pos += 2;
            if (marker == 0xFF || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD9)) {
                continue;
            }
            if (pos + 1 >= data.length) {
                break;
            }
            int segmentLength = int16BE(data, pos);
            if (segmentLength < 2) {
                break;
            }
            boolean startOfFrame = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (startOfFrame) {
                if (pos + 2 >= data.length) {
                    break;
                }
                int precision = data[pos + 2] & 0xFF;
                // SOF2 is progressive; SOF0/SOF1 and the arithmetic-coded variants are sequential.
                boolean progressive = marker == 0xC2 || marker == 0xC6 || marker == 0xCA || marker == 0xCE;
                return precision <= 0
                        ? Optional.empty()
                        : Optional.of(new ImageEncoding(Format.JPEG, precision, progressive));
            }
            pos += segmentLength;
        }
        return Optional.empty();
    }

    /** Reads dimensions from a file, reading only as many header bytes as the format needs. */
    public static Optional<ImageDimensions> read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = readAtMost(in, 33);
            if (isPng(head)) {
                return parsePng(head);
            }
            if (isJpeg(head)) {
                byte[] rest = readAtMost(in, MAX_SCAN - head.length);
                byte[] buffer = new byte[head.length + rest.length];
                System.arraycopy(head, 0, buffer, 0, head.length);
                System.arraycopy(rest, 0, buffer, head.length, rest.length);
                return parseJpeg(buffer);
            }
            return Optional.empty();
        }
    }

    /** Parses dimensions from an in-memory header buffer (for callers that already hold bytes). */
    public static Optional<ImageDimensions> readBytes(byte[] data) {
        if (data == null) {
            return Optional.empty();
        }
        if (isPng(data)) {
            return parsePng(data);
        }
        if (isJpeg(data)) {
            return parseJpeg(data);
        }
        return Optional.empty();
    }

    private static boolean isPng(byte[] data) {
        if (data.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static Optional<ImageDimensions> parsePng(byte[] data) {
        // IHDR is the first chunk: [8-byte sig][len(4)][ "IHDR"(4) ][ width(4) ][ height(4) ]
        // [bitDepth(1)][colorType(1)]. Width/height start at offset 16, colorType at offset 25.
        if (data.length < 26) {
            return Optional.empty();
        }
        int width = int32BE(data, 16);
        int height = int32BE(data, 20);
        int colorType = data[25] & 0xFF;
        if (width <= 0 || height <= 0) {
            return Optional.empty();
        }
        int channels = switch (colorType) {
            case 0 -> 1; // grayscale
            case 2 -> 3; // truecolor
            case 3 -> 4; // indexed/palette (conservative RGBA expansion)
            case 4 -> 2; // grayscale + alpha
            case 6 -> 4; // truecolor + alpha
            default -> 0;
        };
        return channels == 0 ? Optional.empty() : Optional.of(new ImageDimensions(width, height, channels));
    }

    private static boolean isJpeg(byte[] data) {
        return data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
    }

    private static Optional<ImageDimensions> parseJpeg(byte[] data) {
        int pos = 2; // skip SOI (FF D8)
        while (pos + 1 < data.length) {
            if ((data[pos] & 0xFF) != 0xFF) {
                pos++; // resync to the next marker byte
                continue;
            }
            int marker = data[pos + 1] & 0xFF;
            pos += 2;
            // Standalone markers carry no length: padding fill (FF), TEM (01), RSTn (D0-D7), SOI/EOI.
            if (marker == 0xFF || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD9)) {
                continue;
            }
            if (pos + 1 >= data.length) {
                break;
            }
            int segmentLength = int16BE(data, pos);
            if (segmentLength < 2) {
                break;
            }
            boolean startOfFrame = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (startOfFrame) {
                // SOFn payload: [precision(1)][height(2)][width(2)][components(1)].
                if (pos + 7 >= data.length) {
                    break;
                }
                int height = int16BE(data, pos + 3);
                int width = int16BE(data, pos + 5);
                int components = data[pos + 7] & 0xFF;
                if (width <= 0 || height <= 0 || components < 1 || components > 4) {
                    return Optional.empty();
                }
                return Optional.of(new ImageDimensions(width, height, components));
            }
            pos += segmentLength;
        }
        return Optional.empty();
    }

    private static int int32BE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static int int16BE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static byte[] readAtMost(InputStream in, int limit) throws IOException {
        byte[] buffer = new byte[Math.max(0, limit)];
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        if (total == buffer.length) {
            return buffer;
        }
        byte[] trimmed = new byte[total];
        System.arraycopy(buffer, 0, trimmed, 0, total);
        return trimmed;
    }
}

package dev.starsector.preflight.core;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/** Immutable output of the Starsector-compatible texture preparation pipeline. */
public final class PreparedTexture {
    public static final int FORMAT_VERSION = 1;

    private final String sourceSha256;
    private final Transformation transformation;
    private final int originalWidth;
    private final int originalHeight;
    private final int uploadWidth;
    private final int uploadHeight;
    private final int channels;
    private final int color0Rgba;
    private final int color1Rgba;
    private final int color2Rgba;
    private final byte[] pixels;

    private PreparedTexture(
            String sourceSha256,
            Transformation transformation,
            int originalWidth,
            int originalHeight,
            int uploadWidth,
            int uploadHeight,
            int channels,
            int color0Rgba,
            int color1Rgba,
            int color2Rgba,
            byte[] pixels,
            boolean adopt) {
        Hashes.decodeSha256(sourceSha256);
        this.sourceSha256 = sourceSha256.toLowerCase(java.util.Locale.ROOT);
        this.transformation = Objects.requireNonNull(transformation, "transformation");
        this.originalWidth = positive(originalWidth, "originalWidth");
        this.originalHeight = positive(originalHeight, "originalHeight");
        this.uploadWidth = positive(uploadWidth, "uploadWidth");
        this.uploadHeight = positive(uploadHeight, "uploadHeight");
        if (channels != 3 && channels != 4) {
            throw new IllegalArgumentException("channels must be 3 or 4");
        }
        this.channels = channels;
        long expected = Math.multiplyExact(Math.multiplyExact((long) uploadWidth, uploadHeight), channels);
        if (expected > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Prepared texture exceeds the Java byte-array limit");
        }
        if (pixels == null || pixels.length != (int) expected) {
            throw new IllegalArgumentException(
                    "Pixel payload length is " + (pixels == null ? "null" : pixels.length) + "; expected " + expected);
        }
        this.color0Rgba = color0Rgba;
        this.color1Rgba = color1Rgba;
        this.color2Rgba = color2Rgba;
        this.pixels = adopt ? pixels : pixels.clone();
    }

    public PreparedTexture(
            String sourceSha256,
            Transformation transformation,
            int originalWidth,
            int originalHeight,
            int uploadWidth,
            int uploadHeight,
            int channels,
            int color0Rgba,
            int color1Rgba,
            int color2Rgba,
            byte[] pixels) {
        this(sourceSha256, transformation, originalWidth, originalHeight, uploadWidth, uploadHeight,
                channels, color0Rgba, color1Rgba, color2Rgba, pixels, false);
    }

    /**
     * Takes ownership of {@code pixels} instead of copying it.
     *
     * <p>Only for a caller that just allocated the array and will not touch it again -- reading a
     * blob is the case this exists for, where the array comes straight off the stream and the clone
     * is a second full copy of a texture nothing else can reach. Package-private because the
     * promise cannot be checked, only kept.
     */
    static PreparedTexture adopting(
            String sourceSha256,
            Transformation transformation,
            int originalWidth,
            int originalHeight,
            int uploadWidth,
            int uploadHeight,
            int channels,
            int color0Rgba,
            int color1Rgba,
            int color2Rgba,
            byte[] pixels) {
        return new PreparedTexture(sourceSha256, transformation, originalWidth, originalHeight,
                uploadWidth, uploadHeight, channels, color0Rgba, color1Rgba, color2Rgba, pixels, true);
    }

    public String sourceSha256() {
        return sourceSha256;
    }

    public Transformation transformation() {
        return transformation;
    }

    public int originalWidth() {
        return originalWidth;
    }

    public int originalHeight() {
        return originalHeight;
    }

    public int uploadWidth() {
        return uploadWidth;
    }

    public int uploadHeight() {
        return uploadHeight;
    }

    public int channels() {
        return channels;
    }

    public boolean hasAlpha() {
        return channels == 4;
    }

    public int color0Rgba() {
        return color0Rgba;
    }

    public int color1Rgba() {
        return color1Rgba;
    }

    public int color2Rgba() {
        return color2Rgba;
    }

    public int pixelBytes() {
        return pixels.length;
    }

    public byte[] pixels() {
        return pixels.clone();
    }

    /**
     * The stored pixels without a defensive copy, as a buffer that cannot write through to them.
     *
     * <p>{@link #pixels()} clones, which is right for a caller that wants an array it owns and wrong
     * for the serving path, where every clone is a second copy of a texture that is about to be
     * copied again into the upload buffer. A launch of the reviewed profile serves 2.53 GB of
     * pixels, and clones them twice on the way -- once to build the readable raster, once to fill
     * the upload buffer -- for 5.06 GB of allocation and copying that nothing ever reads twice.
     *
     * <p>Read-only rather than a bare array reference: immutability is what makes this class safe to
     * share, and handing out something that can write through to the backing store would trade a
     * copy for the ability to corrupt a cached texture in place.
     */
    public ByteBuffer pixelsView() {
        return ByteBuffer.wrap(pixels).asReadOnlyBuffer();
    }

    public void copyPixelsTo(byte[] destination, int offset) {
        Objects.requireNonNull(destination, "destination");
        if (offset < 0 || offset > destination.length - pixels.length) {
            throw new IndexOutOfBoundsException("Pixel destination is too small");
        }
        System.arraycopy(pixels, 0, destination, offset, pixels.length);
    }

    public static int rgba(int red, int green, int blue, int alpha) {
        return (component(red) << 24)
                | (component(green) << 16)
                | (component(blue) << 8)
                | component(alpha);
    }

    public static int red(int rgba) {
        return (rgba >>> 24) & 0xff;
    }

    public static int green(int rgba) {
        return (rgba >>> 16) & 0xff;
    }

    public static int blue(int rgba) {
        return (rgba >>> 8) & 0xff;
    }

    public static int alpha(int rgba) {
        return rgba & 0xff;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int component(int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("Color component must be between 0 and 255: " + value);
        }
        return value;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PreparedTexture other)) {
            return false;
        }
        return originalWidth == other.originalWidth
                && originalHeight == other.originalHeight
                && uploadWidth == other.uploadWidth
                && uploadHeight == other.uploadHeight
                && channels == other.channels
                && color0Rgba == other.color0Rgba
                && color1Rgba == other.color1Rgba
                && color2Rgba == other.color2Rgba
                && sourceSha256.equals(other.sourceSha256)
                && transformation == other.transformation
                && Arrays.equals(pixels, other.pixels);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                sourceSha256,
                transformation,
                originalWidth,
                originalHeight,
                uploadWidth,
                uploadHeight,
                channels,
                color0Rgba,
                color1Rgba,
                color2Rgba);
        return 31 * result + Arrays.hashCode(pixels);
    }

    public enum Transformation {
        IDENTITY(0),
        ALPHA_ADDER(1);

        private final int id;

        Transformation(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static Transformation fromId(int id) {
            for (Transformation value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown texture transformation id: " + id);
        }
    }
}

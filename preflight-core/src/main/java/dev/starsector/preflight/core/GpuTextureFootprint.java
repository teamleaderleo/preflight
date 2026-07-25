package dev.starsector.preflight.core;

/**
 * How much video memory a texture actually occupies once Starsector has uploaded it, which is not
 * {@code width * height * channels}.
 *
 * <p>Three engine facts drive this, all read from the installed {@code com.fs.graphics.TextureLoader}
 * (an unobfuscated near-copy of Slick2D's loader of the same name):
 *
 * <ol>
 *   <li><b>Dimensions are rounded up to a power of two.</b> The loader passes both dimensions through
 *       Slick's {@code get2Fold} before {@code glTexImage2D}, so a 288x384 sprite allocates 512x512.
 *       The source occupies a corner of that box and the sprite's texture coordinates address only
 *       the used sub-rectangle; the remainder is allocated and wasted.
 *   <li><b>The internal format is {@code GL_RGBA}</b>, hardcoded, for every texture. An opaque RGB
 *       source still costs four bytes per pixel resident, whatever we uploaded. (Generic
 *       {@code GL_RGBA} lets the driver choose the concrete format; desktop drivers resolve it to
 *       RGBA8.)
 *   <li><b>Mip chains are opt-in per texture path.</b> The loader consults a static set of resource
 *       names and only those get {@code GL_LINEAR_MIPMAP_LINEAR}; the rest get plain
 *       {@code GL_LINEAR}. A complete chain adds at most a third, so mipmapped paths cost up to
 *       {@code 16/3} bytes per padded pixel and the rest cost exactly 4.
 * </ol>
 *
 * <p>This is not a preflight invention. Dark.Revenant, GraphicsLib's author, published the same
 * arithmetic on the Fractal forum in July 2019 — a 288x384 Onslaught sprite as "512x512 pixels with
 * 4 bytes per pixel and an overall 4/3 increase in size due to the mipmapping", 1365 KiB resident —
 * and gave the community estimate as width times height times {@code 16/3} bytes. The bytecode above
 * is an independent confirmation of that figure, and {@link #residentBytes} and
 * {@link #residentBytesWithMipChain} are its lower and upper bounds respectively.
 *
 * @see <a href="https://fractalsoftworks.com/forum/index.php?topic=15674.15">Forum: A Small Change
 *     to the Installer that Could Help Your Game (reply #15 and #21)</a>
 */
public final class GpuTextureFootprint {
    /** Bytes per resident pixel: the loader's hardcoded {@code GL_RGBA} internal format. */
    public static final int RESIDENT_BYTES_PER_PIXEL = 4;

    /**
     * The smallest dimension the engine will allocate. Slick's {@code get2Fold} seeds its search at
     * two and never returns less, so even a one-pixel edge is padded to two.
     */
    public static final int MINIMUM_UPLOAD_DIMENSION = 2;

    /** Largest source dimension whose power-of-two rounding still fits a positive {@code int}. */
    private static final int MAXIMUM_SOURCE_DIMENSION = 1 << 30;

    private GpuTextureFootprint() {
    }

    /**
     * The power-of-two dimension the engine allocates for a source dimension, reproducing Slick's
     * {@code get2Fold} exactly — including its floor of {@link #MINIMUM_UPLOAD_DIMENSION}, which a
     * plain "next power of two" implementation gets wrong for an edge of one pixel.
     *
     * @return the padded dimension, or {@code -1} if the source is not a representable size
     */
    public static int uploadDimension(int sourceDimension) {
        if (sourceDimension <= 0 || sourceDimension > MAXIMUM_SOURCE_DIMENSION) {
            return -1;
        }
        int padded = MINIMUM_UPLOAD_DIMENSION;
        while (padded < sourceDimension) {
            padded *= 2;
        }
        return padded;
    }

    /**
     * Bytes this texture occupies in video memory with no mip chain: the padded box at four bytes a
     * pixel. This is a floor, not an estimate — every term is exact.
     *
     * @return the resident size, or {@code -1} if either dimension is not representable
     */
    public static long residentBytes(int width, int height) {
        int paddedWidth = uploadDimension(width);
        int paddedHeight = uploadDimension(height);
        if (paddedWidth < 0 || paddedHeight < 0) {
            return -1;
        }
        return (long) paddedWidth * (long) paddedHeight * RESIDENT_BYTES_PER_PIXEL;
    }

    /**
     * Bytes this texture occupies if its path is one of the mipmapped ones: {@link #residentBytes}
     * plus a complete chain, which sums to strictly less than a third more (1/4 + 1/16 + ... &lt; 1/3).
     * Equivalently the community's {@code 16/3} bytes per padded pixel.
     *
     * @return the upper bound, or {@code -1} if either dimension is not representable
     */
    public static long residentBytesWithMipChain(int width, int height) {
        long base = residentBytes(width, height);
        if (base < 0) {
            return -1;
        }
        return base + ceilDiv(base, 3);
    }

    /** Bytes the padded box wastes: allocated because of rounding, never sampled. */
    public static long paddingBytes(int width, int height) {
        long resident = residentBytes(width, height);
        if (resident < 0) {
            return -1;
        }
        return resident - (long) width * (long) height * RESIDENT_BYTES_PER_PIXEL;
    }

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }
}

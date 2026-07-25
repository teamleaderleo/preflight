package dev.starsector.preflight.core;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A texture stored as GPU-ready S3TC blocks, together with the measured cost of storing it that way.
 *
 * <p>This is deliberately <em>not</em> a {@link PreparedTexture}. That type is a promise: its pixels
 * are exactly what the game's own loader would have produced, so a consumer may substitute it for
 * the real decode without further thought. Block data breaks that promise — it is lossy, by design,
 * in exchange for a quarter of the video memory and no PNG decode at all. Sharing one type between
 * an exact cache and a lossy one would leave the difference to a codec field that any consumer could
 * fail to check, and the failure mode is a silently degraded game rather than an exception. Two
 * types, two magics, two namespaces.
 *
 * <p>The blob carries three things beyond the blocks themselves, each because the alternative is a
 * cache that cannot be reasoned about after the fact:
 *
 * <ul>
 *   <li>the {@linkplain BlockCompressor#CODEC_VERSION encoder identity}, because a blob written by an
 *       older encoder is indistinguishable from a current one by inspection;
 *   <li>the GL internal format, so the upload path passes a constant that was decided when the pixels
 *       were looked at rather than re-derived from a channel count at load time;
 *   <li>the {@link TextureFidelity.Report} measured at bake time. This is required rather than
 *       optional: it should not be possible to have written a lossy texture into a cache without
 *       having measured the loss, and keeping the number in the artifact means a later policy
 *       decision — or a complaint about a specific sprite — can be answered without re-encoding
 *       anything.
 * </ul>
 *
 * <p>Levels are a mip chain, level 0 first, each half the previous size rounded down and floored at
 * one. Most Starsector textures are uploaded without mips, so a one-level texture is the common case;
 * the chain is expressible because the engine does mipmap a named subset of paths, and a format that
 * could not represent those would push exactly the largest textures back onto the slow path.
 */
public final class BlockTexture {
    public static final int FORMAT_VERSION = 1;
    /** Upper bound on levels, from a 32-bit dimension: no chain can be longer without overflow. */
    public static final int MAX_LEVELS = 32;

    private final String sourceSha256;
    private final Format format;
    private final int codecVersion;
    private final int originalWidth;
    private final int originalHeight;
    private final int width;
    private final int height;
    private final TextureFidelity.Report fidelity;
    private final byte[][] levels;

    /**
     * @param sourceSha256 hash of the source image bytes, so a changed asset invalidates the blob
     * @param originalWidth width before any resize; equal to {@code width} when nothing was resized
     * @param width level 0 width, in pixels, which need not be a multiple of four
     * @param fidelity the loss measured when these blocks were encoded
     * @param levels mip chain, level 0 first, at least one level
     */
    public BlockTexture(
            String sourceSha256,
            Format format,
            int codecVersion,
            int originalWidth,
            int originalHeight,
            int width,
            int height,
            TextureFidelity.Report fidelity,
            List<byte[]> levels) {
        Hashes.decodeSha256(sourceSha256);
        this.sourceSha256 = sourceSha256.toLowerCase(java.util.Locale.ROOT);
        this.format = Objects.requireNonNull(format, "format");
        if (codecVersion <= 0) {
            throw new IllegalArgumentException("codecVersion must be positive");
        }
        this.codecVersion = codecVersion;
        this.originalWidth = positive(originalWidth, "originalWidth");
        this.originalHeight = positive(originalHeight, "originalHeight");
        this.width = positive(width, "width");
        this.height = positive(height, "height");
        this.fidelity = Objects.requireNonNull(fidelity, "fidelity");
        Objects.requireNonNull(levels, "levels");
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("A block texture needs at least a level 0");
        }
        if (levels.size() > MAX_LEVELS || levels.size() > levelCount(width, height)) {
            throw new IllegalArgumentException(
                    "A " + width + "x" + height + " texture has at most " + levelCount(width, height)
                            + " mip levels; got " + levels.size());
        }
        this.levels = new byte[levels.size()][];
        for (int level = 0; level < levels.size(); level++) {
            byte[] blocks = Objects.requireNonNull(levels.get(level), "level " + level);
            long expected = format.blockBytesFor(levelWidth(width, level), levelHeight(height, level));
            if (blocks.length != expected) {
                throw new IllegalArgumentException(
                        "Level " + level + " is " + blocks.length + " bytes; expected " + expected);
            }
            this.levels[level] = blocks.clone();
        }
    }

    public String sourceSha256() {
        return sourceSha256;
    }

    public Format format() {
        return format;
    }

    /** The {@link BlockCompressor#CODEC_VERSION} in force when these blocks were encoded. */
    public int codecVersion() {
        return codecVersion;
    }

    public int originalWidth() {
        return originalWidth;
    }

    public int originalHeight() {
        return originalHeight;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public TextureFidelity.Report fidelity() {
        return fidelity;
    }

    public int levelCount() {
        return levels.length;
    }

    /** Blocks for one mip level, ready to hand to {@code glCompressedTexImage2D} unchanged. */
    public byte[] level(int level) {
        return levels[level].clone();
    }

    public int levelWidth(int level) {
        checkLevel(level);
        return levelWidth(width, level);
    }

    public int levelHeight(int level) {
        checkLevel(level);
        return levelHeight(height, level);
    }

    /** Total block bytes across every level: what this texture costs on disk and resident in VRAM. */
    public long blockBytes() {
        long total = 0;
        for (byte[] level : levels) {
            total += level.length;
        }
        return total;
    }

    /**
     * What the same texture costs uncompressed at four bytes per pixel — the figure the block form is
     * traded against, and the one the loader pays today.
     */
    public long uncompressedBytes() {
        long total = 0;
        for (int level = 0; level < levels.length; level++) {
            total += 4L * levelWidth(width, level) * levelHeight(height, level);
        }
        return total;
    }

    /** Mip levels a texture of this size can have, counting level 0. */
    public static int levelCount(int width, int height) {
        int levels = 1;
        int w = width;
        int h = height;
        while (w > 1 || h > 1) {
            w = Math.max(1, w >> 1);
            h = Math.max(1, h >> 1);
            levels++;
        }
        return levels;
    }

    static int levelWidth(int width, int level) {
        return Math.max(1, width >> level);
    }

    static int levelHeight(int height, int level) {
        return Math.max(1, height >> level);
    }

    private void checkLevel(int level) {
        if (level < 0 || level >= levels.length) {
            throw new IndexOutOfBoundsException("No mip level " + level + "; this texture has " + levels.length);
        }
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof BlockTexture other)) {
            return false;
        }
        return codecVersion == other.codecVersion
                && originalWidth == other.originalWidth
                && originalHeight == other.originalHeight
                && width == other.width
                && height == other.height
                && sourceSha256.equals(other.sourceSha256)
                && format == other.format
                && fidelity.equals(other.fidelity)
                && Arrays.deepEquals(levels, other.levels);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                sourceSha256, format, codecVersion, originalWidth, originalHeight, width, height, fidelity);
        return 31 * result + Arrays.deepHashCode(levels);
    }

    /**
     * The block formats this cache can hold, each paired with the GL constant that uploads it.
     *
     * <p>Only the two the driver probe found usable everywhere Starsector runs are here. BC7 would be
     * better on both counts and is not an option: Apple's OpenGL stops at 4.1, BPTC is core in 4.2,
     * and the extension was never shipped, so {@code glCompressedTexImage2D} returns
     * {@code GL_INVALID_ENUM}. See {@code docs/evidence/2026-07-25-macos-gl-capability-probe.md}.
     *
     * <p>BC1 declares the RGB variant rather than the RGBA one because the encoder never emits a
     * punch-through block — it orders its endpoints so the four-colour mode bit is always set. Asking
     * for the RGBA variant would be declaring an alpha channel that the bytes deliberately do not
     * carry.
     */
    public enum Format {
        /** DXT1, opaque, 8 bytes per 4x4 block: half a byte per pixel. */
        BC1(0, 0x83F0, BlockCompressor.BC1_BLOCK_BYTES, false),
        /** DXT5, interpolated alpha, 16 bytes per 4x4 block: one byte per pixel. */
        BC3(1, 0x83F3, BlockCompressor.BC3_BLOCK_BYTES, true);

        private final int id;
        private final int glInternalFormat;
        private final int blockBytes;
        private final boolean withAlpha;

        Format(int id, int glInternalFormat, int blockBytes, boolean withAlpha) {
            this.id = id;
            this.glInternalFormat = glInternalFormat;
            this.blockBytes = blockBytes;
            this.withAlpha = withAlpha;
        }

        public int id() {
            return id;
        }

        /** The {@code internalformat} argument for {@code glCompressedTexImage2D}. */
        public int glInternalFormat() {
            return glInternalFormat;
        }

        public int blockBytes() {
            return blockBytes;
        }

        /** Whether this format carries an alpha channel, and so which {@link BlockCompressor} path. */
        public boolean withAlpha() {
            return withAlpha;
        }

        public long blockBytesFor(int width, int height) {
            return BlockCompressor.compressedBytes(width, height, withAlpha);
        }

        public static Format fromId(int id) {
            for (Format value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown block texture format id: " + id);
        }
    }
}

package dev.starsector.preflight.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns decoded pixels into a {@link BlockTexture}: picks a format, encodes, and measures what the
 * encoding cost.
 *
 * <p>Baking is the expensive half of the whole idea and runs once, offline, so it is written for
 * quality rather than speed. The saving is entirely on the other side — a cached block texture is
 * uploaded straight to the GPU with no PNG decode, no raster walk and no power-of-two padding, which
 * on the measured profile is 94.6% of what a texture load costs today.
 *
 * <p>Every bake measures its own loss and the measurement travels with the blocks. This is not
 * bookkeeping: block compression is the one step in preflight that is deliberately not exact, so the
 * question "how much worse did this get" has to be answerable per texture, months later, without the
 * source image to hand.
 */
public final class BlockTextureBaker {
    private BlockTextureBaker() {
    }

    /** Bakes pixels that were not resized on the way in. */
    public static BlockTexture bake(String sourceSha256, int[] argb, int width, int height, Mips mips) {
        return bake(sourceSha256, argb, width, height, width, height, mips);
    }

    /**
     * @param sourceSha256 hash of the source image bytes, so a changed asset invalidates the blob
     * @param argb packed ARGB pixels, row major, {@code width * height} long
     * @param originalWidth width before any resize, for reporting; pass {@code width} if unresized
     */
    public static BlockTexture bake(
            String sourceSha256,
            int[] argb,
            int width,
            int height,
            int originalWidth,
            int originalHeight,
            Mips mips) {
        if (argb == null || width <= 0 || height <= 0 || argb.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("argb must hold exactly width*height pixels");
        }
        BlockTexture.Format format = chooseFormat(argb);
        List<byte[]> levels = new ArrayList<>();
        int[] pixels = argb;
        int levelWidth = width;
        int levelHeight = height;
        int levelCount = mips == Mips.FULL_CHAIN ? BlockTexture.levelCount(width, height) : 1;
        TextureFidelity.Report fidelity = null;
        for (int level = 0; level < levelCount; level++) {
            byte[] blocks = BlockCompressor.encode(pixels, levelWidth, levelHeight, format.withAlpha());
            levels.add(blocks);
            if (level == 0) {
                // Level 0 only. A mip level is already a resampling of the original, so comparing it
                // against a downsampled reference would fold two different losses into one number and
                // answer neither question; what a player sees at full size is level 0.
                fidelity = TextureFidelity.compare(
                        pixels, BlockCompressor.decode(blocks, levelWidth, levelHeight, format.withAlpha()));
            }
            if (level + 1 < levelCount) {
                int nextWidth = BlockTexture.levelWidth(width, level + 1);
                int nextHeight = BlockTexture.levelHeight(height, level + 1);
                pixels = halve(pixels, levelWidth, levelHeight, nextWidth, nextHeight);
                levelWidth = nextWidth;
                levelHeight = nextHeight;
            }
        }
        return new BlockTexture(
                sourceSha256,
                format,
                BlockCompressor.CODEC_VERSION,
                originalWidth,
                originalHeight,
                width,
                height,
                fidelity,
                levels);
    }

    /**
     * Picks BC1 for fully opaque images and BC3 for everything else.
     *
     * <p>BC3 costs twice as much — one byte per pixel against half — so the choice is worth making per
     * texture rather than globally, and on real Starsector art most of the VRAM sits in large opaque
     * backgrounds while the alpha lives in comparatively small sprites.
     *
     * <p>A single non-opaque pixel forces BC3 because BC1's opaque mode does not carry alpha at all;
     * there is no partial answer. BC1 does have a punch-through mode that would encode one-bit alpha
     * at half the cost, which would suit cutout sprites, but {@link BlockCompressor} never emits it —
     * it orders its endpoints so the four-colour mode bit is always set — so that saving is not
     * available here and is not pretended at.
     */
    public static BlockTexture.Format chooseFormat(int[] argb) {
        for (int pixel : argb) {
            if ((pixel >>> 24) != 0xff) {
                return BlockTexture.Format.BC3;
            }
        }
        return BlockTexture.Format.BC1;
    }

    /**
     * Area-averages an image down to the next mip level.
     *
     * <p>Averaging happens in premultiplied alpha and the result is unpremultiplied afterwards. This
     * matters more than it looks: a straight average of RGB weights the colour of a fully transparent
     * pixel equally with a fully opaque one, and since transparent pixels in real art are usually
     * black, the classic symptom is a dark halo creeping around every sprite edge as the mips get
     * smaller. Weighting each pixel's colour by its own alpha is the fix, and it costs nothing.
     *
     * <p>The filter is an area average rather than the obvious 2x2 box, because a 2x2 box is only
     * correct when the dimension is even. OpenGL's next level is {@code max(1, size >> 1)}, so a
     * 5-pixel row becomes 2, and a 2x2 box reads source pixels 0-1 and 2-3 while the fifth is never
     * read at all — content silently deleted, worse at every subsequent level. Letting each
     * destination pixel average exactly the source interval it covers, with fractional weights at the
     * ends, reduces to the plain 2x2 box whenever the dimension is even and stays correct when it is
     * not.
     */
    private static int[] halve(int[] source, int width, int height, int targetWidth, int targetHeight) {
        int[] out = new int[Math.multiplyExact(targetWidth, targetHeight)];
        Taps horizontal = taps(width, targetWidth);
        Taps vertical = taps(height, targetHeight);
        for (int y = 0; y < targetHeight; y++) {
            double[] rowWeights = vertical.weights[y];
            for (int x = 0; x < targetWidth; x++) {
                double[] columnWeights = horizontal.weights[x];
                double alphaSum = 0;
                double redSum = 0;
                double greenSum = 0;
                double blueSum = 0;
                for (int dy = 0; dy < rowWeights.length; dy++) {
                    int row = (vertical.first[y] + dy) * width;
                    for (int dx = 0; dx < columnWeights.length; dx++) {
                        double weight = rowWeights[dy] * columnWeights[dx];
                        int pixel = source[row + horizontal.first[x] + dx];
                        double alpha = weight * (pixel >>> 24);
                        alphaSum += alpha;
                        redSum += alpha * ((pixel >>> 16) & 0xff);
                        greenSum += alpha * ((pixel >>> 8) & 0xff);
                        blueSum += alpha * (pixel & 0xff);
                    }
                }
                // Weights sum to one, so alphaSum is already the average; the colour sums are
                // premultiplied and divide back out by it.
                int alpha = clampByte(Math.round(alphaSum));
                int red = alphaSum == 0 ? 0 : clampByte(Math.round(redSum / alphaSum));
                int green = alphaSum == 0 ? 0 : clampByte(Math.round(greenSum / alphaSum));
                int blue = alphaSum == 0 ? 0 : clampByte(Math.round(blueSum / alphaSum));
                out[y * targetWidth + x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
        }
        return out;
    }

    /**
     * Source pixels and normalised weights for each destination pixel along one axis.
     *
     * <p>Destination pixel {@code i} covers the source interval {@code [i*source/target,
     * (i+1)*source/target)}; each overlapping source pixel is weighted by how much of it falls
     * inside, and the weights are normalised to sum to one so the two axes compose by multiplication.
     */
    private static Taps taps(int source, int target) {
        int[] first = new int[target];
        double[][] weights = new double[target][];
        for (int i = 0; i < target; i++) {
            double start = (double) i * source / target;
            double end = (double) (i + 1) * source / target;
            int from = (int) Math.floor(start);
            int to = Math.min(source - 1, (int) Math.ceil(end) - 1);
            first[i] = from;
            double[] row = new double[to - from + 1];
            for (int s = from; s <= to; s++) {
                row[s - from] = (Math.min(end, s + 1) - Math.max(start, s)) / (end - start);
            }
            weights[i] = row;
        }
        return new Taps(first, weights);
    }

    private static int clampByte(long value) {
        return (int) Math.max(0, Math.min(255, value));
    }

    private record Taps(int[] first, double[][] weights) {
    }

    /** Whether to bake a full mip chain or level 0 alone. */
    public enum Mips {
        /**
         * Level 0 only. Starsector uploads most textures without mips, and one that is never sampled
         * minified does not benefit from a chain it still has to store.
         */
        NONE,
        /**
         * Every level down to 1x1. The engine gives {@code GL_LINEAR_MIPMAP_LINEAR} to a named subset
         * of paths, and those are the large textures where baking the chain offline saves the most.
         */
        FULL_CHAIN
    }
}

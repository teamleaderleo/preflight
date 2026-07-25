package dev.starsector.preflight.core;

/**
 * Area-average image reduction, shared by everything in preflight that makes a texture smaller.
 *
 * <p>Two places halve images — {@code assets shrink}, which writes a smaller override pack, and
 * {@link BlockTextureBaker}, which bakes a mip chain. They must agree, because a projection made by
 * one and delivered by the other would differ silently and only on some art.
 *
 * <h2>Why not the obvious 2x2 box</h2>
 *
 * <p>A 2x2 box filter — output pixel {@code i} is the mean of source {@code 2i} and {@code 2i+1} — is
 * correct only when the dimension is even. The next size down is {@code max(1, size >> 1)}, so a
 * 5-pixel row becomes 2, and a 2x2 box reads source pixels 0-1 and 2-3 while the fifth is never read
 * at all. The content is deleted, not blended, and the loss compounds at every subsequent level.
 *
 * <p>It also moves the image. Starsector positions a hull sprite by its centre, so a sprite that
 * loses its right column and bottom row has moved half a source pixel up and left relative to the
 * geometry the game will draw around it — for the exact art most likely to be shrunk.
 *
 * <p>An area average has neither problem: each output pixel averages precisely the source interval it
 * covers, weighting partially-covered pixels at the ends by their overlap. When the dimension is even
 * every interval is exactly two whole pixels and this <em>is</em> the 2x2 box, so power-of-two art —
 * most of Starsector's — is unaffected.
 */
public final class ImageResampler {
    private ImageResampler() {
    }

    /** The size below this one in a mip chain, matching OpenGL's {@code max(1, size >> 1)}. */
    public static int halved(int size) {
        return Math.max(1, size >> 1);
    }

    /**
     * Halves an ARGB image, averaging colour premultiplied by alpha.
     *
     * <p>Premultiplying is not a refinement. A straight RGB average weights a fully transparent
     * pixel's colour as heavily as an opaque one, and transparent pixels in real art are stored as
     * black, so the result is a dark halo creeping around every sprite edge — worse at each level.
     *
     * @param argb packed ARGB pixels, row major, {@code width * height} long
     */
    public static int[] halve(int[] argb, int width, int height) {
        if (argb == null || width <= 0 || height <= 0 || argb.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("argb must hold exactly width*height pixels");
        }
        int targetWidth = halved(width);
        int targetHeight = halved(height);
        int[] out = new int[Math.multiplyExact(targetWidth, targetHeight)];
        Taps horizontal = taps(width, targetWidth);
        Taps vertical = taps(height, targetHeight);
        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                double alphaSum = 0;
                double redSum = 0;
                double greenSum = 0;
                double blueSum = 0;
                for (int dy = 0; dy < vertical.weightCount(y); dy++) {
                    int row = (vertical.first(y) + dy) * width;
                    for (int dx = 0; dx < horizontal.weightCount(x); dx++) {
                        double weight = vertical.weight(y, dy) * horizontal.weight(x, dx);
                        int pixel = argb[row + horizontal.first(x) + dx];
                        double alpha = weight * (pixel >>> 24);
                        alphaSum += alpha;
                        redSum += alpha * ((pixel >>> 16) & 0xff);
                        greenSum += alpha * ((pixel >>> 8) & 0xff);
                        blueSum += alpha * (pixel & 0xff);
                    }
                }
                out[y * targetWidth + x] = pack(alphaSum, redSum, greenSum, blueSum);
            }
        }
        return out;
    }

    /**
     * Combines premultiplied sums into one ARGB pixel.
     *
     * <p>Weights sum to one, so {@code alphaSum} is already the average alpha and the colour sums
     * divide back out by it. A destination pixel covering nothing but transparency has no colour to
     * recover and becomes transparent black.
     *
     * <p>Exposed so callers that hold their pixels in another shape can share the arithmetic rather
     * than reimplement the premultiply-and-divide, which is where the halo bug lives.
     */
    public static int pack(double alphaSum, double redSum, double greenSum, double blueSum) {
        int alpha = clampByte(Math.round(alphaSum));
        if (alphaSum == 0) {
            return 0;
        }
        return (alpha << 24)
                | (clampByte(Math.round(redSum / alphaSum)) << 16)
                | (clampByte(Math.round(greenSum / alphaSum)) << 8)
                | clampByte(Math.round(blueSum / alphaSum));
    }

    /**
     * Source pixels and normalised weights for every destination pixel along one axis.
     *
     * <p>Destination pixel {@code i} covers the source interval
     * {@code [i*source/target, (i+1)*source/target)}. Each overlapping source pixel is weighted by how
     * much of it falls inside, normalised to sum to one so the two axes compose by multiplication.
     */
    public static Taps taps(int source, int target) {
        if (source <= 0 || target <= 0 || target > source) {
            throw new IllegalArgumentException("target must be between 1 and source");
        }
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

    /** Which source pixels each destination pixel reads along one axis, and with what weight. */
    public static final class Taps {
        private final int[] first;
        private final double[][] weights;

        private Taps(int[] first, double[][] weights) {
            this.first = first;
            this.weights = weights;
        }

        /** Index of the first source pixel destination {@code index} reads. */
        public int first(int index) {
            return first[index];
        }

        /** How many consecutive source pixels destination {@code index} reads. */
        public int weightCount(int index) {
            return weights[index].length;
        }

        /** Weight of the {@code offset}-th source pixel read by destination {@code index}. */
        public double weight(int index, int offset) {
            return weights[index][offset];
        }

        /** The widest run any destination pixel reads: how many source rows a streaming caller needs. */
        public int maxWeightCount() {
            int most = 0;
            for (double[] row : weights) {
                most = Math.max(most, row.length);
            }
            return most;
        }
    }
}

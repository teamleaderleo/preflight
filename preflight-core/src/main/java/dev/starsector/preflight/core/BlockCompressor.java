package dev.starsector.preflight.core;

/**
 * Encoder and decoder for the S3TC/DXT block formats the GPU can sample directly: BC1 (DXT1, opaque,
 * 8 bytes per 4x4 block) and BC3 (DXT5, with alpha, 16 bytes per block).
 *
 * <p>These are fixed-rate: BC1 is half a byte per pixel and BC3 one byte, against the four bytes a
 * pixel costs resident today. Unlike a container-level compression they stay compressed in video
 * memory and are decoded by the texture unit at sample time, so the saving is in residency, not just
 * on disk.
 *
 * <p>They are also lossy, which is the whole question. Encoding here exists so the loss can be
 * <em>measured</em> on real art with {@link TextureFidelity} rather than argued about: a 4x4 block is
 * approximated by two endpoint colours and a two-bit blend index per pixel, which is exact for a flat
 * or linear block and degrades on blocks containing several distinct hues.
 *
 * <p>The encoder is a bounding-box fit with least-squares refinement — the same shape as the standard
 * reference encoders. It is deliberately not the fastest possible implementation; it runs offline,
 * once, and being a poor encoder would understate the format's real quality and bias the very
 * measurement this exists to make.
 */
public final class BlockCompressor {
    /** Bytes per 4x4 block in BC1/DXT1. */
    public static final int BC1_BLOCK_BYTES = 8;
    /** Bytes per 4x4 block in BC3/DXT5. */
    public static final int BC3_BLOCK_BYTES = 16;
    private static final int BLOCK_EDGE = 4;
    private static final int BLOCK_PIXELS = BLOCK_EDGE * BLOCK_EDGE;
    /** Endpoint refinement passes. Two is where quality stops improving measurably. */
    private static final int REFINEMENT_PASSES = 2;

    private BlockCompressor() {
    }

    /** Compressed size in bytes for an image of these dimensions, in the given format. */
    public static long compressedBytes(int width, int height, boolean withAlpha) {
        long blocks = (long) ceilDiv(width, BLOCK_EDGE) * ceilDiv(height, BLOCK_EDGE);
        return blocks * (withAlpha ? BC3_BLOCK_BYTES : BC1_BLOCK_BYTES);
    }

    /**
     * Round-trips an ARGB image through BC1 or BC3 and returns the decoded result, which is what the
     * GPU would actually sample. Dimensions need not be multiples of four; partial edge blocks repeat
     * their last row and column, as the reference encoders do.
     *
     * @param argb packed ARGB pixels, row major, {@code width * height} long
     * @param withAlpha encode as BC3 (keeps an alpha channel) rather than BC1
     */
    public static int[] roundTrip(int[] argb, int width, int height, boolean withAlpha) {
        if (argb == null || width <= 0 || height <= 0 || argb.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("argb must hold exactly width*height pixels");
        }
        int[] out = new int[argb.length];
        int[] block = new int[BLOCK_PIXELS];
        int[] decoded = new int[BLOCK_PIXELS];
        for (int blockY = 0; blockY < height; blockY += BLOCK_EDGE) {
            for (int blockX = 0; blockX < width; blockX += BLOCK_EDGE) {
                gather(argb, width, height, blockX, blockY, block);
                encodeAndDecodeBlock(block, decoded, withAlpha);
                scatter(out, width, height, blockX, blockY, decoded);
            }
        }
        return out;
    }

    private static void gather(int[] argb, int width, int height, int blockX, int blockY, int[] block) {
        for (int y = 0; y < BLOCK_EDGE; y++) {
            int sourceY = Math.min(blockY + y, height - 1);
            for (int x = 0; x < BLOCK_EDGE; x++) {
                int sourceX = Math.min(blockX + x, width - 1);
                block[y * BLOCK_EDGE + x] = argb[sourceY * width + sourceX];
            }
        }
    }

    private static void scatter(int[] out, int width, int height, int blockX, int blockY, int[] decoded) {
        for (int y = 0; y < BLOCK_EDGE && blockY + y < height; y++) {
            for (int x = 0; x < BLOCK_EDGE && blockX + x < width; x++) {
                out[(blockY + y) * width + blockX + x] = decoded[y * BLOCK_EDGE + x];
            }
        }
    }

    private static void encodeAndDecodeBlock(int[] block, int[] decoded, boolean withAlpha) {
        int[] alpha = new int[BLOCK_PIXELS];
        if (withAlpha) {
            encodeAndDecodeAlpha(block, alpha);
        } else {
            java.util.Arrays.fill(alpha, 255);
        }
        encodeAndDecodeColour(block, decoded);
        for (int i = 0; i < BLOCK_PIXELS; i++) {
            decoded[i] = (alpha[i] << 24) | (decoded[i] & 0x00FFFFFF);
        }
    }

    /**
     * BC3's alpha block: two 8-bit endpoints and a three-bit index per pixel selecting one of eight
     * interpolated levels. Endpoints are the block's alpha extremes, which is optimal for the common
     * cases here — a flat interior, or a single soft edge ramp.
     */
    private static void encodeAndDecodeAlpha(int[] block, int[] alphaOut) {
        int high = 0;
        int low = 255;
        for (int pixel : block) {
            int a = pixel >>> 24;
            high = Math.max(high, a);
            low = Math.min(low, a);
        }
        if (high == low) {
            java.util.Arrays.fill(alphaOut, high);
            return;
        }
        int[] levels = new int[8];
        levels[0] = high;
        levels[1] = low;
        for (int i = 0; i < 6; i++) {
            levels[i + 2] = ((6 - i) * high + (i + 1) * low) / 7;
        }
        for (int i = 0; i < BLOCK_PIXELS; i++) {
            int a = block[i] >>> 24;
            int best = 0;
            int bestError = Integer.MAX_VALUE;
            for (int level = 0; level < 8; level++) {
                int error = Math.abs(levels[level] - a);
                if (error < bestError) {
                    bestError = error;
                    best = level;
                }
            }
            alphaOut[i] = levels[best];
        }
    }

    /**
     * The BC1 colour block, shared by BC3. Two RGB565 endpoints define a four-colour palette (the
     * endpoints plus two evenly spaced blends) and each pixel stores a two-bit index into it.
     *
     * <p>Endpoints start from the block's colour bounding box inset slightly towards the mean — the
     * standard trick that stops outliers from stretching the palette and blurring everything else —
     * then are refit by least squares against the chosen indices.
     */
    private static void encodeAndDecodeColour(int[] block, int[] decoded) {
        int minR = 255;
        int minG = 255;
        int minB = 255;
        int maxR = 0;
        int maxG = 0;
        int maxB = 0;
        for (int pixel : block) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
            minG = Math.min(minG, g);
            maxG = Math.max(maxG, g);
            minB = Math.min(minB, b);
            maxB = Math.max(maxB, b);
        }
        // Inset by 1/16 of the range, matching the reference encoders.
        int insetR = (maxR - minR) >> 4;
        int insetG = (maxG - minG) >> 4;
        int insetB = (maxB - minB) >> 4;
        double[] endpoint0 = {clamp(maxR - insetR), clamp(maxG - insetG), clamp(maxB - insetB)};
        double[] endpoint1 = {clamp(minR + insetR), clamp(minG + insetG), clamp(minB + insetB)};

        int[] indices = new int[BLOCK_PIXELS];
        int[][] palette = new int[4][3];
        for (int pass = 0; pass <= REFINEMENT_PASSES; pass++) {
            buildPalette(endpoint0, endpoint1, palette);
            assign(block, palette, indices);
            if (pass < REFINEMENT_PASSES) {
                refit(block, indices, endpoint0, endpoint1);
            }
        }

        int[] codes = {code565(endpoint0), code565(endpoint1)};
        polish(block, codes);
        buildPaletteFromCodes(codes, palette);
        assign(block, palette, indices);
        for (int i = 0; i < BLOCK_PIXELS; i++) {
            int[] colour = palette[indices[i]];
            decoded[i] = (colour[0] << 16) | (colour[1] << 8) | colour[2];
        }
    }

    /**
     * Hill-climbs the two endpoints on the quantised 5:6:5 grid they are actually stored on.
     *
     * <p>This is what separates a naive encoder from a usable one, and it matters most in the case
     * that looks easiest. A flat block fitted by least squares puts both endpoints on the same
     * quantised colour, so the whole palette collapses to one entry and the block carries the full
     * rounding error of that grid — around Delta-E 2, right at the edge of visibility, on exactly the
     * large smooth areas where an artefact would be most obvious. Straddling instead — placing the
     * endpoints either side of the target so an interpolated entry lands on it — recovers most of
     * that. Least squares cannot find it because the improvement only exists after quantisation.
     */
    private static void polish(int[] block, int[] codes) {
        int[][] palette = new int[4][3];
        buildPaletteFromCodes(codes, palette);
        long best = blockError(block, palette);
        int[] shifts = {5, 0, 11};       // bit offsets of blue, green, red within an RGB565 code
        int[] widths = {31, 63, 31};
        for (int round = 0; round < 2; round++) {
            boolean improved = false;
            for (int endpoint = 0; endpoint < 2; endpoint++) {
                for (int channel = 0; channel < 3; channel++) {
                    int shift = shifts[channel];
                    int mask = widths[channel];
                    int current = (codes[endpoint] >> shift) & mask;
                    for (int delta = -1; delta <= 1; delta += 2) {
                        int candidate = current + delta;
                        if (candidate < 0 || candidate > mask) {
                            continue;
                        }
                        int original = codes[endpoint];
                        codes[endpoint] = (original & ~(mask << shift)) | (candidate << shift);
                        buildPaletteFromCodes(codes, palette);
                        long error = blockError(block, palette);
                        if (error < best) {
                            best = error;
                            current = candidate;
                            improved = true;
                        } else {
                            codes[endpoint] = original;
                        }
                    }
                }
            }
            if (!improved) {
                return;
            }
        }
    }

    private static long blockError(int[] block, int[][] palette) {
        long total = 0;
        for (int pixel : block) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            long best = Long.MAX_VALUE;
            for (int entry = 0; entry < 4; entry++) {
                long dr = r - palette[entry][0];
                long dg = g - palette[entry][1];
                long db = b - palette[entry][2];
                best = Math.min(best, 2 * dr * dr + 4 * dg * dg + db * db);
            }
            total += best;
        }
        return total;
    }

    private static int code565(double[] colour) {
        int r5 = (int) Math.round(clamp(colour[0]) * 31.0 / 255.0);
        int g6 = (int) Math.round(clamp(colour[1]) * 63.0 / 255.0);
        int b5 = (int) Math.round(clamp(colour[2]) * 31.0 / 255.0);
        return (r5 << 11) | (g6 << 5) | b5;
    }

    private static void buildPaletteFromCodes(int[] codes, int[][] palette) {
        palette[0] = expand565(codes[0]);
        palette[1] = expand565(codes[1]);
        for (int channel = 0; channel < 3; channel++) {
            palette[2][channel] = (2 * palette[0][channel] + palette[1][channel]) / 3;
            palette[3][channel] = (palette[0][channel] + 2 * palette[1][channel]) / 3;
        }
    }

    private static int[] expand565(int code) {
        int r5 = (code >> 11) & 31;
        int g6 = (code >> 5) & 63;
        int b5 = code & 31;
        return new int[] {(r5 << 3) | (r5 >> 2), (g6 << 2) | (g6 >> 4), (b5 << 3) | (b5 >> 2)};
    }

    /** Quantises the endpoints to RGB565 as the hardware stores them, then interpolates 1/3 and 2/3. */
    private static void buildPalette(double[] endpoint0, double[] endpoint1, int[][] palette) {
        int[] first = quantise565(endpoint0);
        int[] second = quantise565(endpoint1);
        palette[0] = first;
        palette[1] = second;
        for (int channel = 0; channel < 3; channel++) {
            palette[2][channel] = (2 * first[channel] + second[channel]) / 3;
            palette[3][channel] = (first[channel] + 2 * second[channel]) / 3;
        }
    }

    private static void assign(int[] block, int[][] palette, int[] indices) {
        for (int i = 0; i < BLOCK_PIXELS; i++) {
            int r = (block[i] >> 16) & 0xFF;
            int g = (block[i] >> 8) & 0xFF;
            int b = block[i] & 0xFF;
            int best = 0;
            long bestError = Long.MAX_VALUE;
            for (int entry = 0; entry < 4; entry++) {
                long dr = r - palette[entry][0];
                long dg = g - palette[entry][1];
                long db = b - palette[entry][2];
                // Green weighted higher, matching luminance sensitivity.
                long error = 2 * dr * dr + 4 * dg * dg + db * db;
                if (error < bestError) {
                    bestError = error;
                    best = entry;
                }
            }
            indices[i] = best;
        }
    }

    /**
     * Least-squares refit: with indices fixed, each pixel is endpoint0*w + endpoint1*(1-w) for a known
     * weight w, so the endpoints minimising squared error are the solution of a 2x2 normal system.
     */
    private static void refit(int[] block, int[] indices, double[] endpoint0, double[] endpoint1) {
        double[] weights = {1.0, 0.0, 2.0 / 3.0, 1.0 / 3.0};
        double sumWW = 0;
        double sumWV = 0;
        double sumVV = 0;
        double[] sumWC = new double[3];
        double[] sumVC = new double[3];
        for (int i = 0; i < BLOCK_PIXELS; i++) {
            double w = weights[indices[i]];
            double v = 1.0 - w;
            sumWW += w * w;
            sumWV += w * v;
            sumVV += v * v;
            int pixel = block[i];
            int[] channels = {(pixel >> 16) & 0xFF, (pixel >> 8) & 0xFF, pixel & 0xFF};
            for (int c = 0; c < 3; c++) {
                sumWC[c] += w * channels[c];
                sumVC[c] += v * channels[c];
            }
        }
        double determinant = sumWW * sumVV - sumWV * sumWV;
        if (Math.abs(determinant) < 1e-6) {
            return;
        }
        for (int c = 0; c < 3; c++) {
            double a = (sumVV * sumWC[c] - sumWV * sumVC[c]) / determinant;
            double b = (sumWW * sumVC[c] - sumWV * sumWC[c]) / determinant;
            endpoint0[c] = clamp(a);
            endpoint1[c] = clamp(b);
        }
    }

    /** Rounds to the 5:6:5 grid the format stores, then expands back to 8 bits as hardware does. */
    private static int[] quantise565(double[] colour) {
        int r5 = (int) Math.round(clamp(colour[0]) * 31.0 / 255.0);
        int g6 = (int) Math.round(clamp(colour[1]) * 63.0 / 255.0);
        int b5 = (int) Math.round(clamp(colour[2]) * 31.0 / 255.0);
        return new int[] {
                (r5 << 3) | (r5 >> 2),
                (g6 << 2) | (g6 >> 4),
                (b5 << 3) | (b5 >> 2)};
    }

    private static double clamp(double value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}

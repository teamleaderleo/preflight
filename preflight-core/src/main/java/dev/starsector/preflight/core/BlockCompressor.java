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
 *
 * <p>Where it departs from the reference encoders is the objective. They minimise squared error in
 * gamma-space RGB, usually with fixed per-channel weights standing in for luminance sensitivity. That
 * is a proxy for visibility, and a poor one in dark regions: lightness goes roughly as the cube root
 * of luminance, so a fixed RGB step is far more visible near black than near white, and a
 * uniformly-weighted encoder spends its endpoint precision in the wrong places. This encoder instead
 * chooses endpoints and indices by distance in {@link Oklab} — a perceptually uniform space — so it
 * optimises what {@link TextureFidelity} independently measures rather than a proxy for it.
 */
public final class BlockCompressor {
    /** Bytes per 4x4 block in BC1/DXT1. */
    public static final int BC1_BLOCK_BYTES = 8;
    /** Bytes per 4x4 block in BC3/DXT5. */
    public static final int BC3_BLOCK_BYTES = 16;
    private static final int BLOCK_EDGE = 4;
    private static final int BLOCK_PIXELS = BLOCK_EDGE * BLOCK_EDGE;

    private BlockCompressor() {
    }

    /** Compressed size in bytes for an image of these dimensions, in the given format. */
    public static long compressedBytes(int width, int height, boolean withAlpha) {
        long blocks = (long) ceilDiv(width, BLOCK_EDGE) * ceilDiv(height, BLOCK_EDGE);
        return blocks * (withAlpha ? BC3_BLOCK_BYTES : BC1_BLOCK_BYTES);
    }

    /**
     * Round-trips an ARGB image through BC1 or BC3 and returns the decoded result, which is what the
     * GPU would actually sample.
     *
     * <p>This is {@link #encode} followed by {@link #decode}, deliberately rather than a direct
     * calculation of the decoded colours. Those are not the same thing: the encoder can hold a
     * palette in mind that the byte format cannot express, and a measurement taken before
     * serialisation would score the palette it wanted rather than the one the texture unit will
     * reconstruct. Going through the bytes means the fidelity numbers describe the file.
     *
     * @param argb packed ARGB pixels, row major, {@code width * height} long
     * @param withAlpha encode as BC3 (keeps an alpha channel) rather than BC1
     */
    public static int[] roundTrip(int[] argb, int width, int height, boolean withAlpha) {
        return decode(encode(argb, width, height, withAlpha), width, height, withAlpha);
    }

    /**
     * Encodes an ARGB image to the exact byte layout {@code glCompressedTexImage2D} expects for
     * {@code GL_COMPRESSED_RGB_S3TC_DXT1_EXT} or {@code GL_COMPRESSED_RGBA_S3TC_DXT5_EXT}: 4x4 blocks
     * in raster order, each little-endian internally.
     *
     * <p>Dimensions need not be multiples of four; partial edge blocks repeat their last row and
     * column, as the reference encoders do, and the returned length is the whole-block size the GL
     * call requires.
     */
    public static byte[] encode(int[] argb, int width, int height, boolean withAlpha) {
        if (argb == null || width <= 0 || height <= 0 || argb.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("argb must hold exactly width*height pixels");
        }
        byte[] out = new byte[Math.toIntExact(compressedBytes(width, height, withAlpha))];
        int[] block = new int[BLOCK_PIXELS];
        int offset = 0;
        for (int blockY = 0; blockY < height; blockY += BLOCK_EDGE) {
            for (int blockX = 0; blockX < width; blockX += BLOCK_EDGE) {
                gather(argb, width, height, blockX, blockY, block);
                if (withAlpha) {
                    encodeAlphaBlock(block, out, offset);
                    offset += 8;
                }
                encodeColourBlock(block, out, offset);
                offset += 8;
            }
        }
        return out;
    }

    /**
     * Decodes S3TC blocks exactly as the texture unit does, including BC1's punch-through mode, and
     * returns packed ARGB.
     *
     * <p>Faithfulness to the hardware rules matters more here than it would in a utility decoder:
     * this is the model against which the encoder's output is judged, so a decoder that quietly
     * ignored a mode would hide precisely the encoding mistakes worth catching.
     */
    public static int[] decode(byte[] blocks, int width, int height, boolean withAlpha) {
        if (blocks == null || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("blocks, width and height are required");
        }
        long expected = compressedBytes(width, height, withAlpha);
        if (blocks.length != expected) {
            throw new IllegalArgumentException(
                    "block payload is " + blocks.length + " bytes; expected " + expected);
        }
        int[] out = new int[Math.multiplyExact(width, height)];
        int[] decoded = new int[BLOCK_PIXELS];
        int[] alpha = new int[BLOCK_PIXELS];
        int offset = 0;
        for (int blockY = 0; blockY < height; blockY += BLOCK_EDGE) {
            for (int blockX = 0; blockX < width; blockX += BLOCK_EDGE) {
                if (withAlpha) {
                    decodeAlphaBlock(blocks, offset, alpha);
                    offset += 8;
                } else {
                    java.util.Arrays.fill(alpha, 255);
                }
                decodeColourBlock(blocks, offset, withAlpha, decoded, alpha);
                offset += 8;
                for (int i = 0; i < BLOCK_PIXELS; i++) {
                    decoded[i] = (alpha[i] << 24) | (decoded[i] & 0x00FFFFFF);
                }
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

    /**
     * BC3's alpha block: two 8-bit endpoints and a three-bit index per pixel selecting one of eight
     * interpolated levels. Endpoints are the block's alpha extremes, which is optimal for the common
     * cases here — a flat interior, or a single soft edge ramp.
     *
     * <p>Writing {@code alpha0 > alpha1} selects the eight-level mode. The other ordering means six
     * interpolated levels plus a hard 0 and 255, which is the mode for blocks that want fully
     * transparent and fully opaque pixels exactly; the extremes-as-endpoints fit never wants it,
     * since it already places endpoints on whatever those extremes are.
     */
    private static void encodeAlphaBlock(int[] block, byte[] out, int offset) {
        int high = 0;
        int low = 255;
        for (int pixel : block) {
            int a = pixel >>> 24;
            high = Math.max(high, a);
            low = Math.min(low, a);
        }
        out[offset] = (byte) high;
        out[offset + 1] = (byte) low;
        if (high == low) {
            // alpha0 == alpha1 forces the six-level mode, where level 0 is still alpha0. Index 0
            // everywhere is therefore exact, and no other index would be.
            java.util.Arrays.fill(out, offset + 2, offset + 8, (byte) 0);
            return;
        }
        int[] levels = alphaLevels(high, low);
        long packed = 0;
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
            packed |= (long) best << (3 * i);
        }
        for (int byteIndex = 0; byteIndex < 6; byteIndex++) {
            out[offset + 2 + byteIndex] = (byte) (packed >>> (8 * byteIndex));
        }
    }

    private static void decodeAlphaBlock(byte[] blocks, int offset, int[] alphaOut) {
        int high = blocks[offset] & 0xFF;
        int low = blocks[offset + 1] & 0xFF;
        int[] levels = alphaLevels(high, low);
        long packed = 0;
        for (int byteIndex = 0; byteIndex < 6; byteIndex++) {
            packed |= (long) (blocks[offset + 2 + byteIndex] & 0xFF) << (8 * byteIndex);
        }
        for (int i = 0; i < BLOCK_PIXELS; i++) {
            alphaOut[i] = levels[(int) ((packed >>> (3 * i)) & 7)];
        }
    }

    /**
     * The eight selectable alpha values, in both of BC3's modes.
     *
     * <p>The interpolated levels round rather than truncate. That is not a stylistic choice: measured
     * against Apple's driver, truncating put every interpolated alpha one below the hardware's value
     * on half the pixels, while rounding reproduces it exactly. The colour blends in
     * {@link #buildPaletteFromCodes} truncate on the same hardware, so the two halves of a BC3 block
     * genuinely disagree — which is why this was measured rather than assumed. The specification
     * pins neither, so other drivers may differ; {@code probe-kits/gpu-capability} is how to find out
     * on a given machine.
     */
    private static int[] alphaLevels(int alpha0, int alpha1) {
        int[] levels = new int[8];
        levels[0] = alpha0;
        levels[1] = alpha1;
        if (alpha0 > alpha1) {
            for (int i = 0; i < 6; i++) {
                levels[i + 2] = ((6 - i) * alpha0 + (i + 1) * alpha1 + 3) / 7;
            }
        } else {
            for (int i = 0; i < 4; i++) {
                levels[i + 2] = ((4 - i) * alpha0 + (i + 1) * alpha1 + 2) / 5;
            }
            levels[6] = 0;
            levels[7] = 255;
        }
        return levels;
    }

    /**
     * The BC1 colour block, shared by BC3. Two RGB565 endpoints define a four-colour palette (the
     * endpoints plus two evenly spaced blends) and each pixel stores a two-bit index into it.
     *
     * <p>Endpoints start from the block's colour bounding box inset slightly towards the mean — the
     * standard trick that stops outliers from stretching the palette and blurring everything else —
     * and are then replaced by the best pair {@link #clusterFit} can find. {@link #polish} finally
     * nudges them on the quantised grid they are actually stored on.
     *
     * <p>The stored order of the two codes is not free. In BC1 it <em>is</em> the mode bit:
     * {@code code0 > code1} means the four-colour palette assumed everywhere above, and the other
     * ordering means a three-colour palette whose fourth entry is transparent black. Nothing in the
     * fitting cares which endpoint ends up first — the principal axis has an arbitrary sign, so the
     * answer is close to a coin flip per block — so the codes are ordered here, at the point where
     * the ordering acquires meaning. Swapping them exchanges palette entries 2 and 3, which is why
     * doing it before indices are assigned costs nothing.
     */
    private static void encodeColourBlock(int[] block, byte[] out, int offset) {
        double[] scratch = new double[3];
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
        clusterFit(block, endpoint0, endpoint1);

        // The block's pixels in perceptual space, computed once and reused by every candidate
        // palette below. Converting them per candidate would dominate the encode.
        double[] blockLab = new double[BLOCK_PIXELS * 3];
        for (int i = 0; i < BLOCK_PIXELS; i++) {
            int pixel = block[i];
            Oklab.fromSrgb((pixel >> 16) & 0xFF, (pixel >> 8) & 0xFF, pixel & 0xFF, scratch);
            blockLab[i * 3] = scratch[0];
            blockLab[i * 3 + 1] = scratch[1];
            blockLab[i * 3 + 2] = scratch[2];
        }

        int[] indices = new int[BLOCK_PIXELS];
        int[][] palette = new int[4][3];
        double[] paletteLab = new double[4 * 3];
        // No refit pass here. Cluster fit already returns the least-squares optimum for the best
        // index assignment it found; running the greedy assign-then-refit loop on top would walk
        // the endpoints straight back to the local optimum cluster fit exists to escape, which is
        // exactly what it did when this was first measured.
        int[] codes = {code565(endpoint0), code565(endpoint1)};
        polish(blockLab, codes, paletteLab);
        if (codes[0] < codes[1]) {
            int swap = codes[0];
            codes[0] = codes[1];
            codes[1] = swap;
        }
        out[offset] = (byte) codes[0];
        out[offset + 1] = (byte) (codes[0] >>> 8);
        out[offset + 2] = (byte) codes[1];
        out[offset + 3] = (byte) (codes[1] >>> 8);
        if (codes[0] == codes[1]) {
            // Equal codes cannot express four colours in either mode, and entry 3 would be
            // transparent. Entry 0 is the colour both endpoints agree on, so index it everywhere.
            java.util.Arrays.fill(out, offset + 4, offset + 8, (byte) 0);
            return;
        }
        buildPaletteFromCodes(codes, palette);
        assign(blockLab, palette, paletteLab, indices);
        for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
            int packed = 0;
            for (int i = 0; i < 4; i++) {
                packed |= indices[byteIndex * 4 + i] << (2 * i);
            }
            out[offset + 4 + byteIndex] = (byte) packed;
        }
    }

    /**
     * The texture unit's side of the colour block. BC1 reads the endpoint ordering as a mode bit;
     * BC3 does not, and always takes the four-colour interpretation regardless of order.
     */
    private static void decodeColourBlock(
            byte[] blocks, int offset, boolean withAlpha, int[] decoded, int[] alpha) {
        int code0 = (blocks[offset] & 0xFF) | ((blocks[offset + 1] & 0xFF) << 8);
        int code1 = (blocks[offset + 2] & 0xFF) | ((blocks[offset + 3] & 0xFF) << 8);
        int[][] palette = new int[4][3];
        boolean punchThrough = !withAlpha && code0 <= code1;
        if (punchThrough) {
            palette[0] = expand565(code0);
            palette[1] = expand565(code1);
            for (int channel = 0; channel < 3; channel++) {
                palette[2][channel] = (palette[0][channel] + palette[1][channel]) / 2;
                palette[3][channel] = 0;
            }
        } else {
            buildPaletteFromCodes(new int[] {code0, code1}, palette);
        }
        for (int i = 0; i < BLOCK_PIXELS; i++) {
            int index = (blocks[offset + 4 + (i / 4)] >>> (2 * (i % 4))) & 3;
            int[] colour = palette[index];
            decoded[i] = (colour[0] << 16) | (colour[1] << 8) | colour[2];
            if (punchThrough && index == 3) {
                alpha[i] = 0;
            }
        }
    }

    /**
     * Replaces the endpoints with the best pair found by <b>cluster fit</b>, the technique that
     * separates a reference-quality BC1 encoder from a merely correct one.
     *
     * <p>Bounding-box-plus-refit, which produced the incoming endpoints, alternates between choosing
     * each pixel's palette entry and refitting the endpoints to that choice. It converges, but only
     * to a local optimum: it can never reach a solution whose index assignment differs from the one
     * greedy nearest-entry selection produces. Measurement showed that to be the binding constraint
     * here — deepening the endpoint search barely moved the result, because the endpoints were not
     * what was stuck.
     *
     * <p>Cluster fit searches the index assignments directly. Because the palette is four points
     * evenly spaced along a line, an optimal assignment is always <em>contiguous</em> once the
     * pixels are sorted along that line: some prefix takes the first endpoint, the next run takes
     * the 2/3 blend, and so on. So the whole space of sensible assignments is the ways of splitting
     * sixteen sorted pixels into four ordered runs — 969 of them — and each can be scored exactly,
     * in constant time, from prefix sums. The best one wins.
     *
     * <p>The search runs in gamma RGB rather than perceptual space on purpose: the hardware blends
     * the stored endpoints linearly in exactly that space, so it is where the "palette is a line"
     * premise this relies on is actually true. Perceptual distance then decides the quantised
     * endpoints and the final indices, where no such linearity is assumed.
     */
    private static void clusterFit(int[] block, double[] endpoint0, double[] endpoint1) {
        double[] axis = principalAxis(block);
        // Sort pixel indices by position along the axis. Sixteen elements: insertion sort wins.
        int[] order = new int[BLOCK_PIXELS];
        double[] projection = new double[BLOCK_PIXELS];
        for (int i = 0; i < BLOCK_PIXELS; i++) {
            order[i] = i;
            projection[i] = ((block[i] >> 16) & 0xFF) * axis[0] + ((block[i] >> 8) & 0xFF) * axis[1]
                    + (block[i] & 0xFF) * axis[2];
        }
        for (int i = 1; i < BLOCK_PIXELS; i++) {
            int index = order[i];
            double value = projection[index];
            int j = i - 1;
            while (j >= 0 && projection[order[j]] > value) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = index;
        }

        // Prefix sums over the sorted colours, so any run's colour sum is one subtraction.
        double[][] prefix = new double[BLOCK_PIXELS + 1][3];
        double sumSquares = 0;
        for (int i = 0; i < BLOCK_PIXELS; i++) {
            int pixel = block[order[i]];
            double r = (pixel >> 16) & 0xFF;
            double g = (pixel >> 8) & 0xFF;
            double b = pixel & 0xFF;
            prefix[i + 1][0] = prefix[i][0] + r;
            prefix[i + 1][1] = prefix[i][1] + g;
            prefix[i + 1][2] = prefix[i][2] + b;
            sumSquares += r * r + g * g + b * b;
        }

        double bestError = Double.MAX_VALUE;
        boolean found = false;
        // Hoisted: the loop below runs 969 times per block, and allocating inside it dominated the
        // encode when it did.
        double[] bestA = new double[3];
        double[] bestB = new double[3];
        double[] a = new double[3];
        double[] b = new double[3];
        double[] alphaSum = new double[3];
        double[] betaSum = new double[3];
        // Runs of sizes (first, second, third, rest) taking weights 1, 2/3, 1/3, 0.
        for (int first = 0; first <= BLOCK_PIXELS; first++) {
            for (int second = 0; second + first <= BLOCK_PIXELS; second++) {
                for (int third = 0; third + second + first <= BLOCK_PIXELS; third++) {
                    int fourth = BLOCK_PIXELS - first - second - third;
                    int endOfSecond = first + second;
                    int endOfThird = endOfSecond + third;
                    for (int c = 0; c < 3; c++) {
                        double run0 = prefix[first][c];
                        double run1 = prefix[endOfSecond][c] - prefix[first][c];
                        double run2 = prefix[endOfThird][c] - prefix[endOfSecond][c];
                        double run3 = prefix[BLOCK_PIXELS][c] - prefix[endOfThird][c];
                        alphaSum[c] = run0 + run1 * (2.0 / 3.0) + run2 * (1.0 / 3.0);
                        betaSum[c] = run1 * (1.0 / 3.0) + run2 * (2.0 / 3.0) + run3;
                    }
                    // The normal-equation coefficients depend only on the run lengths.
                    double aa = first + second * (4.0 / 9.0) + third * (1.0 / 9.0);
                    double ab = second * (2.0 / 9.0) + third * (2.0 / 9.0);
                    double bb = second * (1.0 / 9.0) + third * (4.0 / 9.0) + fourth;
                    double determinant = aa * bb - ab * ab;
                    if (Math.abs(determinant) < 1e-9) {
                        continue;
                    }
                    double crossAB = 0;
                    double lengthA = 0;
                    double lengthB = 0;
                    double projected = 0;
                    for (int c = 0; c < 3; c++) {
                        // The unconstrained solution can land outside the representable cube, and a
                        // colour that cannot be stored is not a solution — so clamp first and score
                        // what would actually be encoded.
                        a[c] = clamp((bb * alphaSum[c] - ab * betaSum[c]) / determinant);
                        b[c] = clamp((aa * betaSum[c] - ab * alphaSum[c]) / determinant);
                        projected += a[c] * alphaSum[c] + b[c] * betaSum[c];
                        lengthA += a[c] * a[c];
                        lengthB += b[c] * b[c];
                        crossAB += a[c] * b[c];
                    }
                    // General residual, valid for any endpoints rather than only the optimum:
                    // S|c|^2 - 2(A.Sac + B.Sbc) + (aa|A|^2 + 2ab(A.B) + bb|B|^2).
                    double error = sumSquares - 2 * projected
                            + aa * lengthA + 2 * ab * crossAB + bb * lengthB;
                    if (error < bestError) {
                        bestError = error;
                        found = true;
                        System.arraycopy(a, 0, bestA, 0, 3);
                        System.arraycopy(b, 0, bestB, 0, 3);
                    }
                }
            }
        }
        if (!found) {
            return;
        }
        for (int c = 0; c < 3; c++) {
            endpoint0[c] = clamp(bestA[c]);
            endpoint1[c] = clamp(bestB[c]);
        }
    }

    /**
     * The direction of greatest colour variation in the block, by power iteration on the covariance
     * matrix. This is the line the palette will lie along, so sorting by position on it is what makes
     * the optimal index assignment contiguous.
     */
    private static double[] principalAxis(int[] block) {
        double meanR = 0;
        double meanG = 0;
        double meanB = 0;
        for (int pixel : block) {
            meanR += (pixel >> 16) & 0xFF;
            meanG += (pixel >> 8) & 0xFF;
            meanB += pixel & 0xFF;
        }
        meanR /= BLOCK_PIXELS;
        meanG /= BLOCK_PIXELS;
        meanB /= BLOCK_PIXELS;

        double xx = 0;
        double xy = 0;
        double xz = 0;
        double yy = 0;
        double yz = 0;
        double zz = 0;
        for (int pixel : block) {
            double r = ((pixel >> 16) & 0xFF) - meanR;
            double g = ((pixel >> 8) & 0xFF) - meanG;
            double b = (pixel & 0xFF) - meanB;
            xx += r * r;
            xy += r * g;
            xz += r * b;
            yy += g * g;
            yz += g * b;
            zz += b * b;
        }
        double[] vector = {1, 1, 1};
        for (int iteration = 0; iteration < 8; iteration++) {
            double r = xx * vector[0] + xy * vector[1] + xz * vector[2];
            double g = xy * vector[0] + yy * vector[1] + yz * vector[2];
            double b = xz * vector[0] + yz * vector[1] + zz * vector[2];
            double length = Math.sqrt(r * r + g * g + b * b);
            if (length < 1e-9) {
                // A flat block has no principal direction; any axis orders it identically.
                return new double[] {1, 0, 0};
            }
            vector[0] = r / length;
            vector[1] = g / length;
            vector[2] = b / length;
        }
        return vector;
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
    private static void polish(double[] blockLab, int[] codes, double[] paletteLab) {
        int[][] palette = new int[4][3];
        buildPaletteFromCodes(codes, palette);
        double best = blockError(blockLab, palette, paletteLab);
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
                        double error = blockError(blockLab, palette, paletteLab);
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

    /** Total perceptual error if this block were encoded with this palette, each pixel taking its best entry. */
    private static double blockError(double[] blockLab, int[][] palette, double[] paletteLab) {
        toLab(palette, paletteLab);
        double total = 0;
        for (int i = 0; i < BLOCK_PIXELS; i++) {
            double best = Double.MAX_VALUE;
            for (int entry = 0; entry < 4; entry++) {
                best = Math.min(best, Oklab.squaredDistance(blockLab, i * 3, paletteLab, entry * 3));
            }
            total += best;
        }
        return total;
    }

    private static void toLab(int[][] palette, double[] paletteLab) {
        double[] converted = new double[3];
        for (int entry = 0; entry < 4; entry++) {
            Oklab.fromSrgb(palette[entry][0], palette[entry][1], palette[entry][2], converted);
            paletteLab[entry * 3] = converted[0];
            paletteLab[entry * 3 + 1] = converted[1];
            paletteLab[entry * 3 + 2] = converted[2];
        }
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

    /** Picks each pixel's palette entry by perceptual distance rather than by RGB distance. */
    private static void assign(double[] blockLab, int[][] palette, double[] paletteLab, int[] indices) {
        toLab(palette, paletteLab);
        for (int i = 0; i < BLOCK_PIXELS; i++) {
            int best = 0;
            double bestError = Double.MAX_VALUE;
            for (int entry = 0; entry < 4; entry++) {
                double error = Oklab.squaredDistance(blockLab, i * 3, paletteLab, entry * 3);
                if (error < bestError) {
                    bestError = error;
                    best = entry;
                }
            }
            indices[i] = best;
        }
    }

    private static double clamp(double value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}

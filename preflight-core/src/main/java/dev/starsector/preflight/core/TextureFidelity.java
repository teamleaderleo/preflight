package dev.starsector.preflight.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Measures how different two versions of the same texture look, in units tied to human perception
 * rather than to bytes.
 *
 * <p>The measure is CIELAB <b>Delta-E</b> (CIE76): both colours are converted to the CIE L*a*b* space,
 * which is built so that equal numeric distances are roughly equally visible, and the error is the
 * Euclidean distance between them. The thresholds are the standard ones:
 *
 * <ul>
 *   <li><b>&lt; 1.0</b> — not perceptible to the human eye.
 *   <li><b>1 to 2</b> — perceptible only on close side-by-side inspection.
 *   <li><b>&gt; 2</b> — perceptible at a glance.
 * </ul>
 *
 * <p>This is what makes "lossy but invisible" a claim that can be gated rather than asserted. PSNR
 * cannot do that job: it is a pixel-difference average with no perceptual scale and no threshold at
 * which a human stops noticing.
 *
 * <p>Two adjustments matter for sprite art. Every error is scaled by <b>coverage</b> — a colour error
 * under alpha 10 contributes about 4% of itself to the composited result, and a fully transparent
 * pixel contributes none — so the measurement tracks what reaches the screen rather than what is
 * stored. And the report leads with <b>tail</b> statistics, because a texture is judged by its worst
 * visible block, not by its average one.
 *
 * <p><b>Every statistic here is on the coverage-scaled scale</b>, and they are comparable to each
 * other because of it. This was not always true: the mean used to divide the coverage-scaled sum by
 * the total weight rather than the visible-pixel count, which cancelled the scaling and returned the
 * mean alone to the raw scale. The two were then reported side by side in the same units. It showed
 * up on a real profile as textures passing a p99 gate of 1.0 while reporting a mean of 1.97 — a 99th
 * percentile below the mean, which a non-negative distribution cannot produce. Recorded in
 * {@code docs/evidence/2026-07-26-first-real-profile-run.md}.
 *
 * <p>The consequence worth knowing: a mostly-transparent texture measures low here even when its
 * colours are badly wrong, because those colours barely reach the screen. That is the intent, not an
 * oversight. What it means in practice is that these numbers describe a texture <em>as composited</em>,
 * and cannot be read as a statement about the stored pixels.
 */
public final class TextureFidelity {
    /** Below this Delta-E, a difference is held to be imperceptible. */
    public static final double JUST_NOTICEABLE = 1.0;
    /** Above this Delta-E, a difference is visible at a glance. */
    public static final double OBVIOUS = 2.0;

    private static final int HISTOGRAM_BINS = 2000;
    private static final double BIN_WIDTH = 0.05;
    private static final double[] LINEAR = buildLinearTable();

    private TextureFidelity() {
    }

    /**
     * Per-texture fidelity.
     *
     * @param visiblePixels pixels with any opacity; fully transparent pixels are excluded throughout
     * @param meanDeltaE mean coverage-scaled perceptual error over the visible pixels; on the same
     *     scale as {@link #p99DeltaE} and {@link #maxDeltaE}, so the three can be compared
     * @param p99DeltaE the error the worst 1% of visible pixels exceed
     * @param maxDeltaE the single worst visible pixel
     * @param imperceptibleFraction share of visible pixels under {@link #JUST_NOTICEABLE}
     * @param obviousFraction share of visible pixels over {@link #OBVIOUS}
     * @param maxAlphaError largest absolute alpha difference, in 0..255
     */
    public record Report(
            long visiblePixels,
            double meanDeltaE,
            double p99DeltaE,
            double maxDeltaE,
            double imperceptibleFraction,
            double obviousFraction,
            int maxAlphaError) {

        /** True when even the worst 1% of pixels stays under the just-noticeable threshold. */
        public boolean perceptuallyLossless() {
            return p99DeltaE < JUST_NOTICEABLE && maxAlphaError <= 2;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("visiblePixels", visiblePixels);
            values.put("meanDeltaE", round(meanDeltaE));
            values.put("p99DeltaE", round(p99DeltaE));
            values.put("maxDeltaE", round(maxDeltaE));
            values.put("imperceptibleFraction", round(imperceptibleFraction));
            values.put("obviousFraction", round(obviousFraction));
            values.put("maxAlphaError", maxAlphaError);
            values.put("perceptuallyLossless", perceptuallyLossless());
            return values;
        }

        private static double round(double value) {
            return Math.round(value * 10000.0) / 10000.0;
        }
    }

    /**
     * Compares two equally sized ARGB images.
     *
     * @throws IllegalArgumentException if the images differ in length
     */
    public static Report compare(int[] original, int[] candidate) {
        if (original == null || candidate == null || original.length != candidate.length) {
            throw new IllegalArgumentException("images must be non-null and the same length");
        }
        long[] histogram = new long[HISTOGRAM_BINS + 1];
        long visible = 0;
        double weightedSum = 0;
        double max = 0;
        int maxAlphaError = 0;

        double[] originalLab = new double[3];
        double[] candidateLab = new double[3];
        for (int i = 0; i < original.length; i++) {
            int left = original[i];
            int right = candidate[i];
            int leftAlpha = left >>> 24;
            maxAlphaError = Math.max(maxAlphaError, Math.abs(leftAlpha - (right >>> 24)));
            if (leftAlpha == 0) {
                continue;
            }
            visible++;
            double deltaE = weightedDeltaE(left, right, originalLab, candidateLab);
            weightedSum += deltaE;
            max = Math.max(max, deltaE);
            histogram[Math.min(HISTOGRAM_BINS, (int) (deltaE / BIN_WIDTH))]++;
        }

        if (visible == 0) {
            return new Report(0, 0, 0, 0, 1, 0, maxAlphaError);
        }
        return new Report(
                visible,
                weightedSum / visible,
                percentile(histogram, visible, 0.99),
                max,
                fractionBelow(histogram, visible, JUST_NOTICEABLE),
                1.0 - fractionBelow(histogram, visible, OBVIOUS),
                maxAlphaError);
    }

    /**
     * The same error {@link #compare} aggregates, kept per pixel.
     *
     * <p>This exists so that anything wanting to <em>show</em> where a texture lost fidelity uses the
     * measurement the gate is stated in, rather than a second opinion that happens to look similar. A
     * difference image drawn from an unrelated metric would disagree with the number beside it, and the
     * disagreement would be invisible.
     *
     * @return one alpha-weighted Delta-E per pixel; fully transparent pixels are 0, matching their
     *     exclusion from the aggregates
     * @throws IllegalArgumentException if the images differ in length
     */
    public static double[] deltaEMap(int[] original, int[] candidate) {
        if (original == null || candidate == null || original.length != candidate.length) {
            throw new IllegalArgumentException("images must be non-null and the same length");
        }
        double[] map = new double[original.length];
        double[] originalLab = new double[3];
        double[] candidateLab = new double[3];
        for (int i = 0; i < original.length; i++) {
            map[i] = weightedDeltaE(original[i], candidate[i], originalLab, candidateLab);
        }
        return map;
    }

    /**
     * Scaled by coverage. A colour error under alpha 10 contributes about 4% of itself to what is
     * composited on screen; counting it at full strength would let the invisible fringe of every sprite
     * dominate the tail statistics, which is a measurement artefact and not something anyone can see.
     *
     * <p>The scratch arrays are arguments so the per-pixel loops above do not allocate two objects per
     * pixel of a 2048-square texture.
     */
    private static double weightedDeltaE(int left, int right, double[] leftLab, double[] rightLab) {
        int alpha = left >>> 24;
        if (alpha == 0) {
            return 0;
        }
        toLab(left, leftLab);
        toLab(right, rightLab);
        double dl = leftLab[0] - rightLab[0];
        double da = leftLab[1] - rightLab[1];
        double db = leftLab[2] - rightLab[2];
        return Math.sqrt(dl * dl + da * da + db * db) * (alpha / 255.0);
    }

    private static double percentile(long[] histogram, long total, double quantile) {
        long target = (long) Math.ceil(total * quantile);
        long seen = 0;
        for (int bin = 0; bin < histogram.length; bin++) {
            seen += histogram[bin];
            if (seen >= target) {
                return bin * BIN_WIDTH;
            }
        }
        return HISTOGRAM_BINS * BIN_WIDTH;
    }

    private static double fractionBelow(long[] histogram, long total, double threshold) {
        int limit = Math.min(histogram.length, (int) (threshold / BIN_WIDTH));
        long seen = 0;
        for (int bin = 0; bin < limit; bin++) {
            seen += histogram[bin];
        }
        return (double) seen / total;
    }

    /** sRGB to CIE L*a*b* under the D65 white point. */
    private static void toLab(int argb, double[] lab) {
        double r = LINEAR[(argb >> 16) & 0xFF];
        double g = LINEAR[(argb >> 8) & 0xFF];
        double b = LINEAR[argb & 0xFF];
        double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
        double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
        double fx = pivot(x);
        double fy = pivot(y);
        double fz = pivot(z);
        lab[0] = 116.0 * fy - 16.0;
        lab[1] = 500.0 * (fx - fy);
        lab[2] = 200.0 * (fy - fz);
    }

    private static double pivot(double value) {
        return value > 216.0 / 24389.0
                ? Math.cbrt(value)
                : (24389.0 / 27.0 * value + 16.0) / 116.0;
    }

    private static double[] buildLinearTable() {
        double[] table = new double[256];
        for (int i = 0; i < 256; i++) {
            double channel = i / 255.0;
            table[i] = channel <= 0.04045
                    ? channel / 12.92
                    : Math.pow((channel + 0.055) / 1.055, 2.4);
        }
        return table;
    }
}

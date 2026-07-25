package dev.starsector.preflight.core;

/**
 * Converts sRGB to Oklab, a perceptually uniform colour space, for use as an <em>optimisation</em>
 * objective where a numeric distance needs to track how different two colours look.
 *
 * <p>The problem it solves is that RGB distance is not visual distance. Lightness rises roughly as
 * the cube root of luminance, so a fixed step in RGB is far more visible near black than near white;
 * an encoder minimising plain squared RGB error therefore misallocates precision, spending it on
 * bright regions where the error would have been invisible and starving dark ones where it shows.
 * Per-channel weights (the usual {@code 2:4:1} luminance stand-in) reduce that but cannot fix it,
 * because the distortion is a nonlinearity, not a scaling.
 *
 * <p>Oklab is chosen over CIELAB for this role deliberately. It is materially better behaved for
 * blues and for blends between distant colours — which is exactly what a block encoder does when it
 * interpolates between two endpoints — and it is cheaper, needing no white-point division or piecewise
 * pivot. Its lightness is scaled to roughly {@code [0, 1]} rather than CIELAB's {@code [0, 100]}, so
 * distances here are not Delta-E values and are not interchangeable with them.
 *
 * <p>That separation is the point. {@link TextureFidelity} scores results in CIELAB Delta-E against
 * published perceptibility thresholds, and it is deliberately not the metric being optimised, so a
 * measured improvement is not simply an encoder marking its own homework.
 *
 * <p>Reference: Björn Ottosson, "A perceptual color space for image processing" (2020),
 * <a href="https://bottosson.github.io/posts/oklab/">bottosson.github.io/posts/oklab</a>.
 */
public final class Oklab {
    /** sRGB 8-bit to linear light, the one part of the transform that a table can cover exactly. */
    private static final double[] LINEAR = buildLinearTable();

    private Oklab() {
    }

    /**
     * Converts one sRGB colour, writing {@code L, a, b} into {@code out}.
     *
     * @param out a scratch array of length 3, overwritten; supplied by the caller so a hot loop does
     *     not allocate per pixel
     */
    public static void fromSrgb(int r, int g, int b, double[] out) {
        double red = LINEAR[r];
        double green = LINEAR[g];
        double blue = LINEAR[b];

        double longCone = 0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue;
        double mediumCone = 0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue;
        double shortCone = 0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue;

        double l = Math.cbrt(longCone);
        double m = Math.cbrt(mediumCone);
        double s = Math.cbrt(shortCone);

        out[0] = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s;
        out[1] = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        out[2] = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
    }

    /** Squared perceptual distance between two colours already converted by {@link #fromSrgb}. */
    public static double squaredDistance(double[] first, int firstOffset, double[] second, int secondOffset) {
        double dl = first[firstOffset] - second[secondOffset];
        double da = first[firstOffset + 1] - second[secondOffset + 1];
        double db = first[firstOffset + 2] - second[secondOffset + 2];
        return dl * dl + da * da + db * db;
    }

    private static double[] buildLinearTable() {
        double[] table = new double[256];
        for (int i = 0; i < table.length; i++) {
            double channel = i / 255.0;
            table[i] = channel <= 0.04045
                    ? channel / 12.92
                    : Math.pow((channel + 0.055) / 1.055, 2.4);
        }
        return table;
    }
}

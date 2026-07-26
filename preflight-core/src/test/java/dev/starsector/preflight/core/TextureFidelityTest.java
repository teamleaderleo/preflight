package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextureFidelityTest {
    @Test
    void reportsZeroForIdenticalImages() {
        int[] image = {0xFF102030, 0xFF405060, 0x80708090, 0x00000000};
        TextureFidelity.Report report = TextureFidelity.compare(image, image);
        assertEquals(0.0, report.maxDeltaE());
        assertEquals(3L, report.visiblePixels(), "the fully transparent pixel is not visible");
        assertTrue(report.perceptuallyLossless());
    }

    @Test
    void ignoresColourUnderFullyTransparentPixels() {
        int[] original = {0x00FF0000};
        int[] candidate = {0x0000FF00};
        TextureFidelity.Report report = TextureFidelity.compare(original, candidate);
        assertEquals(0L, report.visiblePixels());
        assertEquals(0, report.maxAlphaError());
        assertTrue(report.perceptuallyLossless(), "an invisible colour difference is not a difference");
    }

    @Test
    void scalesErrorByCoverage() {
        // The same colour error under low alpha contributes proportionally less to what is
        // composited, so it must be scored proportionally lower.
        int[] opaqueBefore = {0xFF000000};
        int[] opaqueAfter = {0xFFFFFFFF};
        int[] faintBefore = {0x10000000};
        int[] faintAfter = {0x10FFFFFF};

        double opaque = TextureFidelity.compare(opaqueBefore, opaqueAfter).maxDeltaE();
        double faint = TextureFidelity.compare(faintBefore, faintAfter).maxDeltaE();
        assertTrue(faint < opaque / 10.0,
                "alpha 16 should score far below alpha 255: " + faint + " vs " + opaque);
    }

    @Test
    void flagsAnObviousDifference() {
        int[] original = new int[256];
        int[] candidate = new int[256];
        java.util.Arrays.fill(original, 0xFF204080);
        java.util.Arrays.fill(candidate, 0xFF208040);
        TextureFidelity.Report report = TextureFidelity.compare(original, candidate);
        assertTrue(report.maxDeltaE() > TextureFidelity.OBVIOUS, "was " + report.maxDeltaE());
        assertEquals(1.0, report.obviousFraction(), 0.001);
        assertTrue(!report.perceptuallyLossless());
    }

    @Test
    void catchesAlphaDamageEvenWhenColourIsPerfect() {
        int[] original = {0xFF808080, 0xC0808080};
        int[] candidate = {0xFF808080, 0x40808080};
        TextureFidelity.Report report = TextureFidelity.compare(original, candidate);
        assertEquals(0x80, report.maxAlphaError());
        assertTrue(!report.perceptuallyLossless(), "a wrecked alpha channel is not lossless");
    }

    @Test
    void keepsTheMeanOnTheSameScaleAsTheTail() {
        // Every pixel identical, so the distribution is a single point and mean, p99 and max must all
        // land on it. The mean used to divide by the total alpha weight rather than the pixel count,
        // which cancelled the coverage scaling and left it alone on the raw scale -- roughly ten times
        // the tail here. On a real profile that produced textures reporting a 99th percentile below
        // their own mean, which is impossible, and let them pass a gate their mean exceeded.
        int[] original = new int[400];
        int[] candidate = new int[400];
        java.util.Arrays.fill(original, (26 << 24) | 0x000000);
        java.util.Arrays.fill(candidate, (26 << 24) | 0x8080FF);

        TextureFidelity.Report report = TextureFidelity.compare(original, candidate);

        assertEquals(400, report.visiblePixels());
        assertEquals(report.maxDeltaE(), report.meanDeltaE(), 0.001,
                "a uniform error must give the same mean and max; a mismatch means one of them is "
                        + "not coverage-scaled");
        assertTrue(report.meanDeltaE() <= report.p99DeltaE() + BIN_WIDTH,
                "the mean cannot exceed the 99th percentile of the same distribution, was mean "
                        + report.meanDeltaE() + " against p99 " + report.p99DeltaE());
    }

    @Test
    void scalesTheMeanByCoverageSoInvisibleErrorStaysInvisible() {
        // The same colour error, once at full opacity and once at 10%. The faint copy barely reaches
        // the screen, so it must measure far lower -- this is the property that makes the gate a claim
        // about the composited result rather than about stored bytes.
        int[] original = new int[64];
        int[] candidate = new int[64];
        java.util.Arrays.fill(original, 0xFF000000);
        java.util.Arrays.fill(candidate, 0xFF8080FF);
        double opaque = TextureFidelity.compare(original, candidate).meanDeltaE();

        java.util.Arrays.fill(original, (26 << 24));
        java.util.Arrays.fill(candidate, (26 << 24) | 0x8080FF);
        double faint = TextureFidelity.compare(original, candidate).meanDeltaE();

        assertEquals(opaque * (26 / 255.0), faint, 0.001,
                "coverage scaling must be exactly linear in alpha");
    }

    private static final double BIN_WIDTH = 0.05;

    @Test
    void rejectsMismatchedImages() {
        assertThrows(IllegalArgumentException.class,
                () -> TextureFidelity.compare(new int[4], new int[5]));
        assertThrows(IllegalArgumentException.class, () -> TextureFidelity.compare(null, new int[4]));
    }
}

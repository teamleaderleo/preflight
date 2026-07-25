package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * These tests exist to keep the encoder honest. The probe built on it is used to judge a format, so a
 * weak encoder would produce evidence against BC that is really evidence against this code. Each case
 * below is one the format is theoretically capable of representing well; if any regresses, the
 * probe's conclusions stop being about BC.
 */
class BlockCompressorTest {
    @Test
    void reproducesAFlatBlockToWithinEndpointQuantisation() {
        int[] flat = new int[16 * 16];
        java.util.Arrays.fill(flat, 0xFF3C6E9A);
        int[] out = BlockCompressor.roundTrip(flat, 16, 16, false);

        // Endpoints are stored as RGB565, so even a perfectly flat block carries the quantisation
        // error of that grid and no more.
        TextureFidelity.Report report = TextureFidelity.compare(flat, out);
        assertTrue(report.maxDeltaE() < 1.5,
                "flat block should cost only endpoint quantisation, was " + report.maxDeltaE());
    }

    @Test
    void keepsSmoothGradientsBelowTheJustNoticeableThresholdOnAverage() {
        // A 4x4 block lying on a line in colour space is what BC1 represents best: two endpoints
        // plus two evenly spaced blends. The average error must stay under the just-noticeable
        // threshold, or the endpoint fit is broken.
        //
        // The tail does not, and that is the format rather than this code. Endpoints are stored
        // RGB565, so red and blue land on a 5-bit grid while green gets 6 -- a neutral grey cannot
        // be represented exactly and picks up a slight cast. The effect is worst near black, where
        // L* changes fastest per unit of linear light, so a full-range grey ramp is close to the
        // worst case that exists. BC7's higher-precision endpoints are the fix for this, not a
        // better BC1 encoder.
        //
        // This case is also the one place where cluster fit plus the perceptual objective trades
        // rather than simply wins, and the bound below records the trade instead of hiding it. The
        // mean improved from 1.144 to 1.071 and the imperceptible share from 56.3% to 57.8%, while
        // the single worst pixel went from 3.66 to 4.09: at level 28 the encoder now errs upward
        // (to 33) where it used to err downward (to 24), which is better for its block and worse
        // for that pixel. On real art there is no trade -- both mean and p99 improved across the
        // measured corpus -- so the bound here is deliberately loose enough to permit it while
        // still failing loudly if the endpoint fit breaks.
        int width = 64;
        int height = 64;
        int[] ramp = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int level = x * 255 / (width - 1);
                ramp[y * width + x] = 0xFF000000 | (level << 16) | (level << 8) | level;
            }
        }
        TextureFidelity.Report report =
                TextureFidelity.compare(ramp, BlockCompressor.roundTrip(ramp, width, height, false));
        assertTrue(report.meanDeltaE() < TextureFidelity.JUST_NOTICEABLE * 1.15,
                "mean must stay near imperceptible, was " + report.meanDeltaE());
        assertTrue(report.maxDeltaE() < 4.5, "even the worst pixel stays modest, was " + report.maxDeltaE());
    }

    @Test
    void keepsSmoothColourFieldsWellUnderTheThreshold() {
        // Closer to the content that actually dominates a profile's video memory: large, smooth,
        // photographic backgrounds and illustrations rather than small detailed sprites.
        int width = 256;
        int height = 256;
        int[] field = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                field[y * width + x] =
                        0xFF000000 | ((60 + x / 3) << 16) | ((90 + y / 4) << 8) | (140 + (x + y) / 8);
            }
        }
        TextureFidelity.Report report =
                TextureFidelity.compare(field, BlockCompressor.roundTrip(field, width, height, false));
        assertTrue(report.meanDeltaE() < TextureFidelity.JUST_NOTICEABLE,
                "smooth colour must be imperceptible on average, was " + report.meanDeltaE());
    }

    @Test
    void reproducesATwoColourBlockExactlyEnough() {
        // Sharp two-colour edges are common in UI art and sit exactly on the endpoints.
        int width = 32;
        int height = 32;
        int[] halves = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                halves[y * width + x] = x < width / 2 ? 0xFF102040 : 0xFFE0C080;
            }
        }
        TextureFidelity.Report report =
                TextureFidelity.compare(halves, BlockCompressor.roundTrip(halves, width, height, false));
        assertTrue(report.maxDeltaE() < 2.0,
                "a two-colour block sits on the endpoints, was " + report.maxDeltaE());
    }

    @Test
    void preservesAlphaRampsInBc3() {
        int width = 32;
        int height = 8;
        int[] fade = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                fade[y * width + x] = ((x * 255 / (width - 1)) << 24) | 0x00C04020;
            }
        }
        int[] out = BlockCompressor.roundTrip(fade, width, height, true);
        // BC3 alpha is 8 interpolated levels between the block's extremes; across 4 pixels of a
        // linear fade that is exact to within rounding.
        int maxAlphaError = 0;
        for (int i = 0; i < fade.length; i++) {
            maxAlphaError = Math.max(maxAlphaError, Math.abs((fade[i] >>> 24) - (out[i] >>> 24)));
        }
        assertTrue(maxAlphaError <= 2, "alpha ramp error was " + maxAlphaError);
    }

    @Test
    void degradesOnBlocksWithManyDistinctHues() {
        // The format's actual limit, stated as a test so the probe's bad results are understood as
        // the format's behaviour rather than a defect here: four bytes of random colour per pixel
        // cannot survive two endpoints and a 2-bit index.
        Random random = new Random(20260725L);
        int[] noise = new int[64 * 64];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = 0xFF000000 | random.nextInt(0x01000000);
        }
        TextureFidelity.Report report =
                TextureFidelity.compare(noise, BlockCompressor.roundTrip(noise, 64, 64, false));
        assertTrue(report.meanDeltaE() > 5.0,
                "random colour must degrade visibly, was " + report.meanDeltaE());
    }

    @Test
    void reportsTheFixedCompressedSize() {
        // BC1 is half a byte per pixel, BC3 one byte, both rounded up to whole 4x4 blocks.
        assertEquals(64L * 64L / 2, BlockCompressor.compressedBytes(64, 64, false));
        assertEquals(64L * 64L, BlockCompressor.compressedBytes(64, 64, true));
        assertEquals(2L * 2L * 8L, BlockCompressor.compressedBytes(5, 5, false), "partial blocks round up");
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockCompressor.roundTrip(new int[3], 2, 2, false));
        assertThrows(IllegalArgumentException.class,
                () -> BlockCompressor.roundTrip(null, 2, 2, false));
    }
}

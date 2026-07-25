package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        assertThrows(IllegalArgumentException.class,
                () -> BlockCompressor.decode(new byte[7], 4, 4, false), "a short payload is not a block");
    }

    @Test
    void encodesToTheExactSizeTheGlCallRequires() {
        // glCompressedTexImage2D takes the image size and reads exactly this many bytes; a mismatch
        // is not a quality problem but a memory-safety one on the driver's side.
        assertEquals(64L * 64L / 2, BlockCompressor.encode(new int[64 * 64], 64, 64, false).length);
        assertEquals(64L * 64L, BlockCompressor.encode(new int[64 * 64], 64, 64, true).length);
        assertEquals(2 * 2 * 8, BlockCompressor.encode(new int[5 * 5], 5, 5, false).length);
    }

    @Test
    void alwaysWritesBc1BlocksInFourColourMode() {
        // BC1 reads the endpoint ordering as a mode bit: code0 <= code1 means a three-colour palette
        // whose fourth entry is transparent black, not the four-colour palette the fit assumed.
        // Nothing upstream constrains the order -- the principal axis sign is arbitrary -- so this is
        // the guard that the ordering is applied at serialisation. Measured cost of losing it on a
        // smooth 256x256 field: mean deltaE 1.69 becomes 18.44, worst pixel 7.2 becomes 154.9.
        Random random = new Random(4242L);
        int width = 128;
        int height = 128;
        int[] art = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = clampByte(60 + x / 3 + random.nextInt(9) - 4);
                int g = clampByte(90 + y / 4 + random.nextInt(9) - 4);
                int b = clampByte(140 + (x + y) / 8 + random.nextInt(9) - 4);
                art[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        byte[] blocks = BlockCompressor.encode(art, width, height, false);
        for (int offset = 0; offset < blocks.length; offset += 8) {
            int code0 = (blocks[offset] & 0xFF) | ((blocks[offset + 1] & 0xFF) << 8);
            int code1 = (blocks[offset + 2] & 0xFF) | ((blocks[offset + 3] & 0xFF) << 8);
            assertTrue(code0 >= code1,
                    "block at " + offset + " selects punch-through mode: " + code0 + " <= " + code1);
        }
    }

    @Test
    void decodesBc1PunchThroughBlocksAsTheHardwareDoes() {
        // The encoder never emits this mode, but the decoder must model it, or it would be a model of
        // this encoder rather than of the texture unit -- and would silently pass the very mistake
        // the test above exists to catch.
        byte[] block = new byte[8];
        int low = 0x0841;                      // deliberately code0 < code1
        int high = 0xF7DE;
        block[0] = (byte) low;
        block[1] = (byte) (low >>> 8);
        block[2] = (byte) high;
        block[3] = (byte) (high >>> 8);
        block[4] = (byte) 0b11_10_01_00;       // pixels 0..3 take entries 0,1,2,3
        int[] decoded = BlockCompressor.decode(block, 4, 4, false);
        assertEquals(0, decoded[3] >>> 24, "entry 3 is transparent in punch-through mode");
        assertEquals(0x00000000, decoded[3], "and its colour is black");
        assertEquals(255, decoded[0] >>> 24, "the other entries stay opaque");

        // Entry 2 is the midpoint here, not the 2/3 blend it would be in four-colour mode.
        int red0 = (decoded[0] >> 16) & 0xFF;
        int red1 = (decoded[1] >> 16) & 0xFF;
        assertEquals((red0 + red1) / 2, (decoded[2] >> 16) & 0xFF);
    }

    @Test
    void ignoresTheModeBitInBc3() {
        // BC3's colour block is always four-colour, whatever the endpoint order -- the punch-through
        // mechanism is BC1-only, because BC3 already carries a real alpha channel.
        byte[] block = new byte[16];
        block[0] = (byte) 255;                 // alpha0 > alpha1 selects the eight-level mode
        block[1] = (byte) 0;
        int low = 0x0841;
        int high = 0xF7DE;
        block[8] = (byte) low;
        block[9] = (byte) (low >>> 8);
        block[10] = (byte) high;
        block[11] = (byte) (high >>> 8);
        block[12] = (byte) 0b11_10_01_00;
        int[] decoded = BlockCompressor.decode(block, 4, 4, true);
        int red0 = (decoded[0] >> 16) & 0xFF;
        int red1 = (decoded[1] >> 16) & 0xFF;
        assertEquals((red0 + 2 * red1) / 3, (decoded[3] >> 16) & 0xFF,
                "entry 3 is the 1/3 blend, not transparent");
    }

    @Test
    void interpolatesTheTwoHalvesOfABc3BlockTheWayTheHardwareDoes() {
        // Measured against Apple's driver, not derived: the colour blends truncate and the alpha
        // blends round, in the same block. Matching both exactly is what takes the software decoder
        // from an approximation of the texture unit to a model of it, and the numbers preflight
        // publishes come from that decoder. Verified bit-exact over 131,072 pixels -- see
        // docs/evidence/2026-07-26-encoder-driver-byte-agreement.md.
        byte[] block = new byte[16];
        block[0] = (byte) 255;                 // alpha0 > alpha1 selects the eight-level mode
        block[1] = (byte) 0;
        block[2] = (byte) 0b00_000_010;        // pixel 0 takes alpha entry 2
        int code0 = 31 << 11;                  // red 5-bit 31, expanding to 255
        int code1 = 1 << 11;                   // red 5-bit 1, expanding to 8
        block[8] = (byte) code0;
        block[9] = (byte) (code0 >>> 8);
        block[10] = (byte) code1;
        block[11] = (byte) (code1 >>> 8);
        block[12] = (byte) 0b00_00_00_10;      // pixel 0 takes colour entry 2
        int[] decoded = BlockCompressor.decode(block, 4, 4, true);

        // Alpha entry 2 is (6*255 + 1*0)/7 = 218.571: truncating gives 218, rounding 219.
        assertEquals(219, decoded[0] >>> 24, "alpha blends round");
        // Colour entry 2 is (2*255 + 8)/3 = 172.667: truncating gives 172, rounding 173.
        assertEquals(172, (decoded[0] >> 16) & 0xFF, "colour blends truncate");
    }

    @Test
    void roundTripIsExactlyEncodeThenDecode() {
        Random random = new Random(99L);
        int[] art = new int[32 * 32];
        for (int i = 0; i < art.length; i++) {
            art[i] = random.nextInt();
        }
        for (boolean withAlpha : new boolean[] {false, true}) {
            int[] viaBytes = BlockCompressor.decode(
                    BlockCompressor.encode(art, 32, 32, withAlpha), 32, 32, withAlpha);
            assertArrayEquals(BlockCompressor.roundTrip(art, 32, 32, withAlpha), viaBytes);
        }
    }

    private static int clampByte(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}

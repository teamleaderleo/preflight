package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlockTextureBakerTest {
    private static final String HASH = Hashes.sha256(new byte[] {9});

    @Test
    void spendsTheExtraByteOnlyWhereThereIsAlphaToKeep() {
        int[] opaque = flat(0xff204060, 8, 8);
        int[] withAlpha = flat(0xff204060, 8, 8);
        withAlpha[17] = 0x80204060;

        assertEquals(BlockTexture.Format.BC1, BlockTextureBaker.chooseFormat(opaque));
        assertEquals(BlockTexture.Format.BC3, BlockTextureBaker.chooseFormat(withAlpha));

        // The whole point of choosing per texture: BC3 is exactly twice the cost.
        BlockTexture cheap = BlockTextureBaker.bake(HASH, opaque, 8, 8, BlockTextureBaker.Mips.NONE);
        BlockTexture dear = BlockTextureBaker.bake(HASH, withAlpha, 8, 8, BlockTextureBaker.Mips.NONE);
        assertEquals(2 * cheap.blockBytes(), dear.blockBytes());
    }

    @Test
    void reportsTheFootprintTheFormatIsTradedAgainst() {
        BlockTexture texture = BlockTextureBaker.bake(HASH, flat(0xff808080, 64, 64), 64, 64,
                BlockTextureBaker.Mips.NONE);

        assertEquals(64 * 64 * 4, texture.uncompressedBytes());
        assertEquals(64 * 64 / 2, texture.blockBytes(), "BC1 is half a byte per pixel");
    }

    @Test
    void measuresALossItCanDefend() {
        // A flat block is the case BC1 represents exactly: two identical endpoints and one index.
        // If this ever stops being lossless the encoder has regressed in a way no ratio would show.
        BlockTexture texture = BlockTextureBaker.bake(HASH, flat(0xff3366aa, 16, 16), 16, 16,
                BlockTextureBaker.Mips.NONE);

        assertEquals(0.0, texture.fidelity().maxDeltaE(), 1e-9);
        assertTrue(texture.fidelity().perceptuallyLossless());
        assertEquals(256, texture.fidelity().visiblePixels());
    }

    @Test
    void doesNotBleedTransparentBlackIntoMipmappedEdges() {
        // The classic mip bug: averaging straight RGB weights a fully transparent pixel's colour as
        // heavily as an opaque one. Real art stores transparent pixels as black, so a naive filter
        // darkens every sprite edge a little more at each level.
        //
        // Columns 0-2 are opaque white and the rest transparent black. The boundary is deliberately
        // at an odd column, so destination column 1 averages one opaque and one transparent pixel and
        // actually exercises the mixed case; an even boundary would leave every destination pixel
        // wholly inside one region and prove nothing.
        int width = 8;
        int height = 8;
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = x < 3 ? 0xffffffff : 0x00000000;
            }
        }

        BlockTexture texture = BlockTextureBaker.bake(HASH, pixels, width, height,
                BlockTextureBaker.Mips.FULL_CHAIN);
        int[] level1 = BlockCompressor.decode(texture.level(1), 4, 4, texture.format().withAlpha());

        int straddling = level1[1];
        int alpha = straddling >>> 24;
        // Bounds rather than an exact value: BC3 stores alpha on eight interpolated levels, so the
        // half-transparent pixel lands on whichever of those is nearest, not on 128 exactly.
        assertTrue(alpha > 60 && alpha < 200, "alpha should fall toward half at the boundary: " + alpha);
        assertTrue((straddling & 0xff) > 0xf0, "colour should stay white, not drift toward black");
    }

    @Test
    void keepsAnOddDimensionsLastColumnInsteadOfDroppingIt() {
        // Truncating instead of clamping would discard the last column and shift the image half a
        // pixel per level, which compounds down the chain.
        int width = 5;
        int height = 4;
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = i % width == width - 1 ? 0xffff0000 : 0xff000000;
        }

        BlockTexture texture = BlockTextureBaker.bake(HASH, pixels, width, height,
                BlockTextureBaker.Mips.FULL_CHAIN);
        int[] level1 = BlockCompressor.decode(texture.level(1), 2, 2, texture.format().withAlpha());

        assertTrue(((level1[1] >>> 16) & 0xff) > 0x40, "the red column must survive into level 1");
    }

    @Test
    void bakesTheChainDownToASinglePixel() {
        BlockTexture texture = BlockTextureBaker.bake(HASH, flat(0xff112233, 16, 8), 16, 8,
                BlockTextureBaker.Mips.FULL_CHAIN);

        assertEquals(5, texture.levelCount());
        assertEquals(1, texture.levelWidth(4));
        assertEquals(1, texture.levelHeight(4));
        assertEquals(BlockCompressor.CODEC_VERSION, texture.codecVersion());
    }

    @Test
    void survivesTheDegenerateArtThatRealModsContain() {
        // Blank and one-pixel sprites exist in real mods. A fully transparent image has no visible
        // pixel to measure, and a 1x1 image is smaller than a block; neither should reach an
        // arithmetic edge in the fidelity pass or the padding in the encoder.
        BlockTexture blank = BlockTextureBaker.bake(HASH, flat(0x00000000, 4, 4), 4, 4,
                BlockTextureBaker.Mips.FULL_CHAIN);
        assertEquals(0, blank.fidelity().visiblePixels());
        assertEquals(0.0, blank.fidelity().meanDeltaE(), 0.0, "no visible pixels must not mean NaN");

        BlockTexture single = BlockTextureBaker.bake(HASH, flat(0xff123456, 1, 1), 1, 1,
                BlockTextureBaker.Mips.FULL_CHAIN);
        assertEquals(1, single.levelCount());
        assertEquals(BlockCompressor.BC1_BLOCK_BYTES, single.blockBytes(), "a sub-block image still pays one block");
    }

    @Test
    void rejectsPixelsThatDoNotMatchTheDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockTextureBaker.bake(HASH, new int[15], 4, 4, BlockTextureBaker.Mips.NONE));
    }

    private static int[] flat(int argb, int width, int height) {
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, argb);
        return pixels;
    }
}

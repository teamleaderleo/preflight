package com.fs.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the padding behaviour of the repository-owned {@code TextureLoader} fixture against what the
 * installed loader actually does, recorded in
 * {@code docs/evidence/2026-07-26-padding-sites-in-the-installed-loader.md}.
 *
 * <p>The installed class computes power-of-two dimensions twice over: an extracted {@code get2Fold}
 * that sizes the {@code glTexImage2D} allocation, and the same algorithm inlined at the head of the
 * conversion method that sizes the upload buffer. They are two halves of one invariant — change
 * either alone and the buffer and the allocation disagree about the same texture. The fixture models
 * both separately for that reason, and these tests are what would notice if they stopped agreeing.
 */
class TextureLoaderPaddingTest {
    @BeforeEach
    void reset() {
        TextureLoader.reset();
        L.reset();
    }

    @Test
    void padsAOnePixelEdgeToTwo() throws Exception {
        // get2Fold seeds at 2 and never returns less. This is the case the fixture used to get wrong,
        // and getting it wrong is what let two integration tests pass for seventeen commits while the
        // agent was quietly declining every texture they asked it to serve.
        TextureLoader.Result result = new TextureLoader().loadPixelsForTest("graphics/missing.png");

        assertEquals(2, result.uploadWidth());
        assertEquals(2, result.uploadHeight());
        assertEquals(2 * 2 * 4, result.pixels().length,
                "a 1x1 ARGB source must upload as a 2x2 RGBA buffer");
    }

    @Test
    void sizesTheBufferAndTheAllocationIdentically() throws Exception {
        // loadPixelsForTest sizes its requirement through the extracted implementation while the
        // buffer was sized by the inlined one, and throws when the buffer is short. Reaching this
        // assertion at all means the two agreed.
        for (int edge : new int[] {1, 2, 3, 5, 8, 9}) {
            TextureLoader.reset();
            L.preload(opaque(edge, edge));

            TextureLoader.Result result = new TextureLoader().loadPixelsForTest("graphics/test.png");

            int expected = fold(edge);
            assertEquals(expected, result.uploadWidth(), "width for a " + edge + "px edge");
            assertEquals(expected, result.uploadHeight(), "height for a " + edge + "px edge");
            assertEquals(expected * expected * 4, result.pixels().length,
                    "upload bytes for a " + edge + "px edge");
        }
    }

    @Test
    void paddingIsTheDifferenceBetweenTheSourceAndTheUpload() throws Exception {
        // 3x3 pads to 4x4, so 7 of the 16 pixels are allocated and never sampled. This is the waste
        // the 1.86 GiB figure is made of, at the scale of one texture.
        L.preload(opaque(3, 3));

        TextureLoader.Result result = new TextureLoader().loadPixelsForTest("graphics/test.png");

        assertEquals(4, result.uploadWidth());
        assertEquals(4, result.uploadHeight());
        int uploaded = result.uploadWidth() * result.uploadHeight();
        assertTrue(uploaded > 3 * 3, "a 3x3 source must allocate more than it uses");
        assertEquals(7, uploaded - 3 * 3, "pixels allocated but never sampled");
    }

    /** An independent restatement of get2Fold, so the test does not inherit the fixture's copy. */
    private static int fold(int value) {
        int result = 2;
        while (result < value) {
            result *= 2;
        }
        return result;
    }

    private static BufferedImage opaque(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFF204080);
            }
        }
        return image;
    }
}

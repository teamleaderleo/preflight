package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GpuTextureFootprintTest {
    @Test
    void roundsUpToThePowerOfTwoTheEngineAllocates() {
        assertEquals(2, GpuTextureFootprint.uploadDimension(2));
        assertEquals(4, GpuTextureFootprint.uploadDimension(3));
        assertEquals(4, GpuTextureFootprint.uploadDimension(4));
        assertEquals(8, GpuTextureFootprint.uploadDimension(5));
        assertEquals(512, GpuTextureFootprint.uploadDimension(288));
        assertEquals(512, GpuTextureFootprint.uploadDimension(384));
    }

    @Test
    void padsASinglePixelEdgeToTwo() {
        // Slick's get2Fold seeds at 2 and never returns less, so get2Fold(1) == 2. A plain
        // "next power of two" returns 1 here, which would size an upload buffer at half what the
        // engine reads.
        assertEquals(2, GpuTextureFootprint.uploadDimension(1));
    }

    @Test
    void rejectsUnrepresentableDimensions() {
        assertEquals(-1, GpuTextureFootprint.uploadDimension(0));
        assertEquals(-1, GpuTextureFootprint.uploadDimension(-1));
        assertEquals(-1, GpuTextureFootprint.uploadDimension((1 << 30) + 1));
        assertEquals(-1, GpuTextureFootprint.residentBytes(16, -1));
        assertEquals(-1, GpuTextureFootprint.residentBytesWithMipChain(-1, 16));
    }

    @Test
    void reproducesTheCommunityOnslaughtFigure() {
        // Dark.Revenant, Fractal forum, July 2019: a 288x384 Onslaught sprite is "512x512 pixels
        // with 4 bytes per pixel and an overall 4/3 increase in size due to the mipmapping",
        // "at least 1365 KB on a graphics device".
        assertEquals(512L * 512L * 4L, GpuTextureFootprint.residentBytes(288, 384));
        assertEquals(1365, GpuTextureFootprint.residentBytesWithMipChain(288, 384) / 1024);
    }

    @Test
    void countsWhatThePaddingWastes() {
        // A 1230x1230 sprite occupies a 2048x2048 box: 64 percent of it is never sampled.
        long resident = GpuTextureFootprint.residentBytes(1230, 1230);
        assertEquals(2048L * 2048L * 4L, resident);
        assertEquals(resident - 1230L * 1230L * 4L, GpuTextureFootprint.paddingBytes(1230, 1230));
        // An already-power-of-two sprite wastes nothing.
        assertEquals(0L, GpuTextureFootprint.paddingBytes(1024, 512));
    }
}

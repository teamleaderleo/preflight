package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.PreparedTexture;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.Objects;

/**
 * Materialises the readable surface every prepared-pixel carrier is built on.
 *
 * <p>There used to be a second, lightweight option here: a 1x1 raster paired with a carrier that
 * reported the texture's real dimensions. It was removed on 2026-08-01 rather than kept behind a
 * flag, because a carrier that lies about its size is only safe while exactly one consumer ever
 * sees it, and that stopped being true when this mode took the prefetch bypass. See
 * {@code TexturePreparedPixelRuntime#servesUnreadableCarriers()}.
 */
final class TexturePreparedPixelCarrierSurface {
    private TexturePreparedPixelCarrierSurface() {
    }

    static Surface coherent(PreparedTexture texture) {
        Objects.requireNonNull(texture, "texture");
        int width = texture.originalWidth();
        int height = texture.originalHeight();
        int channels = texture.channels();
        int stride = Math.multiplyExact(width, channels);
        int expected = Math.multiplyExact(stride, height);
        if (expected != texture.pixelBytes()) {
            throw new IllegalArgumentException(
                    "Prepared carrier source length is " + texture.pixelBytes() + "; expected " + expected);
        }

        // SPFT v1 stores OpenGL-ready source rows bottom-up. BufferedImage rasters are
        // addressed top-down, so materialize the same pixels with only the row order changed.
        byte[] bottomUp = texture.pixels();
        byte[] topDown = new byte[bottomUp.length];
        for (int sourceRow = 0; sourceRow < height; sourceRow++) {
            int targetRow = height - 1 - sourceRow;
            System.arraycopy(bottomUp, sourceRow * stride, topDown, targetRow * stride, stride);
        }

        boolean alpha = channels == 4;
        ColorModel colorModel = new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_sRGB),
                alpha ? new int[] {8, 8, 8, 8} : new int[] {8, 8, 8},
                alpha,
                false,
                alpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE,
                DataBuffer.TYPE_BYTE);
        DataBufferByte data = new DataBufferByte(topDown, topDown.length);
        WritableRaster raster = Raster.createInterleavedRaster(
                data,
                width,
                height,
                stride,
                channels,
                alpha ? new int[] {0, 1, 2, 3} : new int[] {0, 1, 2},
                null);
        return new Surface(colorModel, raster, topDown.length, true);
    }

    record Surface(ColorModel colorModel, WritableRaster raster, int rasterBytes, boolean coherent) {
        Surface {
            Objects.requireNonNull(colorModel, "colorModel");
            Objects.requireNonNull(raster, "raster");
            if (!colorModel.isCompatibleRaster(raster)) {
                throw new IllegalArgumentException("Carrier color model and raster are incompatible");
            }
            if (rasterBytes < 0) {
                throw new IllegalArgumentException("rasterBytes must be non-negative");
            }
        }
    }
}

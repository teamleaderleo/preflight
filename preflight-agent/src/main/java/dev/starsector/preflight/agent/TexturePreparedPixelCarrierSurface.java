package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.PreparedTexture;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.ColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Builds the readable surfaces prepared-pixel carriers use.
 *
 * <p>The lightweight surface is still full-size and truthful; only its storage is virtualized onto
 * the immutable bottom-up SPFT array. The conventional surface exists for lazy compatibility with
 * consumers that ask for a concrete writable raster. This is deliberately not the old 1x1 token
 * carrier, whose reported and addressable dimensions disagreed and which crashed a raster-walking
 * game consumer after the prefetch bypass widened its reach.
 */
final class TexturePreparedPixelCarrierSurface {
    private TexturePreparedPixelCarrierSurface() {
    }

    static Surface coherent(PreparedTexture texture) {
        Layout layout = layout(texture);
        int width = layout.width();
        int height = layout.height();
        int channels = layout.channels();
        int stride = layout.stride();

        // SPFT v1 stores OpenGL-ready source rows bottom-up. BufferedImage rasters are
        // addressed top-down, so materialize the same pixels with only the row order changed.
        //
        // Read through a view rather than pixels(): the flip already allocates a second array the
        // size of the texture, and cloning the source to read it once makes that a third.
        ByteBuffer bottomUp = texture.pixelsView();
        byte[] topDown = new byte[texture.pixelBytes()];
        for (int sourceRow = 0; sourceRow < height; sourceRow++) {
            int targetRow = height - 1 - sourceRow;
            bottomUp.position(sourceRow * stride);
            bottomUp.get(topDown, targetRow * stride, stride);
        }

        ColorModel colorModel = colorModel(channels);
        DataBufferByte data = new DataBufferByte(topDown, topDown.length);
        WritableRaster raster = Raster.createInterleavedRaster(
                data,
                width,
                height,
                stride,
                channels,
                bands(channels),
                null);
        return new Surface(colorModel, raster, topDown.length, true);
    }

    /**
     * Builds a full-size readable raster without copying the bottom-up prepared pixels.
     *
     * <p>The sample model addresses ordinary top-down rows. The data buffer maps those linear
     * offsets onto the immutable bottom-up SPFT view. Callers therefore observe the same pixels as
     * {@link #coherent(PreparedTexture)}, while the normal upload path pays no second heap array.
     * A carrier materialises the conventional {@link DataBufferByte} surface lazily before it
     * exposes a raster to third-party code, preserving the concrete buffer behavior of the old
     * implementation at that compatibility boundary.
     */
    static Surface lazy(PreparedTexture texture) {
        Layout layout = layout(texture);
        ComponentSampleModel samples = new ComponentSampleModel(
                DataBuffer.TYPE_BYTE,
                layout.width(),
                layout.height(),
                layout.channels(),
                layout.stride(),
                bands(layout.channels()));
        DataBuffer data = new BottomUpDataBuffer(
                texture.pixelsView(), layout.stride(), layout.height());
        WritableRaster raster = Raster.createWritableRaster(samples, data, null);
        return new Surface(colorModel(layout.channels()), raster, 0, true);
    }

    private static Layout layout(PreparedTexture texture) {
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
        return new Layout(width, height, channels, stride);
    }

    private static ColorModel colorModel(int channels) {
        boolean alpha = channels == 4;
        return new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_sRGB),
                alpha ? new int[] {8, 8, 8, 8} : new int[] {8, 8, 8},
                alpha,
                false,
                alpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE,
                DataBuffer.TYPE_BYTE);
    }

    private static int[] bands(int channels) {
        return channels == 4 ? new int[] {0, 1, 2, 3} : new int[] {0, 1, 2};
    }

    private record Layout(int width, int height, int channels, int stride) {
    }

    private static final class BottomUpDataBuffer extends DataBuffer {
        private final ByteBuffer bottomUp;
        private final int stride;
        private final int height;

        private BottomUpDataBuffer(ByteBuffer bottomUp, int stride, int height) {
            super(DataBuffer.TYPE_BYTE, Math.multiplyExact(stride, height));
            this.bottomUp = Objects.requireNonNull(bottomUp, "bottomUp").asReadOnlyBuffer();
            this.stride = stride;
            this.height = height;
        }

        @Override
        public int getElem(int bank, int index) {
            if (bank != 0 || index < 0 || index >= getSize()) {
                throw new ArrayIndexOutOfBoundsException(index);
            }
            int row = index / stride;
            int column = index - row * stride;
            int bottomUpIndex = (height - 1 - row) * stride + column;
            return bottomUp.get(bottomUpIndex) & 0xff;
        }

        @Override
        public void setElem(int bank, int index, int value) {
            // CarrierImage materialises before exposing a writable raster or any mutation API.
            // Reaching this means a new inherited mutation seam escaped that boundary.
            throw new UnsupportedOperationException("lazy prepared texture raster is read-only");
        }
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

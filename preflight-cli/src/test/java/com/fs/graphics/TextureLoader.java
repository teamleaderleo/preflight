package com.fs.graphics;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.nio.ByteBuffer;

/** Repository-owned fixture with the reviewed decoded-image and prepared-pixel descriptors. */
public final class TextureLoader {
    private static int originalCalls;
    private static int originalConversionCalls;
    private static int originalCleanupCalls;
    private static boolean failAfterConversion;
    private static byte[] originalUpload;

    private BufferedImage Ô00000(String logicalPath) {
        BufferedImage preloaded = L.clazz(logicalPath);
        if (preloaded != null) {
            return preloaded;
        }
        originalCalls++;
        BufferedImage fallback = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        fallback.setRGB(0, 0, 0xffcc00cc);
        return fallback;
    }

    private ByteBuffer o00000(BufferedImage image, Object texture) {
        originalConversionCalls++;
        // The installed loader does not call o00000(int) here. It writes the same doubling loop out
        // by hand, once per axis, and that inlined copy is what sizes the upload buffer. Keeping the
        // duplication is the point: the two implementations must agree about every texture, and a
        // model that shared one of them could not show a padding change breaking that agreement.
        int uploadWidth = 2;
        while (uploadWidth < image.getWidth()) {
            uploadWidth *= 2;
        }
        int uploadHeight = 2;
        while (uploadHeight < image.getHeight()) {
            uploadHeight *= 2;
        }
        texture.Ô00000(uploadHeight);
        texture.Ó00000(uploadWidth);
        Raster raster = image.getData();
        int[] pixel = raster.getPixel(0, 0, (int[]) null);
        int red = pixel.length > 0 ? pixel[0] : 0;
        int green = pixel.length > 1 ? pixel[1] : 0;
        int blue = pixel.length > 2 ? pixel[2] : 0;
        texture.derived0 = new Color(red, green, blue, 255);
        texture.derived1 = Color.GREEN;
        texture.derived2 = Color.BLUE;
        byte[] configured = originalUpload;
        if (configured != null) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(configured.length);
            buffer.put(configured).flip();
            return buffer;
        }
        return convertPowerOfTwoUpload(image, uploadWidth, uploadHeight);
    }

    private static ByteBuffer convertPowerOfTwoUpload(BufferedImage image, int uploadWidth, int uploadHeight) {
        int width = image.getWidth();
        int height = image.getHeight();
        int channels = image.getColorModel().hasAlpha() ? 4 : 3;
        int uploadStride = uploadWidth * channels;
        byte[] upload = new byte[uploadStride * uploadHeight];
        for (int uploadRow = 0; uploadRow < height; uploadRow++) {
            int imageY = height - 1 - uploadRow;
            int rowOffset = uploadRow * uploadStride;
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, imageY);
                int offset = rowOffset + x * channels;
                upload[offset] = (byte) ((argb >>> 16) & 0xff);
                upload[offset + 1] = (byte) ((argb >>> 8) & 0xff);
                upload[offset + 2] = (byte) (argb & 0xff);
                if (channels == 4) {
                    upload[offset + 3] = (byte) ((argb >>> 24) & 0xff);
                }
            }
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(upload.length);
        buffer.put(upload).flip();
        return buffer;
    }

    public static void o00000(ByteBuffer buffer, String logicalPath) {
        originalCleanupCalls++;
    }

    public BufferedImage loadForTest(String logicalPath) {
        return Ô00000(logicalPath);
    }

    public Result loadPixelsForTest(String logicalPath) {
        BufferedImage image = Ô00000(logicalPath);
        Object texture = new Object();
        ByteBuffer buffer = o00000(image, texture);
        // The installed loader sizes its glTexImage2D allocation through the *extracted* o00000(int),
        // while the buffer above was sized by the inlined copy. This check is where the two meet, so
        // it is the model of the invariant that any padding change has to preserve.
        int channels = image.getColorModel().hasAlpha() ? 4 : 3;
        int required = Math.multiplyExact(
                Math.multiplyExact(o00000(image.getWidth()), o00000(image.getHeight())),
                channels);
        if (buffer.remaining() < required) {
            throw new IllegalArgumentException(
                    "Number of remaining buffer elements is " + buffer.remaining()
                            + ", must be at least " + required);
        }
        if (failAfterConversion) {
            throw new IllegalStateException("synthetic upload failure");
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        o00000(buffer, logicalPath);
        return new Result(
                bytes,
                texture.derived0 == null ? 0 : texture.derived0.getRGB(),
                texture.derived1 == null ? 0 : texture.derived1.getRGB(),
                texture.derived2 == null ? 0 : texture.derived2.getRGB(),
                texture.uploadWidth,
                texture.uploadHeight);
    }

    public static int originalCalls() {
        return originalCalls;
    }

    public static int originalConversionCalls() {
        return originalConversionCalls;
    }

    public static int originalCleanupCalls() {
        return originalCleanupCalls;
    }

    public static void setFailAfterConversion(boolean value) {
        failAfterConversion = value;
    }

    public static void setOriginalUpload(byte[] value) {
        originalUpload = value == null ? null : value.clone();
    }

    public static void reset() {
        originalCalls = 0;
        originalConversionCalls = 0;
        originalCleanupCalls = 0;
        failAfterConversion = false;
        originalUpload = null;
    }

    /**
     * Slick's {@code get2Fold}, as the installed loader extracts it: seed at two, double while short.
     * It cannot return less than two, which is why a one-pixel edge allocates a two-pixel one.
     *
     * <p>Written out here rather than delegated to {@code GpuTextureFootprint}. The value of this
     * fixture is being an independent model of the engine; sharing the production implementation
     * would make the two agree by construction and stop the tests from being able to disagree.
     */
    private int o00000(int value) {
        int fold = 2;
        while (fold < value) {
            fold *= 2;
        }
        return fold;
    }

    public record Result(
            byte[] pixels,
            int color0,
            int color1,
            int color2,
            int uploadWidth,
            int uploadHeight) {
        public Result {
            pixels = pixels.clone();
        }

        @Override
        public byte[] pixels() {
            return pixels.clone();
        }
    }
}

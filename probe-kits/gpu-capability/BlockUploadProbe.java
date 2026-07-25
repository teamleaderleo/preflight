import dev.starsector.preflight.core.BlockCompressor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

/**
 * Checks that the blocks {@link BlockCompressor} writes are the blocks the graphics hardware reads.
 *
 * <p>Every fidelity number preflight publishes about block compression comes from decoding the
 * encoder's output with preflight's own decoder. That is circular on its own: a byte layout could be
 * wrong in a way both halves agree on, and the measurement would look perfect right up until the
 * texture reached a GPU. The endpoint-ordering rule is the concrete example — BC1 reads the order of
 * the two stored colours as a mode bit, and an encoder that ignores it produces blocks that decode
 * correctly in software and turn to garbage on hardware.
 *
 * <p>So this uploads real encoded blocks through {@code glCompressedTexImage2D}, reads them back
 * decompressed through {@code glGetTexImage}, and compares against the software decoder. The driver
 * is the authority here, not this code.
 *
 * <p>Exact agreement is not required to pass, and its absence is not a defect. The S3TC
 * specification defines the two interpolated palette entries as weighted averages without pinning
 * the rounding, so implementations legitimately differ by a unit or so per channel. A layout error
 * looks nothing like that: it produces wholly different colours, not off-by-one ones.
 *
 * <p>Read-only: an offscreen pbuffer and two textures. No window, no game launch.
 */
public final class BlockUploadProbe {
    private static final int GL_COMPRESSED_RGB_S3TC_DXT1 = 0x83F0;
    private static final int GL_COMPRESSED_RGBA_S3TC_DXT5 = 0x83F3;

    private static final int SIZE = 256;
    /** Per-channel deviation above which a difference stops being rounding and starts being a bug. */
    private static final int ROUNDING_TOLERANCE = 8;

    public static void main(String[] args) {
        try {
            System.exit(new BlockUploadProbe().run() ? 0 : 1);
        } catch (Throwable failure) {
            System.out.println("PROBE FAILED: " + failure);
            failure.printStackTrace(System.out);
            System.exit(2);
        }
    }

    private boolean run() throws Exception {
        Pbuffer buffer = new Pbuffer(64, 64, new PixelFormat(8, 24, 0), null);
        buffer.makeCurrent();
        try {
            System.out.println("renderer: " + GL11.glGetString(GL11.GL_RENDERER));
            System.out.println("version:  " + GL11.glGetString(GL11.GL_VERSION));
            System.out.println();
            int[] image = testImage();
            boolean bc1 = check("BC1 (DXT1, opaque)", image, GL_COMPRESSED_RGB_S3TC_DXT1, false);
            boolean bc3 = check("BC3 (DXT5, with alpha)", image, GL_COMPRESSED_RGBA_S3TC_DXT5, true);
            boolean passed = bc1 && bc3;
            System.out.println(passed
                    ? "VERDICT: the encoder's byte layout matches this driver's decoder."
                    : "VERDICT: MISMATCH — the encoded blocks are not what the hardware reads.");
            return passed;
        } finally {
            buffer.destroy();
        }
    }

    /**
     * Content chosen so a layout error cannot hide. Flat blocks survive almost any misreading, so the
     * image mixes a smooth gradient, per-pixel noise that forces distinct indices within a block,
     * hard edges that pin pixels to the endpoints, and an alpha ramp for BC3's separate alpha block.
     */
    private static int[] testImage() {
        java.util.Random random = new java.util.Random(20260726L);
        int[] image = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int red = clamp(40 + x / 2 + random.nextInt(21) - 10);
                int green = clamp(70 + y / 2 + random.nextInt(21) - 10);
                int blue = clamp(120 + (x + y) / 4 + random.nextInt(21) - 10);
                if ((x / 16 + y / 16) % 2 == 0 && x % 16 < 3) {
                    red = 250;
                    green = 12;
                    blue = 200;
                }
                int alpha = clamp(x * 255 / (SIZE - 1));
                image[y * SIZE + x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
        }
        return image;
    }

    private boolean check(String label, int[] image, int format, boolean withAlpha) {
        byte[] blocks = BlockCompressor.encode(image, SIZE, SIZE, withAlpha);
        int[] software = BlockCompressor.decode(blocks, SIZE, SIZE, withAlpha);

        int name = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
        drainErrors();
        ByteBuffer upload = ByteBuffer.allocateDirect(blocks.length).order(ByteOrder.nativeOrder());
        upload.put(blocks).flip();
        GL13.glCompressedTexImage2D(GL11.GL_TEXTURE_2D, 0, format, SIZE, SIZE, 0, upload);
        int uploadError = GL11.glGetError();
        if (uploadError != GL11.GL_NO_ERROR) {
            System.out.printf("%-24s upload rejected, error=0x%04X — cannot compare.%n",
                    label, uploadError);
            GL11.glDeleteTextures(name);
            return false;
        }

        ByteBuffer readback = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder());
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readback);
        int readError = GL11.glGetError();
        GL11.glDeleteTextures(name);
        if (readError != GL11.GL_NO_ERROR) {
            System.out.printf("%-24s readback failed, error=0x%04X — cannot compare.%n", label, readError);
            return false;
        }

        long identical = 0;
        int worstChannel = 0;
        long deviationSum = 0;
        int worstIndex = -1;
        for (int i = 0; i < software.length; i++) {
            int driverRed = readback.get(i * 4) & 0xFF;
            int driverGreen = readback.get(i * 4 + 1) & 0xFF;
            int driverBlue = readback.get(i * 4 + 2) & 0xFF;
            int driverAlpha = readback.get(i * 4 + 3) & 0xFF;
            int ours = software[i];
            int deviation = Math.max(
                    Math.max(Math.abs(driverRed - ((ours >> 16) & 0xFF)),
                            Math.abs(driverGreen - ((ours >> 8) & 0xFF))),
                    Math.max(Math.abs(driverBlue - (ours & 0xFF)),
                            Math.abs(driverAlpha - (ours >>> 24))));
            if (deviation == 0) {
                identical++;
            }
            if (deviation > worstChannel) {
                worstChannel = deviation;
                worstIndex = i;
            }
            deviationSum += deviation;
        }

        double exactShare = 100.0 * identical / software.length;
        double meanDeviation = (double) deviationSum / software.length;
        boolean passed = worstChannel <= ROUNDING_TOLERANCE;
        System.out.printf("%-24s pixels=%d  exact=%.2f%%  mean dev=%.3f  worst dev=%d%n",
                label, software.length, exactShare, meanDeviation, worstChannel);
        if (worstChannel > 0 && worstIndex >= 0) {
            int ours = software[worstIndex];
            System.out.printf("%-24s   worst pixel (%d,%d): driver %02X%02X%02X%02X vs ours %02X%02X%02X%02X%n",
                    "", worstIndex % SIZE, worstIndex / SIZE,
                    readback.get(worstIndex * 4 + 3) & 0xFF, readback.get(worstIndex * 4) & 0xFF,
                    readback.get(worstIndex * 4 + 1) & 0xFF, readback.get(worstIndex * 4 + 2) & 0xFF,
                    ours >>> 24, (ours >> 16) & 0xFF, (ours >> 8) & 0xFF, ours & 0xFF);
        }
        System.out.printf("%-24s   %s%n", "", passed
                ? (worstChannel == 0
                        ? "identical — layout and interpolation rounding both match."
                        : "within the spec's interpolation tolerance; layout is correct.")
                : "beyond rounding — the layout does not match. This is a bug in the encoder.");
        return passed;
    }

    private static void drainErrors() {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // Clear state left by earlier calls so the next read is unambiguous.
        }
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}

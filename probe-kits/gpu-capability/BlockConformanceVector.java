import dev.starsector.preflight.core.BlockCompressor;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Writes a self-contained conformance vector: encoded blocks, plus the pixels preflight's decoder
 * says those blocks mean.
 *
 * <p>This exists because the interesting driver is usually not the one you are sitting at. The
 * in-process check ({@code BlockUploadProbe}) needs LWJGL, which needs a window system, so it cannot
 * run in a headless container — which is exactly where a rented NVIDIA GPU lives. Splitting the
 * check in two removes that constraint: this half needs Java and no GPU, the other half needs a GPU
 * and no Java.
 *
 * <p>It also makes the check something a stranger can run. A Starsector player with a GeForce needs
 * a few hundred kilobytes and one small binary, not a JDK, a Maven build and a game installation.
 *
 * <p>The vector is deterministic — fixed seed, fixed content — so results from different machines
 * are directly comparable, and a difference is a fact about the driver rather than about the input.
 */
public final class BlockConformanceVector {
    private static final byte[] MAGIC = {'S', 'P', 'F', 'V'};
    private static final int VERSION = 1;
    private static final int SIZE = 256;

    private static final int GL_COMPRESSED_RGB_S3TC_DXT1 = 0x83F0;
    private static final int GL_COMPRESSED_RGBA_S3TC_DXT5 = 0x83F3;

    public static void main(String[] args) throws IOException {
        Path target = Path.of(args.length > 0 ? args[0] : "block-conformance-vector.bin");
        int[] image = testImage();
        try (OutputStream stream = Files.newOutputStream(target);
                DataOutputStream out = new DataOutputStream(stream)) {
            out.write(MAGIC);
            writeInt(out, VERSION);
            writeInt(out, 2);
            writeCase(out, "BC1 (DXT1, opaque)", GL_COMPRESSED_RGB_S3TC_DXT1, image, false);
            writeCase(out, "BC3 (DXT5, with alpha)", GL_COMPRESSED_RGBA_S3TC_DXT5, image, true);
        }
        System.out.println("Wrote " + target.toAbsolutePath() + " (" + Files.size(target) + " bytes)");
        System.out.println("Check it against a driver with block-conformance-probe.");
    }

    private static void writeCase(DataOutputStream out, String name, int format, int[] image,
            boolean withAlpha) throws IOException {
        byte[] blocks = BlockCompressor.encode(image, SIZE, SIZE, withAlpha);
        int[] expected = BlockCompressor.decode(blocks, SIZE, SIZE, withAlpha);
        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeInt(out, nameBytes.length);
        out.write(nameBytes);
        writeInt(out, format);
        writeInt(out, SIZE);
        writeInt(out, SIZE);
        writeInt(out, blocks.length);
        out.write(blocks);
        writeInt(out, expected.length * 4);
        // RGBA byte order, matching what glGetTexImage(GL_RGBA, GL_UNSIGNED_BYTE) returns.
        byte[] rgba = new byte[expected.length * 4];
        for (int i = 0; i < expected.length; i++) {
            int pixel = expected[i];
            rgba[i * 4] = (byte) (pixel >> 16);
            rgba[i * 4 + 1] = (byte) (pixel >> 8);
            rgba[i * 4 + 2] = (byte) pixel;
            rgba[i * 4 + 3] = (byte) (pixel >>> 24);
        }
        out.write(rgba);
    }

    /** Little-endian, so the C side can read it with a plain fread on every platform that matters. */
    private static void writeInt(DataOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    /**
     * Identical content to {@code BlockUploadProbe}, so the hosted result and the local one are
     * comparable: a gradient, per-pixel noise forcing distinct indices within a block, hard edges
     * pinning pixels to the endpoints, and an alpha ramp for BC3's separate alpha block. Flat blocks
     * survive almost any misreading, so none of it is flat.
     */
    private static int[] testImage() {
        Random random = new Random(20260726L);
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

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}

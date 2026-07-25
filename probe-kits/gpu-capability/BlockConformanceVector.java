import dev.starsector.preflight.core.BlockCompressor;
import dev.starsector.preflight.core.BlockConformanceVectorIO;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * That is what makes this the right vector for asking a question <em>about a driver</em>. To ask
 * whether a real baked cache survives a real driver, {@code preflight assets cache-conformance}
 * writes the same format from the profile's own art.
 *
 * <p>The layout itself lives in {@link BlockConformanceVectorIO}, so the two producers cannot drift
 * apart from each other or from the C reader.
 */
public final class BlockConformanceVector {
    private static final int SIZE = 256;

    private static final int GL_COMPRESSED_RGB_S3TC_DXT1 = 0x83F0;
    private static final int GL_COMPRESSED_RGBA_S3TC_DXT5 = 0x83F3;

    public static void main(String[] args) throws IOException {
        Path target = Path.of(args.length > 0 ? args[0] : "block-conformance-vector.bin");
        int[] image = testImage();
        try (OutputStream stream = Files.newOutputStream(target)) {
            BlockConformanceVectorIO.write(stream, List.of(
                    testCase("BC1 (DXT1, opaque)", GL_COMPRESSED_RGB_S3TC_DXT1, image, false),
                    testCase("BC3 (DXT5, with alpha)", GL_COMPRESSED_RGBA_S3TC_DXT5, image, true)));
        }
        System.out.println("Wrote " + target.toAbsolutePath() + " (" + Files.size(target) + " bytes)");
        System.out.println("Check it against a driver with block-conformance-probe.");
        System.out.println("For a vector made of this profile's own art instead, see "
                + "`preflight assets cache-conformance`.");
    }

    private static BlockConformanceVectorIO.Case testCase(
            String name, int format, int[] image, boolean withAlpha) {
        byte[] blocks = BlockCompressor.encode(image, SIZE, SIZE, withAlpha);
        return new BlockConformanceVectorIO.Case(
                name, format, SIZE, SIZE, blocks,
                BlockCompressor.decode(blocks, SIZE, SIZE, withAlpha));
    }

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

import dev.starsector.preflight.core.BlockCompressor;
import dev.starsector.preflight.core.GpuTextureFootprint;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

/**
 * Decomposes what loading a texture actually costs, so the claim that pre-encoded blocks belong on
 * the speed track can be checked rather than asserted.
 *
 * <p>Three pipelines are compared on the same real textures, in the JVM the game actually runs:
 *
 * <ol>
 *   <li><b>vanilla</b> — read the PNG, decode through ImageIO, walk the raster into an RGBA buffer,
 *       pad to a power of two, upload at 4 bytes per pixel.
 *   <li><b>prepared pixels</b> — preflight's existing cache: read a raw RGBA blob and upload it. No
 *       decode, but the blob is 4 bytes per pixel <em>on disk</em>.
 *   <li><b>block cache</b> — read pre-encoded BC blocks and upload them compressed. No decode, and
 *       the blob is an eighth the size.
 * </ol>
 *
 * <p>The two halves run as separate processes on purpose. On macOS, AWT (which ImageIO drags in)
 * and LWJGL both want the process's main thread, and once AWT has initialised CoreFoundation,
 * creating a GL context segfaults inside it -- deferring the GL work to a later phase of the same
 * JVM is not enough, so {@code decode} and {@code upload} are separate runs joined by a handoff
 * file. That costs nothing in fidelity: upload time is a function of how many bytes cross the bus,
 * not of what those bytes contain, so the upload phase sends correctly sized buffers rather than
 * the real ones.
 *
 * <p>GL work is asynchronous, so every upload is followed by {@code glFinish} before the clock is
 * read; without it the driver queues the call and the measurement is of nothing.
 */
public final class TextureLoadPipelineProbe {

    /** What phase one learned about one texture, and all phase two needs to size its uploads. */
    private record Texture(int paddedWidth, int paddedHeight, int blockBytes) { }

    private static final int WARMUP = 12;
    private static final int BC3_DXT5 = 0x83F3;

    public static void main(String[] args) throws Exception {
        if (args[0].equals("upload")) {
            upload(Path.of(args[1]));
            return;
        }
        Path handoff = Path.of(args[1]);
        Path root = Path.of(args[2]);
        int wanted = args.length > 3 ? Integer.parseInt(args[3]) : 200;
        int minEdge = args.length > 4 ? Integer.parseInt(args[4]) : 0;

        List<Path> all = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".png")).forEach(all::add);
        }
        all.sort(Comparator.comparing(Path::toString));
        List<Path> sample = new ArrayList<>();
        int stride = Math.max(1, all.size() / Math.max(1, wanted));
        for (int i = 0; i < all.size() && sample.size() < wanted; i += stride) {
            sample.add(all.get(i));
        }

        // ---- phase one: everything that touches AWT ----
        long readNanos = 0;
        long decodeNanos = 0;
        long rasterNanos = 0;
        long encodeNanos = 0;
        long pngBytes = 0;
        long rgbaBytes = 0;
        long blockBytes = 0;
        long residentRgba = 0;
        long pixels = 0;
        List<Texture> textures = new ArrayList<>();

        // Each stage runs as its own pass over the whole sample. Interleaving them makes the
        // read timing meaningless: decoding and encoding allocate heavily, so a read measured
        // between them is really measuring garbage collection and page-cache eviction. Measured
        // interleaved, reads looked like 742 us per file; measured in isolation, 63 us.
        List<byte[]> encodedFiles = new ArrayList<>();
        List<Path> kept = new ArrayList<>();
        for (Path file : sample) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                encodedFiles.add(bytes);
                kept.add(file);
            } catch (Exception ignored) {
                // Not a readable texture; it simply does not take part.
            }
        }
        // Warm the JIT and the page cache before the pass that counts.
        for (byte[] bytes : encodedFiles) {
            pngBytes += bytes.length;
        }
        pngBytes = 0;
        long r0 = System.nanoTime();
        for (Path file : kept) {
            pngBytes += Files.readAllBytes(file).length;
        }
        readNanos = System.nanoTime() - r0;

        List<BufferedImage> images = new ArrayList<>();
        long d0 = System.nanoTime();
        for (byte[] bytes : encodedFiles) {
            try {
                images.add(ImageIO.read(new java.io.ByteArrayInputStream(bytes)));
            } catch (Exception ignored) {
                images.add(null);
            }
        }
        decodeNanos = System.nanoTime() - d0;

        long rasterTotal = 0;
        for (BufferedImage image : images) {
            if (image == null) {
                continue;
            }
            int w = image.getWidth();
            int h = image.getHeight();
            if (w < 8 || h < 8 || (long) w * h > 4_000_000L
                    || (minEdge > 0 && Math.max(w, h) < minEdge)) {
                continue;
            }
            long t2b = System.nanoTime();
            int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
            int padWidth = GpuTextureFootprint.uploadDimension(w);
            int padHeight = GpuTextureFootprint.uploadDimension(h);
            ByteBuffer rgba = ByteBuffer.allocateDirect(padWidth * padHeight * 4)
                    .order(ByteOrder.nativeOrder());
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int pixel = argb[y * w + x];
                    int at = (y * padWidth + x) * 4;
                    rgba.put(at, (byte) (pixel >> 16));
                    rgba.put(at + 1, (byte) (pixel >> 8));
                    rgba.put(at + 2, (byte) pixel);
                    rgba.put(at + 3, (byte) (pixel >>> 24));
                }
            }
            rasterTotal += System.nanoTime() - t2b;

            long e0 = System.nanoTime();
            BlockCompressor.roundTrip(argb, w, h, true);
            encodeNanos += System.nanoTime() - e0;

            int blocks = (int) BlockCompressor.compressedBytes(padWidth, padHeight, true);
            rgbaBytes += (long) padWidth * padHeight * 4;
            blockBytes += blocks;
            residentRgba += GpuTextureFootprint.residentBytes(w, h);
            pixels += (long) w * h;
            textures.add(new Texture(padWidth, padHeight, blocks));
        }
        rasterNanos = rasterTotal;

        StringBuilder out = new StringBuilder();
        out.append(readNanos).append(' ').append(decodeNanos).append(' ').append(rasterNanos)
                .append(' ').append(encodeNanos).append(' ').append(pngBytes).append(' ')
                .append(rgbaBytes).append(' ').append(blockBytes).append(' ')
                .append(residentRgba).append(' ').append(pixels).append('\n');
        for (Texture texture : textures) {
            out.append(texture.paddedWidth()).append(' ').append(texture.paddedHeight())
                    .append(' ').append(texture.blockBytes()).append('\n');
        }
        Files.writeString(handoff, out.toString());
        System.out.printf("decoded %d textures, handoff written%n", textures.size());
    }

    /** Phase two: times the two upload paths in a fresh JVM that AWT has never touched. */
    private static void upload(Path handoff) throws Exception {
        List<String> lines = Files.readAllLines(handoff);
        String[] totals = lines.get(0).split(" ");
        long readNanos = Long.parseLong(totals[0]);
        long decodeNanos = Long.parseLong(totals[1]);
        long rasterNanos = Long.parseLong(totals[2]);
        long encodeNanos = Long.parseLong(totals[3]);
        long pngBytes = Long.parseLong(totals[4]);
        long rgbaBytes = Long.parseLong(totals[5]);
        long blockBytes = Long.parseLong(totals[6]);
        long residentRgba = Long.parseLong(totals[7]);
        long pixels = Long.parseLong(totals[8]);
        List<Texture> textures = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] parts = line.split(" ");
            textures.add(new Texture(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])));
        }

        Pbuffer buffer = new Pbuffer(64, 64, new PixelFormat(8, 24, 0), null);
        buffer.makeCurrent();
        System.out.println("GL  " + GL11.glGetString(GL11.GL_VERSION) + " / "
                + GL11.glGetString(GL11.GL_RENDERER));
        System.out.println("JVM " + System.getProperty("java.version") + " "
                + System.getProperty("os.arch"));
        System.out.println();

        int widest = 0;
        for (Texture texture : textures) {
            widest = Math.max(widest, texture.paddedWidth() * texture.paddedHeight() * 4);
        }
        ByteBuffer scratch = ByteBuffer.allocateDirect(widest).order(ByteOrder.nativeOrder());

        long uploadRgbaNanos = 0;
        long uploadBlockNanos = 0;
        for (int round = 0; round < 2; round++) {
            long rgbaTotal = 0;
            long blockTotal = 0;
            for (Texture texture : textures) {
                int name = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
                scratch.position(0).limit(texture.paddedWidth() * texture.paddedHeight() * 4);
                long a0 = System.nanoTime();
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, texture.paddedWidth(),
                        texture.paddedHeight(), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, scratch);
                GL11.glFinish();
                long a1 = System.nanoTime();
                GL11.glDeleteTextures(name);

                int blockName = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, blockName);
                scratch.position(0).limit(texture.blockBytes());
                long b0 = System.nanoTime();
                GL13.glCompressedTexImage2D(GL11.GL_TEXTURE_2D, 0, BC3_DXT5,
                        texture.paddedWidth(), texture.paddedHeight(), 0, scratch);
                GL11.glFinish();
                long b1 = System.nanoTime();
                GL11.glDeleteTextures(blockName);

                rgbaTotal += a1 - a0;
                blockTotal += b1 - b0;
            }
            // Keep only the second round; the first warms the driver's allocator.
            uploadRgbaNanos = rgbaTotal;
            uploadBlockNanos = blockTotal;
        }
        buffer.destroy();

        long vanilla = readNanos + decodeNanos + rasterNanos + uploadRgbaNanos;
        System.out.printf("textures %d, %,d source pixels%n%n", textures.size(), pixels);
        System.out.println("per-stage cost of a vanilla load");
        report("read PNG from disk", readNanos, vanilla);
        report("ImageIO decode", decodeNanos, vanilla);
        report("raster walk + pad to POT", rasterNanos, vanilla);
        report("glTexImage2D (RGBA)", uploadRgbaNanos, vanilla);
        System.out.println();
        report("glCompressedTexImage2D", uploadBlockNanos, vanilla);

        // Two separately measured constants stand in for disk, rather than one figure scaled by
        // size: reads have a fixed per-file cost and a per-byte cost, and a cache made of a few
        // large files is dominated by the second where a mod tree of many small ones is dominated
        // by the first. Both were measured on this machine (IoModel): 2.90 GB/s sequential, and
        // 63 us per file for textures inside the .app bundle -- itself 1.5-3.7x the 17 us the same
        // files cost outside it.
        double sequentialBytesPerNano = 2.90;
        long rgbaReadNanos = (long) (rgbaBytes / sequentialBytesPerNano);
        long blockReadNanos = (long) (blockBytes / sequentialBytesPerNano);
        long prepared = rgbaReadNanos + uploadRgbaNanos;
        long blockCache = blockReadNanos + uploadBlockNanos;

        System.out.printf("%nvanilla read measured at %.0f us/file over %d files%n",
                readNanos / 1000.0 / textures.size(), textures.size());
        System.out.println("cache pipelines modelled as one sequential pack at 2.90 GB/s");
        System.out.printf("%n%-18s %10s %13s %13s %10s%n",
                "pipeline", "load ms", "bytes read", "VRAM", "vs vanilla");
        line("vanilla", vanilla, pngBytes, residentRgba, vanilla);
        line("prepared pixels", prepared, rgbaBytes, residentRgba, vanilla);
        line("block cache", blockCache, blockBytes, blockBytes, vanilla);
        System.out.printf("%noffline bake cost (one time, parallelisable): %.0f ms for %,d pixels%n",
                encodeNanos / 1e6, pixels);
    }

    private static void report(String name, long nanos, long vanilla) {
        System.out.printf("  %-26s %8.1f ms  %5.1f%%%n", name, nanos / 1e6, 100.0 * nanos / vanilla);
    }

    private static void line(String name, long nanos, long read, long vram, long vanilla) {
        System.out.printf("%-18s %10.1f %13s %13s %9.2fx%n", name, nanos / 1e6, mib(read), mib(vram),
                (double) vanilla / nanos);
    }

    private static String mib(long bytes) {
        return String.format("%.1f MiB", bytes / 1048576.0);
    }
}

import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTexturePack;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.PreparedTexturePackOrderIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Fresh-process bundled-JVM comparison for all-LZ4 versus low-ratio-raw texture packs. */
final class HybridTexturePackBenchmark {
    private static final double RAW_BELOW_RATIO = 1.10;

    public static void main(String[] args) throws Exception {
        Path cache = Path.of(args[1]);
        String profile = args[2];
        Path manifestPath = cache.resolve("manifests").resolve(profile + ".spfm");
        TextureManifest manifest = TextureManifestIO.read(manifestPath);
        List<String> logical = manifest.entries().values().stream()
                .map(TextureManifest.Entry::blobRelativePath).distinct().toList();
        List<String> observed = PreparedTexturePackOrderIO.read(
                PreparedTexturePackOrderIO.path(cache, profile), profile);
        Path hybrid = Path.of(args[3]);
        if ("build".equals(args[0])) {
            List<String> order = hybridOrder(cache, logical, observed);
            PreparedTexturePackIO.write(hybrid, profile, cache, order);
            long rawEntries = order.stream().filter(path -> !path.endsWith("-lz4.spft")).count();
            long lz4Bytes = Files.size(PreparedTexturePackIO.path(cache, profile));
            System.out.printf(
                    "entries=%d rawEntries=%d lz4Bytes=%d hybridBytes=%d growthBytes=%d%n",
                    order.size(), rawEntries, lz4Bytes, Files.size(hybrid),
                    Files.size(hybrid) - lz4Bytes);
            return;
        }

        boolean useHybrid = "hybrid".equals(args[0]);
        List<String> expected = useHybrid ? hybridOrder(cache, logical, observed) : logical;
        List<String> reads = useHybrid ? selectedPaths(cache, observed) : observed;
        Path packPath = useHybrid ? hybrid : PreparedTexturePackIO.path(cache, profile);
        long checksum = 1;
        long pixels = 0;
        long started = System.nanoTime();
        try (PreparedTexturePack pack = PreparedTexturePackIO.open(
                packPath, profile, expected)) {
            for (String path : reads) {
                PreparedTexture texture = pack.readTrusted(path);
                ByteBuffer bytes = texture.pixelsView();
                pixels += bytes.remaining();
                checksum = checksum * 31 + texture.sourceSha256().hashCode();
                checksum = checksum * 31 + texture.uploadWidth();
                checksum = checksum * 31 + texture.uploadHeight();
                checksum = checksum * 31 + texture.channels();
                if (bytes.hasRemaining()) {
                    checksum = checksum * 31 + bytes.get(0);
                    checksum = checksum * 31 + bytes.get(bytes.remaining() / 2);
                    checksum = checksum * 31 + bytes.get(bytes.remaining() - 1);
                }
            }
        }
        System.out.printf("mode=%s entries=%d pixels=%d millis=%.3f checksum=%d%n",
                args[0], reads.size(), pixels, (System.nanoTime() - started) / 1e6, checksum);
    }

    private static List<String> hybridOrder(
            Path cache, List<String> logical, List<String> observed) throws Exception {
        LinkedHashSet<String> order = new LinkedHashSet<>();
        for (String path : observed) order.add(selected(cache, path));
        for (String path : logical) order.add(selected(cache, path));
        return List.copyOf(order);
    }

    private static List<String> selectedPaths(Path cache, List<String> paths) throws Exception {
        List<String> selected = new ArrayList<>(paths.size());
        for (String path : paths) selected.add(selected(cache, path));
        return List.copyOf(selected);
    }

    private static String selected(Path cache, String lz4) throws Exception {
        if (!lz4.endsWith("-lz4.spft")) return lz4;
        String raw = lz4.substring(0, lz4.length() - "-lz4.spft".length()) + ".spft";
        Path compressedPath = cache.resolve(lz4);
        Path rawPath = cache.resolve(raw);
        if (!Files.isRegularFile(rawPath)) return lz4;
        long stored = Files.size(compressedPath) - 120;
        long pixelBytes;
        try (RandomAccessFile file = new RandomAccessFile(compressedPath.toFile(), "r")) {
            file.seek(84);
            pixelBytes = Integer.toUnsignedLong(file.readInt());
        }
        return stored > 0 && pixelBytes / (double) stored < RAW_BELOW_RATIO ? raw : lz4;
    }
}

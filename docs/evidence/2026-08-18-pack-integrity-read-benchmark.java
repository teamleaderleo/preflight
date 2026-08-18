import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTexturePack;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.PreparedTexturePackOrderIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fresh-process replay for #695 integrity candidates.
 *
 * <p>Compile this source separately against each candidate's packaged preflight.jar, build a
 * dedicated pack with that same candidate, then run one read pass in a fresh bundled-game JVM.
 * The explicit PACK argument keeps v2/v3 files separate while every candidate consumes the same
 * loose blobs and retained startup order.
 *
 * <pre>
 * javac -cp preflight-cli/target/preflight.jar -d /tmp/preflight-pack-bench \
 *   docs/evidence/2026-08-18-pack-integrity-read-benchmark.java
 * java -cp /tmp/preflight-pack-bench:preflight-cli/target/preflight.jar \
 *   PackIntegrityReadBenchmark build CACHE PROFILE /tmp/candidate.spfp
 * java -cp /tmp/preflight-pack-bench:preflight-cli/target/preflight.jar \
 *   PackIntegrityReadBenchmark read CACHE PROFILE /tmp/candidate.spfp
 * </pre>
 *
 * <p>For the release comparison, run current-main trusted reads, #729 SHA-per-hit reads, and the
 * CRC32C candidate in alternating order with fresh JVMs. Report every observation and medians.
 */
final class PackIntegrityReadBenchmark {
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !("build".equals(args[0]) || "read".equals(args[0]))) {
            throw new IllegalArgumentException("Usage: build|read CACHE PROFILE PACK");
        }
        Path cache = Path.of(args[1]).toAbsolutePath().normalize();
        String profile = args[2];
        Path packPath = Path.of(args[3]).toAbsolutePath().normalize();

        TextureManifest manifest = TextureManifestIO.read(
                TextureManifestIO.directory(cache).resolve(profile + ".spfm"));
        List<String> logical = manifest.entries().values().stream()
                .map(TextureManifest.Entry::blobRelativePath)
                .distinct()
                .toList();
        Set<String> available = Set.copyOf(logical);

        // SPFO v1 is pack-format independent and the reviewed large-profile evidence lives in the
        // established packs namespace. Keep that same access evidence across v2/v3 candidates.
        Path orderPath = cache.resolve("packs").resolve(profile + ".spfo");
        List<String> observed = PreparedTexturePackOrderIO.read(orderPath, profile).stream()
                .filter(available::contains)
                .toList();

        LinkedHashSet<String> ordered = new LinkedHashSet<>(observed);
        ordered.addAll(logical);
        List<String> packOrder = List.copyOf(ordered);

        if ("build".equals(args[0])) {
            long started = System.nanoTime();
            PreparedTexturePackIO.write(packPath, profile, cache, packOrder);
            System.out.printf(
                    "mode=build format=%d entries=%d bytes=%d millis=%.3f pack=%s%n",
                    PreparedTexturePackIO.FORMAT_VERSION,
                    packOrder.size(),
                    Files.size(packPath),
                    (System.nanoTime() - started) / 1e6,
                    packPath);
            return;
        }

        long checksum = 1;
        long pixels = 0;
        long started = System.nanoTime();
        try (PreparedTexturePack pack = PreparedTexturePackIO.open(packPath, profile, logical)) {
            for (String path : observed) {
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
        System.out.printf(
                "mode=read format=%d entries=%d pixels=%d millis=%.3f checksum=%d pack=%s%n",
                PreparedTexturePackIO.FORMAT_VERSION,
                observed.size(),
                pixels,
                (System.nanoTime() - started) / 1e6,
                checksum,
                packPath);
    }
}

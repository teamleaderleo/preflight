package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.BlockCacheManifest;
import dev.starsector.preflight.core.BlockCacheManifestIO;
import dev.starsector.preflight.core.BlockConformanceVectorIO;
import dev.starsector.preflight.core.BlockTexture;
import dev.starsector.preflight.core.BlockTextureIO;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports a sample of a baked block cache as a conformance vector, so a real driver can check the
 * bytes preflight actually intends to upload.
 *
 * <p>This closes the one gap the synthetic harnesses structurally cannot. Everything else about the
 * cache is verified in-process: the encoder against its decoder, the blobs against their manifest,
 * the agent against a synthetic {@code com.fs.graphics.TextureLoader} in a child JVM. None of that
 * involves a GPU. A wrong internal-format constant, a wrong mip-level order or a wrong row order
 * would pass every one of those tests and fail only on a player's machine.
 *
 * <p>{@code BlockConformanceVector} already asks that question with a fixed synthetic image, and
 * {@code block-conformance-probe.c} already answers it on a driver with no JVM — including a rented
 * one. What was missing was pointing them at real output. The vector written here holds this
 * profile's own art, so the driver arbitrates the cache rather than a test pattern, and it is the
 * same format, so the C probe is unchanged and stays the already-verified one.
 *
 * <p>Every expected image is decoded here rather than copied from the bake, so the vector states what
 * this build believes. A vector that shipped the baker's opinion of its own output would agree with
 * itself by construction, which is the circularity this whole exercise exists to break.
 */
final class CacheConformanceVector {
    /**
     * Default size budget. The vector is dominated by uncompressed expected images — a single 2048px
     * texture contributes 16 MiB — and it is sent to a rented GPU as a function argument, so it has to
     * stay something you would attach to an email.
     */
    static final long DEFAULT_MAX_BYTES = 24L * 1024 * 1024;

    private CacheConformanceVector() {
    }

    static Map<String, Object> write(Path cacheDir, Path target, int samples, long maxBytes) throws IOException {
        BlockCacheManifest manifest = BlockCacheManifestIO.read(cacheDir.resolve(AssetLabCommand.BLOCK_CACHE_MANIFEST));
        List<BlockCacheManifest.Entry> all = new ArrayList<>(manifest.entries().values());
        List<String> paths = new ArrayList<>(manifest.entries().keySet());

        List<BlockConformanceVectorIO.Case> cases = new ArrayList<>();
        List<String> included = new ArrayList<>();
        long bytes = 0;
        long skippedTooLarge = 0;
        // An even stride rather than the first N, so the sample is not one mod's art -- and not, in a
        // path-sorted manifest, whichever mod happens to sort first.
        int stride = samples <= 0 || all.size() <= samples ? 1 : all.size() / samples;
        for (int i = 0; i < all.size() && (samples <= 0 || cases.size() < samples); i += stride) {
            BlockTexture texture = BlockTextureIO.read(cacheDir.resolve(all.get(i).blobRelativePath()));
            BlockConformanceVectorIO.Case value =
                    BlockConformanceVectorIO.Case.ofLevelZero(paths.get(i), texture);
            if (bytes + value.vectorBytes() > maxBytes) {
                skippedTooLarge++;
                continue;
            }
            bytes += value.vectorBytes();
            cases.add(value);
            included.add(paths.get(i));
        }
        if (cases.isEmpty()) {
            throw new IOException("No cached texture fits the " + maxBytes + " byte vector budget; "
                    + "raise --max-bytes or bake a cache containing smaller textures");
        }
        Path absolute = target.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        try (OutputStream stream = Files.newOutputStream(absolute)) {
            BlockConformanceVectorIO.write(stream, cases);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("cacheDir", cacheDir);
        report.put("vector", absolute);
        report.put("vectorBytes", Files.size(absolute));
        report.put("cachedTextures", (long) all.size());
        report.put("cases", (long) cases.size());
        report.put("skippedOverBudget", skippedTooLarge);
        report.put("codecVersion", (long) manifest.codecVersion());
        report.put("profileFingerprint", manifest.profileFingerprint());
        report.put("includedPaths", included);
        report.put("nextStep", "check it against a driver: ./probe-kits/gpu-capability/block-conformance-probe "
                + absolute + " -- or `modal run probe-kits/gpu-capability/modal-block-conformance.py` "
                + "after copying it to block-conformance-vector.bin");
        return report;
    }
}

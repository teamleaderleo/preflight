package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.BlockCacheManifest;
import dev.starsector.preflight.core.BlockCacheManifestIO;
import dev.starsector.preflight.core.BlockCompressor;
import dev.starsector.preflight.core.BlockTexture;
import dev.starsector.preflight.core.BlockTextureBaker;
import dev.starsector.preflight.core.BlockTextureIO;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.TextureFidelity;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import javax.imageio.ImageIO;

/**
 * Bakes a profile's art into a block cache: decode once, offline, and leave the GPU-ready bytes on
 * disk so the game never decodes a PNG again.
 *
 * <p>The measured case for doing this is that ImageIO decode is 67–70% of a texture load and the GPU
 * upload is under 3%, so eliminating the decode is worth far more than any footprint lever, and
 * happens to cut resident video memory fourfold on the way. See
 * {@code docs/evidence/2026-07-26-texture-load-pipeline-decomposition.md}.
 *
 * <p>The interesting part is not the encoding but the <em>refusal</em> to encode. Block compression
 * is lossy, so a cache that compressed everything would be trading quality it had not measured. Every
 * texture here is baked, measured, and then kept only if the loss came in under a stated threshold —
 * and a texture with no entry is not a failure, it simply keeps the ordinary path. This per-texture
 * policy is the reason an offline encoder is preferable to flipping the engine's internal format
 * constant, which would compress everything at whatever quality the driver felt like.
 *
 * <p>Nothing in the installation is read-modify-written. The cache is a new directory.
 */
final class BlockCacheBaker {
    /** Reported worst cases, ranked by the loss actually accepted. */
    private static final int WORST_REPORTED = 15;

    private BlockCacheBaker() {
    }

    /**
     * @param maxP99DeltaE a texture is cached only if the worst 1% of its visible pixels stays under
     *     this. The default is {@link TextureFidelity#JUST_NOTICEABLE}, so the cache holds only
     *     textures whose loss a person would have to be told about to find.
     * @param limit bake at most this many textures, or 0 for all; a full profile is a long offline job
     * @param mips bake a whole mip chain rather than level 0 alone
     * @param dryRun measure everything and write nothing
     */
    record Options(double maxP99DeltaE, int limit, boolean mips, boolean dryRun) {
    }

    /**
     * What a texture is <em>for</em>, which decides whether a perceptual colour metric means anything
     * about it.
     *
     * <p>A normal or material map stores vectors and scalars in RGB channels. It is never looked at;
     * it is sampled by a shader. Delta-E on one is not a perceptual measurement, so the gate below
     * would be admitting these on the strength of a number that does not describe them —
     * reconstructing a unit vector from two interpolated endpoints is exactly the failure BC5 exists
     * to fix. They are excluded rather than measured and admitted.
     */
    private enum Kind {
        ART,
        SHADER_MAP;

        static Kind of(String logicalPath) {
            String path = logicalPath.toLowerCase(Locale.ROOT);
            boolean shaderMap = path.contains("/shaders/")
                    || path.contains("_normal")
                    || path.contains("_material")
                    || path.contains("_surface")
                    || path.contains("_glow")
                    || path.contains("/maps/");
            return shaderMap ? SHADER_MAP : ART;
        }
    }

    static Map<String, Object> run(
            List<ProfileCensus.TextureWinner> winners,
            String profileFingerprint,
            Path outDir,
            Options options) throws IOException {
        List<ProfileCensus.TextureWinner> candidates = options.limit() > 0 && winners.size() > options.limit()
                ? winners.subList(0, options.limit())
                : winners;

        long started = System.nanoTime();
        List<Baked> baked = new ArrayList<>();
        ConcurrentLinkedQueue<Skipped> skipped = new ConcurrentLinkedQueue<>();
        // Content-addressed, so several mods shipping byte-identical art share one blob and it is
        // encoded once. Encoding is the expensive step, and duplicate art is common in this ecosystem.
        Map<String, Baked> bySourceHash = new ConcurrentHashMap<>();

        ForkJoinPool pool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors()));
        try {
            baked.addAll(pool.submit(() -> candidates.parallelStream()
                    .map(winner -> bakeOne(winner, options, bySourceHash, skipped))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList()).get());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Baking was interrupted", error);
        } catch (ExecutionException error) {
            throw new IOException("Baking failed: " + error.getCause(), error.getCause());
        } finally {
            pool.shutdown();
        }

        Map<String, BlockCacheManifest.Entry> entries = new TreeMap<>();
        Set<String> blobsOnDisk = new HashSet<>();
        long written = 0;
        for (Baked result : baked) {
            String hash = result.texture().sourceSha256();
            String relative = blobRelativePath(hash);
            // Several logical paths can name byte-identical art and so share one blob. Writing it once
            // is not a micro-optimisation: the manifest points many entries at the same file, and
            // rewriting it per entry would multiply the slowest part of the run by the duplication
            // factor of the profile.
            if (blobsOnDisk.add(hash)) {
                if (!options.dryRun()) {
                    BlockTextureIO.write(outDir.resolve(relative), result.texture());
                }
                written++;
            }
            entries.put(result.logicalPath(), BlockCacheManifest.Entry.of(result.texture(), relative));
        }
        if (!options.dryRun()) {
            BlockCacheManifestIO.write(
                    outDir.resolve("blocks.spfc"),
                    new BlockCacheManifest(profileFingerprint, BlockCompressor.CODEC_VERSION, entries));
        }
        return report(baked, List.copyOf(skipped), candidates.size(), written, options, started);
    }

    private static Optional<Baked> bakeOne(
            ProfileCensus.TextureWinner winner,
            Options options,
            Map<String, Baked> bySourceHash,
            ConcurrentLinkedQueue<Skipped> skipped) {
        if (Kind.of(winner.logicalPath()) == Kind.SHADER_MAP) {
            skipped.add(new Skipped(winner.logicalPath(), Reason.SHADER_MAP, 0));
            return Optional.empty();
        }
        try {
            String sourceSha256 = Hashes.sha256(winner.file());
            Baked shared = bySourceHash.get(sourceSha256);
            if (shared != null) {
                return Optional.of(new Baked(winner.logicalPath(), shared.texture()));
            }
            BufferedImage image = ImageIO.read(winner.file().toFile());
            if (image == null) {
                skipped.add(new Skipped(winner.logicalPath(), Reason.UNREADABLE, 0));
                return Optional.empty();
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
            BlockTexture texture = BlockTextureBaker.bake(
                    sourceSha256, argb, width, height,
                    options.mips() ? BlockTextureBaker.Mips.FULL_CHAIN : BlockTextureBaker.Mips.NONE);
            if (texture.fidelity().p99DeltaE() > options.maxP99DeltaE()) {
                // Not an error. This texture keeps the ordinary path, which is the whole reason a
                // per-texture policy is worth having.
                skipped.add(new Skipped(winner.logicalPath(), Reason.OVER_FIDELITY_GATE,
                        texture.fidelity().p99DeltaE()));
                return Optional.empty();
            }
            Baked result = new Baked(winner.logicalPath(), texture);
            bySourceHash.putIfAbsent(sourceSha256, result);
            return Optional.of(result);
        } catch (IOException | RuntimeException error) {
            skipped.add(new Skipped(winner.logicalPath(), Reason.UNREADABLE, 0));
            return Optional.empty();
        }
    }

    /**
     * Two levels of directory from the hash, so a cache of ten thousand blobs does not become one
     * directory of ten thousand entries — which several filesystems handle badly and every file
     * browser handles badly.
     */
    private static String blobRelativePath(String sourceSha256) {
        return "blocks/" + sourceSha256.substring(0, 2) + "/" + sourceSha256 + ".spfb";
    }

    private static Map<String, Object> report(
            List<Baked> baked,
            List<Skipped> skipped,
            int considered,
            long blobsWritten,
            Options options,
            long startedNanos) {
        long blockBytes = baked.stream().mapToLong(b -> b.texture().blockBytes()).sum();
        long uncompressed = baked.stream().mapToLong(b -> b.texture().uncompressedBytes()).sum();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("profileTextures", (long) considered);
        report.put("cachedTextures", (long) baked.size());
        report.put("distinctBlobs", blobsWritten);
        report.put("dryRun", options.dryRun());
        report.put("codecVersion", (long) BlockCompressor.CODEC_VERSION);
        report.put("mipChain", options.mips());
        report.put("fidelityGate", "cached only if p99 Delta-E <= " + options.maxP99DeltaE()
                + "; a texture over the gate keeps the ordinary decode path, which is a normal outcome");
        report.put("uncompressedBytes", uncompressed);
        report.put("blockBytes", blockBytes);
        report.put("compressionRatio", blockBytes == 0
                ? 0.0
                : Math.round((double) uncompressed / blockBytes * 100.0) / 100.0);
        report.put("bc1Textures", baked.stream().filter(b -> b.texture().format() == BlockTexture.Format.BC1).count());
        report.put("bc3Textures", baked.stream().filter(b -> b.texture().format() == BlockTexture.Format.BC3).count());
        report.put("medianMeanDeltaE", median(baked, b -> b.texture().fidelity().meanDeltaE()));
        report.put("medianP99DeltaE", median(baked, b -> b.texture().fidelity().p99DeltaE()));
        report.put("skipped", skippedByReason(skipped));
        // The textures nearest the gate. If this list is uninteresting the gate can be loosened with
        // evidence rather than by feel.
        report.put("closestToTheGate", baked.stream()
                .sorted(Comparator.comparingDouble((Baked b) -> b.texture().fidelity().p99DeltaE()).reversed())
                .limit(WORST_REPORTED)
                .map(BlockCacheBaker::describe)
                .toList());
        report.put("elapsedSeconds", Math.round((System.nanoTime() - startedNanos) / 1e7) / 100.0);
        return report;
    }

    private static Map<String, Object> skippedByReason(List<Skipped> skipped) {
        Map<String, Object> byReason = new LinkedHashMap<>();
        for (Reason reason : Reason.values()) {
            List<Skipped> group = skipped.stream().filter(s -> s.reason() == reason).toList();
            if (group.isEmpty()) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("textures", (long) group.size());
            values.put("why", reason.why());
            values.put("examples", group.stream().limit(5).map(Skipped::logicalPath).toList());
            byReason.put(reason.label(), values);
        }
        return byReason;
    }

    private static Map<String, Object> describe(Baked baked) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("path", baked.logicalPath());
        values.put("width", (long) baked.texture().width());
        values.put("height", (long) baked.texture().height());
        values.put("format", baked.texture().format().name());
        values.put("meanDeltaE", round(baked.texture().fidelity().meanDeltaE()));
        values.put("p99DeltaE", round(baked.texture().fidelity().p99DeltaE()));
        values.put("blockBytes", baked.texture().blockBytes());
        return values;
    }

    private static double median(List<Baked> baked, java.util.function.ToDoubleFunction<Baked> value) {
        if (baked.isEmpty()) {
            return 0.0;
        }
        double[] values = baked.stream().mapToDouble(value).sorted().toArray();
        return round(values[values.length / 2]);
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private record Baked(String logicalPath, BlockTexture texture) {
    }

    private record Skipped(String logicalPath, Reason reason, double p99DeltaE) {
    }

    private enum Reason {
        SHADER_MAP("shaderMaps",
                "a normal or material map stores vectors, not colour; Delta-E does not describe it and "
                        + "S3TC is the wrong codec for it"),
        OVER_FIDELITY_GATE("overFidelityGate",
                "the loss exceeded the gate, so this texture keeps the ordinary decode path"),
        UNREADABLE("unreadable",
                "no image reader accepted the file, or it could not be read");

        private final String label;
        private final String why;

        Reason(String label, String why) {
            this.label = label;
            this.why = why;
        }

        String label() {
            return label;
        }

        String why() {
            return why;
        }
    }
}

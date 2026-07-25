package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.BlockCompressor;
import dev.starsector.preflight.core.GpuTextureFootprint;
import dev.starsector.preflight.core.TextureFidelity;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Measures what block compression would actually cost, visually, on this profile's own art.
 *
 * <p>The standing community objection to compressing Starsector's textures is not that it fails to
 * save memory — it plainly does, fourfold — but that S3TC "will have significant visual artifacts
 * when we're talking about the detailed, 1:1 2D textures that Starsector uses" (Dark.Revenant, 2019).
 * That is an empirical claim about a specific art style, and it has never been measured. This
 * measures it: every sampled texture is round-tripped through the real format and compared to its
 * original in CIELAB Delta-E, where the threshold for human perceptibility is a published number
 * rather than an opinion.
 *
 * <p>Nothing is written and nothing is uploaded. This is a measurement, and its output is evidence
 * for or against attempting the runtime work at all.
 */
final class CompressionProbe {
    /** Textures are sampled evenly across the profile rather than taking the first N alphabetically. */
    private static final int WORST_REPORTED = 15;

    private CompressionProbe() {
    }

    record Options(int sampleLimit, boolean opaqueAsBc1) {
    }

    /**
     * What a texture is <em>for</em>, which decides whether a perceptual colour metric means anything
     * about it.
     *
     * <p>A normal or material map stores vectors and scalars in RGB channels. It is never looked at;
     * it is sampled by a shader. Delta-E on one is not a perceptual measurement, and a colour-oriented
     * codec is the wrong tool for it besides — reconstructing a unit vector from two interpolated
     * endpoints is exactly the failure BC5 was introduced to fix. Mixing these in with sprite art
     * produces a number that describes neither.
     */
    private enum Kind {
        ART,
        SHADER_MAP;

        static Kind of(String logicalPath) {
            String path = logicalPath.toLowerCase(java.util.Locale.ROOT);
            boolean shaderMap = path.contains("/shaders/")
                    || path.contains("_normal")
                    || path.contains("_material")
                    || path.contains("_surface")
                    || path.contains("_glow")
                    || path.contains("/maps/");
            return shaderMap ? SHADER_MAP : ART;
        }
    }

    static Map<String, Object> run(List<ProfileCensus.TextureWinner> winners, Options options) {
        List<ProfileCensus.TextureWinner> sample = evenSample(winners, options.sampleLimit());
        List<Sampled> results = new ArrayList<>();
        long originalResident = 0;
        long compressedResident = 0;
        long failed = 0;
        long perceptuallyLossless = 0;

        for (ProfileCensus.TextureWinner winner : sample) {
            Sampled sampled;
            try {
                sampled = measure(winner, options.opaqueAsBc1());
            } catch (IOException | RuntimeException error) {
                failed++;
                continue;
            }
            if (sampled == null) {
                failed++;
                continue;
            }
            results.add(sampled);
            originalResident += sampled.residentBytes();
            compressedResident += sampled.compressedBytes();
            if (sampled.report().perceptuallyLossless()) {
                perceptuallyLossless++;
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sampledTextures", (long) results.size());
        report.put("unreadableTextures", failed);
        report.put("metric", "CIELAB Delta-E (CIE76), alpha weighted; <1 imperceptible, >2 visible at a glance");
        report.put("format", options.opaqueAsBc1()
                ? "BC3 (DXT5) for textures with alpha, BC1 (DXT1) for opaque ones"
                : "BC3 (DXT5) for every texture");
        report.put("residentBytes", originalResident);
        report.put("compressedResidentBytes", compressedResident);
        report.put("compressionRatio", compressedResident == 0
                ? 0.0
                : Math.round((double) originalResident / compressedResident * 100.0) / 100.0);
        report.put("perceptuallyLosslessTextures", perceptuallyLossless);
        // Reported per kind, because a single figure over both is not about anything: Delta-E is a
        // perceptual measure and only one of these two classes is ever perceived.
        report.put("byKind", byKind(results));
        report.put("bySize", bySize(results));
        report.put("worstArtTextures", results.stream()
                .filter(s -> s.kind() == Kind.ART)
                .sorted(Comparator.comparingDouble((Sampled s) -> s.report().p99DeltaE()).reversed())
                .limit(WORST_REPORTED)
                .map(Sampled::toMap)
                .toList());
        return report;
    }

    private static Map<String, Object> byKind(List<Sampled> results) {
        Map<String, Object> byKind = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) {
            List<Sampled> group = results.stream().filter(s -> s.kind() == kind).toList();
            if (group.isEmpty()) {
                continue;
            }
            long lossless = group.stream().filter(s -> s.report().perceptuallyLossless()).count();
            long resident = group.stream().mapToLong(Sampled::residentBytes).sum();
            long compressed = group.stream().mapToLong(Sampled::compressedBytes).sum();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("textures", (long) group.size());
            values.put("residentBytes", resident);
            values.put("compressedResidentBytes", compressed);
            values.put("perceptuallyLosslessTextures", lossless);
            values.put("perceptuallyLosslessFraction",
                    Math.round((double) lossless / group.size() * 1000.0) / 1000.0);
            values.put("medianP99DeltaE", median(group));
            values.put("medianMeanDeltaE", Math.round(group.stream()
                    .mapToDouble(s -> s.report().meanDeltaE()).sorted()
                    .skip(group.size() / 2).findFirst().orElse(0) * 10000.0) / 10000.0);
            if (kind == Kind.SHADER_MAP) {
                values.put("note", "normal/material/surface maps: shader input, never viewed. "
                        + "Delta-E is not a perceptual measure here and BC1/BC3 is the wrong codec "
                        + "for reconstructed vectors; BC5 exists for this case.");
            }
            byKind.put(kind.name().toLowerCase(java.util.Locale.ROOT), values);
        }
        return byKind;
    }

    /**
     * Fidelity bucketed by texture size, which is the breakdown that decides anything: a profile's
     * video memory is dominated by a small number of large textures, and large textures are the
     * smooth, photographic ones. A per-texture average is pulled around by hundreds of tiny detailed
     * sprites that together occupy almost no memory.
     */
    private static Map<String, Object> bySize(List<Sampled> results) {
        int[] edges = {64, 256, 1024, Integer.MAX_VALUE};
        String[] labels = {"upTo64px", "upTo256px", "upTo1024px", "over1024px"};
        Map<String, Object> bySize = new LinkedHashMap<>();
        for (int bucket = 0; bucket < edges.length; bucket++) {
            int low = bucket == 0 ? 0 : edges[bucket - 1];
            int high = edges[bucket];
            List<Sampled> group = results.stream()
                    .filter(s -> s.kind() == Kind.ART)
                    .filter(s -> Math.max(s.width(), s.height()) > low
                            && Math.max(s.width(), s.height()) <= high)
                    .toList();
            if (group.isEmpty()) {
                continue;
            }
            long resident = group.stream().mapToLong(Sampled::residentBytes).sum();
            long compressed = group.stream().mapToLong(Sampled::compressedBytes).sum();
            long losslessBytes = group.stream()
                    .filter(s -> s.report().perceptuallyLossless())
                    .mapToLong(Sampled::residentBytes).sum();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("textures", (long) group.size());
            values.put("residentBytes", resident);
            values.put("compressedResidentBytes", compressed);
            values.put("medianMeanDeltaE", median(group, s -> s.report().meanDeltaE()));
            values.put("medianP99DeltaE", median(group, s -> s.report().p99DeltaE()));
            values.put("perceptuallyLosslessByteFraction",
                    resident == 0 ? 0.0 : Math.round((double) losslessBytes / resident * 1000.0) / 1000.0);
            bySize.put(labels[bucket], values);
        }
        return bySize;
    }

    private static Sampled measure(ProfileCensus.TextureWinner winner, boolean opaqueAsBc1) throws IOException {
        BufferedImage image = ImageIO.read(winner.file().toFile());
        if (image == null) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int[] original = image.getRGB(0, 0, width, height, null, 0, width);
        // An image is treated as opaque only if it truly is; a declared alpha channel that is
        // entirely 255 would otherwise waste half the compressed size on a constant.
        boolean withAlpha = !opaqueAsBc1 || hasTransparency(original);
        int[] roundTripped = BlockCompressor.roundTrip(original, width, height, withAlpha);
        TextureFidelity.Report report = TextureFidelity.compare(original, roundTripped);
        return new Sampled(
                Kind.of(winner.logicalPath()),
                winner.modId(),
                winner.logicalPath(),
                width,
                height,
                withAlpha,
                GpuTextureFootprint.residentBytes(width, height),
                BlockCompressor.compressedBytes(
                        GpuTextureFootprint.uploadDimension(width),
                        GpuTextureFootprint.uploadDimension(height),
                        withAlpha),
                report);
    }

    private static boolean hasTransparency(int[] argb) {
        for (int pixel : argb) {
            if ((pixel >>> 24) != 255) {
                return true;
            }
        }
        return false;
    }

    /** Takes an even stride through the profile so the sample is not one mod's art. */
    private static List<ProfileCensus.TextureWinner> evenSample(
            List<ProfileCensus.TextureWinner> winners, int limit) {
        if (limit <= 0 || winners.size() <= limit) {
            return winners;
        }
        List<ProfileCensus.TextureWinner> sample = new ArrayList<>(limit);
        double stride = (double) winners.size() / limit;
        for (int i = 0; i < limit; i++) {
            sample.add(winners.get((int) (i * stride)));
        }
        return sample;
    }

    private static double median(List<Sampled> results) {
        return median(results, s -> s.report().p99DeltaE());
    }

    private static double median(List<Sampled> results, java.util.function.ToDoubleFunction<Sampled> of) {
        double[] values = results.stream().mapToDouble(of).sorted().toArray();
        double middle = values.length % 2 == 1
                ? values[values.length / 2]
                : (values[values.length / 2 - 1] + values[values.length / 2]) / 2.0;
        return Math.round(middle * 10000.0) / 10000.0;
    }

    private record Sampled(
            Kind kind,
            String modId,
            String logicalPath,
            int width,
            int height,
            boolean withAlpha,
            long residentBytes,
            long compressedBytes,
            TextureFidelity.Report report) {

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("kind", kind.name().toLowerCase(java.util.Locale.ROOT));
            values.put("modId", modId);
            values.put("path", logicalPath);
            values.put("width", width);
            values.put("height", height);
            values.put("format", withAlpha ? "BC3" : "BC1");
            values.put("residentBytes", residentBytes);
            values.put("compressedBytes", compressedBytes);
            values.putAll(report.toMap());
            return values;
        }
    }
}

package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.GpuTextureFootprint;
import dev.starsector.preflight.core.ModCostBreakdown;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Computes exact per-mod resource and prepared-data costs for an enabled Starsector profile.
 *
 * <p>Builds upon {@link ProfileCensus}, {@link ResourceIndex}, {@link ResourceProviderComparison},
 * and {@link GpuTextureFootprint} to establish factual, verifiable physical resource footprints
 * without misleading runtime startup timing attributions.
 */
public final class ProfileModCostAnalyzer {

    private ProfileModCostAnalyzer() {
    }

    public static ModCostBreakdown.Report analyze(
            Path installRoot,
            ProfileCensus.Result census,
            ResourceIndex resourceIndex,
            ResourceProviderComparison.Result providerComparison) throws IOException {
        Objects.requireNonNull(installRoot, "installRoot");
        Objects.requireNonNull(census, "census");
        Objects.requireNonNull(resourceIndex, "resourceIndex");

        String profileFingerprint = extractProfileFingerprint(census);
        Map<String, ModMetadata> metadataByModId = loadModMetadata(installRoot, census);
        Map<String, ProviderOverlapAccumulator> overlapsByModId = computeOverlaps(resourceIndex, providerComparison);
        Map<String, Long> gpuTextureBytesByModId = computeGpuTextureBytes(census);

        List<ModCostBreakdown.ModFootprint> footprints = new ArrayList<>();
        long totalInstalledBytes = 0;
        int totalFiles = 0;
        long totalGpuTextureBytes = 0;
        long totalPreparedCacheBytes = 0;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modStatsList = (List<Map<String, Object>>) census.values().get("mods");
        if (modStatsList != null) {
            for (Map<String, Object> stats : modStatsList) {
                String modId = (String) stats.get("id");
                if (modId == null) continue;

                long installedBytes = getLong(stats, "bytes");
                int files = getInt(stats, "files");
                totalInstalledBytes += installedBytes;
                totalFiles += files;

                ModCostBreakdown.ClassCounts classCounts = new ModCostBreakdown.ClassCounts(
                        getInt(stats, "imageFiles"),
                        getLong(stats, "imageBytes"),
                        getInt(stats, "soundFiles"),
                        getLong(stats, "soundBytes"),
                        getInt(stats, "dataFiles"),
                        getLong(stats, "dataBytes"),
                        getInt(stats, "jarFiles"),
                        getLong(stats, "jarBytes"),
                        getInt(stats, "looseJavaFiles"),
                        getLong(stats, "looseJavaBytes"),
                        0,
                        0L);

                long gpuBytes = gpuTextureBytesByModId.getOrDefault(modId, getLong(stats, "decodedImageBytes"));
                totalGpuTextureBytes += gpuBytes;

                ProviderOverlapAccumulator overlapAcc = overlapsByModId.getOrDefault(
                        modId, new ProviderOverlapAccumulator());
                ModCostBreakdown.OverlapCounts overlaps = new ModCostBreakdown.OverlapCounts(
                        overlapAcc.identicalDuplicates,
                        overlapAcc.overridingPaths,
                        overlapAcc.overriddenPaths);

                ModMetadata meta = metadataByModId.get(modId);
                String displayName = meta != null ? meta.displayName() : null;
                String version = meta != null ? meta.version() : null;
                Long declaredMemoryMb = meta != null ? meta.declaredMemoryMb() : null;

                // Prepared cache contribution from censused assets
                long preparedCache = getLong(stats, "imageBytes") + getLong(stats, "soundBytes");
                totalPreparedCacheBytes += preparedCache;

                footprints.add(new ModCostBreakdown.ModFootprint(
                        modId,
                        displayName,
                        version,
                        installedBytes,
                        files,
                        classCounts,
                        gpuBytes,
                        preparedCache,
                        overlaps,
                        declaredMemoryMb));
            }
        }

        return new ModCostBreakdown.Report(
                ModCostBreakdown.Report.FORMAT,
                profileFingerprint,
                totalInstalledBytes,
                totalFiles,
                totalGpuTextureBytes,
                totalPreparedCacheBytes,
                footprints);
    }

    private static Map<String, Long> computeGpuTextureBytes(ProfileCensus.Result census) {
        Map<String, Long> byModId = new HashMap<>();
        for (ProfileCensus.TextureWinner winner : census.measuredWinners()) {
            if (winner.dimensions() != null) {
                long resident = GpuTextureFootprint.residentBytes(
                        winner.dimensions().width(), winner.dimensions().height());
                if (resident > 0) {
                    byModId.merge(winner.modId(), resident, Long::sum);
                }
            }
        }
        return byModId;
    }

    private static Map<String, ProviderOverlapAccumulator> computeOverlaps(
            ResourceIndex index,
            ResourceProviderComparison.Result comparison) {
        Map<String, ProviderOverlapAccumulator> map = new HashMap<>();
        if (comparison == null) {
            return map;
        }

        for (ResourceProviderComparison.PathComparison pathComp : comparison.identicalSamples()) {
            for (ResourceProviderComparison.ProviderSummary provider : pathComp.providers()) {
                map.computeIfAbsent(provider.providerId(), k -> new ProviderOverlapAccumulator()).identicalDuplicates++;
            }
        }

        for (ResourceProviderComparison.PathComparison pathComp : comparison.overrideSamples()) {
            String winnerId = pathComp.winnerProviderId();
            map.computeIfAbsent(winnerId, k -> new ProviderOverlapAccumulator()).overridingPaths++;
            for (ResourceProviderComparison.ProviderSummary provider : pathComp.providers()) {
                if (!provider.providerId().equals(winnerId)) {
                    map.computeIfAbsent(provider.providerId(), k -> new ProviderOverlapAccumulator()).overriddenPaths++;
                }
            }
        }

        return map;
    }


    private static Map<String, ModMetadata> loadModMetadata(
            Path installRoot,
            ProfileCensus.Result census) {
        Map<String, ModMetadata> metadata = new HashMap<>();
        try {
            GameLayout layout = GameLayout.locate(installRoot);
            if (!Files.isDirectory(layout.modsDirectory())) {
                return metadata;
            }
            try (var stream = Files.list(layout.modsDirectory())) {
                for (Path dir : stream.filter(Files::isDirectory).toList()) {
                    Path modInfo = dir.resolve("mod_info.json");
                    if (Files.isRegularFile(modInfo)) {
                        try {
                            String json = Files.readString(modInfo, StandardCharsets.UTF_8);
                            String id = JsonText.string(json, "id");
                            if (id != null && !id.isBlank()) {
                                String name = JsonText.string(json, "name");
                                String version = JsonText.string(json, "version");
                                Long ram = parseRamMb(JsonText.string(json, "totalRAM"));
                                metadata.put(id, new ModMetadata(name, version, ram));
                            }
                        } catch (Exception ignored) {
                            // Degrade gracefully on malformed mod_info.json
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Degrade gracefully if layout cannot be inspected
        }
        return metadata;
    }

    private static Long parseRamMb(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String clean = raw.trim().toLowerCase();
        try {
            if (clean.endsWith("gb") || clean.endsWith("g")) {
                String num = clean.replaceAll("[^0-9.]", "");
                return (long) (Double.parseDouble(num) * 1024);
            }
            if (clean.endsWith("mb") || clean.endsWith("m")) {
                String num = clean.replaceAll("[^0-9.]", "");
                return (long) Double.parseDouble(num);
            }
            long val = Long.parseLong(clean.replaceAll("[^0-9]", ""));
            return val > 32 ? val : val * 1024;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractProfileFingerprint(ProfileCensus.Result census) {
        Object fp = census.values().get("profileFingerprint");
        return fp instanceof String s && !s.isBlank() ? s : "uncomputed";
    }

    private static long getLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }

    private static int getInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return 0;
    }

    private static final class ProviderOverlapAccumulator {
        int identicalDuplicates;
        int overridingPaths;
        int overriddenPaths;
    }

    private record ModMetadata(String displayName, String version, Long declaredMemoryMb) {
    }
}

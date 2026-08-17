package dev.starsector.preflight.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable per-mod resource and cost accounting model.
 *
 * <p>Separates physical resource footprint (installed files, GPU texture allocations, audio source bytes,
 * duplicate/override counts) from runtime timing. Mod cost is established only through direct,
 * verifiable observations.
 */
public final class ModCostBreakdown {

    public record ClassCounts(
            int imageFiles,
            long imageBytes,
            int soundFiles,
            long soundBytes,
            int dataFiles,
            long dataBytes,
            int jarFiles,
            long jarBytes,
            int looseJavaFiles,
            long looseJavaBytes,
            int otherFiles,
            long otherBytes) {

        public Map<String, Object> toPublicMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("imageFiles", imageFiles);
            map.put("imageBytes", imageBytes);
            map.put("soundFiles", soundFiles);
            map.put("soundBytes", soundBytes);
            map.put("dataFiles", dataFiles);
            map.put("dataBytes", dataBytes);
            map.put("jarFiles", jarFiles);
            map.put("jarBytes", jarBytes);
            map.put("looseJavaFiles", looseJavaFiles);
            map.put("looseJavaBytes", looseJavaBytes);
            map.put("otherFiles", otherFiles);
            map.put("otherBytes", otherBytes);
            return Collections.unmodifiableMap(map);
        }
    }

    public record OverlapCounts(
            int identicalDuplicates,
            int overridingPaths,
            int overriddenPaths) {

        public Map<String, Object> toPublicMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("identicalDuplicates", identicalDuplicates);
            map.put("overridingPaths", overridingPaths);
            map.put("overriddenPaths", overriddenPaths);
            return Collections.unmodifiableMap(map);
        }
    }

    public record ModFootprint(
            String modId,
            String displayName,
            String version,
            long installedBytes,
            int totalFiles,
            ClassCounts classCounts,
            long uncompressedTextureGpuBytes,
            long preparedCacheContributionBytes,
            OverlapCounts overlaps,
            Long declaredMemoryMb) {

        public ModFootprint {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(classCounts, "classCounts");
            Objects.requireNonNull(overlaps, "overlaps");
            if (installedBytes < 0) throw new IllegalArgumentException("installedBytes must be non-negative");
            if (totalFiles < 0) throw new IllegalArgumentException("totalFiles must be non-negative");
            if (uncompressedTextureGpuBytes < 0) throw new IllegalArgumentException("uncompressedTextureGpuBytes must be non-negative");
            if (preparedCacheContributionBytes < 0) throw new IllegalArgumentException("preparedCacheContributionBytes must be non-negative");
        }

        public Map<String, Object> toPublicMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("modId", modId);
            if (displayName != null && !displayName.isBlank()) {
                map.put("displayName", displayName);
            }
            if (version != null && !version.isBlank()) {
                map.put("version", version);
            }
            map.put("installedBytes", installedBytes);
            map.put("totalFiles", totalFiles);
            map.put("classes", classCounts.toPublicMap());
            map.put("uncompressedTextureGpuBytes", uncompressedTextureGpuBytes);
            map.put("preparedCacheContributionBytes", preparedCacheContributionBytes);
            map.put("overlaps", overlaps.toPublicMap());
            if (declaredMemoryMb != null) {
                map.put("declaredMemoryMb", declaredMemoryMb);
            }
            return Collections.unmodifiableMap(map);
        }
    }

    public record Report(
            String format,
            String profileFingerprint,
            long totalInstalledBytes,
            int totalFiles,
            long totalUncompressedTextureGpuBytes,
            long totalPreparedCacheBytes,
            List<ModFootprint> mods) {

        public static final String FORMAT = "preflight-mod-cost-breakdown-v1";

        public Report {
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(profileFingerprint, "profileFingerprint");
            mods = List.copyOf(mods);
        }

        public Map<String, Object> toPublicMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("format", format);
            map.put("profileFingerprint", profileFingerprint);
            map.put("totalInstalledBytes", totalInstalledBytes);
            map.put("totalFiles", totalFiles);
            map.put("totalUncompressedTextureGpuBytes", totalUncompressedTextureGpuBytes);
            map.put("totalPreparedCacheBytes", totalPreparedCacheBytes);
            map.put("mods", mods.stream().map(ModFootprint::toPublicMap).toList());
            return Collections.unmodifiableMap(map);
        }
    }

    private ModCostBreakdown() {
    }
}

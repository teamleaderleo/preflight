package dev.starsector.preflight.core.drift;

import dev.starsector.preflight.core.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Report containing mod drift assessment against a reference baseline.
 */
public record DriftReport(
        String format,
        String timestamp,
        String installRoot,
        String referenceType,
        String referenceFingerprint,
        DriftSummary summary,
        List<ModDriftItem> mods,
        List<String> diagnostics) {

    public static final String FORMAT = "starsector-preflight-mod-drift-v1";

    public record DriftSummary(
            int totalMods,
            int pristineCount,
            int sameVersionDriftCount,
            int bytecodeDriftCount,
            int corruptMetadataCount,
            int versionChangedCount,
            int missingCount,
            int newCount) {

        public boolean hasDrift() {
            return sameVersionDriftCount > 0 || bytecodeDriftCount > 0 || corruptMetadataCount > 0;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalMods", totalMods);
            map.put("pristineCount", pristineCount);
            map.put("sameVersionDriftCount", sameVersionDriftCount);
            map.put("bytecodeDriftCount", bytecodeDriftCount);
            map.put("corruptMetadataCount", corruptMetadataCount);
            map.put("versionChangedCount", versionChangedCount);
            map.put("missingCount", missingCount);
            map.put("newCount", newCount);
            map.put("hasDrift", hasDrift());
            return map;
        }
    }

    public record FileDiffEntry(
            String relativePath,
            long oldSize,
            long newSize,
            String oldSha256,
            String newSha256) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("relativePath", relativePath);
            map.put("oldSize", oldSize);
            map.put("newSize", newSize);
            map.put("oldSha256", oldSha256);
            map.put("newSha256", newSha256);
            return map;
        }
    }

    public record JarDiffEntry(
            String relativePath,
            String diffType,
            long oldSize,
            long newSize,
            String oldSha256,
            String newSha256) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("relativePath", relativePath);
            map.put("diffType", diffType);
            map.put("oldSize", oldSize);
            map.put("newSize", newSize);
            map.put("oldSha256", oldSha256);
            map.put("newSha256", newSha256);
            return map;
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("format", format);
        map.put("timestamp", timestamp);
        map.put("installRoot", installRoot);
        map.put("referenceType", referenceType);
        map.put("referenceFingerprint", referenceFingerprint);
        map.put("summary", summary.toMap());
        map.put("mods", mods.stream().map(ModDriftItem::toMap).toList());
        map.put("diagnostics", diagnostics);
        return map;
    }

    public String toJson() {
        return Json.object(toMap());
    }
}

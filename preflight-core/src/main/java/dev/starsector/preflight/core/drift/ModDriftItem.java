package dev.starsector.preflight.core.drift;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detailed drift assessment for a single mod.
 */
public record ModDriftItem(
        String modId,
        String name,
        String directoryName,
        ModDriftDetector.DriftSeverity severity,
        String activeVersion,
        String referenceVersion,
        String activeContentSha256,
        String referenceContentSha256,
        long activeTotalBytes,
        long referenceTotalBytes,
        int activeFileCount,
        int referenceFileCount,
        boolean hasBytecodeDrift,
        boolean hasAssetDrift,
        boolean hasConfigDrift,
        List<String> addedFiles,
        List<String> removedFiles,
        List<DriftReport.FileDiffEntry> modifiedFiles,
        List<DriftReport.JarDiffEntry> jarDiffs,
        List<String> diagnostics) {

    public ModDriftItem {
        addedFiles = addedFiles == null ? List.of() : List.copyOf(addedFiles);
        removedFiles = removedFiles == null ? List.of() : List.copyOf(removedFiles);
        modifiedFiles = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
        jarDiffs = jarDiffs == null ? List.of() : List.copyOf(jarDiffs);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("modId", modId);
        map.put("name", name);
        map.put("directoryName", directoryName);
        map.put("severity", severity != null ? severity.name() : "UNKNOWN");
        map.put("activeVersion", activeVersion);
        map.put("referenceVersion", referenceVersion);
        map.put("activeContentSha256", activeContentSha256);
        map.put("referenceContentSha256", referenceContentSha256);
        map.put("activeTotalBytes", activeTotalBytes);
        map.put("referenceTotalBytes", referenceTotalBytes);
        map.put("activeFileCount", activeFileCount);
        map.put("referenceFileCount", referenceFileCount);
        map.put("hasBytecodeDrift", hasBytecodeDrift);
        map.put("hasAssetDrift", hasAssetDrift);
        map.put("hasConfigDrift", hasConfigDrift);
        map.put("addedFiles", addedFiles);
        map.put("removedFiles", removedFiles);
        map.put("modifiedFiles", modifiedFiles.stream().map(DriftReport.FileDiffEntry::toMap).toList());
        map.put("jarDiffs", jarDiffs.stream().map(DriftReport.JarDiffEntry::toMap).toList());
        map.put("diagnostics", diagnostics);
        return map;
    }
}

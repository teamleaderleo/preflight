package dev.starsector.preflight.core.drift;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Compares active mods against baseline reference signatures and classifies content drift.
 */
public final class ModDriftDetector {
    private ModDriftDetector() {
    }

    public enum DriftSeverity {
        PRISTINE,
        SAME_VERSION_DRIFT,
        BYTECODE_DRIFT,
        CORRUPT_METADATA,
        VERSION_CHANGED,
        MISSING_MOD,
        NEW_MOD
    }

    public static ModDriftItem compare(ModContentSignature reference, ModContentSignature active) {
        if (reference == null && active != null) {
            return createItem(active.modId(), active.declaredName(), active.directoryName(),
                    DriftSeverity.NEW_MOD, active.declaredVersion(), null,
                    active.contentSha256(), null, active.totalBytes(), 0L,
                    active.fileCount(), 0, false, false, false,
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }
        if (reference != null && active == null) {
            return createItem(reference.modId(), reference.declaredName(), reference.directoryName(),
                    DriftSeverity.MISSING_MOD, null, reference.declaredVersion(),
                    null, reference.contentSha256(), 0L, reference.totalBytes(),
                    0, reference.fileCount(), false, false, false,
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }
        if (reference == null && active == null) {
            throw new IllegalArgumentException("Both reference and active signatures cannot be null");
        }

        List<String> diagnostics = new ArrayList<>();
        boolean corruptMetadata = (active.modInfoSha256() == null || active.declaredVersion() == null);
        if (corruptMetadata) {
            diagnostics.add("mod_info.json is missing or unparseable in active mod directory");
        }

        // Compare JAR signatures
        Map<String, ModContentSignature.JarSignature> refJars = new LinkedHashMap<>();
        for (ModContentSignature.JarSignature j : reference.jarSignatures()) {
            refJars.put(j.relativePath(), j);
        }
        Map<String, ModContentSignature.JarSignature> actJars = new LinkedHashMap<>();
        for (ModContentSignature.JarSignature j : active.jarSignatures()) {
            actJars.put(j.relativePath(), j);
        }

        List<DriftReport.JarDiffEntry> jarDiffs = new ArrayList<>();
        boolean hasBytecodeDrift = false;

        for (Map.Entry<String, ModContentSignature.JarSignature> entry : actJars.entrySet()) {
            ModContentSignature.JarSignature ref = refJars.get(entry.getKey());
            if (ref == null) {
                hasBytecodeDrift = true;
                jarDiffs.add(new DriftReport.JarDiffEntry(
                        entry.getKey(), "ADDED", 0, entry.getValue().size(), null, entry.getValue().sha256()));
            } else if (!ref.sha256().equals(entry.getValue().sha256())) {
                hasBytecodeDrift = true;
                jarDiffs.add(new DriftReport.JarDiffEntry(
                        entry.getKey(), "MODIFIED", ref.size(), entry.getValue().size(), ref.sha256(), entry.getValue().sha256()));
            }
        }
        for (Map.Entry<String, ModContentSignature.JarSignature> entry : refJars.entrySet()) {
            if (!actJars.containsKey(entry.getKey())) {
                hasBytecodeDrift = true;
                jarDiffs.add(new DriftReport.JarDiffEntry(
                        entry.getKey(), "REMOVED", entry.getValue().size(), 0, entry.getValue().sha256(), null));
            }
        }

        // Detailed critical file diffs
        Map<String, ModContentSignature.FileEntrySignature> refFiles = reference.criticalFileSignatures();
        Map<String, ModContentSignature.FileEntrySignature> actFiles = active.criticalFileSignatures();

        List<String> addedFiles = new ArrayList<>();
        List<String> removedFiles = new ArrayList<>();
        List<DriftReport.FileDiffEntry> modifiedFiles = new ArrayList<>();
        boolean hasConfigDrift = false;
        boolean hasAssetDrift = false;

        for (Map.Entry<String, ModContentSignature.FileEntrySignature> entry : actFiles.entrySet()) {
            ModContentSignature.FileEntrySignature ref = refFiles.get(entry.getKey());
            if (ref == null) {
                addedFiles.add(entry.getKey());
                if (entry.getKey().endsWith(".json") || entry.getKey().endsWith(".csv")) {
                    hasConfigDrift = true;
                }
            } else if (!ref.sha256().equals(entry.getValue().sha256())) {
                modifiedFiles.add(new DriftReport.FileDiffEntry(
                        entry.getKey(), ref.size(), entry.getValue().size(), ref.sha256(), entry.getValue().sha256()));
                if (entry.getKey().endsWith(".json") || entry.getKey().endsWith(".csv")) {
                    hasConfigDrift = true;
                }
            }
        }
        for (Map.Entry<String, ModContentSignature.FileEntrySignature> entry : refFiles.entrySet()) {
            if (!actFiles.containsKey(entry.getKey())) {
                removedFiles.add(entry.getKey());
                if (entry.getKey().endsWith(".json") || entry.getKey().endsWith(".csv")) {
                    hasConfigDrift = true;
                }
            }
        }

        boolean contentMatch = reference.contentSha256().equals(active.contentSha256());
        if (!contentMatch && !hasBytecodeDrift && !hasConfigDrift) {
            hasAssetDrift = true;
        }

        // Determine severity
        DriftSeverity severity;
        if (corruptMetadata) {
            severity = DriftSeverity.CORRUPT_METADATA;
        } else if (contentMatch) {
            severity = DriftSeverity.PRISTINE;
        } else if (hasBytecodeDrift) {
            severity = DriftSeverity.BYTECODE_DRIFT;
        } else if (Objects.equals(reference.declaredVersion(), active.declaredVersion())) {
            severity = DriftSeverity.SAME_VERSION_DRIFT;
        } else {
            severity = DriftSeverity.VERSION_CHANGED;
        }

        return createItem(active.modId(), active.declaredName(), active.directoryName(),
                severity, active.declaredVersion(), reference.declaredVersion(),
                active.contentSha256(), reference.contentSha256(),
                active.totalBytes(), reference.totalBytes(),
                active.fileCount(), reference.fileCount(),
                hasBytecodeDrift, hasAssetDrift, hasConfigDrift,
                addedFiles, removedFiles, modifiedFiles, jarDiffs, diagnostics);
    }

    private static ModDriftItem createItem(
            String modId, String name, String dirName, DriftSeverity severity,
            String activeVer, String refVer, String activeSha, String refSha,
            long activeBytes, long refBytes, int activeCount, int refCount,
            boolean bytecodeDrift, boolean assetDrift, boolean configDrift,
            List<String> added, List<String> removed,
            List<DriftReport.FileDiffEntry> modified,
            List<DriftReport.JarDiffEntry> jarDiffs,
            List<String> diagnostics) {
        return new ModDriftItem(modId, name, dirName, severity, activeVer, refVer,
                activeSha, refSha, activeBytes, refBytes, activeCount, refCount,
                bytecodeDrift, assetDrift, configDrift, added, removed, modified, jarDiffs, diagnostics);
    }

    public static DriftReport detectDrift(
            Path installRoot,
            Map<String, ModContentSignature> referenceSignatures,
            String referenceType,
            String referenceFingerprint) throws IOException {
        Path modsDir = installRoot.resolve("mods");
        Map<String, ModContentSignature> activeSignatures = new LinkedHashMap<>();

        if (Files.isDirectory(modsDir)) {
            try (Stream<Path> stream = Files.list(modsDir)) {
                for (Path dir : stream.filter(Files::isDirectory).sorted().toList()) {
                    try {
                        ModContentSignature sig = ModContentSignature.compute(dir);
                        activeSignatures.put(sig.modId(), sig);
                    } catch (Exception ignored) {
                        // Unreadable mod directories are handled safely
                    }
                }
            }
        }

        Set<String> allModIds = new LinkedHashSet<>();
        if (referenceSignatures != null) {
            allModIds.addAll(referenceSignatures.keySet());
        }
        allModIds.addAll(activeSignatures.keySet());

        List<ModDriftItem> items = new ArrayList<>();
        int pristine = 0;
        int sameVer = 0;
        int bytecode = 0;
        int corrupt = 0;
        int verChanged = 0;
        int missing = 0;
        int newMod = 0;

        for (String id : allModIds) {
            ModContentSignature ref = referenceSignatures != null ? referenceSignatures.get(id) : null;
            ModContentSignature act = activeSignatures.get(id);
            ModDriftItem item = compare(ref, act);
            items.add(item);

            switch (item.severity()) {
                case PRISTINE -> pristine++;
                case SAME_VERSION_DRIFT -> sameVer++;
                case BYTECODE_DRIFT -> bytecode++;
                case CORRUPT_METADATA -> corrupt++;
                case VERSION_CHANGED -> verChanged++;
                case MISSING_MOD -> missing++;
                case NEW_MOD -> newMod++;
            }
        }

        DriftReport.DriftSummary summary = new DriftReport.DriftSummary(
                items.size(), pristine, sameVer, bytecode, corrupt, verChanged, missing, newMod);

        return new DriftReport(
                DriftReport.FORMAT,
                Instant.now().toString(),
                installRoot.toAbsolutePath().normalize().toString(),
                referenceType != null ? referenceType : "NONE",
                referenceFingerprint != null ? referenceFingerprint : "none",
                summary,
                items,
                List.of()
        );
    }
}

package dev.starsector.preflight.core.checkpoints;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Comparison engine for checkpoint state differences.
 *
 * <p>Computes structural and content differences between a Checkpoint and the Live Installation,
 * or between two Checkpoints. Emits {@code starsector-preflight-checkpoint-diff-v1}.</p>
 */
public final class CheckpointComparator {
    public static final String DIFF_FORMAT = "starsector-preflight-checkpoint-diff-v1";

    public enum Status {
        MATCHED,
        DRIFTED,
        DIVERGED,
        INCOMPLETE
    }

    private CheckpointComparator() {}

    /**
     * High-throughput 4-tier fast status evaluation for checkpoint listing (&lt;1ms per checkpoint).
     */
    public static Status evaluateFastStatus(
            Checkpoint checkpoint,
            List<String> liveEnabledMods,
            Set<String> installedModIdsOrFolders,
            Checkpoint.LaunchSettingsSnapshot liveSettings,
            String liveProfileFingerprint) {

        // Tier 1: Check for missing mods
        if (installedModIdsOrFolders != null) {
            for (String modId : checkpoint.enabledMods()) {
                if (!installedModIdsOrFolders.contains(modId)) {
                    return Status.INCOMPLETE;
                }
            }
        }

        // Tier 2: Check enabled mod list divergence (order + containment)
        boolean modListIdentical = checkpoint.enabledMods().equals(liveEnabledMods);
        if (!modListIdentical) {
            return Status.DIVERGED;
        }

        // Tier 3: Check launch settings delta
        boolean settingsIdentical = settingsEqual(checkpoint.launchSettings(), liveSettings);

        // Tier 4: Cache profile fingerprint check
        boolean fingerprintIdentical = checkpoint.profileFingerprint() != null
                && !checkpoint.profileFingerprint().isBlank()
                && checkpoint.profileFingerprint().equals(liveProfileFingerprint);

        if (settingsIdentical && fingerprintIdentical) {
            return Status.MATCHED;
        }
        return Status.DRIFTED;
    }

    /**
     * Deep comparison of a checkpoint against live installation directories and settings.
     */
    public static Map<String, Object> compareWithLive(
            Checkpoint checkpoint,
            Path installRoot,
            List<String> liveEnabledMods,
            Map<String, ModContentSignature> liveModSignatures,
            Checkpoint.LaunchSettingsSnapshot liveSettings,
            String liveProfileFingerprint) {

        boolean modListIdentical = checkpoint.enabledMods().equals(liveEnabledMods);
        List<String> addedMods = liveEnabledMods.stream().filter(id -> !checkpoint.enabledMods().contains(id)).toList();
        List<String> removedMods = checkpoint.enabledMods().stream().filter(id -> !liveEnabledMods.contains(id)).toList();
        boolean reordered = !modListIdentical && addedMods.isEmpty() && removedMods.isEmpty();

        List<Map<String, Object>> modDrift = new ArrayList<>();
        List<String> modifiedMods = new ArrayList<>();
        List<String> missingMods = new ArrayList<>();

        for (Checkpoint.ModSignature savedSig : checkpoint.modSignatures()) {
            ModContentSignature liveSig = liveModSignatures != null ? liveModSignatures.get(savedSig.modId()) : null;
            if (liveSig == null) {
                missingMods.add(savedSig.modId());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("modId", savedSig.modId());
                item.put("status", "MISSING_ON_DISK");
                item.put("checkpointVersion", savedSig.version());
                item.put("currentVersion", null);
                item.put("checkpointSha256", savedSig.contentSha256());
                item.put("currentSha256", null);
                item.put("modifiedFiles", List.of());
                modDrift.add(item);
            } else if (!savedSig.contentSha256().equals(liveSig.contentSha256())) {
                modifiedMods.add(savedSig.modId());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("modId", savedSig.modId());
                String driftStatus = !savedSig.version().equals(liveSig.declaredVersion())
                        ? "VERSION_CHANGED"
                        : hasBytecodeDrift(savedSig, liveSig) ? "BYTECODE_DRIFT" : "CONTENT_MODIFIED";
                item.put("status", driftStatus);
                item.put("checkpointVersion", savedSig.version());
                item.put("currentVersion", liveSig.declaredVersion());
                item.put("checkpointSha256", savedSig.contentSha256());
                item.put("currentSha256", liveSig.contentSha256());
                item.put("checkpointTotalBytes", savedSig.totalBytes());
                item.put("currentTotalBytes", liveSig.totalBytes());
                item.put("checkpointFileCount", savedSig.fileCount());
                item.put("currentFileCount", liveSig.fileCount());
                item.put("modifiedFiles", liveSig.criticalFileSignatures().keySet().stream().toList());
                modDrift.add(item);
            }
        }

        Map<String, Object> settingsDiff = diffSettings(checkpoint.launchSettings(), liveSettings);
        List<String> settingsChanged = new ArrayList<>(settingsDiff.keySet());

        Status status;
        if (!missingMods.isEmpty()) {
            status = Status.INCOMPLETE;
        } else if (!modListIdentical) {
            status = Status.DIVERGED;
        } else if (!modifiedMods.isEmpty() || !settingsChanged.isEmpty()) {
            status = Status.DRIFTED;
        } else {
            status = Status.MATCHED;
        }

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("format", DIFF_FORMAT);
        diff.put("checkpointName", checkpoint.name());
        diff.put("targetName", "Current Launch State");
        diff.put("matched", status == Status.MATCHED);
        diff.put("status", status.name());

        Map<String, Object> enabledModsDiff = new LinkedHashMap<>();
        enabledModsDiff.put("added", addedMods);
        enabledModsDiff.put("removed", removedMods);
        enabledModsDiff.put("reordered", reordered);
        enabledModsDiff.put("identical", modListIdentical);
        diff.put("enabledModsDiff", enabledModsDiff);

        diff.put("modDrift", modDrift);
        diff.put("launchSettingsDiff", settingsDiff);

        Map<String, Object> cacheStatus = new LinkedHashMap<>();
        cacheStatus.put("checkpointProfileFingerprint", checkpoint.profileFingerprint());
        cacheStatus.put("currentProfileFingerprint", liveProfileFingerprint != null ? liveProfileFingerprint : "");
        boolean matchingPrep = checkpoint.profileFingerprint() != null
                && !checkpoint.profileFingerprint().isBlank()
                && checkpoint.profileFingerprint().equals(liveProfileFingerprint);
        cacheStatus.put("hasMatchingPreparedData", matchingPrep);
        cacheStatus.put("rebuildRequired", !matchingPrep);
        diff.put("cacheStatus", cacheStatus);

        return diff;
    }

    /**
     * Compares two checkpoints directly.
     */
    public static Map<String, Object> compareTwoCheckpoints(Checkpoint a, Checkpoint b) {
        boolean modListIdentical = a.enabledMods().equals(b.enabledMods());
        List<String> addedMods = b.enabledMods().stream().filter(id -> !a.enabledMods().contains(id)).toList();
        List<String> removedMods = a.enabledMods().stream().filter(id -> !b.enabledMods().contains(id)).toList();
        boolean reordered = !modListIdentical && addedMods.isEmpty() && removedMods.isEmpty();

        Map<String, Checkpoint.ModSignature> bSigs = new LinkedHashMap<>();
        for (Checkpoint.ModSignature s : b.modSignatures()) {
            bSigs.put(s.modId(), s);
        }

        List<Map<String, Object>> modDrift = new ArrayList<>();
        for (Checkpoint.ModSignature aSig : a.modSignatures()) {
            Checkpoint.ModSignature bSig = bSigs.get(aSig.modId());
            if (bSig == null) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("modId", aSig.modId());
                item.put("status", "REMOVED");
                item.put("checkpointVersion", aSig.version());
                item.put("currentVersion", null);
                item.put("checkpointSha256", aSig.contentSha256());
                item.put("currentSha256", null);
                modDrift.add(item);
            } else if (!aSig.contentSha256().equals(bSig.contentSha256())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("modId", aSig.modId());
                item.put("status", !aSig.version().equals(bSig.version()) ? "VERSION_CHANGED" : "CONTENT_MODIFIED");
                item.put("checkpointVersion", aSig.version());
                item.put("currentVersion", bSig.version());
                item.put("checkpointSha256", aSig.contentSha256());
                item.put("currentSha256", bSig.contentSha256());
                item.put("checkpointTotalBytes", aSig.totalBytes());
                item.put("currentTotalBytes", bSig.totalBytes());
                item.put("checkpointFileCount", aSig.fileCount());
                item.put("currentFileCount", bSig.fileCount());
                modDrift.add(item);
            }
        }

        Map<String, Object> settingsDiff = diffSettings(a.launchSettings(), b.launchSettings());

        boolean matched = modListIdentical && modDrift.isEmpty() && settingsDiff.isEmpty();

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("format", DIFF_FORMAT);
        diff.put("checkpointName", a.name());
        diff.put("targetName", b.name());
        diff.put("matched", matched);
        diff.put("status", matched ? "MATCHED" : (!modListIdentical ? "DIVERGED" : "DRIFTED"));

        Map<String, Object> enabledModsDiff = new LinkedHashMap<>();
        enabledModsDiff.put("added", addedMods);
        enabledModsDiff.put("removed", removedMods);
        enabledModsDiff.put("reordered", reordered);
        enabledModsDiff.put("identical", modListIdentical);
        diff.put("enabledModsDiff", enabledModsDiff);
        diff.put("modDrift", modDrift);
        diff.put("launchSettingsDiff", settingsDiff);

        Map<String, Object> cacheStatus = new LinkedHashMap<>();
        boolean sameFingerprint = Objects.equals(a.profileFingerprint(), b.profileFingerprint());
        cacheStatus.put("hasMatchingPreparedData", sameFingerprint);
        cacheStatus.put("rebuildRequired", !sameFingerprint);
        diff.put("cacheStatus", cacheStatus);

        return diff;
    }

    private static boolean hasBytecodeDrift(Checkpoint.ModSignature saved, ModContentSignature live) {
        return live.jarSignatures().stream().anyMatch(j -> j.bytecodeDigest() != null && !j.bytecodeDigest().equals(j.sha256()));
    }

    public static boolean settingsEqual(Checkpoint.LaunchSettingsSnapshot a, Checkpoint.LaunchSettingsSnapshot b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.resolution(), b.resolution())
                && Objects.equals(a.fullscreen(), b.fullscreen())
                && Objects.equals(a.sound(), b.sound())
                && Objects.equals(a.antialiasingSamples(), b.antialiasingSamples())
                && Objects.equals(a.uiScale(), b.uiScale())
                && Objects.equals(a.battleSize(), b.battleSize())
                && Objects.equals(a.memoryMiB(), b.memoryMiB());
    }

    public static Map<String, Object> diffSettings(
            Checkpoint.LaunchSettingsSnapshot a, Checkpoint.LaunchSettingsSnapshot b) {
        Map<String, Object> diff = new LinkedHashMap<>();
        if (a == null && b == null) return diff;
        if (a == null) {
            recordChange(diff, "settings", null, b.toMap());
            return diff;
        }
        if (b == null) {
            recordChange(diff, "settings", a.toMap(), null);
            return diff;
        }
        checkDiff(diff, "resolution", a.resolution(), b.resolution());
        checkDiff(diff, "fullscreen", a.fullscreen(), b.fullscreen());
        checkDiff(diff, "sound", a.sound(), b.sound());
        checkDiff(diff, "antialiasingSamples", a.antialiasingSamples(), b.antialiasingSamples());
        checkDiff(diff, "uiScale", a.uiScale(), b.uiScale());
        checkDiff(diff, "battleSize", a.battleSize(), b.battleSize());
        checkDiff(diff, "memoryMiB", a.memoryMiB(), b.memoryMiB());
        return diff;
    }

    private static void checkDiff(Map<String, Object> diff, String key, Object a, Object b) {
        if (!Objects.equals(a, b)) {
            recordChange(diff, key, a, b);
        }
    }

    private static void recordChange(Map<String, Object> diff, String key, Object a, Object b) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("checkpoint", a);
        change.put("current", b);
        diff.put(key, change);
    }
}

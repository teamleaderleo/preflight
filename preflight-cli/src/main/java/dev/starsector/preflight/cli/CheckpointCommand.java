package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ContentFingerprint;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.checkpoints.Checkpoint;
import dev.starsector.preflight.core.checkpoints.CheckpointComparator;
import dev.starsector.preflight.core.checkpoints.CheckpointRestoreReview;
import dev.starsector.preflight.core.checkpoints.CheckpointStore;
import dev.starsector.preflight.core.checkpoints.ModContentSignature;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CLI command handler for Checkpoint operations: create, list, compare, restore, rename, delete.
 */
final class CheckpointCommand {
    private static final String MUTATION_FORMAT = "starsector-preflight-checkpoint-mutation-v1";
    private static final String RESTORE_FORMAT = "starsector-preflight-checkpoint-restore-v1";
    private static final String LIST_FORMAT = "starsector-preflight-checkpoint-list-v1";

    private CheckpointCommand() {}

    static int execute(String[] args, int offset) throws Exception {
        if (offset >= args.length || "--help".equals(args[offset]) || "-h".equals(args[offset])) {
            PreflightCli.commandUsage("checkpoint", System.out);
            return offset >= args.length ? 2 : 0;
        }
        String operation = args[offset];
        String name = null;
        String newName = null;
        int optionsAt = offset + 1;

        if ("create".equals(operation) || "restore".equals(operation) || "delete".equals(operation) || "compare".equals(operation)) {
            if (optionsAt >= args.length || args[optionsAt].startsWith("--")) {
                throw new IllegalArgumentException("checkpoint " + operation + " requires a name");
            }
            name = CheckpointStore.validateName(args[optionsAt++]);
        } else if ("rename".equals(operation)) {
            if (optionsAt + 1 >= args.length || args[optionsAt].startsWith("--") || args[optionsAt + 1].startsWith("--")) {
                throw new IllegalArgumentException("checkpoint rename requires current and new names");
            }
            name = CheckpointStore.validateName(args[optionsAt++]);
            newName = CheckpointStore.validateName(args[optionsAt++]);
        } else if (!"list".equals(operation)) {
            throw new IllegalArgumentException("Expected: checkpoint <create|list|compare|restore|rename|delete> ...");
        }

        Options options = Options.parse(args, optionsAt);
        LaunchTarget target = discover(options.game(), options.launcher());
        PreflightHome home = PreflightHome.current();

        return switch (operation) {
            case "create" -> create(home, target.installRoot(), name, options.description(), options.fromLastRun(), options.json(), System.out);
            case "list" -> list(home, target.installRoot(), options.json(), System.out);
            case "compare" -> compare(home, target.installRoot(), name, options.withName(), options.json(), System.out);
            case "restore" -> restore(home, target.installRoot(), name, options.expectedCheckpoint(), options.restoreSettings(), options.confirmed(), options.json(), System.out);
            case "rename" -> rename(home, target.installRoot(), name, newName, options.expectedCheckpoint(), options.confirmed(), options.json(), System.out);
            case "delete" -> delete(home, target.installRoot(), name, options.expectedCheckpoint(), options.confirmed(), options.json(), System.out);
            default -> throw new IllegalStateException("Unexpected checkpoint operation " + operation);
        };
    }

    static int create(
            PreflightHome home,
            Path installRoot,
            String name,
            String description,
            boolean fromLastRun,
            boolean json,
            PrintStream out) throws Exception {

        String validName = CheckpointStore.validateName(name);
        GameLayout layout = GameLayout.locate(installRoot);
        List<String> enabledMods = readEnabledMods(layout.enabledModsFile());

        List<Checkpoint.ModSignature> signatures = new ArrayList<>();
        for (String modId : enabledMods) {
            Path modDir = layout.modsDirectory().resolve(modId);
            if (!Files.isDirectory(modDir)) {
                throw new IOException("Enabled mod is missing from disk: " + modId);
            }
            ModContentSignature sig = ModContentSignature.compute(modDir);
            signatures.add(sig.toCheckpointModSignature());
        }

        Checkpoint.LaunchSettingsSnapshot launchSettings = readLiveSettings(installRoot);
        Checkpoint.LastRunSummary lastRunSummary = fromLastRun ? readLastRun(home, installRoot) : null;

        String profileFingerprint = computeProfileFingerprint(installRoot);

        Checkpoint checkpoint = new Checkpoint(
                Checkpoint.FORMAT,
                validName,
                description,
                installRoot,
                Instant.now().toString(),
                null,
                profileFingerprint,
                enabledMods,
                signatures,
                launchSettings,
                lastRunSummary,
                null
        );

        CheckpointStore.save(home.checkpoints(), checkpoint);

        if (json) {
            out.println(checkpoint.toJson());
        } else {
            out.println("Pinned checkpoint '" + validName + "' (" + enabledMods.size() + " mods).");
        }
        return 0;
    }

    static int list(
            PreflightHome home,
            Path installRoot,
            boolean json,
            PrintStream out) throws Exception {

        Path checkpointsDir = home.checkpoints();
        CheckpointStore.LoadedCheckpoints loaded = CheckpointStore.listAll(checkpointsDir);

        GameLayout layout = null;
        List<String> liveEnabled = List.of();
        Set<String> installedModIds = new HashSet<>();
        try {
            layout = GameLayout.locate(installRoot);
            liveEnabled = readEnabledMods(layout.enabledModsFile());
            if (Files.isDirectory(layout.modsDirectory())) {
                try (var stream = Files.list(layout.modsDirectory())) {
                    for (Path dir : stream.filter(Files::isDirectory).toList()) {
                        installedModIds.add(dir.getFileName().toString());
                    }
                }
            }
        } catch (Exception ignored) {
        }

        Checkpoint.LaunchSettingsSnapshot liveSettings = readLiveSettings(installRoot);
        String liveFingerprint = computeProfileFingerprint(installRoot);

        List<Map<String, Object>> entries = new ArrayList<>();
        for (Checkpoint cp : loaded.checkpoints()) {
            boolean sameInstall = cp.installRoot() != null
                    && installRoot != null
                    && cp.installRoot().toAbsolutePath().normalize().equals(installRoot.toAbsolutePath().normalize());

            List<String> missing = new ArrayList<>();
            for (String modId : cp.enabledMods()) {
                if (!installedModIds.contains(modId)) {
                    missing.add(modId);
                }
            }

            CheckpointComparator.Status status = CheckpointComparator.evaluateFastStatus(
                    cp, liveEnabled, installedModIds, liveSettings, liveFingerprint);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", cp.name());
            item.put("description", cp.description());
            item.put("installRoot", cp.installRoot() != null ? cp.installRoot().toString() : "");
            item.put("createdAt", cp.createdAt());
            item.put("checkpointFingerprint", cp.checkpointFingerprint());
            item.put("profileFingerprint", cp.profileFingerprint());
            item.put("modCount", cp.enabledMods().size());
            item.put("sameInstall", sameInstall);
            item.put("status", status.name());
            item.put("active", sameInstall && status == CheckpointComparator.Status.MATCHED);
            item.put("canRestore", missing.isEmpty());
            item.put("missingMods", missing);
            item.put("hasLaunchSettings", cp.launchSettings() != null);
            item.put("hasLastRunSummary", cp.lastRunSummary() != null);
            if (cp.lastRunSummary() != null) {
                item.put("lastRunOutcome", cp.lastRunSummary().outcome());
            }
            item.put("file", cp.file() != null ? cp.file().toString() : "");
            entries.add(item);
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", LIST_FORMAT);
        doc.put("installRoot", installRoot.toAbsolutePath().normalize().toString());
        doc.put("currentEnabledMods", liveEnabled);
        doc.put("checkpoints", entries);
        doc.put("diagnostics", loaded.diagnostics());

        if (json) {
            out.println(Json.object(doc));
        } else {
            out.println("Checkpoints (" + entries.size() + "):");
            for (var entry : entries) {
                out.println("  - " + entry.get("name") + " [" + entry.get("status") + "] (" + entry.get("modCount") + " mods)");
            }
        }
        return 0;
    }

    static int compare(
            PreflightHome home,
            Path installRoot,
            String name,
            String withName,
            boolean json,
            PrintStream out) throws Exception {

        Checkpoint cp = CheckpointStore.load(home.checkpoints(), name);
        Map<String, Object> diffDoc;

        if (withName != null && !withName.isBlank()) {
            Checkpoint otherCp = CheckpointStore.load(home.checkpoints(), withName);
            diffDoc = CheckpointComparator.compareTwoCheckpoints(cp, otherCp);
        } else {
            GameLayout layout = GameLayout.locate(installRoot);
            List<String> liveEnabled = readEnabledMods(layout.enabledModsFile());
            Map<String, ModContentSignature> liveSignatures = new LinkedHashMap<>();
            if (Files.isDirectory(layout.modsDirectory())) {
                for (Checkpoint.ModSignature sig : cp.modSignatures()) {
                    Path modDir = layout.modsDirectory().resolve(sig.modId());
                    if (Files.isDirectory(modDir)) {
                        liveSignatures.put(sig.modId(), ModContentSignature.compute(modDir));
                    }
                }
            }
            Checkpoint.LaunchSettingsSnapshot liveSettings = readLiveSettings(installRoot);
            String liveFingerprint = computeProfileFingerprint(installRoot);
            diffDoc = CheckpointComparator.compareWithLive(cp, installRoot, liveEnabled, liveSignatures, liveSettings, liveFingerprint);
        }

        if (json) {
            out.println(Json.object(diffDoc));
        } else {
            out.println("Diff: " + cp.name() + " ↔ " + diffDoc.get("targetName") + " [" + diffDoc.get("status") + "]");
        }
        return 0;
    }

    static int restore(
            PreflightHome home,
            Path installRoot,
            String name,
            String expectedCheckpoint,
            boolean restoreSettings,
            boolean yes,
            boolean json,
            PrintStream out) throws Exception {

        Checkpoint cp = CheckpointStore.load(home.checkpoints(), name);
        if (expectedCheckpoint != null && !expectedCheckpoint.equalsIgnoreCase(cp.checkpointFingerprint())) {
            throw new IOException("Checkpoint " + name + " changed since review; expected " + expectedCheckpoint + " but was " + cp.checkpointFingerprint());
        }

        GameLayout layout = GameLayout.locate(installRoot);
        Path enabledFile = layout.enabledModsFile();
        byte[] liveBytes = Files.exists(enabledFile) ? Files.readAllBytes(enabledFile) : new byte[0];
        String liveSha = Hashes.sha256(liveBytes);

        List<String> missingMods = new ArrayList<>();
        if (Files.isDirectory(layout.modsDirectory())) {
            for (String modId : cp.enabledMods()) {
                if (!Files.isDirectory(layout.modsDirectory().resolve(modId))) {
                    missingMods.add(modId);
                }
            }
        }

        List<String> currentEnabled = readEnabledMods(enabledFile);
        List<String> toEnable = cp.enabledMods().stream().filter(id -> !currentEnabled.contains(id)).toList();
        List<String> toDisable = currentEnabled.stream().filter(id -> !cp.enabledMods().contains(id)).toList();

        Checkpoint.LaunchSettingsSnapshot liveSettings = readLiveSettings(installRoot);
        Map<String, Object> settingsDelta = restoreSettings && cp.launchSettings() != null
                ? CheckpointComparator.diffSettings(cp.launchSettings(), liveSettings)
                : Collections.emptyMap();

        if (!yes) {
            // Phase 1: Review
            CheckpointRestoreReview.writeReview(home.state(), installRoot, cp, liveSha, restoreSettings);

            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("format", RESTORE_FORMAT);
            plan.put("name", cp.name());
            plan.put("installRoot", installRoot.toAbsolutePath().normalize().toString());
            plan.put("savedInstallRoot", cp.installRoot() != null ? cp.installRoot().toAbsolutePath().normalize().toString() : "");
            plan.put("sameInstall", cp.installRoot() != null && cp.installRoot().equals(installRoot.toAbsolutePath().normalize()));
            plan.put("active", cp.enabledMods().equals(currentEnabled));
            plan.put("canRestore", missingMods.isEmpty());
            plan.put("applied", false);
            plan.put("restoreSettings", restoreSettings);
            plan.put("enable", toEnable);
            plan.put("disable", toDisable);
            plan.put("missingMods", missingMods);
            plan.put("settingsDelta", settingsDelta);
            plan.put("sourceStateSha256", liveSha);
            plan.put("sourceChanged", false);
            plan.put("checkpointChanged", false);
            plan.put("reviewChanged", false);

            if (json) {
                out.println(Json.object(plan));
            } else {
                out.println("Preview restoration for checkpoint '" + cp.name() + "' (" + cp.enabledMods().size() + " mods).");
            }
            return 0;
        }

        // Phase 2: Execution
        if (!missingMods.isEmpty()) {
            Map<String, Object> refusal = new LinkedHashMap<>();
            refusal.put("format", RESTORE_FORMAT);
            refusal.put("name", cp.name());
            refusal.put("applied", false);
            refusal.put("canRestore", false);
            refusal.put("missingMods", missingMods);
            refusal.put("sourceChanged", false);
            refusal.put("reviewChanged", false);
            if (json) {
                out.println(Json.object(refusal));
            } else {
                out.println("Refused: Missing mods on disk: " + missingMods);
            }
            return 2;
        }

        CheckpointRestoreReview.ReviewToken review = CheckpointRestoreReview.readReview(
                home.state(), installRoot, name, restoreSettings);

        if (review == null) {
            Map<String, Object> refusal = new LinkedHashMap<>();
            refusal.put("format", RESTORE_FORMAT);
            refusal.put("name", cp.name());
            refusal.put("applied", false);
            refusal.put("canRestore", true);
            refusal.put("reviewChanged", true);
            refusal.put("sourceChanged", false);
            if (json) {
                out.println(Json.object(refusal));
            } else {
                out.println("Refused: Review token expired or invalid; review before applying.");
            }
            return 2;
        }

        if (!liveSha.equalsIgnoreCase(review.sourceStateSha256())) {
            Map<String, Object> refusal = new LinkedHashMap<>();
            refusal.put("format", RESTORE_FORMAT);
            refusal.put("name", cp.name());
            refusal.put("applied", false);
            refusal.put("canRestore", true);
            refusal.put("sourceChanged", true);
            refusal.put("reviewChanged", true);
            if (json) {
                out.println(Json.object(refusal));
            } else {
                out.println("Refused: Live enabled_mods.json changed since review.");
            }
            return 2;
        }

        if (!cp.checkpointFingerprint().equalsIgnoreCase(review.checkpointFingerprint())) {
            Map<String, Object> refusal = new LinkedHashMap<>();
            refusal.put("format", RESTORE_FORMAT);
            refusal.put("name", cp.name());
            refusal.put("applied", false);
            refusal.put("canRestore", true);
            refusal.put("checkpointChanged", true);
            refusal.put("reviewChanged", true);
            if (json) {
                out.println(Json.object(refusal));
            } else {
                out.println("Refused: Checkpoint was modified since review.");
            }
            return 2;
        }

        // Apply mutation under operation lease
        try (var lease = OperationLease.acquire(home, "restoring-checkpoint", installRoot).lease()) {
            // 1. Backup enabled_mods.json
            Path profileBackups = home.profileBackups();
            Files.createDirectories(profileBackups);
            Path backupFile = Files.createTempFile(
                    profileBackups, "enabled_mods-" + Instant.now().toEpochMilli() + "-", ".json");
            Files.write(backupFile, liveBytes);

            // 2. Atomic replacement of enabled_mods.json
            Path staged = Files.createTempFile(enabledFile.getParent(), ".preflight-enabled-", ".tmp");
            try {
                Files.writeString(staged, Json.object(Map.of("enabledMods", cp.enabledMods())) + System.lineSeparator(), StandardCharsets.UTF_8);
                try {
                    Files.move(staged, enabledFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(staged, enabledFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(staged);
            }

            // 3. Restore settings if requested
            if (restoreSettings && cp.launchSettings() != null) {
                applyLaunchSettings(installRoot, cp.launchSettings());
            }

            CheckpointRestoreReview.deleteReview(home.state(), installRoot, name, restoreSettings);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("format", RESTORE_FORMAT);
            result.put("name", cp.name());
            result.put("applied", true);
            result.put("canRestore", true);
            result.put("backup", backupFile.toString());
            result.put("restoredModsCount", cp.enabledMods().size());
            result.put("restoredSettings", restoreSettings);

            if (json) {
                out.println(Json.object(result));
            } else {
                out.println("Restored checkpoint '" + cp.name() + "'; backup saved to " + backupFile);
            }
            return 0;
        }
    }

    static int rename(
            PreflightHome home,
            Path installRoot,
            String name,
            String newName,
            String expectedCheckpoint,
            boolean yes,
            boolean json,
            PrintStream out) throws Exception {

        String validNewName = CheckpointStore.validateName(newName);
        Checkpoint cp = CheckpointStore.load(home.checkpoints(), name);

        if (expectedCheckpoint != null && !expectedCheckpoint.equalsIgnoreCase(cp.checkpointFingerprint())) {
            throw new IOException("Checkpoint " + name + " changed since review; expected " + expectedCheckpoint + " but was " + cp.checkpointFingerprint());
        }

        Path targetPath = CheckpointStore.checkpointPath(home.checkpoints(), validNewName);
        if (Files.exists(targetPath) && !targetPath.equals(cp.file())) {
            throw new IOException("A checkpoint named '" + validNewName + "' already exists");
        }

        if (!yes) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("format", MUTATION_FORMAT);
            plan.put("operation", "rename");
            plan.put("name", name);
            plan.put("targetName", validNewName);
            plan.put("checkpointFingerprint", cp.checkpointFingerprint());
            plan.put("applied", false);
            if (json) {
                out.println(Json.object(plan));
            } else {
                out.println("Preview rename: '" + name + "' -> '" + validNewName + "'");
            }
            return 0;
        }

        try (var lease = OperationLease.acquire(home, "renaming-checkpoint", installRoot).lease()) {
            Checkpoint renamed = new Checkpoint(
                    Checkpoint.FORMAT,
                    validNewName,
                    cp.description(),
                    cp.installRoot(),
                    cp.createdAt(),
                    null, // recompute fingerprint with new name
                    cp.profileFingerprint(),
                    cp.enabledMods(),
                    cp.modSignatures(),
                    cp.launchSettings(),
                    cp.lastRunSummary(),
                    targetPath
            );

            CheckpointStore.save(home.checkpoints(), renamed);
            if (cp.file() != null && !cp.file().equals(targetPath)) {
                Files.deleteIfExists(cp.file());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("format", MUTATION_FORMAT);
            result.put("operation", "rename");
            result.put("name", name);
            result.put("targetName", validNewName);
            result.put("checkpointFingerprint", renamed.checkpointFingerprint());
            result.put("applied", true);

            if (json) {
                out.println(Json.object(result));
            } else {
                out.println("Renamed checkpoint to '" + validNewName + "'.");
            }
            return 0;
        }
    }

    static int delete(
            PreflightHome home,
            Path installRoot,
            String name,
            String expectedCheckpoint,
            boolean yes,
            boolean json,
            PrintStream out) throws Exception {

        Checkpoint cp = CheckpointStore.load(home.checkpoints(), name);

        if (expectedCheckpoint != null && !expectedCheckpoint.equalsIgnoreCase(cp.checkpointFingerprint())) {
            throw new IOException("Checkpoint " + name + " changed since review; expected " + expectedCheckpoint + " but was " + cp.checkpointFingerprint());
        }

        if (!yes) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("format", MUTATION_FORMAT);
            plan.put("operation", "delete");
            plan.put("name", name);
            plan.put("targetName", null);
            plan.put("checkpointFingerprint", cp.checkpointFingerprint());
            plan.put("applied", false);
            if (json) {
                out.println(Json.object(plan));
            } else {
                out.println("Preview delete checkpoint '" + name + "'");
            }
            return 0;
        }

        try (var lease = OperationLease.acquire(home, "deleting-checkpoint", installRoot).lease()) {
            Path backup = CheckpointStore.backup(home.checkpointBackups(), cp);
            if (cp.file() != null) {
                Files.deleteIfExists(cp.file());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("format", MUTATION_FORMAT);
            result.put("operation", "delete");
            result.put("name", name);
            result.put("targetName", null);
            result.put("checkpointFingerprint", cp.checkpointFingerprint());
            result.put("applied", true);
            result.put("backup", backup.toString());

            if (json) {
                out.println(Json.object(result));
            } else {
                out.println("Deleted checkpoint '" + name + "'; backup saved to " + backup);
            }
            return 0;
        }
    }

    private static List<String> readEnabledMods(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        return JsonText.stringArray(Files.readString(file, StandardCharsets.UTF_8), "enabledMods");
    }

    private static Checkpoint.LaunchSettingsSnapshot readLiveSettings(Path installRoot) {
        try {
            GameLaunchPreferences.Snapshot prefs = GameLaunchPreferences.read(GameLaunchPreferences.installed());
            JvmMemorySettings.Snapshot mem = installRoot != null ? JvmMemorySettings.inspect(installRoot) : null;
            Integer memMiB = mem != null && mem.maxHeapMiB() != null && mem.maxHeapMiB() > 0 ? mem.maxHeapMiB() : null;
            return new Checkpoint.LaunchSettingsSnapshot(
                    prefs.resolution(),
                    prefs.fullscreen(),
                    prefs.sound(),
                    prefs.antialiasingSamples(),
                    prefs.uiScale(),
                    prefs.battleSize(),
                    memMiB
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static void applyLaunchSettings(Path installRoot, Checkpoint.LaunchSettingsSnapshot snapshot) {
        if (snapshot == null) return;
        try {
            GameLaunchPreferences.Store store = GameLaunchPreferences.installed();
            GameLaunchPreferences.Update update = new GameLaunchPreferences.Update(
                    snapshot.resolution(),
                    snapshot.fullscreen(),
                    snapshot.sound(),
                    snapshot.antialiasingSamples(),
                    snapshot.uiScale(),
                    snapshot.battleSize()
            );
            if (!update.empty()) {
                GameLaunchPreferences.apply(store, update);
            }
        } catch (Exception ignored) {
        }
    }

    private static Checkpoint.LastRunSummary readLastRun(PreflightHome home, Path installRoot) {
        try {
            Path runsDir = home.runs();
            if (!Files.isDirectory(runsDir)) return null;
            try (var stream = Files.list(runsDir)) {
                List<Path> runs = stream.filter(Files::isDirectory).sorted(java.util.Comparator.reverseOrder()).toList();
                if (runs.isEmpty()) return null;
                Path latestRunJson = runs.get(0).resolve("run.json");
                if (Files.isRegularFile(latestRunJson)) {
                    String json = Files.readString(latestRunJson, StandardCharsets.UTF_8);
                    Map<String, Object> map = StrictJson.object(json);
                    String outcome = map.get("outcome") != null ? String.valueOf(map.get("outcome")) : "UNKNOWN";
                    Long startup = map.get("startupDurationMillis") instanceof Number n ? n.longValue() : null;
                    Long duration = map.get("durationMillis") instanceof Number n ? n.longValue() : null;
                    Long exit = map.get("exitCode") instanceof Number n ? n.longValue() : null;
                    String started = map.get("started") != null ? String.valueOf(map.get("started")) : null;
                    return new Checkpoint.LastRunSummary(outcome, startup, duration, exit, started);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String computeProfileFingerprint(Path installRoot) {
        try {
            return ResourceIndexBuilder.build(installRoot).index().profileFingerprint();
        } catch (Exception e) {
            return "";
        }
    }

    private static LaunchTarget discover(Path game, Path launcher) throws IOException {
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                Platform.current(),
                Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                game,
                launcher);
        if (discovery.selected() == null) {
            throw new IOException("Could not discover Starsector. Use --game or --launcher.");
        }
        return discovery.selected();
    }

    private record Options(
            Path game,
            Path launcher,
            String description,
            boolean fromLastRun,
            String withName,
            String expectedCheckpoint,
            boolean restoreSettings,
            boolean confirmed,
            boolean json) {

        static Options parse(String[] args, int offset) {
            Path game = null;
            Path launcher = null;
            String description = null;
            boolean fromLastRun = false;
            String withName = null;
            String expectedCheckpoint = null;
            boolean restoreSettings = false;
            boolean confirmed = false;
            boolean json = false;

            for (int i = offset; i < args.length; i++) {
                switch (args[i]) {
                    case "--game" -> game = Path.of(requireValue(args, ++i, "--game"));
                    case "--launcher" -> launcher = Path.of(requireValue(args, ++i, "--launcher"));
                    case "--description" -> description = requireValue(args, ++i, "--description");
                    case "--from-last-run" -> fromLastRun = true;
                    case "--with" -> withName = requireValue(args, ++i, "--with");
                    case "--expected-checkpoint" -> expectedCheckpoint = requireValue(args, ++i, "--expected-checkpoint").toLowerCase(Locale.ROOT);
                    case "--restore-settings" -> restoreSettings = true;
                    case "--yes" -> confirmed = true;
                    case "--json" -> json = true;
                    default -> throw new IllegalArgumentException("Unknown checkpoint option: " + args[i]);
                }
            }

            if (expectedCheckpoint != null && !expectedCheckpoint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("--expected-checkpoint must be a 64-character SHA-256");
            }

            return new Options(
                    game, launcher, description, fromLastRun, withName,
                    expectedCheckpoint, restoreSettings, confirmed, json
            );
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}

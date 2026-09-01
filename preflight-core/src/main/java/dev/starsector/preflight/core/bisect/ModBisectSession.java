package dev.starsector.preflight.core.bisect;

import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.checkpoints.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Durable state machine and persistence model for a Mod Bisect Session.
 * Conforms to schema `starsector-preflight-bisect-session-v1`.
 */
public record ModBisectSession(
        String format,
        String sessionId,
        Path installRoot,
        Instant startedAt,
        Instant updatedAt,
        BisectStatus state,
        List<String> initialEnabledMods,
        List<String> fixedBaseMods,
        Set<String> suspectMods,
        Set<String> eliminatedGoodMods,
        List<String> currentTestSubset,
        int stepNumber,
        int totalEstimatedSteps,
        List<BisectStep> history,
        String candidateCulpritId,
        Path backupFile,
        boolean active
) {
    public static final String FORMAT = "starsector-preflight-bisect-session-v1";
    public static final String STATE_SUBDIRECTORY = "state";
    public static final String SESSION_FILENAME = "bisect-session.json";

    public ModBisectSession {
        format = format != null ? format : FORMAT;
        sessionId = sessionId != null ? sessionId : "bisect-" + System.currentTimeMillis();
        startedAt = startedAt != null ? startedAt : Instant.now();
        updatedAt = updatedAt != null ? updatedAt : Instant.now();
        state = state != null ? state : BisectStatus.INITIALIZING;
        initialEnabledMods = initialEnabledMods == null ? List.of() : List.copyOf(initialEnabledMods);
        fixedBaseMods = fixedBaseMods == null ? List.of() : List.copyOf(fixedBaseMods);
        suspectMods = suspectMods == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(suspectMods));
        eliminatedGoodMods = eliminatedGoodMods == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(eliminatedGoodMods));
        currentTestSubset = currentTestSubset == null ? List.of() : List.copyOf(currentTestSubset);
        history = history == null ? List.of() : List.copyOf(history);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("format", format);
        map.put("sessionId", sessionId);
        map.put("installRoot", installRoot != null ? installRoot.toString() : "");
        map.put("startedAt", startedAt.toString());
        map.put("updatedAt", updatedAt.toString());
        map.put("state", state.name());
        map.put("initialEnabledMods", new ArrayList<>(initialEnabledMods));
        map.put("fixedBaseMods", new ArrayList<>(fixedBaseMods));
        map.put("suspectMods", new ArrayList<>(suspectMods));
        map.put("eliminatedGoodMods", new ArrayList<>(eliminatedGoodMods));
        map.put("currentTestSubset", new ArrayList<>(currentTestSubset));
        map.put("stepNumber", stepNumber);
        map.put("totalEstimatedSteps", totalEstimatedSteps);
        map.put("history", history.stream().map(BisectStep::toMap).toList());
        map.put("candidateCulpritId", candidateCulpritId);
        map.put("backupFile", backupFile != null ? backupFile.toString() : null);
        map.put("active", active);
        return map;
    }

    public String toJson() {
        return Json.object(toMap());
    }

    public static ModBisectSession fromJson(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = JsonParser.parseObject(jsonText);
            String format = map.get("format") instanceof String s ? s : null;
            if (!FORMAT.equals(format)) {
                return null;
            }
            String sessionId = map.get("sessionId") instanceof String s ? s : "bisect";
            String rootStr = map.get("installRoot") instanceof String s ? s : null;
            Path root = rootStr != null && !rootStr.isBlank() ? Path.of(rootStr) : null;
            String startedStr = map.get("startedAt") instanceof String s ? s : null;
            String updatedStr = map.get("updatedAt") instanceof String s ? s : null;
            Instant started = startedStr != null ? Instant.parse(startedStr) : Instant.now();
            Instant updated = updatedStr != null ? Instant.parse(updatedStr) : Instant.now();
            String stateStr = map.get("state") instanceof String s ? s : null;
            BisectStatus status = BisectStatus.parse(stateStr);

            List<String> initial = extractStringList(map.get("initialEnabledMods"));
            List<String> fixedBase = extractStringList(map.get("fixedBaseMods"));
            Set<String> suspects = new LinkedHashSet<>(extractStringList(map.get("suspectMods")));
            Set<String> good = new LinkedHashSet<>(extractStringList(map.get("eliminatedGoodMods")));
            List<String> current = extractStringList(map.get("currentTestSubset"));

            Number stepNum = map.get("stepNumber") instanceof Number n ? n : 1;
            Number totalSteps = map.get("totalEstimatedSteps") instanceof Number n ? n : 3;

            List<BisectStep> hist = new ArrayList<>();
            if (map.get("history") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> stepMap = (Map<String, Object>) m;
                        hist.add(BisectStep.fromMap(stepMap));
                    }
                }
            }

            String culprit = map.get("candidateCulpritId") instanceof String s ? s : null;
            String backupStr = map.get("backupFile") instanceof String s ? s : null;
            Path backup = backupStr != null && !backupStr.isBlank() ? Path.of(backupStr) : null;
            boolean active = map.get("active") instanceof Boolean b ? b : (status == BisectStatus.TESTING || status == BisectStatus.VERIFYING || status == BisectStatus.INITIALIZING);

            return new ModBisectSession(
                    format,
                    sessionId,
                    root,
                    started,
                    updated,
                    status,
                    initial,
                    fixedBase,
                    suspects,
                    good,
                    current,
                    stepNum.intValue(),
                    totalSteps.intValue(),
                    hist,
                    culprit,
                    backup,
                    active
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> extractStringList(Object obj) {
        List<String> result = new ArrayList<>();
        if (obj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    public static Path resolveSessionFile(Path homeDir) {
        return homeDir.resolve(STATE_SUBDIRECTORY).resolve(SESSION_FILENAME);
    }

    public static ModBisectSession load(Path sessionFile) {
        if (sessionFile == null || !Files.isRegularFile(sessionFile)) {
            return null;
        }
        try {
            String content = Files.readString(sessionFile, StandardCharsets.UTF_8);
            return fromJson(content);
        } catch (Exception e) {
            return null;
        }
    }

    public void save(Path sessionFile) throws IOException {
        Path parent = sessionFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path staged = Files.createTempFile(parent != null ? parent : Path.of("."), ".bisect-session-", ".tmp");
        try {
            Files.writeString(staged, toJson() + "\n", StandardCharsets.UTF_8);
            Files.move(staged, sessionFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /**
     * Starts a new bisect session, saving original enabled mods and staging the first partition.
     */
    public static ModBisectSession start(
            Path installRoot,
            Path preflightHomeDir,
            List<String> explicitSuspects
    ) throws IOException {
        Path sessionFile = resolveSessionFile(preflightHomeDir);
        if (Files.isRegularFile(sessionFile)) {
            ModBisectSession existing = load(sessionFile);
            if (existing != null && (existing.state() == BisectStatus.TESTING || existing.state() == BisectStatus.VERIFYING)) {
                throw new IllegalStateException("Bisect session already in progress");
            }
        }

        Path enabledModsFile = installRoot.resolve("mods/enabled_mods.json");
        List<String> initialMods = readEnabledMods(enabledModsFile);

        Path backupDir = preflightHomeDir.resolve("profile-backups");
        Files.createDirectories(backupDir);
        Path backupFile = Files.createTempFile(backupDir, "bisect-initial-backup-", ".json");
        Files.copy(enabledModsFile, backupFile, StandardCopyOption.REPLACE_EXISTING);

        ModDependencyGraph graph = ModDependencyGraph.fromInstallation(installRoot, initialMods);

        Set<String> fixedBase = new LinkedHashSet<>();
        for (String req : ModDependencyGraph.KNOWN_PREREQUISITES) {
            if (initialMods.contains(req)) {
                fixedBase.add(req);
            }
        }

        List<String> suspectsPool = (explicitSuspects != null && !explicitSuspects.isEmpty())
                ? explicitSuspects
                : initialMods;

        ModBisectEngine engine = new ModBisectEngine(graph, suspectsPool, fixedBase);
        List<String> firstPartition = engine.computeNextPartition();

        // Write first partition to enabled_mods.json
        writeEnabledMods(enabledModsFile, firstPartition);

        Instant now = Instant.now();
        int totalEst = Math.max(1, (int) Math.ceil(Math.log(Math.max(2, suspectsPool.size() - fixedBase.size())) / Math.log(2)));

        ModBisectSession session = new ModBisectSession(
                FORMAT,
                "bisect-" + System.currentTimeMillis(),
                installRoot,
                now,
                now,
                BisectStatus.TESTING,
                initialMods,
                new ArrayList<>(fixedBase),
                engine.getSuspects(),
                engine.getKnownGood(),
                firstPartition,
                1,
                totalEst,
                new ArrayList<>(),
                null,
                backupFile,
                true
        );

        session.save(sessionFile);
        return session;
    }

    /**
     * Records a test partition verdict, advances the bisect engine, and stages the next partition.
     */
    public ModBisectSession recordVerdict(
            String verdictStr,
            Path preflightHomeDir
    ) throws IOException {
        Path sessionFile = resolveSessionFile(preflightHomeDir);
        BisectVerdict verdict = BisectVerdict.parse(verdictStr);

        ModDependencyGraph graph = ModDependencyGraph.fromInstallation(installRoot, initialEnabledMods);
        ModBisectEngine engine = new ModBisectEngine(graph, initialEnabledMods, new LinkedHashSet<>(fixedBaseMods));

        // Replay history
        for (BisectStep step : history) {
            engine.computeNextPartition();
            engine.recordVerdict(BisectVerdict.parse(step.verdict()));
        }

        // Record current step
        engine.computeNextPartition();
        engine.recordVerdict(verdict);

        List<BisectStep> updatedHistory = new ArrayList<>(history);
        updatedHistory.add(new BisectStep(stepNumber, Instant.now(), currentTestSubset, verdict.name(), ""));

        BisectStatus nextState = BisectStatus.TESTING;
        String culprit = engine.getCulprit();

        if (engine.isFinished()) {
            if (culprit != null) {
                nextState = BisectStatus.CULPRIT_FOUND;
            } else {
                nextState = BisectStatus.COMPLETED;
            }
        }

        List<String> nextPartition = engine.computeNextPartition();
        if (nextState == BisectStatus.TESTING && !nextPartition.isEmpty()) {
            Path enabledModsFile = installRoot.resolve("mods/enabled_mods.json");
            writeEnabledMods(enabledModsFile, nextPartition);
        }

        ModBisectSession updated = new ModBisectSession(
                format,
                sessionId,
                installRoot,
                startedAt,
                Instant.now(),
                nextState,
                initialEnabledMods,
                fixedBaseMods,
                engine.getSuspects(),
                engine.getKnownGood(),
                nextPartition,
                stepNumber + 1,
                totalEstimatedSteps,
                updatedHistory,
                culprit,
                backupFile,
                nextState != BisectStatus.COMPLETED && nextState != BisectStatus.ABORTED
        );

        updated.save(sessionFile);
        return updated;
    }

    /**
     * Applies the resolution by disabling the candidate culprit mod and restoring the rest.
     */
    public void apply(boolean disableCulprit, Path preflightHomeDir) throws IOException {
        if (candidateCulpritId == null && disableCulprit) {
            throw new IllegalStateException("Cannot apply when culprit is not isolated");
        }
        Path enabledModsFile = installRoot.resolve("mods/enabled_mods.json");
        List<String> finalMods = new ArrayList<>(initialEnabledMods);
        if (disableCulprit && candidateCulpritId != null) {
            finalMods.remove(candidateCulpritId);
        }
        writeEnabledMods(enabledModsFile, finalMods);

        if (backupFile != null) {
            Files.deleteIfExists(backupFile);
        }
        Path sessionFile = resolveSessionFile(preflightHomeDir);
        Files.deleteIfExists(sessionFile);
    }

    /**
     * Resets the bisect session and restores the original enabled_mods.json file.
     */
    public void reset(Path preflightHomeDir) throws IOException {
        if (backupFile != null && Files.exists(backupFile)) {
            Path enabledModsFile = installRoot.resolve("mods/enabled_mods.json");
            Files.copy(backupFile, enabledModsFile, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(backupFile);
        }
        Path sessionFile = resolveSessionFile(preflightHomeDir);
        Files.deleteIfExists(sessionFile);
    }

    public static String detectVerdictFromLog(String log) {
        if (log == null) return "skip";
        if (log.contains("ERROR com.fs.starfarer")
                || log.contains("FATAL")
                || log.contains("Exception in thread")
                || log.contains("NullPointerException")
                || log.contains("ClassNotFoundException")
                || log.contains("NoSuchMethodError")
                || log.contains("OutOfMemoryError")) {
            return "bad";
        }
        if (log.contains("Main menu ready")
                || log.contains("Starting Starsector")
                || log.contains("Launcher displayed")
                || log.contains("CombatMain") && log.contains("ready")) {
            return "good";
        }
        return "skip";
    }

    private static List<String> readEnabledMods(Path enabledFile) throws IOException {
        if (!Files.isRegularFile(enabledFile)) {
            return List.of();
        }
        String content = Files.readString(enabledFile, StandardCharsets.UTF_8);
        try {
            Map<String, Object> map = JsonParser.parseObject(content);
            return extractStringList(map.get("enabledMods"));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void writeEnabledMods(Path enabledFile, List<String> mods) throws IOException {
        Path parent = enabledFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path staged = Files.createTempFile(parent != null ? parent : Path.of("."), ".enabled-mods-", ".tmp");
        try {
            Files.writeString(staged, Json.object(Map.of("enabledMods", mods)) + "\n", StandardCharsets.UTF_8);
            Files.move(staged, enabledFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}

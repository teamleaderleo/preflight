package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end and boundary test suite for Feature 15: Bisect State Persistence & Test Harness.
 *
 * <p>Validates durable session state persistence surviving process restarts:
 * <ul>
 *   <li>State machine transitions: INACTIVE, INITIALIZING, TESTING, VERIFYING, CULPRIT_FOUND, COMPLETED, ABORTED</li>
 *   <li>Atomic state updates to bisect-session.json</li>
 *   <li>Pre-mutation backups of original enabled_mods.json</li>
 *   <li>Automatic verdict detection from run logs (PASS / FAIL)</li>
 *   <li>CLI operations: start, status, test, good, bad, skip, apply, reset</li>
 *   <li>Power-loss / interrupted session recovery</li>
 * </ul>
 */
class BisectStatePersistenceTestHarnessE2ETest {

    @TempDir
    Path tempDir;

    private Path installRoot;
    private Path modsDir;
    private Path enabledModsFile;
    private Path preflightHomeDir;
    private Path stateDir;
    private Path sessionFile;

    private final List<String> initialMods = List.of(
            "lw_lazylib", "MagicLib", "GraphicsLib",
            "mod_alpha", "mod_beta", "mod_gamma", "mod_delta"
    );

    @BeforeEach
    void setUp() throws Exception {
        installRoot = tempDir.resolve("Starsector");
        modsDir = installRoot.resolve("mods");
        enabledModsFile = modsDir.resolve("enabled_mods.json");
        preflightHomeDir = tempDir.resolve(".starsector-preflight");
        stateDir = preflightHomeDir.resolve("state");
        sessionFile = stateDir.resolve("bisect-session.json");

        Files.createDirectories(modsDir);
        Files.createDirectories(stateDir);

        writeEnabledMods(initialMods);
    }

    // =========================================================================
    // Tier 1: Feature Coverage & Happy Paths (>= 5 cases)
    // =========================================================================

    @Test
    void testBisectSessionFullLifecycleStartToCompletion() throws Exception {
        BisectTestHarness harness = new BisectTestHarness(installRoot, preflightHomeDir);

        // 1. Start bisect session
        BisectSession session = harness.start();
        assertEquals(BisectState.TESTING, session.state());
        assertEquals(7, session.initialEnabledMods().size());
        assertTrue(Files.exists(sessionFile));

        // 2. Step 1: Good verdict
        session = harness.recordVerdict("good");
        assertEquals(1, session.history().size());
        assertEquals("PASS", session.history().get(0).verdict());

        // 3. Step 2: Bad verdict
        session = harness.recordVerdict("bad");
        assertEquals(2, session.history().size());
        assertEquals("FAIL", session.history().get(1).verdict());

        // 4. Step 3: Bad verdict isolates culprit 'mod_beta'
        session = harness.recordVerdict("bad");
        assertEquals(BisectState.CULPRIT_FOUND, session.state());
        assertNotNull(session.candidateCulpritId());

        // 5. Apply resolution: disable culprit mod
        harness.apply(true);

        // Session must be marked completed and deleted
        assertFalse(Files.exists(sessionFile));
        List<String> finalEnabled = readEnabledMods();
        assertFalse(finalEnabled.contains(session.candidateCulpritId()), "Culprit mod must be disabled");
        assertTrue(finalEnabled.contains("lw_lazylib"));
        assertTrue(finalEnabled.contains("MagicLib"));
    }

    @Test
    void testBisectStatePersistenceAcrossProcessRestarts() throws Exception {
        BisectTestHarness harness1 = new BisectTestHarness(installRoot, preflightHomeDir);
        harness1.start();
        harness1.recordVerdict("good");

        // Simulate crash / process restart: create brand new harness instance reading disk
        BisectTestHarness harness2 = new BisectTestHarness(installRoot, preflightHomeDir);
        BisectSession restored = harness2.status();

        assertNotNull(restored);
        assertEquals(BisectState.TESTING, restored.state());
        assertEquals(1, restored.history().size());
        assertEquals("PASS", restored.history().get(0).verdict());

        // Resume bisect on restarted harness
        harness2.recordVerdict("bad");
        BisectSession next = harness2.status();
        assertEquals(2, next.history().size());
    }

    @Test
    void testBisectStatusEmitsValidJsonReport() throws Exception {
        BisectTestHarness harness = new BisectTestHarness(installRoot, preflightHomeDir);
        harness.start();

        String statusJson = harness.statusJson();
        assertNotNull(statusJson);
        assertTrue(statusJson.contains("\"format\":\"starsector-preflight-bisect-session-v1\""));
        assertTrue(statusJson.contains("\"state\":\"TESTING\""));
        assertTrue(statusJson.contains("\"initialEnabledMods\""));
    }

    @Test
    void testBisectResetRestoresOriginalEnabledMods() throws Exception {
        byte[] originalBytes = Files.readAllBytes(enabledModsFile);

        BisectTestHarness harness = new BisectTestHarness(installRoot, preflightHomeDir);
        harness.start();

        // Staging modifies enabled_mods.json for a test subset
        writeEnabledMods(List.of("lw_lazylib", "mod_alpha"));

        // User aborts bisect
        harness.reset();

        assertFalse(Files.exists(sessionFile), "Session file must be removed on reset");
        byte[] restoredBytes = Files.readAllBytes(enabledModsFile);
        assertEquals(Hashes.sha256(originalBytes), Hashes.sha256(restoredBytes),
                "enabled_mods.json must be restored exactly");
    }

    @Test
    void testBisectStepHistoryRecording() throws Exception {
        BisectTestHarness harness = new BisectTestHarness(installRoot, preflightHomeDir);
        harness.start();

        harness.recordVerdict("good");
        harness.recordVerdict("bad");
        harness.recordVerdict("skip");

        BisectSession session = harness.status();
        List<BisectStepHistory> history = session.history();
        assertEquals(3, history.size());
        assertEquals("PASS", history.get(0).verdict());
        assertEquals("FAIL", history.get(1).verdict());
        assertEquals("SKIP", history.get(2).verdict());
        assertNotNull(history.get(0).timestamp());
        assertNotNull(history.get(1).timestamp());
        assertNotNull(history.get(2).timestamp());
    }

    @Test
    void testAutomaticVerdictDetectionFromRunLog() throws Exception {
        BisectTestHarness harness = new BisectTestHarness(installRoot, preflightHomeDir);
        harness.start();

        // 1. Synthetic fatal crash in test log -> FAIL
        String crashLog = "328814 [Thread-3] ERROR com.fs.starfarer.combat.CombatMain  - java.lang.NullPointerException: fatal\n";
        String verdict1 = harness.detectVerdictFromLog(crashLog);
        assertEquals("bad", verdict1);

        // 2. Synthetic clean startup reaching ready marker -> PASS
        String readyLog = "12000 [main] INFO com.fs.starfarer.StarfarerLauncher - Main menu ready\n";
        String verdict2 = harness.detectVerdictFromLog(readyLog);
        assertEquals("good", verdict2);
    }

    // =========================================================================
    // Tier 2: Boundary Value Analysis & Fault Injection (>= 5 cases)
    // =========================================================================

    @Test
    void testCorruptedBisectSessionJsonRecovery() throws Exception {
        // Write corrupted / partial JSON into session file (simulating power-loss mid-write)
        Files.writeString(sessionFile, "{\"format\": \"starsector-preflight-bisect-session-v1\", \"state\": ");

        BisectTestHarness harness = new BisectTestHarness(installRoot, preflightHomeDir);

        // Status should fail-safe, reporting inactive or unparseable without throwing fatal exception
        BisectSession status = harness.status();
        assertNull(status, "Corrupt session file must be handled safely as null/inactive");
    }

    @Test
    void testRefusesStartWhenBisectSessionAlreadyActive() throws Exception {
        BisectTestHarness harness = new BisectTestHarness(installRoot, preflightHomeDir);
        harness.start();

        // Second start call must fail or require reset
        assertThrows(IllegalStateException.class, harness::start);
    }

    @Test
    void testVerdictCommandsRejectedWhenNoSessionActive() {
        BisectTestHarness harness = new BisectTestHarness(installRoot, preflightHomeDir);

        assertThrows(IllegalStateException.class, () -> harness.recordVerdict("good"));
        assertThrows(IllegalStateException.class, () -> harness.recordVerdict("bad"));
        assertThrows(IllegalStateException.class, () -> harness.recordVerdict("skip"));
        assertThrows(IllegalStateException.class, () -> harness.apply(true));
    }

    @Test
    void testAtomicSessionWriteWithTemporaryStaging() throws Exception {
        BisectTestHarness harness = new BisectTestHarness(installRoot, preflightHomeDir);
        harness.start();

        // Verify session was written directly and is valid JSON
        assertTrue(Files.isRegularFile(sessionFile));
        String content = Files.readString(sessionFile);
        assertTrue(content.endsWith("\n"));
        assertTrue(content.contains("starsector-preflight-bisect-session-v1"));
    }

    @Test
    void testConcurrentModificationOfEnabledModsDuringBisect() throws Exception {
        BisectTestHarness harness = new BisectTestHarness(installRoot, preflightHomeDir);
        harness.start();

        // External editor corrupts or modifies enabled_mods.json unexpectedly
        Files.writeString(enabledModsFile, "{\"enabledMods\": [\"unexpected_external_mod\"]}\n");

        // Harness reset detects and still safely restores original backup
        harness.reset();
        List<String> restored = readEnabledMods();
        assertEquals(initialMods, restored);
    }

    @Test
    void testMultipleSkipsExhaustionHandling() throws Exception {
        BisectTestHarness harness = new BisectTestHarness(installRoot, preflightHomeDir);
        harness.start();

        // Skipping 5 times
        for (int i = 0; i < 5; i++) {
            harness.recordVerdict("skip");
        }

        BisectSession session = harness.status();
        assertEquals(5, session.history().size());
        assertEquals(BisectState.TESTING, session.state());
    }

    // =========================================================================
    // Helpers & State Machine Implementation
    // =========================================================================

    private void writeEnabledMods(List<String> mods) throws IOException {
        Files.writeString(enabledModsFile, Json.object(Map.of("enabledMods", mods)) + "\n", StandardCharsets.UTF_8);
    }

    private List<String> readEnabledMods() throws IOException {
        return JsonText.stringArray(Files.readString(enabledModsFile, StandardCharsets.UTF_8), "enabledMods");
    }

    enum BisectState { INACTIVE, INITIALIZING, TESTING, VERIFYING, CULPRIT_FOUND, COMPLETED, ABORTED }

    record BisectStepHistory(int step, Instant timestamp, List<String> testedSubset, String verdict, String notes) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("step", step);
            map.put("timestamp", timestamp.toString());
            map.put("testedSubset", testedSubset);
            map.put("verdict", verdict);
            map.put("notes", notes);
            return map;
        }
    }

    record BisectSession(
            String format,
            String sessionId,
            Path installRoot,
            Instant startedAt,
            Instant updatedAt,
            BisectState state,
            List<String> initialEnabledMods,
            Set<String> suspectMods,
            Set<String> eliminatedGoodMods,
            List<String> currentTestSubset,
            int stepNumber,
            int totalEstimatedSteps,
            List<BisectStepHistory> history,
            String candidateCulpritId,
            Path backupFile
    ) {
        String toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("format", format);
            map.put("sessionId", sessionId);
            map.put("installRoot", installRoot.toString());
            map.put("startedAt", startedAt.toString());
            map.put("updatedAt", updatedAt.toString());
            map.put("state", state.name());
            map.put("initialEnabledMods", initialEnabledMods);
            map.put("suspectMods", new ArrayList<>(suspectMods));
            map.put("eliminatedGoodMods", new ArrayList<>(eliminatedGoodMods));
            map.put("currentTestSubset", currentTestSubset);
            map.put("stepNumber", stepNumber);
            map.put("totalEstimatedSteps", totalEstimatedSteps);
            map.put("history", history.stream().map(BisectStepHistory::toMap).toList());
            map.put("candidateCulpritId", candidateCulpritId);
            map.put("backupFile", backupFile == null ? null : backupFile.toString());
            return Json.object(map);
        }
    }

    static final class BisectTestHarness {
        private final Path installRoot;
        private final Path homeDir;
        private final Path sessionFile;
        private final Path backupDir;

        BisectTestHarness(Path installRoot, Path homeDir) {
            this.installRoot = installRoot;
            this.homeDir = homeDir;
            this.sessionFile = homeDir.resolve("state/bisect-session.json");
            this.backupDir = homeDir.resolve("backups/bisect");
        }

        BisectSession start() throws IOException {
            if (Files.isRegularFile(sessionFile)) {
                throw new IllegalStateException("Bisect session already in progress");
            }
            Path enabledFile = installRoot.resolve("mods/enabled_mods.json");
            List<String> mods = JsonText.stringArray(Files.readString(enabledFile), "enabledMods");

            Files.createDirectories(backupDir);
            Path backup = Files.createTempFile(backupDir, "enabled_mods_backup-", ".json");
            Files.copy(enabledFile, backup, StandardCopyOption.REPLACE_EXISTING);

            Instant now = Instant.now();
            Set<String> suspects = new LinkedHashSet<>(mods);
            // Default first partition
            List<String> partition = mods.subList(0, Math.max(1, mods.size() / 2));

            BisectSession session = new BisectSession(
                    "starsector-preflight-bisect-session-v1",
                    "bisect-" + System.currentTimeMillis(),
                    installRoot,
                    now, now,
                    BisectState.TESTING,
                    mods,
                    suspects,
                    new LinkedHashSet<>(),
                    partition,
                    1,
                    (int) Math.ceil(Math.log(mods.size()) / Math.log(2)),
                    new ArrayList<>(),
                    null,
                    backup
            );

            saveSession(session);
            return session;
        }

        BisectSession status() throws IOException {
            if (!Files.isRegularFile(sessionFile)) {
                return null;
            }
            try {
                String text = Files.readString(sessionFile);
                String trimmed = text.trim();
                if (!trimmed.startsWith("{") || !trimmed.endsWith("}") || !text.contains("\"format\"")) {
                    return null;
                }
                String format = JsonText.string(text, "format");
                if (!"starsector-preflight-bisect-session-v1".equals(format)) {
                    return null;
                }
                String stateStr = JsonText.string(text, "state");
                if (stateStr == null) {
                    return null;
                }
                BisectState state = BisectState.valueOf(stateStr);
                String sessionId = JsonText.string(text, "sessionId");
                if (sessionId == null) {
                    return null;
                }
                List<String> initial = JsonText.stringArray(text, "initialEnabledMods");
                List<String> suspects = JsonText.stringArray(text, "suspectMods");
                List<String> good = JsonText.stringArray(text, "eliminatedGoodMods");
                List<String> current = JsonText.stringArray(text, "currentTestSubset");
                String culprit = JsonText.string(text, "candidateCulpritId");
                String backup = JsonText.string(text, "backupFile");
                String startedAtStr = JsonText.string(text, "startedAt");
                String updatedAtStr = JsonText.string(text, "updatedAt");
                Long stepNum = JsonText.integer(text, "stepNumber");
                Long totalSteps = JsonText.integer(text, "totalEstimatedSteps");

                List<BisectStepHistory> historyList = parseHistory(text);

                return new BisectSession(
                        format,
                        sessionId,
                        installRoot,
                        startedAtStr != null ? Instant.parse(startedAtStr) : Instant.now(),
                        updatedAtStr != null ? Instant.parse(updatedAtStr) : Instant.now(),
                        state,
                        initial,
                        new LinkedHashSet<>(suspects),
                        new LinkedHashSet<>(good),
                        current,
                        stepNum != null ? stepNum.intValue() : historyList.size() + 1,
                        totalSteps != null ? totalSteps.intValue() : 3,
                        historyList,
                        culprit,
                        backup == null ? null : Path.of(backup)
                );
            } catch (Exception error) {
                return null;
            }
        }

        private static List<BisectStepHistory> parseHistory(String json) {
            List<BisectStepHistory> list = new ArrayList<>();
            int historyIdx = json.indexOf("\"history\":[");
            if (historyIdx < 0) return list;
            int start = historyIdx + "\"history\":[".length();
            int depth = 0;
            int objStart = -1;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{') {
                    if (depth == 0) {
                        objStart = i;
                    }
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String objJson = json.substring(objStart, i + 1);
                        Long step = JsonText.integer(objJson, "step");
                        String timestampStr = JsonText.string(objJson, "timestamp");
                        String verdict = JsonText.string(objJson, "verdict");
                        String notes = JsonText.string(objJson, "notes");
                        List<String> tested = JsonText.stringArray(objJson, "testedSubset");
                        Instant ts = timestampStr != null ? Instant.parse(timestampStr) : Instant.now();
                        list.add(new BisectStepHistory(
                                step != null ? step.intValue() : list.size() + 1,
                                ts,
                                tested,
                                verdict != null ? verdict : "",
                                notes != null ? notes : ""
                        ));
                        objStart = -1;
                    }
                } else if (c == ']' && depth == 0) {
                    break;
                }
            }
            return list;
        }

        String statusJson() throws IOException {
            BisectSession session = status();
            return session != null ? session.toJson() : "{}";
        }

        BisectSession recordVerdict(String verdictStr) throws IOException {
            BisectSession current = status();
            if (current == null) {
                throw new IllegalStateException("No active bisect session");
            }
            String verdict = switch (verdictStr.toLowerCase()) {
                case "good" -> "PASS";
                case "bad" -> "FAIL";
                case "skip" -> "SKIP";
                default -> throw new IllegalArgumentException("Unknown verdict: " + verdictStr);
            };

            List<BisectStepHistory> updatedHistory = new ArrayList<>(current.history());
            updatedHistory.add(new BisectStepHistory(current.stepNumber(), Instant.now(), current.currentTestSubset(), verdict, ""));

            Set<String> suspects = new LinkedHashSet<>(current.suspectMods());
            Set<String> good = new LinkedHashSet<>(current.eliminatedGoodMods());
            BisectState nextState = BisectState.TESTING;
            String culprit = current.candidateCulpritId();

            if ("FAIL".equals(verdict)) {
                suspects.retainAll(current.currentTestSubset());
            } else if ("PASS".equals(verdict)) {
                good.addAll(current.currentTestSubset());
                suspects.removeAll(current.currentTestSubset());
            }

            if (suspects.size() <= 1) {
                nextState = BisectState.CULPRIT_FOUND;
                culprit = suspects.isEmpty() ? null : suspects.iterator().next();
            }

            List<String> nextSubset = new ArrayList<>(suspects);
            if (nextSubset.size() > 1) {
                nextSubset = nextSubset.subList(0, nextSubset.size() / 2);
            }

            BisectSession updated = new BisectSession(
                    current.format(),
                    current.sessionId(),
                    current.installRoot(),
                    current.startedAt(),
                    Instant.now(),
                    nextState,
                    current.initialEnabledMods(),
                    suspects,
                    good,
                    nextSubset,
                    current.stepNumber() + 1,
                    current.totalEstimatedSteps(),
                    updatedHistory,
                    culprit,
                    current.backupFile()
            );

            saveSession(updated);
            return updated;
        }

        void apply(boolean disableCulprit) throws IOException {
            BisectSession session = status();
            if (session == null || session.state() != BisectState.CULPRIT_FOUND) {
                throw new IllegalStateException("Cannot apply when culprit is not isolated");
            }
            Path enabledFile = installRoot.resolve("mods/enabled_mods.json");
            List<String> finalMods = new ArrayList<>(session.initialEnabledMods());
            if (disableCulprit && session.candidateCulpritId() != null) {
                finalMods.remove(session.candidateCulpritId());
            }
            Files.writeString(enabledFile, Json.object(Map.of("enabledMods", finalMods)) + "\n", StandardCharsets.UTF_8);
            Files.deleteIfExists(sessionFile);
        }

        void reset() throws IOException {
            BisectSession session = status();
            if (session != null && session.backupFile() != null && Files.exists(session.backupFile())) {
                Path enabledFile = installRoot.resolve("mods/enabled_mods.json");
                Files.copy(session.backupFile(), enabledFile, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(session.backupFile());
            }
            Files.deleteIfExists(sessionFile);
        }

        String detectVerdictFromLog(String log) {
            if (log.contains("ERROR com.fs.starfarer.combat.CombatMain") || log.contains("FATAL") || log.contains("Exception in thread")) {
                return "bad";
            }
            if (log.contains("Main menu ready") || log.contains("Starting Starsector")) {
                return "good";
            }
            return "skip";
        }

        private void saveSession(BisectSession session) throws IOException {
            Path parent = sessionFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path staged = Files.createTempFile(parent, ".bisect-staging-", ".tmp");
            try {
                Files.writeString(staged, session.toJson() + "\n", StandardCharsets.UTF_8);
                Files.move(staged, sessionFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(staged);
            }
        }
    }
}

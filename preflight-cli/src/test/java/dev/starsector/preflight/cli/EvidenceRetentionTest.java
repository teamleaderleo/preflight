package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceRetentionTest {
    @TempDir
    Path directory;

    @Test
    void keepsNewestSelectedSessionsAndLeavesTheOtherCategoryUntouched() throws Exception {
        PreflightHome home = home();
        Path oldRun = session(home.runs(), "old-run", 1_000, "old");
        Path newRun = session(home.runs(), "new-run", 3_000, "new");
        Path benchmark = session(home.benchmarks(), "benchmark", 2_000, "benchmark");
        EvidenceRetention.Inventory inventory = EvidenceRetention.inventory(home);

        EvidenceRetention.Plan plan = EvidenceRetention.plan(inventory, 1, null);

        assertEquals(List.of(oldRun.toAbsolutePath()),
                plan.removals().stream().map(EvidenceRetention.Session::path).toList());
        EvidenceRetention.apply(plan);
        assertFalse(Files.exists(oldRun));
        assertTrue(Files.isDirectory(newRun));
        assertTrue(Files.isDirectory(benchmark));
    }

    @Test
    void aSessionThatChangesAfterPlanningRefusesBeforeDeletion() throws Exception {
        PreflightHome home = home();
        Path run = session(home.runs(), "run", 1_000, "first");
        EvidenceRetention.Plan plan = EvidenceRetention.plan(
                EvidenceRetention.inventory(home), 0, null);
        Files.writeString(run.resolve("late.json"), "new evidence");

        IOException error = assertThrows(IOException.class, () -> EvidenceRetention.apply(plan));

        assertTrue(error.getMessage().contains("changed while pruning"));
        assertTrue(Files.isDirectory(run));
        assertTrue(Files.isRegularFile(run.resolve("late.json")));
    }

    @Test
    void newestCompletedPairOccupiesOneBoundedRunSlot() throws Exception {
        PreflightHome home = home();
        Path paired = completedPair(home.runs(), "paired", 1_000);
        Path middle = session(home.runs(), "middle", 2_000, "middle");
        Path newest = session(home.runs(), "newest", 3_000, "newest");

        EvidenceRetention.Plan plan = EvidenceRetention.plan(
                EvidenceRetention.inventory(home), 2, null);

        assertEquals(List.of(middle.toAbsolutePath()),
                plan.removals().stream().map(EvidenceRetention.Session::path).toList());
        EvidenceRetention.apply(plan);
        assertTrue(Files.isDirectory(paired));
        assertFalse(Files.exists(middle));
        assertTrue(Files.isDirectory(newest));
    }

    @Test
    void explicitZeroRunRetentionCanStillRemoveACompletedPair() throws Exception {
        PreflightHome home = home();
        Path paired = completedPair(home.runs(), "paired", 1_000);

        EvidenceRetention.Plan plan = EvidenceRetention.plan(
                EvidenceRetention.inventory(home), 0, null);

        assertEquals(List.of(paired.toAbsolutePath()),
                plan.removals().stream().map(EvidenceRetention.Session::path).toList());
    }

    @Test
    void incompletePairDoesNotDisplaceARecentRun() throws Exception {
        PreflightHome home = home();
        Path incomplete = pairResult(home.runs(), "incomplete", 1_000, false);
        session(home.runs(), "middle", 2_000, "middle");
        session(home.runs(), "newest", 3_000, "newest");

        EvidenceRetention.Plan plan = EvidenceRetention.plan(
                EvidenceRetention.inventory(home), 2, null);

        assertEquals(List.of(incomplete.toAbsolutePath()),
                plan.removals().stream().map(EvidenceRetention.Session::path).toList());
    }

    @Test
    void newestCompletedPairAndSaveLifecycleOccupyTwoBoundedRunSlots() throws Exception {
        PreflightHome home = home();
        Path paired = completedPair(home.runs(), "paired", 1_000);
        Path pilot = pilotAttestation(home.runs(), "pilot", 1_100, true);
        Path middle = session(home.runs(), "middle", 2_000, "middle");
        Path newest = session(home.runs(), "newest", 3_000, "newest");

        EvidenceRetention.Plan plan = EvidenceRetention.plan(
                EvidenceRetention.inventory(home), 3, null);

        assertEquals(List.of(middle.toAbsolutePath()),
                plan.removals().stream().map(EvidenceRetention.Session::path).toList());
        EvidenceRetention.apply(plan);
        assertTrue(Files.isDirectory(paired));
        assertTrue(Files.isDirectory(pilot));
        assertFalse(Files.exists(middle));
        assertTrue(Files.isDirectory(newest));
    }

    @Test
    void incompleteSaveLifecycleDoesNotDisplaceARecentRun() throws Exception {
        PreflightHome home = home();
        Path incomplete = pilotAttestation(home.runs(), "incomplete-pilot", 1_000, false);
        session(home.runs(), "middle", 2_000, "middle");
        session(home.runs(), "newest", 3_000, "newest");

        EvidenceRetention.Plan plan = EvidenceRetention.plan(
                EvidenceRetention.inventory(home), 2, null);

        assertEquals(List.of(incomplete.toAbsolutePath()),
                plan.removals().stream().map(EvidenceRetention.Session::path).toList());
    }

    @Test
    void legacySaveLifecycleWithoutProfileBindingDoesNotDisplaceARecentRun() throws Exception {
        PreflightHome home = home();
        Path legacy = pilotAttestation(home.runs(), "legacy-pilot", 1_000, true);
        Path legacyAttestation = legacy.resolve("operator-attestation.json");
        Files.writeString(legacyAttestation, """
                {"format":"preflight-gameplay-pilot-operator-attestation-v3",
                 "complete":true,"attested":true}
                """);
        Files.setLastModifiedTime(legacyAttestation, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(legacy, FileTime.fromMillis(1_000));
        session(home.runs(), "middle", 2_000, "middle");
        session(home.runs(), "newest", 3_000, "newest");

        EvidenceRetention.Plan plan = EvidenceRetention.plan(
                EvidenceRetention.inventory(home), 2, null);

        assertEquals(List.of(legacy.toAbsolutePath()),
                plan.removals().stream().map(EvidenceRetention.Session::path).toList());
    }

    @Test
    void previousBoundSaveLifecycleRemainsProtectedAfterTheRouteContractTightens() throws Exception {
        PreflightHome home = home();
        Path previous = pilotAttestation(home.runs(), "previous-pilot", 1_000, true);
        Path previousAttestation = previous.resolve("operator-attestation.json");
        Files.writeString(previousAttestation, """
                {"format":"preflight-gameplay-pilot-operator-attestation-v4",
                 "complete":true,"attested":true}
                """);
        Files.setLastModifiedTime(previousAttestation, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(previous, FileTime.fromMillis(1_000));
        Path middle = session(home.runs(), "middle", 2_000, "middle");
        Path newest = session(home.runs(), "newest", 3_000, "newest");

        EvidenceRetention.Plan plan = EvidenceRetention.plan(
                EvidenceRetention.inventory(home), 2, null);

        assertEquals(List.of(middle.toAbsolutePath()),
                plan.removals().stream().map(EvidenceRetention.Session::path).toList());
        assertTrue(plan.removals().stream().noneMatch(session -> session.path().equals(previous)));
        assertTrue(plan.removals().stream().noneMatch(session -> session.path().equals(newest)));
    }

    @Test
    void pairedComparisonKeepsPriorityWhenOnlyOneProtectedSlotExists() throws Exception {
        PreflightHome home = home();
        Path paired = completedPair(home.runs(), "paired", 1_000);
        Path pilot = pilotAttestation(home.runs(), "pilot", 2_000, true);

        EvidenceRetention.Plan plan = EvidenceRetention.plan(
                EvidenceRetention.inventory(home), 1, null);

        assertEquals(List.of(pilot.toAbsolutePath()),
                plan.removals().stream().map(EvidenceRetention.Session::path).toList());
        assertTrue(plan.removals().stream().noneMatch(session -> session.path().equals(paired)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonContractsSeparateInventoryFromPreviewAndApplication() throws Exception {
        PreflightHome home = home();
        session(home.runs(), "run", 1_000, "evidence");
        EvidenceRetention.Inventory inventory = EvidenceRetention.inventory(home);
        ByteArrayOutputStream reportBytes = new ByteArrayOutputStream();
        assertEquals(0, EvidenceCommand.report(
                home,
                inventory,
                true,
                new PrintStream(reportBytes, true, StandardCharsets.UTF_8)));
        Map<String, Object> report = StrictJson.object(reportBytes.toString(StandardCharsets.UTF_8));
        assertEquals("starsector-preflight-evidence-v1", report.get("format"));
        assertEquals(1, ((List<?>) report.get("runs")).size());

        EvidenceRetention.Plan plan = EvidenceRetention.plan(inventory, 0, null);
        ByteArrayOutputStream previewBytes = new ByteArrayOutputStream();
        assertEquals(0, EvidenceCommand.prune(
                plan,
                false,
                true,
                new PrintStream(previewBytes, true, StandardCharsets.UTF_8)));
        Map<String, Object> preview =
                StrictJson.object(previewBytes.toString(StandardCharsets.UTF_8));
        assertEquals("starsector-preflight-evidence-prune-v1", preview.get("format"));
        assertEquals(Boolean.FALSE, preview.get("applied"));
        assertEquals(1, ((List<Map<String, Object>>) preview.get("sessions")).size());
        assertTrue(Files.isDirectory(home.runs().resolve("run")));
    }

    private PreflightHome home() {
        return PreflightHome.resolve(Platform.MAC, directory, Map.of());
    }

    private static Path session(Path root, String name, long modifiedMillis, String contents)
            throws IOException {
        Path session = root.resolve(name);
        Files.createDirectories(session);
        Path evidence = session.resolve("report.json");
        Files.writeString(evidence, contents);
        FileTime time = FileTime.fromMillis(modifiedMillis);
        Files.setLastModifiedTime(evidence, time);
        Files.setLastModifiedTime(session, time);
        return session;
    }

    private static Path completedPair(Path root, String name, long modifiedMillis)
            throws IOException {
        return pairResult(root, name, modifiedMillis, true);
    }

    private static Path pairResult(
            Path root, String name, long modifiedMillis, boolean complete) throws IOException {
        Path session = session(root, name, modifiedMillis, "ordinary evidence");
        Path result = session.resolve(DesktopBenchmarkLaunch.RESULT_FILE);
        Files.writeString(result, """
                {"format":"starsector-preflight-desktop-benchmark-v1","status":"passed",
                 "complete":%s,"comparison":{"available":true}}
                """.formatted(complete));
        FileTime time = FileTime.fromMillis(modifiedMillis);
        Files.setLastModifiedTime(result, time);
        Files.setLastModifiedTime(session, time);
        return session;
    }

    private static Path pilotAttestation(
            Path root, String name, long modifiedMillis, boolean complete) throws IOException {
        Path session = session(root, name, modifiedMillis, "ordinary evidence");
        Path attestation = session.resolve("operator-attestation.json");
        Files.writeString(attestation, """
                {"format":"preflight-gameplay-pilot-operator-attestation-v5",
                 "complete":%s,"attested":%s}
                """.formatted(complete, complete));
        FileTime time = FileTime.fromMillis(modifiedMillis);
        Files.setLastModifiedTime(attestation, time);
        Files.setLastModifiedTime(session, time);
        return session;
    }
}

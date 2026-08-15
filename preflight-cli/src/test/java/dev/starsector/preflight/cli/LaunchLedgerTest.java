package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ledger is what survives when a run directory does not.
 *
 * <p>So the properties worth pinning are the ones that make it safe to prune evidence early: a
 * launch is never failed by its own bookkeeping, one corrupt line costs one line, and the file has
 * an answer to how large it can get.
 */
class LaunchLedgerTest {
    @TempDir
    Path root;

    @Test
    void aRecordedLaunchComesBackAsHistory() {
        PreflightHome home = new PreflightHome(root, List.of());

        assertNull(LaunchLedger.record(home, entry("2026-08-16T00:00:00Z", "COMPLETED", false)));
        assertNull(LaunchLedger.record(home, entry("2026-08-16T01:00:00Z", "COMPLETED", false)));
        assertNull(LaunchLedger.record(home, entry("2026-08-16T02:00:00Z", "FATAL_LOG_EVIDENCE", true)));

        LaunchLedger.Summary summary = LaunchLedger.summarize(read(home));
        assertEquals(3, summary.launches());
        assertEquals(2, summary.completed());
        assertEquals(1, summary.fatal());
        assertEquals(Instant.parse("2026-08-16T00:00:00Z"), summary.first());
        assertEquals(Instant.parse("2026-08-16T02:00:00Z"), summary.last());
    }

    @Test
    void aLaunchThatCompletedButLoggedFatalEvidenceDoesNotCountAsClean() {
        PreflightHome home = new PreflightHome(root, List.of());

        LaunchLedger.record(home, entry("2026-08-16T00:00:00Z", "COMPLETED", true));

        LaunchLedger.Summary summary = LaunchLedger.summarize(read(home));
        assertEquals(1, summary.launches());
        assertEquals(0, summary.completed(), "a clean exit code is not a clean launch");
        assertEquals(1, summary.fatal());
    }

    @Test
    void oneUnreadableLineCostsOneLine() throws IOException {
        PreflightHome home = new PreflightHome(root, List.of());
        LaunchLedger.record(home, entry("2026-08-16T00:00:00Z", "COMPLETED", false));
        // What a machine that lost power mid-append leaves behind.
        Files.writeString(
                LaunchLedger.path(home),
                "{\"format\":\"starsector-preflight-launch-ledg\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        LaunchLedger.record(home, entry("2026-08-16T02:00:00Z", "COMPLETED", false));

        List<LaunchLedger.Entry> entries = read(home);

        assertEquals(2, entries.size(), "the surrounding launches still load");
        assertEquals(Instant.parse("2026-08-16T00:00:00Z"), entries.get(0).started());
        assertEquals(Instant.parse("2026-08-16T02:00:00Z"), entries.get(1).started());
    }

    @Test
    void aLineFromSomeOtherFormatIsNotMistakenForALaunch() throws IOException {
        PreflightHome home = new PreflightHome(root, List.of());
        Files.createDirectories(LaunchLedger.path(home).getParent());
        Files.writeString(
                LaunchLedger.path(home),
                "{\"format\":\"something-else-v1\",\"started\":\"2026-08-16T00:00:00Z\"}\n",
                StandardCharsets.UTF_8);

        assertTrue(read(home).isEmpty());
    }

    @Test
    void theFileHasAnAnswerForHowLargeItCanGet() throws IOException {
        PreflightHome home = new PreflightHome(root, List.of());
        Path path = LaunchLedger.path(home);
        Files.createDirectories(path.getParent());
        StringBuilder existing = new StringBuilder();
        for (int index = 0; index < LaunchLedger.MAX_ENTRIES + 40; index++) {
            existing.append(line("2026-01-01T00:00:00Z", "COMPLETED", false)).append('\n');
        }
        Files.writeString(path, existing.toString(), StandardCharsets.UTF_8);

        LaunchLedger.record(home, entry("2026-08-16T00:00:00Z", "COMPLETED", false));

        List<LaunchLedger.Entry> entries = read(home);
        assertEquals(LaunchLedger.MAX_ENTRIES, entries.size());
        assertEquals(
                Instant.parse("2026-08-16T00:00:00Z"),
                entries.get(entries.size() - 1).started(),
                "the newest launch survives; the oldest are what go");
    }

    @Test
    void anUnwritableHistoryIsReportedRatherThanThrown() throws IOException {
        // A launch must not fail because its bookkeeping could not be written.
        PreflightHome home = new PreflightHome(root, List.of());
        Files.createFile(root.resolve("history"));

        String problem = LaunchLedger.record(home, entry("2026-08-16T00:00:00Z", "COMPLETED", false));

        assertFalse(problem == null || problem.isBlank(), "the reason should be reportable");
    }

    @Test
    void aSymlinkedHistoryCannotRedirectLaunchDataOutsidePreflight() throws IOException {
        PreflightHome home = new PreflightHome(root.resolve("home"), List.of());
        Files.createDirectories(home.root());
        Path outside = root.resolve("outside");
        Files.createDirectories(outside);
        Path externalLedger = outside.resolve("launches.jsonl");
        Files.writeString(externalLedger, "outside data stays unchanged\n", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(home.root().resolve("history"), outside);
        } catch (UnsupportedOperationException | SecurityException | IOException error) {
            assumeTrue(false, "Symbolic links aren't available in this test environment: " + error);
        }

        String problem = LaunchLedger.record(
                home, entry("2026-08-16T00:00:00Z", "COMPLETED", false));

        assertFalse(problem == null || problem.isBlank());
        assertEquals("outside data stays unchanged\n", Files.readString(externalLedger));
    }

    @Test
    void aSymlinkedLedgerCannotRedirectLaunchDataOutsidePreflight() throws IOException {
        PreflightHome home = new PreflightHome(root.resolve("home"), List.of());
        Files.createDirectories(LaunchLedger.path(home).getParent());
        Path externalLedger = root.resolve("outside-ledger.jsonl");
        Files.writeString(externalLedger, "outside data stays unchanged\n", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(LaunchLedger.path(home), externalLedger);
        } catch (UnsupportedOperationException | SecurityException | IOException error) {
            assumeTrue(false, "Symbolic links aren't available in this test environment: " + error);
        }

        String problem = LaunchLedger.record(
                home, entry("2026-08-16T00:00:00Z", "COMPLETED", false));

        assertFalse(problem == null || problem.isBlank());
        assertEquals("outside data stays unchanged\n", Files.readString(externalLedger));
    }

    private List<LaunchLedger.Entry> read(PreflightHome home) {
        try {
            return LaunchLedger.read(home);
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static LaunchLedger.Entry entry(String started, String outcome, boolean fatal) {
        return new LaunchLedger.Entry(
                Instant.parse(started),
                15_250L,
                outcome,
                0,
                fatal,
                "recommended",
                List.of(),
                "20260816-000000-000-abcdef01");
    }

    private static String line(String started, String outcome, boolean fatal) {
        return dev.starsector.preflight.core.Json.object(entry(started, outcome, fatal).toMap());
    }
}

package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code preflight stop} is allowed to signal.
 *
 * <p>The command sends a termination request to a PID read off disk, so the only thing standing
 * between it and an unrelated process is the identity check. These pin that check and the record
 * scan around it.
 */
class StopCommandTest {
    @TempDir
    Path runs;

    /**
     * The one that would be unforgivable: an operating system that has recycled a PID.
     *
     * <p>The record names a process that really is alive -- this test uses its own JVM, so the PID
     * is certainly live -- but the start instant belongs to a process that has since exited. The
     * command must report it and leave it alone.
     */
    @Test
    void refusesAPidWhoseStartInstantDoesNotMatchTheRecord() throws Exception {
        long livePid = ProcessHandle.current().pid();
        writeRecord("20260813-000001", livePid, Instant.parse("2001-01-01T00:00:00Z"), "running");

        List<RuntimeProcessIdentity> records = StopCommand.recordedRuns(runs);

        assertEquals(1, records.size());
        Map<String, Object> inspection = records.get(0).inspect();
        assertTrue((Boolean) inspection.get("alive"), "the test's own JVM should be alive");
        assertFalse((Boolean) inspection.get("startMatches"));
        assertFalse((Boolean) inspection.get("attachable"));
        assertEquals(
                "live process start instant does not match; PID may have been reused",
                inspection.get("reason"));
    }

    /** A process this JVM can prove is itself is the only shape the command will act on. */
    @Test
    void acceptsARecordThatMatchesTheLiveProcess() throws Exception {
        ProcessHandle self = ProcessHandle.current();
        Instant startedAt = self.info().startInstant().orElseThrow();
        writeRecord("20260813-000002", self.pid(), startedAt, "running");

        Map<String, Object> inspection = StopCommand.recordedRuns(runs).get(0).inspect();

        assertTrue((Boolean) inspection.get("attachable"));
        assertEquals(null, inspection.get("reason"));
    }

    /** An interrupted run can leave a partial file behind; it must not hide the other records. */
    @Test
    void readsPastARecordItCannotParse() throws Exception {
        Files.createDirectories(runs.resolve("20260813-000003"));
        Files.writeString(runs.resolve("20260813-000003/runtime-process.json"), "{\"format\":");
        writeRecord("20260813-000004", ProcessHandle.current().pid(), Instant.now(), "running");

        List<RuntimeProcessIdentity> records = StopCommand.recordedRuns(runs);

        assertEquals(1, records.size());
        assertEquals(ProcessHandle.current().pid(), records.get(0).pid());
    }

    /** Newest first, so a stop reaches the launch someone is most likely waiting on. */
    @Test
    void readsTheNewestRunFirst() throws Exception {
        writeRecord("20260813-000005", 424242, Instant.now(), "stopped");
        writeRecord("20260813-000006", 424243, Instant.now(), "stopped");

        List<RuntimeProcessIdentity> records = StopCommand.recordedRuns(runs);

        assertEquals(List.of(424243L, 424242L), records.stream().map(RuntimeProcessIdentity::pid).toList());
    }

    @Test
    void ignoresADirectoryWithNoRuntimeRecord() throws Exception {
        Files.createDirectories(runs.resolve("20260813-000007"));

        assertEquals(List.of(), StopCommand.recordedRuns(runs));
    }

    @Test
    void scopesAStopToTheExactRequestedProcess() throws Exception {
        writeRecord("20260813-000008", 424244, Instant.now(), "running");
        writeRecord("20260813-000009", 424245, Instant.now(), "running");

        List<RuntimeProcessIdentity> records = StopCommand.recordedRuns(runs);

        assertEquals(
                List.of(424244L),
                StopCommand.selectPid(records, 424244L).stream()
                        .map(RuntimeProcessIdentity::pid)
                        .toList());
        assertEquals(List.of(), StopCommand.selectPid(records, 999999L));
        assertEquals(records, StopCommand.selectPid(records, null));
    }

    @Test
    void previewOutputLabelsEveryProspectiveAction() {
        String rendered = render(
                List.of(outcome(424246, "would-stop", null)),
                List.of(outcome(424247, "skipped", "PID may have been reused")),
                true);

        assertEquals(
                platformLines("""
                Preview: would stop PID 424246.
                Preview: would leave PID 424247 alone: PID may have been reused.
                """),
                rendered);
    }

    @Test
    void emptyPreviewStillSaysItIsAPreview() {
        assertEquals(
                platformLines("Preview: no Preflight-started Starsector process is running.\n"),
                render(List.of(), List.of(), true));
    }

    @Test
    void appliedOutputUsesDirectPastTenseAndReadableReasons() {
        String rendered = render(
                List.of(
                        outcome(424248, "stopped", null),
                        outcome(424249, "already-exited", null),
                        outcome(424250, "still-running", "still alive after 20s; re-run with --force to end it")),
                List.of(outcome(424251, "skipped", "PID may have been reused")),
                false);

        assertEquals(
                platformLines("""
                Stopped PID 424248.
                PID 424249 already exited.
                Could not stop PID 424250: still alive after 20s; re-run with --force to end it.
                Left PID 424251 alone: PID may have been reused.
                """),
                rendered);
    }

    private static String platformLines(String value) {
        return value.replace("\n", System.lineSeparator());
    }

    private static String render(
            List<Map<String, Object>> stopped,
            List<Map<String, Object>> skipped,
            boolean dryRun) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            StopCommand.print(output, stopped, skipped, dryRun);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> outcome(long pid, String result, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("pid", pid);
        entry.put("result", result);
        entry.put("reason", reason);
        return entry;
    }

    private void writeRecord(String directory, long pid, Instant startedAt, String state)
            throws IOException {
        Path folder = runs.resolve(directory);
        Files.createDirectories(folder);
        String stoppedAt = "stopped".equals(state) ? "\"" + startedAt + "\"" : "null";
        Files.writeString(folder.resolve("runtime-process.json"), """
                {
                  "format": "starsector-preflight-runtime-process-v1",
                  "pid": %d,
                  "parentPid": null,
                  "startedAt": "%s",
                  "observedAt": "%s",
                  "state": "%s",
                  "stoppedAt": %s
                }
                """.formatted(pid, startedAt, Instant.now(), state, stoppedAt));
    }
}

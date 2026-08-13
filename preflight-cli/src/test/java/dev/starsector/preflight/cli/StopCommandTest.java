package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

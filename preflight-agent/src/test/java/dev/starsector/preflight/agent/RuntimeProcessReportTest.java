package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeProcessReportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesTheExactRuntimeIdentityAndOrderlyStopState() throws Exception {
        Path destination = temporaryDirectory.resolve("runtime-process.json");
        RuntimeProcessReport report = RuntimeProcessReport.current(destination);

        report.running();
        String running = Files.readString(destination);
        assertTrue(running.contains("\"format\":\"starsector-preflight-runtime-process-v1\""));
        assertTrue(running.contains("\"pid\":" + ProcessHandle.current().pid()));
        assertTrue(running.contains("\"state\":\"running\""));
        assertTrue(running.contains("\"startedAt\":"));
        assertFalse(running.contains(".tmp-"));

        report.stopped();
        String stopped = Files.readString(destination);
        assertTrue(stopped.contains("\"state\":\"stopped\""));
        assertTrue(stopped.contains("\"stoppedAt\":"));
        assertTrue(stopped.contains("\"pid\":" + ProcessHandle.current().pid()));
    }
}

package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopSmokeLiveReportTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        FrameTimeRuntime.reset();
        RuntimeSemanticState.reset();
    }

    @Test
    void publishesProcessBoundFrameAndHealthSnapshotsOnlyWhenRequested() throws Exception {
        Path adapter = temporaryDirectory.resolve("enabled/adapter.json");
        RuntimeSemanticState.beginSession(adapter.resolveSibling("runtime-state.json"));
        FrameTimeRuntime.beginSession(true);
        FrameTimeRuntime.installed();
        FrameTimeRuntime.recordBoundary(1_000_000L);
        FrameTimeRuntime.recordBoundary(18_000_000L);

        DesktopSmokeLiveReport report =
                DesktopSmokeLiveReport.start(adapter, AdapterMode.ENABLED, true);
        Path frames = adapter.resolveSibling("runtime-frame-report.json");
        Path health = adapter.resolveSibling("runtime-adapter-health.json");
        assertTrue(Files.isRegularFile(frames));
        assertTrue(Files.isRegularFile(health));
        String frameJson = Files.readString(frames);
        String healthJson = Files.readString(health);
        assertTrue(frameJson.contains("\"format\":\"" + DesktopSmokeLiveReport.FRAME_FORMAT + "\""));
        assertTrue(frameJson.contains("\"pid\":" + ProcessHandle.current().pid()));
        assertTrue(frameJson.contains("\"averageFps\""));
        assertTrue(healthJson.contains(
                "\"format\":\"" + DesktopSmokeLiveReport.HEALTH_FORMAT + "\""));
        assertTrue(healthJson.contains("\"adapterMode\":\"enabled\""));
        assertTrue(healthJson.contains("\"memoryPressure\":"));
        assertTrue(report.enabled());
        report.close();
        assertTrue(report.writeProblem() == null);
    }

    @Test
    void regularRunsCreateNoPublisherOrFiles() throws Exception {
        Path adapter = temporaryDirectory.resolve("disabled/adapter.json");
        DesktopSmokeLiveReport report =
                DesktopSmokeLiveReport.start(adapter, AdapterMode.ENABLED, false);
        assertFalse(report.enabled());
        report.close();
        assertFalse(Files.exists(adapter.resolveSibling("runtime-frame-report.json")));
        assertFalse(Files.exists(adapter.resolveSibling("runtime-adapter-health.json")));
    }
}

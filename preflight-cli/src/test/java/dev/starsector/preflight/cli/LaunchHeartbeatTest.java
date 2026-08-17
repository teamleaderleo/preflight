package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchHeartbeatTest {
    @TempDir
    Path root;

    @Test
    void heartbeatWritesAndRecoversValidRecord() throws IOException {
        Path runDir = Files.createDirectories(root.resolve("run-1"));
        Instant started = Instant.now().minusSeconds(60);
        long startedNanos = System.nanoTime() - 60_000_000_000L; // 60s ago
        String launchId = UUID.randomUUID().toString();

        try (LaunchHeartbeat heartbeat = LaunchHeartbeat.start(
                runDir, launchId, started, startedNanos, "profile-abc")) {
            heartbeat.write();
            LaunchHeartbeat.Record record = LaunchHeartbeat.read(runDir);
            assertNotNull(record);
            assertEquals(launchId, record.launchId());
            assertEquals(started, record.started());
            assertEquals("profile-abc", record.profileFingerprint());
            assertTrue(record.elapsedMillis() >= 50_000L);
            assertTrue(record.ownerAlive());
        }

        // Closing leaves the final beat until the ledger row is durable.
        assertNotNull(LaunchHeartbeat.read(runDir));
        LaunchHeartbeat.complete(runDir, launchId);
        assertNull(LaunchHeartbeat.read(runDir));
    }

    @Test
    void corruptedHeartbeatFailsClosedSafely() throws IOException {
        Path runDir = Files.createDirectories(root.resolve("run-corrupt"));
        Files.writeString(runDir.resolve(LaunchHeartbeat.FILE_NAME), "not json", StandardCharsets.UTF_8);
        assertNull(LaunchHeartbeat.read(runDir));
    }
}

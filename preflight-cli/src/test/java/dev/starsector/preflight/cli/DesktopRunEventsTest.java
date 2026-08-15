package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.starsector.preflight.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopRunEventsTest {
    @TempDir
    Path temporary;

    @Test
    void emitsOnlyAfterTheExactLiveJvmIdentityAppears() throws Exception {
        ProcessHandle current = ProcessHandle.current();
        Instant startedAt = current.info().startInstant().orElse(null);
        assumeTrue(startedAt != null, "This runtime does not expose its process start instant");
        Path identity = temporary.resolve("runtime-process.json");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
                var events = DesktopRunEvents.watch(
                        identity,
                        Map.of(DesktopRunEvents.ENVIRONMENT_VARIABLE, DesktopRunEvents.ENVIRONMENT_VALUE),
                        output)) {
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains(DesktopRunEvents.PREFIX));
            writeIdentity(identity, current.pid(), startedAt);
            awaitEvent(bytes);
        }

        String event = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(event.startsWith(DesktopRunEvents.PREFIX));
        assertTrue(event.contains("\"state\":\"game-running\""));
        assertTrue(event.contains("\"pid\":" + current.pid()));
    }

    @Test
    void ordinaryCliLaunchesPublishNoMachineProtocolAndKeepExistingEvidence() throws Exception {
        Path identity = temporary.resolve("runtime-process.json");
        Files.writeString(identity, "existing");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
                var ignored = DesktopRunEvents.watch(identity, Map.of(), output)) {
            Thread.sleep(50);
        }

        assertTrue(bytes.toString(StandardCharsets.UTF_8).isEmpty());
        assertTrue(Files.readString(identity).equals("existing"));
    }

    private static void writeIdentity(Path destination, long pid, Instant startedAt) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", "starsector-preflight-runtime-process-v1");
        values.put("pid", pid);
        values.put("parentPid", null);
        values.put("startedAt", startedAt);
        values.put("observedAt", Instant.now());
        values.put("state", "running");
        values.put("stoppedAt", null);
        Files.writeString(destination, Json.object(values));
    }

    private static void awaitEvent(ByteArrayOutputStream bytes) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
        while (Instant.now().isBefore(deadline)) {
            if (bytes.toString(StandardCharsets.UTF_8).contains(DesktopRunEvents.PREFIX)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("The verified launch event did not arrive");
    }
}

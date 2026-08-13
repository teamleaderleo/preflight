package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeProcessIdentityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOnlyTheLiveProcessWithTheSameStartInstant() throws Exception {
        ProcessHandle current = ProcessHandle.current();
        Path file = write(current.pid(), current.info().startInstant().orElseThrow(), "running", null);
        Map<String, Object> live = RuntimeProcessIdentity.read(file).inspect();
        assertTrue((Boolean) live.get("alive"));
        assertTrue((Boolean) live.get("startMatches"));
        assertTrue((Boolean) live.get("attachable"));

        Path stale = write(current.pid(), Instant.EPOCH, "running", null);
        Map<String, Object> reused = RuntimeProcessIdentity.read(stale).inspect();
        assertTrue((Boolean) reused.get("alive"));
        assertFalse((Boolean) reused.get("startMatches"));
        assertFalse((Boolean) reused.get("attachable"));
        assertTrue(reused.get("reason").toString().contains("reused"));
    }

    @Test
    void rejectsUnknownFieldsAndContradictoryStopState() throws Exception {
        Path contradictory = write(ProcessHandle.current().pid(), Instant.now(), "stopped", null);
        assertThrows(IllegalArgumentException.class, () -> RuntimeProcessIdentity.read(contradictory));

        String withUnknown = Files.readString(write(
                ProcessHandle.current().pid(), Instant.now(), "running", null))
                .replace("{", "{\"windowTitle\":\"Starsector\",");
        Path unknown = temporaryDirectory.resolve("unknown.json");
        Files.writeString(unknown, withUnknown);
        assertThrows(IllegalArgumentException.class, () -> RuntimeProcessIdentity.read(unknown));
    }

    @Test
    void cannotAttachWithoutAnOperatingSystemStartInstant() throws Exception {
        Path file = write(ProcessHandle.current().pid(), null, "running", null);

        Map<String, Object> inspected = RuntimeProcessIdentity.read(file).inspect();

        assertTrue((Boolean) inspected.get("alive"));
        assertFalse((Boolean) inspected.get("startMatches"));
        assertFalse((Boolean) inspected.get("attachable"));
        assertTrue(inspected.get("reason").toString().contains("no process start instant"));
    }

    private Path write(long pid, Instant startedAt, String state, Instant stoppedAt) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", "starsector-preflight-runtime-process-v1");
        values.put("pid", pid);
        values.put("parentPid", ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(null));
        values.put("startedAt", startedAt);
        values.put("observedAt", Instant.now());
        values.put("state", state);
        values.put("stoppedAt", stoppedAt);
        Path file = temporaryDirectory.resolve("runtime-" + System.nanoTime() + ".json");
        Files.writeString(file, Json.object(values));
        return file;
    }
}

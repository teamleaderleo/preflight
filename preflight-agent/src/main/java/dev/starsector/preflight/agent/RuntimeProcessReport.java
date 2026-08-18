package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Staged process identity for PID-addressed desktop smoke drivers. */
final class RuntimeProcessReport {
    static final String FORMAT = "starsector-preflight-runtime-process-v1";

    private final Path destination;
    private final long pid;
    private final Long parentPid;
    private final Instant startedAt;

    private RuntimeProcessReport(Path destination, long pid, Long parentPid, Instant startedAt) {
        this.destination = destination.toAbsolutePath().normalize();
        this.pid = pid;
        this.parentPid = parentPid;
        this.startedAt = startedAt;
    }

    static RuntimeProcessReport current(Path destination) {
        ProcessHandle process = ProcessHandle.current();
        return new RuntimeProcessReport(
                destination,
                process.pid(),
                process.parent().map(ProcessHandle::pid).orElse(null),
                process.info().startInstant().orElse(null));
    }

    void running() throws IOException {
        write("running", null);
    }

    void stopped() throws IOException {
        write("stopped", Instant.now());
    }

    private void write(String state, Instant stoppedAt) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", FORMAT);
        values.put("pid", pid);
        values.put("parentPid", parentPid);
        values.put("startedAt", startedAt);
        values.put("observedAt", Instant.now());
        values.put("state", state);
        values.put("stoppedAt", stoppedAt);
        atomicWrite(destination, Json.object(values) + System.lineSeparator());
    }

    private static void atomicWrite(Path destination, String value) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = destination.resolveSibling(destination.getFileName()
                + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }
}

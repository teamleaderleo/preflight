package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Strict PID/start-instant validation for a desktop driver's live attachment target. */
final class RuntimeProcessIdentity {
    private static final String FORMAT = "starsector-preflight-runtime-process-v1";
    private static final long MAX_BYTES = 64 * 1024;
    private static final Set<String> FIELDS = Set.of(
            "format", "pid", "parentPid", "startedAt", "observedAt", "state", "stoppedAt");

    private final Path source;
    private final long pid;
    private final Long parentPid;
    private final Instant startedAt;
    private final Instant observedAt;
    private final String state;
    private final Instant stoppedAt;

    private RuntimeProcessIdentity(
            Path source,
            long pid,
            Long parentPid,
            Instant startedAt,
            Instant observedAt,
            String state,
            Instant stoppedAt) {
        this.source = source;
        this.pid = pid;
        this.parentPid = parentPid;
        this.startedAt = startedAt;
        this.observedAt = observedAt;
        this.state = state;
        this.stoppedAt = stoppedAt;
    }

    static RuntimeProcessIdentity read(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime process identity is not a regular file: " + absolute);
        }
        if (Files.size(absolute) > MAX_BYTES) {
            throw new IOException("Runtime process identity exceeds " + MAX_BYTES + " bytes");
        }
        Map<String, Object> root = StrictJson.object(Files.readString(absolute, StandardCharsets.UTF_8));
        for (String field : root.keySet()) {
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("Runtime process identity contains unknown field: " + field);
            }
        }
        for (String field : FIELDS) {
            if (!root.containsKey(field)) {
                throw new IllegalArgumentException("Runtime process identity is missing field: " + field);
            }
        }
        if (!FORMAT.equals(string(root, "format"))) {
            throw new IllegalArgumentException("Runtime process identity format is unsupported");
        }
        long pid = positiveInteger(root, "pid");
        Long parentPid = nullablePositiveInteger(root, "parentPid");
        Instant startedAt = instant(root, "startedAt", true);
        Instant observedAt = instant(root, "observedAt", false);
        String state = string(root, "state");
        if (!Set.of("running", "stopped").contains(state)) {
            throw new IllegalArgumentException("Runtime process identity state is unsupported: " + state);
        }
        Instant stoppedAt = instant(root, "stoppedAt", true);
        if (("running".equals(state) && stoppedAt != null)
                || ("stopped".equals(state) && stoppedAt == null)) {
            throw new IllegalArgumentException("Runtime process identity state and stoppedAt disagree");
        }
        return new RuntimeProcessIdentity(
                absolute, pid, parentPid, startedAt, observedAt, state, stoppedAt);
    }

    long pid() {
        return pid;
    }

    boolean attachable() {
        ProcessHandle process = ProcessHandle.of(pid).orElse(null);
        Instant liveStartedAt = process == null ? null : process.info().startInstant().orElse(null);
        return "running".equals(state)
                && process != null
                && process.isAlive()
                && startedAt != null
                && startedAt.equals(liveStartedAt);
    }

    Path source() {
        return source;
    }

    Map<String, Object> inspect() {
        ProcessHandle process = ProcessHandle.of(pid).orElse(null);
        Instant liveStartedAt = process == null ? null : process.info().startInstant().orElse(null);
        boolean alive = process != null && process.isAlive();
        boolean startMatches = startedAt != null
                && liveStartedAt != null
                && liveStartedAt.equals(startedAt);
        boolean attachable = "running".equals(state) && alive && startMatches;
        String reason = attachable
                ? null
                : "stopped".equals(state) ? "runtime record is stopped"
                : !alive ? "recorded PID is not alive"
                : startedAt == null ? "runtime record has no process start instant"
                : liveStartedAt == null ? "live process has no start instant"
                : "live process start instant does not match; PID may have been reused";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", FORMAT);
        result.put("source", source);
        result.put("pid", pid);
        result.put("parentPid", parentPid);
        result.put("startedAt", startedAt);
        result.put("observedAt", observedAt);
        result.put("state", state);
        result.put("stoppedAt", stoppedAt);
        result.put("alive", alive);
        result.put("liveStartedAt", liveStartedAt);
        result.put("startMatches", startMatches);
        result.put("attachable", attachable);
        result.put("reason", reason);
        return result;
    }

    private static String string(Map<String, Object> root, String field) {
        Object value = root.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return text;
    }

    private static long positiveInteger(Map<String, Object> root, String field) {
        Object value = root.get(field);
        if (!(value instanceof Number number)
                || number.doubleValue() != number.longValue()
                || number.longValue() <= 0) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        return number.longValue();
    }

    private static Long nullablePositiveInteger(Map<String, Object> root, String field) {
        return root.get(field) == null ? null : positiveInteger(root, field);
    }

    private static Instant instant(Map<String, Object> root, String field, boolean nullable) {
        Object value = root.get(field);
        if (value == null && nullable) return null;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + " must be an RFC 3339 instant");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(field + " must be an RFC 3339 instant", error);
        }
    }
}

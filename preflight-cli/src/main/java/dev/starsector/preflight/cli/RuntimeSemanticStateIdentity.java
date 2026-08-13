package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

/** Strict reader for the live semantic state published by the injected JVM. */
final class RuntimeSemanticStateIdentity {
    private static final String FORMAT = "starsector-preflight-runtime-state-v1";
    private static final long MAX_BYTES = 64 * 1024;
    private static final Set<String> FIELDS = Set.of(
            "format", "pid", "processStartedAt", "state", "sequence", "observedAt");
    private static final Set<String> STATES = Set.of(
            "starting", "main-menu-ready", "campaign-ready", "combat-ready", "stopped");

    private final long pid;
    private final Instant processStartedAt;
    private final String state;
    private final long sequence;
    private final Instant observedAt;

    private RuntimeSemanticStateIdentity(
            long pid, Instant processStartedAt, String state, long sequence, Instant observedAt) {
        this.pid = pid;
        this.processStartedAt = processStartedAt;
        this.state = state;
        this.sequence = sequence;
        this.observedAt = observedAt;
    }

    static RuntimeSemanticStateIdentity read(
            Path file, DesktopSmokeDriver.ProcessTarget target) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime semantic state is not a regular file: " + absolute);
        }
        if (Files.size(absolute) > MAX_BYTES) {
            throw new IOException("Runtime semantic state exceeds " + MAX_BYTES + " bytes");
        }
        Map<String, Object> root = StrictJson.object(
                Files.readString(absolute, StandardCharsets.UTF_8));
        for (String field : root.keySet()) {
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("Runtime semantic state contains unknown field: " + field);
            }
        }
        for (String field : FIELDS) {
            if (!root.containsKey(field)) {
                throw new IllegalArgumentException("Runtime semantic state is missing field: " + field);
            }
        }
        if (!FORMAT.equals(string(root, "format"))) {
            throw new IllegalArgumentException("Runtime semantic state format is unsupported");
        }
        long pid = integer(root, "pid", 1L);
        Instant processStartedAt = instant(root, "processStartedAt");
        String state = string(root, "state");
        if (!STATES.contains(state)) {
            throw new IllegalArgumentException("Runtime semantic state is unsupported: " + state);
        }
        long sequence = integer(root, "sequence", 0L);
        Instant observedAt = instant(root, "observedAt");
        if (pid != target.pid() || !processStartedAt.equals(target.startedAt())) {
            throw new IllegalArgumentException(
                    "Runtime semantic state belongs to another process lifetime");
        }
        return new RuntimeSemanticStateIdentity(pid, processStartedAt, state, sequence, observedAt);
    }

    boolean is(String expected) {
        return state.equals(expected);
    }

    String state() {
        return state;
    }

    long sequence() {
        return sequence;
    }

    Instant observedAt() {
        return observedAt;
    }

    private static String string(Map<String, Object> root, String field) {
        Object value = root.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return text;
    }

    private static long integer(Map<String, Object> root, String field, long minimum) {
        Object value = root.get(field);
        if (!(value instanceof Number number)
                || number.doubleValue() != number.longValue()
                || number.longValue() < minimum) {
            throw new IllegalArgumentException(field + " must be an integer >= " + minimum);
        }
        return number.longValue();
    }

    private static Instant instant(Map<String, Object> root, String field) {
        Object value = root.get(field);
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

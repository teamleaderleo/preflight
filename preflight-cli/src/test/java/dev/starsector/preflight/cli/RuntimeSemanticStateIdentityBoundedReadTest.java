package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeSemanticStateIdentityBoundedReadTest {
    private static final int LIMIT = 64 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void initiallyOversizedSemanticStateIsRejectedBeforeParsing() throws Exception {
        Path file = temporaryDirectory.resolve("oversized.json");
        Files.write(file, new byte[LIMIT + 1]);

        IOException error = assertThrows(
                IOException.class,
                () -> RuntimeSemanticStateIdentity.read(file));

        assertTrue(error.getMessage().contains("exceeds " + LIMIT + " bytes"), error.getMessage());
    }

    @Test
    void malformedUtf8KeepsHardIoFailureSemantics() throws Exception {
        Path file = temporaryDirectory.resolve("malformed.json");
        Files.write(file, new byte[] {(byte) 0x80});

        IOException error = assertThrows(
                IOException.class,
                () -> RuntimeSemanticStateIdentity.read(file));

        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
    }

    @Test
    void ordinaryValidSemanticStateStillParsesThroughTheSharedReader() throws Exception {
        Instant started = Instant.parse("2026-08-20T12:00:00Z");
        Instant observed = Instant.parse("2026-08-20T12:00:03Z");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", "starsector-preflight-runtime-state-v1");
        values.put("pid", 42L);
        values.put("processStartedAt", started);
        values.put("state", "campaign-ready");
        values.put("sequence", 7L);
        values.put("observedAt", observed);
        Path file = temporaryDirectory.resolve("valid.json");
        Files.writeString(file, Json.object(values), StandardCharsets.UTF_8);

        RuntimeSemanticStateIdentity identity = RuntimeSemanticStateIdentity.read(file);

        assertEquals("campaign-ready", identity.state());
        assertEquals(7L, identity.sequence());
        assertEquals(observed, identity.observedAt());
    }

    @Test
    void schemaValidationRemainsAnIllegalArgumentException() throws Exception {
        Path file = temporaryDirectory.resolve("unknown-field.json");
        Files.writeString(file, """
                {"format":"starsector-preflight-runtime-state-v1","pid":42,
                 "processStartedAt":"2026-08-20T12:00:00Z","state":"starting",
                 "sequence":0,"observedAt":"2026-08-20T12:00:01Z","extra":true}
                """, StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> RuntimeSemanticStateIdentity.read(file));
    }
}

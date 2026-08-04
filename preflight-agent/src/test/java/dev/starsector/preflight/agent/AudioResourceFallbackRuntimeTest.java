package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AudioResourceFallbackRuntimeTest {
    @BeforeEach
    void begin() {
        AudioResourceFallbackRuntime.beginSession();
    }

    @AfterEach
    void reset() {
        AudioResourceFallbackRuntime.reset();
    }

    @Test
    void preservesRelativeHitsThenRetriesClasspathRoot() throws Exception {
        try (InputStream relative = AudioResourceFallbackRuntime.open(
                getClass(), "AudioResourceFallbackRuntimeTest.class")) {
            assertNotNull(relative);
        }
        try (InputStream absoluteFallback = AudioResourceFallbackRuntime.open(
                getClass(), "dev/starsector/preflight/agent/AudioResourceFallbackRuntimeTest.class")) {
            assertNotNull(absoluteFallback);
        }
        assertNull(AudioResourceFallbackRuntime.open(getClass(), "does/not/exist.ogg"));

        Map<String, Object> telemetry = AudioResourceFallbackRuntime.telemetry();
        assertEquals(3L, telemetry.get("lookups"));
        assertEquals(1L, telemetry.get("originalHits"));
        assertEquals(2L, telemetry.get("fallbackAttempts"));
        assertEquals(1L, telemetry.get("fallbackHits"));
        assertEquals(1L, telemetry.get("fallbackMisses"));
        assertEquals(0L, telemetry.get("fallbackFailures"));
    }

    @Test
    void preservesAbsoluteMissWithoutRetryingIt() {
        assertNull(AudioResourceFallbackRuntime.open(getClass(), "/does/not/exist.ogg"));

        Map<String, Object> telemetry = AudioResourceFallbackRuntime.telemetry();
        assertEquals(1L, telemetry.get("lookups"));
        assertEquals(0L, telemetry.get("fallbackAttempts"));
        assertEquals(0L, telemetry.get("fallbackMisses"));
    }
}

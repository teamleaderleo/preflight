package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MacMemoryWarningRuntimeTest {
    @AfterEach
    void reset() {
        MacMemoryWarningRuntime.reset();
    }

    @Test
    void healthyReportedFreeMemoryKeepsVanillaDecisionWithoutAProbe() {
        AtomicInteger probes = new AtomicInteger();
        assertFalse(MacMemoryWarningRuntime.shouldWarn(1_000L, 24_576L, true, () -> {
            probes.incrementAndGet();
            return 0;
        }));
        assertEquals(0, probes.get());
        assertEquals(0L, MacMemoryWarningRuntime.telemetry().get("macChecks"));
    }

    @Test
    void macUsesPressureAwareAvailableMemoryAgainstTheSameThreshold() {
        assertFalse(MacMemoryWarningRuntime.shouldWarn(128L, 24_576L, true, () -> 84));
        assertEquals(1L, MacMemoryWarningRuntime.telemetry().get("corrections"));
        assertEquals(20_643L,
                MacMemoryWarningRuntime.telemetry().get("lastEstimatedAvailableMiB"));

        assertTrue(MacMemoryWarningRuntime.shouldWarn(128L, 24_576L, true, () -> 2));
        assertEquals(1L, MacMemoryWarningRuntime.telemetry().get("warningsPreserved"));
        assertEquals(491L,
                MacMemoryWarningRuntime.telemetry().get("lastEstimatedAvailableMiB"));
    }

    @Test
    void nonMacAndProbeFailurePreserveTheVanillaWarning() {
        assertTrue(MacMemoryWarningRuntime.shouldWarn(999L, 8_192L, false,
                () -> { throw new AssertionError("non-mac probe must not run"); }));
        assertTrue(MacMemoryWarningRuntime.shouldWarn(512L, 24_576L, true,
                () -> { throw new IllegalStateException("probe unavailable"); }));
        assertEquals(2L, MacMemoryWarningRuntime.telemetry().get("warningsPreserved"));
        assertEquals(1L, MacMemoryWarningRuntime.telemetry().get("probeFailures"));
    }

    @Test
    void parsesTheStableQueryLineAndRejectsMissingOrInvalidValues() {
        assertEquals(84, MacMemoryWarningRuntime.parseAvailablePercent("""
                The system has 25769803776 bytes.
                System-wide memory free percentage: 84%
                """));
        assertThrows(IllegalArgumentException.class,
                () -> MacMemoryWarningRuntime.parseAvailablePercent("Pages free: 42"));
        assertThrows(IllegalArgumentException.class,
                () -> MacMemoryWarningRuntime.parseAvailablePercent(
                        "System-wide memory free percentage: 101%"));
    }
}

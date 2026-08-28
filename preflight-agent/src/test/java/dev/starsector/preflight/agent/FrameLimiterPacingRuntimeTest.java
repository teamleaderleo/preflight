package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FrameLimiterPacingRuntimeTest {
    @AfterEach
    void reset() {
        System.clearProperty(FrameLimiterPacingRuntime.FPS_PROPERTY);
        System.clearProperty(FrameLimiterPacingRuntime.SPIN_MICROS_PROPERTY);
        FrameLimiterPacingRuntime.reset();
    }

    @Test
    void absoluteCadenceRecoversTheFractionBeyondVanillaIntegerSleep() {
        long deadline = FrameLimiterPacingRuntime.deadlineNanos(
                2_000_000L, 14L, 0L, 16_666_666L);
        assertEquals(16_666_666L, deadline);
    }

    @Test
    void vanillaRequestedSleepRemainsTheFloorWhenConfiguredCadenceIsHigher() {
        long deadline = FrameLimiterPacingRuntime.deadlineNanos(
                2_000_000L, 14L, 0L, 6_944_444L);
        assertEquals(16_000_000L, deadline);
    }

    @Test
    void lowerConfiguredCadenceCanOnlyExtendTheVanillaWait() {
        long deadline = FrameLimiterPacingRuntime.deadlineNanos(
                2_000_000L, 14L, 0L, 33_333_333L);
        assertEquals(33_333_333L, deadline);
    }

    @Test
    void firstInvocationPreservesTheVanillaFloorBeforeCadenceHistoryExists() {
        long deadline = FrameLimiterPacingRuntime.deadlineNanos(
                2_000_000L, 14L, Long.MIN_VALUE, 16_666_666L);
        assertEquals(16_000_000L, deadline);
    }

    @Test
    void loadsOnlyAnExplicitValidTargetAndBoundsSpinMargin() {
        FrameLimiterPacingRuntime.reset();
        assertFalse(FrameLimiterPacingRuntime.enabled());

        System.setProperty(FrameLimiterPacingRuntime.FPS_PROPERTY, "60");
        System.setProperty(FrameLimiterPacingRuntime.SPIN_MICROS_PROPERTY, "50000");
        FrameLimiterPacingRuntime.reset();
        assertTrue(FrameLimiterPacingRuntime.enabled());
        Map<String, Object> telemetry = FrameLimiterPacingRuntime.telemetry();
        assertEquals(60, telemetry.get("targetFps"));
        assertEquals(2_000L, telemetry.get("spinMarginMicros"));

        System.setProperty(FrameLimiterPacingRuntime.FPS_PROPERTY, "14");
        FrameLimiterPacingRuntime.reset();
        assertFalse(FrameLimiterPacingRuntime.enabled());
    }

    @Test
    void actualWaitReportsMechanismCounters() throws Exception {
        FrameLimiterPacingRuntime.beginSession(1000, 100);
        FrameLimiterPacingRuntime.sleep(0L);
        FrameLimiterPacingRuntime.sleep(0L);

        Map<String, Object> telemetry = FrameLimiterPacingRuntime.telemetry();
        assertEquals(2L, telemetry.get("calls"));
        assertEquals(2L, telemetry.get("waits"));
        assertTrue(((Number) telemetry.get("averageDeadlineExtensionMicros")).doubleValue() > 0.0);
        assertTrue(((Number) telemetry.get("maximumWaitMicros")).doubleValue() > 0.0);
    }
}

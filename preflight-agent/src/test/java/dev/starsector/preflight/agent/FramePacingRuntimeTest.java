package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FramePacingRuntimeTest {
    @AfterEach
    void reset() {
        System.clearProperty(FramePacingRuntime.FPS_PROPERTY);
        System.clearProperty(FramePacingRuntime.SPIN_MICROS_PROPERTY);
        FramePacingRuntime.reset();
    }

    @Test
    void loadsAnExplicitCapAndBoundsTheFinalSpin() {
        System.setProperty(FramePacingRuntime.FPS_PROPERTY, "144");
        System.setProperty(FramePacingRuntime.SPIN_MICROS_PROPERTY, "250");
        FramePacingRuntime.reset();

        assertTrue(FramePacingRuntime.enabled());
        Map<String, Object> telemetry = FramePacingRuntime.telemetry();
        assertEquals(144, telemetry.get("targetFps"));
        assertEquals(6_944.444, telemetry.get("targetFrameMicros"));
        assertEquals(250L, telemetry.get("spinMarginMicros"));
        assertEquals(6_694_444L,
                FramePacingRuntime.plannedParkNanos(1_000_000L, 7_944_444L, 250_000L));
    }

    @Test
    void invalidOrAbsentCapKeepsTheExperimentDisabled() {
        FramePacingRuntime.reset();
        assertFalse(FramePacingRuntime.enabled());

        System.setProperty(FramePacingRuntime.FPS_PROPERTY, "14");
        FramePacingRuntime.reset();
        assertFalse(FramePacingRuntime.enabled());

        System.setProperty(FramePacingRuntime.FPS_PROPERTY, "oops");
        FramePacingRuntime.reset();
        assertFalse(FramePacingRuntime.enabled());
    }

    @Test
    void clampsSpinMarginToTheExperimentalCeiling() {
        FramePacingRuntime.beginSession(60, 50_000);
        Map<String, Object> telemetry = FramePacingRuntime.telemetry();
        assertEquals(2_000L, telemetry.get("spinMarginMicros"));
    }
}

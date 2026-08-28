package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TacticalFleetAiTimeRuntimeTest {
    @BeforeEach
    void enable() {
        AdapterPlanControl.configure(Set.of());
        System.clearProperty(TacticalFleetAiTimeRuntime.DISABLED_PROPERTY);
        TacticalFleetAiTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        System.clearProperty(TacticalFleetAiTimeRuntime.DISABLED_PROPERTY);
        TacticalFleetAiTimeRuntime.reset();
        AdapterPlanControl.configure(Set.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsExactSemanticRegion() {
        Object tacticalAi = new Object();
        long started = TacticalFleetAiTimeRuntime.enter(TacticalFleetAiTimeRuntime.OTHER_FLEETS);
        TacticalFleetAiTimeRuntime.exit(
                tacticalAi, TacticalFleetAiTimeRuntime.OTHER_FLEETS, started);

        Map<String, Object> report = TacticalFleetAiTimeRuntime.telemetry();
        List<Map<String, Object>> phases = (List<Map<String, Object>>) report.get("phases");
        assertEquals(1L, phases.get(TacticalFleetAiTimeRuntime.OTHER_FLEETS).get("calls"));
    }

    @Test
    void propertyKillSwitchDisablesTiming() {
        System.setProperty(TacticalFleetAiTimeRuntime.DISABLED_PROPERTY, "true");
        TacticalFleetAiTimeRuntime.beginSession(true);

        assertFalse(TacticalFleetAiTimeRuntime.enabled());
        assertEquals(0L, TacticalFleetAiTimeRuntime.enter(TacticalFleetAiTimeRuntime.POST_SCAN));
    }

    @Test
    void requestedProductionPlanIsAvailableToTheTransformer() {
        assertTrue(AdapterTransformationRegistry.hasPlan(TacticalFleetAiTimeRuntime.PLAN_ID));
    }
}

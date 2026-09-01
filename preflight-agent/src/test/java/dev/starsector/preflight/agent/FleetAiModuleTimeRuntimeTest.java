package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FleetAiModuleTimeRuntimeTest {
    @BeforeEach
    void enable() {
        System.clearProperty(FleetAiModuleTimeRuntime.DISABLED_PROPERTY);
        FleetAiModuleTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        System.clearProperty(FleetAiModuleTimeRuntime.DISABLED_PROPERTY);
        FleetAiModuleTimeRuntime.reset();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsExactPhaseAndConcreteModuleClass() {
        Object fleetAi = new Object();
        Object module = new StringBuilder();
        long started = FleetAiModuleTimeRuntime.enter(module, FleetAiModuleTimeRuntime.TACTICAL);
        FleetAiModuleTimeRuntime.exit(
                fleetAi, module, FleetAiModuleTimeRuntime.TACTICAL, started);

        Map<String, Object> report = FleetAiModuleTimeRuntime.telemetry();
        assertTrue((Boolean) report.get("enabled"));
        List<Map<String, Object>> phases = (List<Map<String, Object>>) report.get("phases");
        assertEquals(1L, phases.get(FleetAiModuleTimeRuntime.TACTICAL).get("calls"));
        Map<String, List<Map<String, Object>>> classes =
                (Map<String, List<Map<String, Object>>>) report.get("moduleClasses");
        assertEquals(StringBuilder.class.getName(), classes.get("tactical").get(0).get("name"));
        assertEquals(1L, classes.get("tactical").get(0).get("calls"));
    }

    @Test
    void propertyKillSwitchDisablesTiming() {
        System.setProperty(FleetAiModuleTimeRuntime.DISABLED_PROPERTY, "true");
        FleetAiModuleTimeRuntime.beginSession(true);

        assertFalse(FleetAiModuleTimeRuntime.enabled());
        assertEquals(0L,
                FleetAiModuleTimeRuntime.enter(new Object(), FleetAiModuleTimeRuntime.ABILITY));
    }
}

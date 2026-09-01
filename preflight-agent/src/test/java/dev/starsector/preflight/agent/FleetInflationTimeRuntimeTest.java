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

class FleetInflationTimeRuntimeTest {
    @BeforeEach
    void enable() {
        AdapterPlanControl.configure(Set.of());
        System.clearProperty(FleetInflationTimeRuntime.DISABLED_PROPERTY);
        FleetInflationTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        System.clearProperty(FleetInflationTimeRuntime.DISABLED_PROPERTY);
        FleetInflationTimeRuntime.reset();
        AdapterPlanControl.configure(Set.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsExactPhaseMemberCountAndConcreteClass() {
        Object inflater = new StringBuilder();
        FleetInflationTimeRuntime.memberVisited();
        long started = FleetInflationTimeRuntime.enter(FleetInflationTimeRuntime.TOTAL);
        FleetInflationTimeRuntime.exit(inflater, FleetInflationTimeRuntime.TOTAL, started);

        Map<String, Object> report = FleetInflationTimeRuntime.telemetry();
        assertTrue((Boolean) report.get("enabled"));
        assertEquals(1L, report.get("membersVisited"));
        List<Map<String, Object>> phases = (List<Map<String, Object>>) report.get("phases");
        assertEquals(1L, phases.get(FleetInflationTimeRuntime.TOTAL).get("calls"));
        List<Map<String, Object>> classes =
                (List<Map<String, Object>>) report.get("inflaterClasses");
        assertEquals(StringBuilder.class.getName(), classes.get(0).get("name"));
        assertEquals(1L, classes.get(0).get("calls"));
    }

    @Test
    void propertyKillSwitchDisablesTiming() {
        System.setProperty(FleetInflationTimeRuntime.DISABLED_PROPERTY, "true");
        FleetInflationTimeRuntime.beginSession(true);

        assertFalse(FleetInflationTimeRuntime.enabled());
        assertEquals(0L, FleetInflationTimeRuntime.enter(FleetInflationTimeRuntime.AUTOFIT));
    }

    @Test
    void requestedProductionPlanIsAvailableToTheTransformer() {
        assertTrue(AdapterTransformationRegistry.hasPlan(FleetInflationTimeRuntime.PLAN_ID));
    }
}

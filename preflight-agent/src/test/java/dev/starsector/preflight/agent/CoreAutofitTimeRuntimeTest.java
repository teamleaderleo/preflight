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

class CoreAutofitTimeRuntimeTest {
    @BeforeEach
    void enable() {
        AdapterPlanControl.configure(Set.of());
        System.clearProperty(CoreAutofitTimeRuntime.DISABLED_PROPERTY);
        CoreAutofitTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        System.clearProperty(CoreAutofitTimeRuntime.DISABLED_PROPERTY);
        CoreAutofitTimeRuntime.reset();
        AdapterPlanControl.configure(Set.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsExactPhaseAndConcreteClass() {
        Object autofit = new StringBuilder();
        long started = CoreAutofitTimeRuntime.enter(CoreAutofitTimeRuntime.TOTAL);
        CoreAutofitTimeRuntime.exit(autofit, CoreAutofitTimeRuntime.TOTAL, started);

        Map<String, Object> report = CoreAutofitTimeRuntime.telemetry();
        assertTrue((Boolean) report.get("enabled"));
        List<Map<String, Object>> phases = (List<Map<String, Object>>) report.get("phases");
        assertEquals(1L, phases.get(CoreAutofitTimeRuntime.TOTAL).get("calls"));
        List<Map<String, Object>> classes =
                (List<Map<String, Object>>) report.get("autofitClasses");
        assertEquals(StringBuilder.class.getName(), classes.get(0).get("name"));
        assertEquals(1L, classes.get(0).get("calls"));
    }

    @Test
    void propertyKillSwitchDisablesTiming() {
        System.setProperty(CoreAutofitTimeRuntime.DISABLED_PROPERTY, "true");
        CoreAutofitTimeRuntime.beginSession(true);

        assertFalse(CoreAutofitTimeRuntime.enabled());
        assertEquals(0L, CoreAutofitTimeRuntime.enter(CoreAutofitTimeRuntime.PRIMARY_FIT));
    }

    @Test
    void requestedProductionPlanIsAvailableToTheTransformer() {
        assertTrue(AdapterTransformationRegistry.hasPlan(CoreAutofitTimeRuntime.PLAN_ID));
    }
}

package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NexEconomyInfoTimeRuntimeTest {
    @BeforeEach
    void enable() {
        System.clearProperty(NexEconomyInfoTimeRuntime.DISABLED_PROPERTY);
        NexEconomyInfoTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        System.clearProperty(NexEconomyInfoTimeRuntime.DISABLED_PROPERTY);
        NexEconomyInfoTimeRuntime.reset();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsRefreshPhasesCardinalityAndSlowSpans() {
        long total = NexEconomyInfoTimeRuntime.beginCall(false);
        long phase = NexEconomyInfoTimeRuntime.enter(NexEconomyInfoTimeRuntime.COMMODITY_SCAN);
        NexEconomyInfoTimeRuntime.visit(NexEconomyInfoTimeRuntime.COMMODITIES_VISITED);
        NexEconomyInfoTimeRuntime.exit(
                NexEconomyInfoTimeRuntime.COMMODITY_SCAN, phase - 2_000_000L);
        NexEconomyInfoTimeRuntime.exit(NexEconomyInfoTimeRuntime.TOTAL, total);

        Map<String, Object> report = NexEconomyInfoTimeRuntime.telemetry();
        assertEquals(0L, report.get("firstRunCalls"));
        assertEquals(1L, report.get("refreshCalls"));
        Map<String, Object> cardinality = (Map<String, Object>) report.get("cardinality");
        assertEquals(1L, cardinality.get("commoditiesVisited"));
        List<Map<String, Object>> phases = (List<Map<String, Object>>) report.get("phases");
        assertEquals(1L, phases.get(NexEconomyInfoTimeRuntime.COMMODITY_SCAN).get("calls"));
        assertFalse(((List<?>) report.get("slowSpans")).isEmpty());
    }

    @Test
    void independentPropertyDisablesProbe() {
        System.setProperty(NexEconomyInfoTimeRuntime.DISABLED_PROPERTY, "true");
        NexEconomyInfoTimeRuntime.beginSession(true);

        assertFalse(NexEconomyInfoTimeRuntime.enabled());
        assertEquals(0L, NexEconomyInfoTimeRuntime.beginCall(false));
        assertEquals(0L, NexEconomyInfoTimeRuntime.enter(NexEconomyInfoTimeRuntime.TOTAL));
    }

    @Test
    void planIsRegistered() {
        assertTrue(AdapterTransformationRegistry.hasPlan(NexEconomyInfoTimeRuntime.PLAN_ID));
    }
}

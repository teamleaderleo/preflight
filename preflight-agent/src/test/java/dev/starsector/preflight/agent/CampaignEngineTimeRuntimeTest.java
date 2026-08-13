package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CampaignEngineTimeRuntimeTest {
    @BeforeEach
    void enable() {
        CampaignEngineTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        CampaignEngineTimeRuntime.reset();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsFixedPhasesAndGroupsScriptsByConcreteClass() {
        long phase = CampaignEngineTimeRuntime.enter(CampaignEngineTimeRuntime.ECONOMY);
        CampaignEngineTimeRuntime.exit(CampaignEngineTimeRuntime.ECONOMY, phase);

        Object first = new FirstScript();
        for (int call = 0; call < 2; call++) {
            long started = CampaignEngineTimeRuntime.enterScript(first);
            CampaignEngineTimeRuntime.exitScript(first, started);
        }
        Object second = new SecondScript();
        long started = CampaignEngineTimeRuntime.enterScript(second);
        CampaignEngineTimeRuntime.exitScript(second, started);

        Map<String, Object> report = CampaignEngineTimeRuntime.telemetry();
        List<Map<String, Object>> phases = (List<Map<String, Object>>) report.get("phases");
        assertEquals(1L, phases.get(CampaignEngineTimeRuntime.ECONOMY).get("calls"));
        assertNotNull(phases.get(CampaignEngineTimeRuntime.ECONOMY).get("maximumEndEpochMillis"));

        List<Map<String, Object>> scripts =
                (List<Map<String, Object>>) report.get("scriptClasses");
        assertEquals(2, scripts.size());
        Map<String, Map<String, Object>> byName = scripts.stream().collect(
                java.util.stream.Collectors.toMap(
                        value -> (String) value.get("name"), value -> value));
        assertEquals(2L, byName.get(FirstScript.class.getName()).get("calls"));
        assertEquals(1L, byName.get(SecondScript.class.getName()).get("calls"));
    }

    private static final class FirstScript {
    }

    private static final class SecondScript {
    }
}

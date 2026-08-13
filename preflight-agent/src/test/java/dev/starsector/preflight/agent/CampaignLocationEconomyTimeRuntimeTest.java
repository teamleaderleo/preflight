package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CampaignLocationEconomyTimeRuntimeTest {
    @BeforeEach
    void enable() {
        CampaignLocationEconomyTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        CampaignLocationEconomyTimeRuntime.reset();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsFixedPhasesAndConcreteClassGroups() {
        long fixed = CampaignLocationEconomyTimeRuntime.enter(
                CampaignLocationEconomyTimeRuntime.LOCATION_MAP);
        CampaignLocationEconomyTimeRuntime.exit(
                CampaignLocationEconomyTimeRuntime.LOCATION_MAP, fixed);

        Object entity = new StringBuilder();
        for (int call = 0; call < 64; call++) {
            long active = CampaignLocationEconomyTimeRuntime.enterClass(
                    entity, CampaignLocationEconomyTimeRuntime.ENTITY_ACTIVE);
            CampaignLocationEconomyTimeRuntime.exitClass(
                    entity, CampaignLocationEconomyTimeRuntime.ENTITY_ACTIVE, active);
            long paused = CampaignLocationEconomyTimeRuntime.enterClass(
                    entity, CampaignLocationEconomyTimeRuntime.ENTITY_PAUSED);
            CampaignLocationEconomyTimeRuntime.exitClass(
                    entity, CampaignLocationEconomyTimeRuntime.ENTITY_PAUSED, paused);
        }

        Map<String, Object> report = CampaignLocationEconomyTimeRuntime.telemetry();
        List<Map<String, Object>> phases =
                (List<Map<String, Object>>) report.get("economyPhases");
        assertEquals(1L, phases.get(CampaignLocationEconomyTimeRuntime.LOCATION_MAP).get("calls"));
        assertNotNull(phases.get(CampaignLocationEconomyTimeRuntime.LOCATION_MAP)
                .get("maximumEndEpochMillis"));
        List<Map<String, Object>> activeClasses =
                (List<Map<String, Object>>) report.get("entityActiveClasses");
        List<Map<String, Object>> pausedClasses =
                (List<Map<String, Object>>) report.get("entityPausedClasses");
        assertEquals(StringBuilder.class.getName(), activeClasses.get(0).get("name"));
        assertEquals(64L, activeClasses.get(0).get("calls"));
        assertEquals(1L, activeClasses.get(0).get("sampledCalls"));
        assertEquals(StringBuilder.class.getName(), pausedClasses.get(0).get("name"));
        assertEquals(64L, pausedClasses.get(0).get("calls"));
    }
}

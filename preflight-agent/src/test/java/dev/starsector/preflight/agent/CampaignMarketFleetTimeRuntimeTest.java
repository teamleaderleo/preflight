package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CampaignMarketFleetTimeRuntimeTest {
    @BeforeEach
    void enable() {
        CampaignMarketFleetTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        CampaignMarketFleetTimeRuntime.reset();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsExactAndSampledCountersWithoutChangingCallers() {
        long exact = CampaignMarketFleetTimeRuntime.enter(CampaignMarketFleetTimeRuntime.FLEET_STATS);
        CampaignMarketFleetTimeRuntime.exit(CampaignMarketFleetTimeRuntime.FLEET_STATS, exact);
        for (int call = 0; call < 32; call++) {
            long sampled = CampaignMarketFleetTimeRuntime.enter(
                    CampaignMarketFleetTimeRuntime.MARKET_MEMORY);
            CampaignMarketFleetTimeRuntime.exit(CampaignMarketFleetTimeRuntime.MARKET_MEMORY, sampled);
        }
        Object ai = new StringBuilder();
        long byClass = CampaignMarketFleetTimeRuntime.enterClass(
                ai, CampaignMarketFleetTimeRuntime.FLEET_AI);
        CampaignMarketFleetTimeRuntime.exitClass(
                ai, CampaignMarketFleetTimeRuntime.FLEET_AI, byClass);

        Map<String, Object> report = CampaignMarketFleetTimeRuntime.telemetry();
        List<Map<String, Object>> phases = (List<Map<String, Object>>) report.get("phases");
        assertEquals(32L, phases.get(CampaignMarketFleetTimeRuntime.MARKET_MEMORY).get("calls"));
        assertEquals(1L,
                phases.get(CampaignMarketFleetTimeRuntime.MARKET_MEMORY).get("sampledCalls"));
        assertEquals(32, phases.get(CampaignMarketFleetTimeRuntime.MARKET_MEMORY).get("sampleRate"));
        assertEquals(1L, phases.get(CampaignMarketFleetTimeRuntime.FLEET_STATS).get("calls"));
        List<Map<String, Object>> classes =
                (List<Map<String, Object>>) report.get("fleetAiClasses");
        assertEquals(StringBuilder.class.getName(), classes.get(0).get("name"));
        assertEquals(1L, classes.get(0).get("sampledCalls"));
    }
}

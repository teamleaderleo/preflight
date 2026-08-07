package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CampaignEntityTelemetryReportTest {
    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    @AfterEach
    void reset() {
        System.clearProperty(EntityLookupRuntime.ENABLED_PROPERTY);
        EntityLookupRuntime.beginSession();
        AdapterPlanControl.configure(Set.of());
    }

    @Test
    void shutdownReportProvesThePilotWasInstalledEnabledAndUsed() throws Exception {
        List<SectorEntityToken> entities = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            entities.add(new Token("entity_" + i));
        }
        Object location = new Object();
        EntityLookupRuntime.locationInstalled();
        EntityLookupRuntime.repositoryInstalled();
        EntityLookupRuntime.idMutationInstalled();
        System.setProperty(EntityLookupRuntime.ENABLED_PROPERTY, "true");

        assertSame(entities.get(3), EntityLookupRuntime.lookup(entities, location, "entity_3"));
        assertTrue(EntityLookupRuntime.missing(
                EntityLookupRuntime.lookup(entities, location, "missing")));

        Path output = temporaryDirectory.resolve("adapter.json");
        AdapterReport adapter = new AdapterReport(
                AdapterMode.ENABLED, output, null, List.of("com/fs/"));
        adapter.transformerInstalled(AdapterTargetRegistry.empty().withHullJsonCacheTarget());
        adapter.write();
        String report = Files.readString(output);
        assertTrue(report.contains("\"registeredPlanTargets\":{"
                + "\"vanilla-hull-merged-json-cache-v1\":1}"), report);
        assertTrue(report.contains("\"campaignEntityIndex\":{"
                + "\"planId\":\"campaign-entity-index-v3\","
                + "\"installed\":true,\"enabled\":true,"), report);
        assertTrue(report.contains("\"served\":1,\"missingServed\":1,\"declined\":0,"), report);
        assertTrue(report.contains("\"rebuilds\":1,\"indexedEntities\":16,"), report);
        assertTrue(report.contains("\"planControl\":"), report);
        assertTrue(report.contains("\"scope\":\"full\""), report);
        assertTrue(report.contains("\"disabledPlans\":[]"), report);
        assertTrue(report.contains("\"texturePreparedPixels\":"), report);
        assertTrue(report.contains("\"texturePadding\":"), report);
    }

    private record Token(String id) implements SectorEntityToken {
        @Override
        public String getId() {
            return id;
        }
    }
}

package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        new AdapterReport(AdapterMode.ENABLED, output, null, List.of("com/fs/")).write();
        String report = Files.readString(output);
        assertTrue(report.contains("\"campaignEntityIndex\":{"
                + "\"planId\":\"campaign-entity-index-v3\","
                + "\"installed\":true,\"enabled\":true,"), report);
        assertTrue(report.contains("\"served\":1,\"missingServed\":1,\"declined\":0,"), report);
        assertTrue(report.contains("\"rebuilds\":1,\"indexedEntities\":16,"), report);
    }

    private record Token(String id) implements SectorEntityToken {
        @Override
        public String getId() {
            return id;
        }
    }
}

package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LunaCampaignRendererSnapshotRuntimeTest {
    @AfterEach
    void reset() {
        LunaCampaignRendererSnapshotRuntime.reset();
    }

    @Test
    void reusesUnchangedOrderedSourcesAndRebuildsForEveryMutationShape() {
        Object owner = new Object();
        ArrayList<Object> transientRenderers = new ArrayList<>(List.of("transient"));
        ArrayList<Object> persistentRenderers = new ArrayList<>(List.of("alpha", "beta"));

        ArrayList<?> first = LunaCampaignRendererSnapshotRuntime.snapshot(
                owner, transientRenderers, persistentRenderers);
        ArrayList<?> hit = LunaCampaignRendererSnapshotRuntime.snapshot(
                owner, transientRenderers, persistentRenderers);
        assertSame(first, hit);
        assertEquals(List.of("transient", "alpha", "beta"), hit);

        persistentRenderers.set(1, "replacement");
        ArrayList<?> replaced = LunaCampaignRendererSnapshotRuntime.snapshot(
                owner, transientRenderers, persistentRenderers);
        assertNotSame(first, replaced);
        assertEquals(List.of("transient", "alpha", "replacement"), replaced);
        assertEquals(List.of("transient", "alpha", "beta"), first,
                "an outer iterator retains its stable snapshot");

        transientRenderers.add("second-transient");
        ArrayList<?> resized = LunaCampaignRendererSnapshotRuntime.snapshot(
                owner, transientRenderers, persistentRenderers);
        assertNotSame(replaced, resized);
        assertEquals(
                List.of("transient", "second-transient", "alpha", "replacement"), resized);

        resized.clear();
        ArrayList<?> repaired = LunaCampaignRendererSnapshotRuntime.snapshot(
                owner, transientRenderers, persistentRenderers);
        assertNotSame(resized, repaired);
        assertEquals(
                List.of("transient", "second-transient", "alpha", "replacement"), repaired);

        assertEquals(5L, LunaCampaignRendererSnapshotRuntime.telemetry().get("requests"));
        assertEquals(1L, LunaCampaignRendererSnapshotRuntime.telemetry().get("hits"));
        assertEquals(4L, LunaCampaignRendererSnapshotRuntime.telemetry().get("rebuilds"));
        assertEquals(0L, LunaCampaignRendererSnapshotRuntime.telemetry().get("failures"));
    }

    @Test
    void ownerIdentityKeepsIndependentCampaignScriptsApart() {
        List<Object> transientRenderers = new ArrayList<>(List.of("transient"));
        List<Object> persistentRenderers = new ArrayList<>(List.of("persistent"));
        ArrayList<?> first = LunaCampaignRendererSnapshotRuntime.snapshot(
                new EqualOwner(), transientRenderers, persistentRenderers);
        ArrayList<?> second = LunaCampaignRendererSnapshotRuntime.snapshot(
                new EqualOwner(), transientRenderers, persistentRenderers);

        assertNotSame(first, second);
        assertEquals(2, LunaCampaignRendererSnapshotRuntime.telemetry().get("owners"));
    }

    @Test
    void ownerCacheRemainsBoundedAcrossCampaignChurn() {
        List<Object> transientRenderers = List.of("transient");
        List<Object> persistentRenderers = List.of("persistent");
        for (int index = 0; index < 9; index++) {
            LunaCampaignRendererSnapshotRuntime.snapshot(
                    new Object(), transientRenderers, persistentRenderers);
        }

        assertEquals(1, LunaCampaignRendererSnapshotRuntime.telemetry().get("owners"));
        assertEquals(1L, LunaCampaignRendererSnapshotRuntime.telemetry().get("evictions"));
    }

    private static final class EqualOwner {
        @Override
        public boolean equals(Object ignored) {
            return true;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}

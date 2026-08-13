package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventModMapSnapshotRuntimeTest {
    @BeforeEach
    void reset() {
        EventModMapSnapshotRuntime.reset();
    }

    @Test
    void unavailableDeepReflectionFailsClosed() {
        if (EventModMapSnapshotRuntime.available()) return;

        var map = new LinkedHashMap<String, Object>();
        map.put("eMod", new Object());
        assertNull(EventModMapSnapshotRuntime.capture(map, "eMod"));
        assertEquals(1L, EventModMapSnapshotRuntime.unavailableCaptures());
    }

    @Test
    void exactMapGenerationAndEntryIdentityCoverEveryMutationShape() {
        assumeTrue(EventModMapSnapshotRuntime.available(),
                "positive path requires --add-opens java.base/java.util=ALL-UNNAMED");

        var first = new Object();
        var replacement = new Object();
        var map = new LinkedHashMap<String, Object>();
        map.put("eMod", first);

        Object stable = EventModMapSnapshotRuntime.capture(map, "eMod");
        assertNotNull(stable);
        assertTrue(EventModMapSnapshotRuntime.unchanged(stable, map, first));

        map.put("eMod", replacement);
        assertFalse(EventModMapSnapshotRuntime.unchanged(stable, map, first));

        Object replaced = EventModMapSnapshotRuntime.capture(map, "eMod");
        assertTrue(EventModMapSnapshotRuntime.unchanged(replaced, map, replacement));
        map.entrySet().iterator().next().setValue(first);
        assertFalse(EventModMapSnapshotRuntime.unchanged(replaced, map, replacement));

        Object beforeStructuralChange = EventModMapSnapshotRuntime.capture(map, "eMod");
        map.remove("eMod");
        map.put("eMod", first);
        assertFalse(EventModMapSnapshotRuntime.unchanged(beforeStructuralChange, map, first));

        Object current = EventModMapSnapshotRuntime.capture(map, "eMod");
        var newMap = new LinkedHashMap<String, Object>();
        newMap.put("eMod", first);
        assertFalse(EventModMapSnapshotRuntime.unchanged(current, newMap, first));

        map.remove("eMod");
        Object absent = EventModMapSnapshotRuntime.capture(map, "eMod");
        assertTrue(EventModMapSnapshotRuntime.unchanged(absent, map, null));
        map.put("eMod", first);
        assertFalse(EventModMapSnapshotRuntime.unchanged(absent, map, null));

        assertEquals(5L, EventModMapSnapshotRuntime.captures());
        assertEquals(5L, EventModMapSnapshotRuntime.invalidations());
    }
}

package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CombatListenerRangeSnapshotRuntimeTest {
    @AfterEach
    void reset() {
        System.clearProperty(CombatListenerRangeSnapshotRuntime.ENABLED_PROPERTY);
        CombatListenerRangeSnapshotRuntime.beginSession();
    }

    @Test
    void disabledModeRetainsFreshPerCallSnapshots() {
        CombatListenerRangeSnapshotRuntime.beginSession();
        List<Object> source = new ArrayList<>(List.of(new Object()));

        Object[] first = CombatListenerRangeSnapshotRuntime.snapshot(source);
        Object[] second = CombatListenerRangeSnapshotRuntime.snapshot(source);

        assertNotSame(first, second);
        assertEquals(false, CombatListenerRangeSnapshotRuntime.telemetry().get("enabled"));
    }

    @Test
    void reusesOnlyAfterFullOrderedIdentityValidation() {
        enable();
        Object firstValue = new Object();
        Object secondValue = new Object();
        List<Object> source = new ArrayList<>(List.of(firstValue, secondValue));

        Object[] first = CombatListenerRangeSnapshotRuntime.snapshot(source);
        Object[] hit = CombatListenerRangeSnapshotRuntime.snapshot(source);
        assertSame(first, hit);

        source.set(1, new Object());
        Object[] replaced = CombatListenerRangeSnapshotRuntime.snapshot(source);
        assertNotSame(first, replaced);
        assertSame(secondValue, first[1]);

        source.add(firstValue);
        Object[] resized = CombatListenerRangeSnapshotRuntime.snapshot(source);
        assertNotSame(replaced, resized);
        assertArrayEquals(source.toArray(), resized);

        Map<String, Object> telemetry = CombatListenerRangeSnapshotRuntime.telemetry();
        assertEquals(1L, telemetry.get("hits"));
        assertEquals(3L, telemetry.get("rebuilds"));
        assertEquals(2L, telemetry.get("comparedElements"));
    }

    @Test
    void directMutationCannotChangeAnInProgressSnapshot() {
        enable();
        Object original = new Object();
        List<Object> source = new ArrayList<>(List.of(original));
        Object[] inProgress = CombatListenerRangeSnapshotRuntime.snapshot(source);

        Object added = new Object();
        source.clear();
        source.add(added);
        Object[] nextQuery = CombatListenerRangeSnapshotRuntime.snapshot(source);

        assertArrayEquals(new Object[] {original}, inProgress);
        assertArrayEquals(new Object[] {added}, nextQuery);
    }

    @Test
    void nonExactArrayListsAlwaysDelegateToFreshSnapshots() {
        enable();
        List<Object> source = new LinkedList<>(List.of(new Object()));

        Object[] first = CombatListenerRangeSnapshotRuntime.snapshot(source);
        Object[] second = CombatListenerRangeSnapshotRuntime.snapshot(source);
        List<Object> empty = new LinkedList<>();
        Object[] firstEmpty = CombatListenerRangeSnapshotRuntime.snapshot(empty);
        Object[] secondEmpty = CombatListenerRangeSnapshotRuntime.snapshot(empty);

        assertNotSame(first, second);
        assertNotSame(firstEmpty, secondEmpty);
        assertEquals(4L,
                CombatListenerRangeSnapshotRuntime.telemetry().get("nonArrayListDelegations"));
    }

    @Test
    void ownerTableClearsAtItsStrictBound() {
        enable();
        for (int index = 0; index < 513; index++) {
            CombatListenerRangeSnapshotRuntime.snapshot(
                    new ArrayList<>(List.of(new Object())));
        }

        Map<String, Object> telemetry = CombatListenerRangeSnapshotRuntime.telemetry();
        assertEquals(1L, telemetry.get("evictions"));
        assertEquals(1, telemetry.get("snapshotOwners"));
        assertEquals(512, telemetry.get("maximumSnapshotOwners"));
    }

    private static void enable() {
        System.setProperty(CombatListenerRangeSnapshotRuntime.ENABLED_PROPERTY, "true");
        CombatListenerRangeSnapshotRuntime.beginSession();
    }
}

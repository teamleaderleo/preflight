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
        List<Object> source = new ArrayList<>();

        assertNotSame(
                CombatListenerRangeSnapshotRuntime.snapshot(source),
                CombatListenerRangeSnapshotRuntime.snapshot(source));
        assertEquals(false, CombatListenerRangeSnapshotRuntime.telemetry().get("enabled"));
    }

    @Test
    void exactEmptyArrayListsShareOnlyThePrivateZeroLengthSnapshot() {
        enable();
        List<Object> empty = new ArrayList<>();
        List<Object> nonEmpty = new ArrayList<>(List.of(new Object()));

        assertSame(
                CombatListenerRangeSnapshotRuntime.snapshot(empty),
                CombatListenerRangeSnapshotRuntime.snapshot(empty));
        assertNotSame(
                CombatListenerRangeSnapshotRuntime.snapshot(nonEmpty),
                CombatListenerRangeSnapshotRuntime.snapshot(nonEmpty));

        Map<String, Object> telemetry = CombatListenerRangeSnapshotRuntime.telemetry();
        assertEquals(2L, telemetry.get("emptySnapshots"));
        assertEquals(2L, telemetry.get("nonEmptyDelegations"));
    }

    @Test
    void directMutationGetsFreshNonEmptySnapshotOnTheNextQuery() {
        enable();
        List<Object> source = new ArrayList<>();
        Object[] empty = CombatListenerRangeSnapshotRuntime.snapshot(source);

        Object added = new Object();
        source.add(added);
        Object[] nextQuery = CombatListenerRangeSnapshotRuntime.snapshot(source);

        assertArrayEquals(new Object[0], empty);
        assertArrayEquals(new Object[] {added}, nextQuery);
    }

    @Test
    void nonExactArrayListsAlwaysDelegateIncludingWhenEmpty() {
        enable();
        List<Object> source = new LinkedList<>();

        Object[] firstEmpty = CombatListenerRangeSnapshotRuntime.snapshot(source);
        Object[] secondEmpty = CombatListenerRangeSnapshotRuntime.snapshot(source);
        source.add(new Object());
        Object[] firstNonEmpty = CombatListenerRangeSnapshotRuntime.snapshot(source);
        Object[] secondNonEmpty = CombatListenerRangeSnapshotRuntime.snapshot(source);

        assertNotSame(firstEmpty, secondEmpty);
        assertNotSame(firstNonEmpty, secondNonEmpty);
        assertEquals(4L,
                CombatListenerRangeSnapshotRuntime.telemetry().get("nonArrayListDelegations"));
    }

    @Test
    void productionTelemetryCanBeDisabledWithoutChangingTheShortcut() {
        System.setProperty(CombatListenerRangeSnapshotRuntime.ENABLED_PROPERTY, "true");
        CombatListenerRangeSnapshotRuntime.beginSession(false);
        List<Object> source = new ArrayList<>();

        assertSame(
                CombatListenerRangeSnapshotRuntime.snapshot(source),
                CombatListenerRangeSnapshotRuntime.snapshot(source));
        Map<String, Object> telemetry = CombatListenerRangeSnapshotRuntime.telemetry();
        assertEquals(false, telemetry.get("telemetryEnabled"));
        assertEquals(0L, telemetry.get("emptySnapshots"));
    }

    private static void enable() {
        System.setProperty(CombatListenerRangeSnapshotRuntime.ENABLED_PROPERTY, "true");
        CombatListenerRangeSnapshotRuntime.beginSession();
    }
}

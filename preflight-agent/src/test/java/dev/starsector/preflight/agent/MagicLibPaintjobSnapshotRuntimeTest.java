package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MagicLibPaintjobSnapshotRuntimeTest {
    @BeforeEach
    void install() {
        MagicLibPaintjobSnapshotRuntime.beginSession();
        MagicLibPaintjobSnapshotRuntime.installed();
        Manager.paintjobs = new LinkedHashSet<>(Set.of("alpha"));
    }

    @AfterEach
    void reset() {
        System.clearProperty(MagicLibPaintjobSnapshotRuntime.DISABLED_PROPERTY);
        MagicLibPaintjobSnapshotRuntime.beginSession();
    }

    @Test
    void reusesPrivateAdvanceSnapshotAndRefreshesAfterReviewedMutation() {
        Set<?> first = MagicLibPaintjobSnapshotRuntime.snapshot(false, 1, null, Manager.class);
        Set<?> second = MagicLibPaintjobSnapshotRuntime.snapshot(false, 1, null, Manager.class);
        assertEquals(Set.of(), second);

        Manager.paintjobs.add("beta");
        MagicLibPaintjobSnapshotRuntime.mutated(true);
        Set<?> third = MagicLibPaintjobSnapshotRuntime.snapshot(false, 1, null, Manager.class);
        assertNotSame(first, third);
        assertEquals(Set.of("alpha", "beta"), third);
        assertEquals(1L, MagicLibPaintjobSnapshotRuntime.telemetry().get("hits"));
        assertEquals(2L, MagicLibPaintjobSnapshotRuntime.telemetry().get("rebuilds"));
        assertEquals(1L, MagicLibPaintjobSnapshotRuntime.telemetry().get("mutations"));
    }

    @Test
    void killSwitchDelegatesToFreshPublicResult() {
        System.setProperty(MagicLibPaintjobSnapshotRuntime.DISABLED_PROPERTY, "true");
        Set<?> first = MagicLibPaintjobSnapshotRuntime.snapshot(false, 1, null, Manager.class);
        Set<?> second = MagicLibPaintjobSnapshotRuntime.snapshot(false, 1, null, Manager.class);
        assertNotSame(first, second);
        assertEquals(2L, MagicLibPaintjobSnapshotRuntime.telemetry().get("delegated"));
    }

    public static final class Manager {
        static Set<String> paintjobs;

        public static Set<String> getPaintjobs(boolean includeShiny) {
            return new LinkedHashSet<>(paintjobs);
        }
    }
}

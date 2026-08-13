package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CombatRuntimeIntegrityRuntimeTest {
    @AfterEach
    void reset() {
        CombatRuntimeIntegrityRuntime.beginSession();
        System.clearProperty("preflight.combatIntegrity.jvmMode");
    }

    @Test
    void recordsTheLoadedAssignableRelationshipOnlyOnce() {
        CombatRuntimeIntegrityRuntime.beginSession();
        CombatRuntimeIntegrityRuntime.observeClasses(Weapon.class, FittedModule.class);
        CombatRuntimeIntegrityRuntime.observeClasses(Object.class, FittedModule.class);

        Map<String, Object> telemetry = CombatRuntimeIntegrityRuntime.telemetry();
        assertTrue((Boolean) telemetry.get("observed"));
        assertTrue((Boolean) telemetry.get("assignable"));
        assertTrue((Boolean) telemetry.get("sameLoader"));
        assertEquals(Weapon.class.getName(), telemetry.get("weaponClass"));
        assertEquals(List.of(FittedModule.class.getName()), telemetry.get("weaponInterfaces"));
        assertNull(telemetry.get("failure"));
    }

    @Test
    void reportsDiagnosticModeAndAnIncompatibleRelationship() {
        System.setProperty("preflight.combatIntegrity.jvmMode", "safer-jvm");
        CombatRuntimeIntegrityRuntime.beginSession();
        CombatRuntimeIntegrityRuntime.observeClasses(Object.class, FittedModule.class);

        Map<String, Object> telemetry = CombatRuntimeIntegrityRuntime.telemetry();
        assertFalse((Boolean) telemetry.get("assignable"));
        assertEquals("safer-jvm", telemetry.get("diagnosticJvmMode"));
    }

    private interface FittedModule {
    }

    private static final class Weapon implements FittedModule {
    }
}

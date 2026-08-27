package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AiTweaksWeaponLocationSnapshotRuntimeTest {
    @AfterEach
    void reset() {
        System.clearProperty(AiTweaksWeaponLocationSnapshotRuntime.ENABLED_PROPERTY);
        AiTweaksWeaponLocationSnapshotRuntime.beginSession();
    }

    @Test
    void remainsTransparentWhenDisabled() {
        AiTweaksWeaponLocationSnapshotRuntime.beginSession();
        installBothTransforms();
        Object weapon = new Object();
        Object location = new Object();

        AiTweaksWeaponLocationSnapshotRuntime.begin(weapon);
        AiTweaksWeaponLocationSnapshotRuntime.rememberLocation(weapon, location);

        assertNull(AiTweaksWeaponLocationSnapshotRuntime.cachedLocation(weapon));
        assertEquals(0L, telemetry().get("selectionContexts"));
        assertFalse((Boolean) telemetry().get("enabled"));
    }

    @Test
    void reusesOnlyTheSameWeaponInsideOneSelectionAndReleasesItAtEnd() {
        System.setProperty(AiTweaksWeaponLocationSnapshotRuntime.ENABLED_PROPERTY, "true");
        AiTweaksWeaponLocationSnapshotRuntime.beginSession();
        installBothTransforms();
        Object weapon = new Object();
        Object anotherWeapon = new Object();
        Object location = new Object();

        AiTweaksWeaponLocationSnapshotRuntime.begin(weapon);
        assertNull(AiTweaksWeaponLocationSnapshotRuntime.cachedLocation(weapon));
        AiTweaksWeaponLocationSnapshotRuntime.rememberLocation(weapon, location);
        assertSame(location, AiTweaksWeaponLocationSnapshotRuntime.cachedLocation(weapon));
        assertNull(AiTweaksWeaponLocationSnapshotRuntime.cachedLocation(anotherWeapon));
        AiTweaksWeaponLocationSnapshotRuntime.end();
        assertNull(AiTweaksWeaponLocationSnapshotRuntime.cachedLocation(weapon));

        Map<String, Object> telemetry = telemetry();
        assertTrue((Boolean) telemetry.get("enabled"));
        assertEquals(1L, telemetry.get("selectionContexts"));
        assertEquals(1L, telemetry.get("locationMisses"));
        assertEquals(1L, telemetry.get("locationHits"));
        assertEquals(0L, telemetry.get("abandonedContexts"));
    }

    @Test
    void aNestedBeginFailsClosedByDiscardingTheOlderContext() {
        System.setProperty(AiTweaksWeaponLocationSnapshotRuntime.ENABLED_PROPERTY, "true");
        AiTweaksWeaponLocationSnapshotRuntime.beginSession();
        installBothTransforms();
        Object firstWeapon = new Object();
        Object secondWeapon = new Object();
        Object firstLocation = new Object();

        AiTweaksWeaponLocationSnapshotRuntime.begin(firstWeapon);
        AiTweaksWeaponLocationSnapshotRuntime.cachedLocation(firstWeapon);
        AiTweaksWeaponLocationSnapshotRuntime.rememberLocation(firstWeapon, firstLocation);
        AiTweaksWeaponLocationSnapshotRuntime.begin(secondWeapon);

        assertNull(AiTweaksWeaponLocationSnapshotRuntime.cachedLocation(firstWeapon));
        assertNull(AiTweaksWeaponLocationSnapshotRuntime.cachedLocation(secondWeapon));
        AiTweaksWeaponLocationSnapshotRuntime.end();
        assertEquals(1L, telemetry().get("abandonedContexts"));
        assertEquals(2L, telemetry().get("selectionContexts"));
        assertEquals(2L, telemetry().get("locationMisses"));
    }

    @Test
    void remainsInertWhenOnlyOneReviewedTransformInstalls() {
        System.setProperty(AiTweaksWeaponLocationSnapshotRuntime.ENABLED_PROPERTY, "true");
        AiTweaksWeaponLocationSnapshotRuntime.beginSession();
        AiTweaksWeaponLocationSnapshotRuntime.autofireInstalled();

        Object weapon = new Object();
        Object location = new Object();
        AiTweaksWeaponLocationSnapshotRuntime.begin(weapon);
        AiTweaksWeaponLocationSnapshotRuntime.rememberLocation(weapon, location);

        assertNull(AiTweaksWeaponLocationSnapshotRuntime.cachedLocation(weapon));
        assertEquals(0L, telemetry().get("selectionContexts"));
        assertFalse((Boolean) telemetry().get("installed"));
    }

    private static void installBothTransforms() {
        AiTweaksWeaponLocationSnapshotRuntime.autofireInstalled();
        AiTweaksWeaponLocationSnapshotRuntime.weaponHandleInstalled();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> telemetry() {
        return AiTweaksWeaponLocationSnapshotRuntime.telemetry();
    }
}

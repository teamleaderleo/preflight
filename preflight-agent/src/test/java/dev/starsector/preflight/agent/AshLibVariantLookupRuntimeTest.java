package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AshLibVariantLookupRuntimeTest {
    @BeforeEach
    void reset() {
        AshLibVariantLookupRuntime.beginSession();
    }

    @Test
    void preservesFirstEligibleVariantAndOrderedFallback() {
        FakeSettings settings = new FakeSettings();
        settings.add("ignored", null, "hull-a");
        settings.add("first", "data/variants/first.variant", "hull-a");
        settings.add("second", "data/variants/second.variant", "hull-a");
        settings.add("ODD_HULL", "data/variants/odd.variant", "another-hull");

        AshLibVariantLookupRuntime.begin(settings);

        assertTrue(AshLibVariantLookupRuntime.active());
        assertEquals("first", AshLibVariantLookupRuntime.lookup("hull-a"));
        assertEquals("ODD_HULL", AshLibVariantLookupRuntime.lookup("odd"));
        assertNull(AshLibVariantLookupRuntime.lookup("absent"));
        Object json = new Object();
        assertNull(AshLibVariantLookupRuntime.cachedShipJson("hull-a"));
        AshLibVariantLookupRuntime.rememberShipJson("hull-a", json);
        assertEquals(json, AshLibVariantLookupRuntime.cachedShipJson("hull-a"));
        assertEquals(1L, AshLibVariantLookupRuntime.telemetry().get("builds"));
        assertEquals(4L, AshLibVariantLookupRuntime.telemetry().get("indexedVariants"));
        assertEquals(1L, AshLibVariantLookupRuntime.telemetry().get("exactHits"));
        assertEquals(1L, AshLibVariantLookupRuntime.telemetry().get("fallbackNameHits"));
        assertEquals(1L, AshLibVariantLookupRuntime.telemetry().get("nullResults"));
        assertEquals(1L, AshLibVariantLookupRuntime.telemetry().get("shipJsonHits"));
        assertEquals(1L, AshLibVariantLookupRuntime.telemetry().get("shipJsonMisses"));
        assertEquals(1L, AshLibVariantLookupRuntime.telemetry().get("shipJsonCaptures"));

        AshLibVariantLookupRuntime.end();
        assertFalse(AshLibVariantLookupRuntime.active());
    }

    @Test
    void malformedLiveRegistryDisablesShortcutAndLeavesVanillaAvailable() {
        SettingsAPI malformed = new SettingsAPI() {
            @SuppressWarnings({"rawtypes", "unchecked"})
            @Override
            public List<String> getAllVariantIds() {
                return (List) List.of(7);
            }

            @Override
            public ShipVariantAPI getVariant(String id) {
                throw new AssertionError("not reached");
            }
        };

        AshLibVariantLookupRuntime.begin(malformed);

        assertFalse(AshLibVariantLookupRuntime.active());
        assertEquals(1L, AshLibVariantLookupRuntime.telemetry().get("buildFailures"));
    }

    private static final class FakeSettings implements SettingsAPI {
        private final Map<String, ShipVariantAPI> variants = new LinkedHashMap<>();

        void add(String id, String path, String hullId) {
            variants.put(id, new FakeVariant(path, () -> hullId));
        }

        @Override
        public List<String> getAllVariantIds() {
            return List.copyOf(variants.keySet());
        }

        @Override
        public ShipVariantAPI getVariant(String id) {
            return variants.get(id);
        }
    }

    private record FakeVariant(String path, ShipHullSpecAPI hull) implements ShipVariantAPI {
        @Override
        public String getVariantFilePath() {
            return path;
        }

        @Override
        public ShipHullSpecAPI getHullSpec() {
            return hull;
        }
    }
}

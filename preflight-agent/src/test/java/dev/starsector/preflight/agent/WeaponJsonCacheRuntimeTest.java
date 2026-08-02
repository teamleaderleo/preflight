package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedWeaponJsonCache;
import dev.starsector.preflight.core.PreparedWeaponJsonCacheIO;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WeaponJsonCacheRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void reset() {
        WeaponJsonCacheRuntime.beginSession();
    }

    @Test
    void learnsVanillaResultsOnlyWhenTheWholeLoaderCompletes() throws Exception {
        String profile = "a".repeat(64);
        Path artifact = temporaryDirectory.resolve(profile + ".spwj");
        WeaponJsonCacheRuntime.configure(artifact);

        assertTrue(WeaponJsonCacheRuntime.ready());
        assertNull(WeaponJsonCacheRuntime.cached("data/weapons/example.wpn"));
        WeaponJsonCacheRuntime.capture(
                new org.json.JSONObject("{\"id\":\"example\"}"),
                "data/weapons/example.wpn");
        assertTrue(java.nio.file.Files.notExists(artifact));

        WeaponJsonCacheRuntime.complete();
        assertEquals(
                "{\"id\":\"example\"}",
                PreparedWeaponJsonCacheIO.read(artifact).entries()
                        .get("data/weapons/example.wpn"));
        assertEquals(1L, WeaponJsonCacheRuntime.telemetry().get("misses"));
        assertEquals(1L, WeaponJsonCacheRuntime.telemetry().get("captures"));
        assertEquals(1L, WeaponJsonCacheRuntime.telemetry().get("writes"));
    }

    @Test
    void reconstructsAFreshJsonObjectForBothWeaponDomains() throws Exception {
        String profile = "b".repeat(64);
        Path artifact = temporaryDirectory.resolve(profile + ".spwj");
        PreparedWeaponJsonCacheIO.write(artifact, new PreparedWeaponJsonCache(
                profile, Map.of(
                        "data/weapons/example.wpn", "{\"id\":\"example\"}",
                        "data/shipsystems/wpn/system.wpn", "{\"id\":\"system\"}")));
        WeaponJsonCacheRuntime.configure(artifact);

        Object first = WeaponJsonCacheRuntime.cached("data/weapons/example.wpn");
        Object second = WeaponJsonCacheRuntime.cached("data/weapons/example.wpn");
        Object system = WeaponJsonCacheRuntime.cached("data/shipsystems/wpn/system.wpn");
        assertEquals("{\"id\":\"example\"}", first.toString());
        assertEquals("{\"id\":\"example\"}", second.toString());
        assertEquals("{\"id\":\"system\"}", system.toString());
        assertTrue(first != second);
        assertEquals(3L, WeaponJsonCacheRuntime.telemetry().get("hits"));
    }

    @Test
    void rejectsWrongProfileAndFallsBackWhenCachedJsonCannotBeParsed() throws Exception {
        String expected = "c".repeat(64);
        Path wrong = temporaryDirectory.resolve(expected + ".spwj");
        PreparedWeaponJsonCacheIO.write(wrong, new PreparedWeaponJsonCache(
                "d".repeat(64), Map.of("data/weapons/example.wpn", "{}")));
        WeaponJsonCacheRuntime.configure(wrong);
        assertNull(WeaponJsonCacheRuntime.cached("data/weapons/example.wpn"));

        Path invalid = temporaryDirectory.resolve("e".repeat(64) + ".spwj");
        PreparedWeaponJsonCacheIO.write(invalid, new PreparedWeaponJsonCache(
                "e".repeat(64), Map.of("data/weapons/example.wpn", "INVALID")));
        WeaponJsonCacheRuntime.configure(invalid);
        assertNull(WeaponJsonCacheRuntime.cached("data/weapons/example.wpn"));
    }
}

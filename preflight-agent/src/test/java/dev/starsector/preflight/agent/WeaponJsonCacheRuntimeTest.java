package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.JsonTree;
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
                object("example"),
                "data/weapons/example.wpn");
        assertTrue(java.nio.file.Files.notExists(artifact));

        WeaponJsonCacheRuntime.complete();
        assertArrayEquals(
                tree("example"),
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
                        "data/weapons/example.wpn", tree("example"),
                        "data/shipsystems/wpn/system.wpn", tree("system"))));
        WeaponJsonCacheRuntime.configure(artifact);

        Object first = WeaponJsonCacheRuntime.cached("data/weapons/example.wpn");
        Object second = WeaponJsonCacheRuntime.cached("data/weapons/example.wpn");
        Object system = WeaponJsonCacheRuntime.cached("data/shipsystems/wpn/system.wpn");
        assertEquals("example", ((org.json.JSONObject) first).get("id"));
        assertEquals("example", ((org.json.JSONObject) second).get("id"));
        assertEquals("system", ((org.json.JSONObject) system).get("id"));
        assertTrue(first != second);
        assertEquals(3L, WeaponJsonCacheRuntime.telemetry().get("hits"));
    }

    @Test
    void rejectsWrongProfileAndFallsBackWhenCachedTreeCannotBeDecoded() throws Exception {
        String expected = "c".repeat(64);
        Path wrong = temporaryDirectory.resolve(expected + ".spwj");
        PreparedWeaponJsonCacheIO.write(wrong, new PreparedWeaponJsonCache(
                "d".repeat(64), Map.of("data/weapons/example.wpn", tree("wrong"))));
        WeaponJsonCacheRuntime.configure(wrong);
        assertNull(WeaponJsonCacheRuntime.cached("data/weapons/example.wpn"));

        Path invalid = temporaryDirectory.resolve("e".repeat(64) + ".spwj");
        PreparedWeaponJsonCacheIO.write(invalid, new PreparedWeaponJsonCache(
                "e".repeat(64), Map.of("data/weapons/example.wpn", new byte[] {127})));
        WeaponJsonCacheRuntime.configure(invalid);
        assertNull(WeaponJsonCacheRuntime.cached("data/weapons/example.wpn"));
    }

    private static org.json.JSONObject object(String id) {
        return new org.json.JSONObject().put("id", id);
    }

    private static byte[] tree(String id) {
        return JsonTree.encode(Map.of("id", id));
    }
}

package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedProjectileJsonCache;
import dev.starsector.preflight.core.PreparedProjectileJsonCacheIO;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectileJsonCacheRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void reset() {
        ProjectileJsonCacheRuntime.beginSession();
    }

    @Test
    void learnsVanillaResultsOnlyWhenTheWholeLoaderCompletes() throws Exception {
        String profile = "a".repeat(64);
        Path artifact = temporaryDirectory.resolve(profile + ".sppj");
        ProjectileJsonCacheRuntime.configure(artifact);

        assertTrue(ProjectileJsonCacheRuntime.ready());
        assertNull(ProjectileJsonCacheRuntime.cached("data/weapons/proj/example.proj"));
        ProjectileJsonCacheRuntime.capture(
                new org.json.JSONObject("{\"id\":\"example\"}"),
                "data/weapons/proj/example.proj");
        assertTrue(java.nio.file.Files.notExists(artifact));

        ProjectileJsonCacheRuntime.complete();
        assertEquals(
                "{\"id\":\"example\"}",
                PreparedProjectileJsonCacheIO.read(artifact).entries()
                        .get("data/weapons/proj/example.proj"));
        assertEquals(1L, ProjectileJsonCacheRuntime.telemetry().get("misses"));
        assertEquals(1L, ProjectileJsonCacheRuntime.telemetry().get("captures"));
        assertEquals(1L, ProjectileJsonCacheRuntime.telemetry().get("writes"));
    }

    @Test
    void reconstructsAFreshJsonObjectForBothProjectileDomains() throws Exception {
        String profile = "b".repeat(64);
        Path artifact = temporaryDirectory.resolve(profile + ".sppj");
        PreparedProjectileJsonCacheIO.write(artifact, new PreparedProjectileJsonCache(
                profile, Map.of(
                        "data/weapons/proj/example.proj", "{\"id\":\"example\"}",
                        "data/shipsystems/proj/system.proj", "{\"id\":\"system\"}")));
        ProjectileJsonCacheRuntime.configure(artifact);

        Object first = ProjectileJsonCacheRuntime.cached("data/weapons/proj/example.proj");
        Object second = ProjectileJsonCacheRuntime.cached("data/weapons/proj/example.proj");
        Object system = ProjectileJsonCacheRuntime.cached("data/shipsystems/proj/system.proj");
        assertEquals("{\"id\":\"example\"}", first.toString());
        assertEquals("{\"id\":\"example\"}", second.toString());
        assertEquals("{\"id\":\"system\"}", system.toString());
        assertTrue(first != second);
        assertEquals(3L, ProjectileJsonCacheRuntime.telemetry().get("hits"));
    }

    @Test
    void rejectsWrongProfileAndFallsBackWhenCachedJsonCannotBeParsed() throws Exception {
        String expected = "c".repeat(64);
        Path wrong = temporaryDirectory.resolve(expected + ".sppj");
        PreparedProjectileJsonCacheIO.write(wrong, new PreparedProjectileJsonCache(
                "d".repeat(64), Map.of("data/weapons/proj/example.proj", "{}")));
        ProjectileJsonCacheRuntime.configure(wrong);
        assertNull(ProjectileJsonCacheRuntime.cached("data/weapons/proj/example.proj"));

        Path invalid = temporaryDirectory.resolve("e".repeat(64) + ".sppj");
        PreparedProjectileJsonCacheIO.write(invalid, new PreparedProjectileJsonCache(
                "e".repeat(64), Map.of("data/weapons/proj/example.proj", "INVALID")));
        ProjectileJsonCacheRuntime.configure(invalid);
        assertNull(ProjectileJsonCacheRuntime.cached("data/weapons/proj/example.proj"));
    }
}

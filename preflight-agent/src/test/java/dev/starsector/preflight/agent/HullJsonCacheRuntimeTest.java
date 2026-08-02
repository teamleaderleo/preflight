package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedHullJsonCache;
import dev.starsector.preflight.core.PreparedHullJsonCacheIO;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HullJsonCacheRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void reset() {
        HullJsonCacheRuntime.beginSession();
    }

    @Test
    void learnsVanillaResultsOnlyWhenTheWholeLoaderCompletes() throws Exception {
        String profile = "a".repeat(64);
        Path artifact = temporaryDirectory.resolve(profile + ".sphj");
        HullJsonCacheRuntime.configure(artifact);

        assertTrue(HullJsonCacheRuntime.ready());
        assertNull(HullJsonCacheRuntime.cached("data/hulls/example.ship"));
        HullJsonCacheRuntime.capture(
                new org.json.JSONObject("{\"id\":\"example\"}"),
                "data/hulls/example.ship");
        assertTrue(java.nio.file.Files.notExists(artifact));

        HullJsonCacheRuntime.complete();
        assertEquals(
                "{\"id\":\"example\"}",
                PreparedHullJsonCacheIO.read(artifact).entries().get("data/hulls/example.ship"));
        assertEquals(1L, HullJsonCacheRuntime.telemetry().get("misses"));
        assertEquals(1L, HullJsonCacheRuntime.telemetry().get("captures"));
        assertEquals(1L, HullJsonCacheRuntime.telemetry().get("writes"));
    }

    @Test
    void reconstructsAFreshJsonObject() throws Exception {
        String profile = "b".repeat(64);
        Path artifact = temporaryDirectory.resolve(profile + ".sphj");
        PreparedHullJsonCacheIO.write(artifact, new PreparedHullJsonCache(
                profile, Map.of("data/hulls/example.ship", "{\"id\":\"example\"}")));
        HullJsonCacheRuntime.configure(artifact);

        Object first = HullJsonCacheRuntime.cached("data/hulls/example.ship");
        Object second = HullJsonCacheRuntime.cached("data/hulls/example.ship");
        assertEquals("{\"id\":\"example\"}", first.toString());
        assertEquals("{\"id\":\"example\"}", second.toString());
        assertTrue(first != second);
        assertEquals(2L, HullJsonCacheRuntime.telemetry().get("hits"));
        assertNull(HullJsonCacheRuntime.cached("data/hulls/example.skin"));
    }

    @Test
    void rejectsWrongProfileAndFallsBackWhenCachedJsonCannotBeParsed() throws Exception {
        String expected = "c".repeat(64);
        Path wrong = temporaryDirectory.resolve(expected + ".sphj");
        PreparedHullJsonCacheIO.write(wrong, new PreparedHullJsonCache(
                "d".repeat(64), Map.of("data/hulls/example.ship", "{}")));
        HullJsonCacheRuntime.configure(wrong);
        assertNull(HullJsonCacheRuntime.cached("data/hulls/example.ship"));

        Path invalid = temporaryDirectory.resolve("e".repeat(64) + ".sphj");
        PreparedHullJsonCacheIO.write(invalid, new PreparedHullJsonCache(
                "e".repeat(64), Map.of("data/hulls/example.ship", "INVALID")));
        HullJsonCacheRuntime.configure(invalid);
        assertNull(HullJsonCacheRuntime.cached("data/hulls/example.ship"));
    }
}

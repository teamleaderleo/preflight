package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.JsonTree;
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
                object("example"),
                "data/hulls/example.ship");
        assertTrue(java.nio.file.Files.notExists(artifact));

        HullJsonCacheRuntime.complete();
        assertArrayEquals(
                tree("example"),
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
                profile, Map.of("data/hulls/example.ship", tree("example"))));
        HullJsonCacheRuntime.configure(artifact);

        Object first = HullJsonCacheRuntime.cached("data/hulls/example.ship");
        Object second = HullJsonCacheRuntime.cached("data/hulls/example.ship");
        assertEquals("example", ((org.json.JSONObject) first).get("id"));
        assertEquals("example", ((org.json.JSONObject) second).get("id"));
        assertTrue(first != second);
        assertEquals(2L, HullJsonCacheRuntime.telemetry().get("hits"));
        assertNull(HullJsonCacheRuntime.cached("data/hulls/example.skin"));
    }

    @Test
    void rejectsWrongProfileAndFallsBackWhenCachedTreeCannotBeDecoded() throws Exception {
        String expected = "c".repeat(64);
        Path wrong = temporaryDirectory.resolve(expected + ".sphj");
        PreparedHullJsonCacheIO.write(wrong, new PreparedHullJsonCache(
                "d".repeat(64), Map.of("data/hulls/example.ship", tree("wrong"))));
        HullJsonCacheRuntime.configure(wrong);
        assertNull(HullJsonCacheRuntime.cached("data/hulls/example.ship"));

        Path invalid = temporaryDirectory.resolve("e".repeat(64) + ".sphj");
        PreparedHullJsonCacheIO.write(invalid, new PreparedHullJsonCache(
                "e".repeat(64), Map.of("data/hulls/example.ship", new byte[] {127})));
        HullJsonCacheRuntime.configure(invalid);
        assertNull(HullJsonCacheRuntime.cached("data/hulls/example.ship"));
    }

    @Test
    void abandonsLearningBeforeCapturedTreesCanExhaustMemory() {
        String profile = "f".repeat(64);
        Path artifact = temporaryDirectory.resolve(profile + ".sphj");
        HullJsonCacheRuntime.configure(artifact);
        String value = "x".repeat(1024 * 1024);

        for (int index = 0; index < 17; index++) {
            HullJsonCacheRuntime.capture(
                    new org.json.JSONObject().put("value", value),
                    "data/hulls/example-" + index + ".ship");
        }
        HullJsonCacheRuntime.complete();

        assertEquals(true, HullJsonCacheRuntime.telemetry().get("learningRejected"));
        assertEquals(0L, HullJsonCacheRuntime.telemetry().get("learnedBytes"));
        assertTrue(java.nio.file.Files.notExists(artifact));
    }

    private static org.json.JSONObject object(String id) {
        return new org.json.JSONObject().put("id", id);
    }

    private static byte[] tree(String id) {
        return JsonTree.encode(Map.of("id", id));
    }
}

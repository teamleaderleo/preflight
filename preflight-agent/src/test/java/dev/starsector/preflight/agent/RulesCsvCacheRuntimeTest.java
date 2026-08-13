package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.JsonTree;
import dev.starsector.preflight.core.PreparedRulesCsvCache;
import dev.starsector.preflight.core.PreparedRulesCsvCacheIO;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulesCsvCacheRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void reset() {
        RulesCsvCacheRuntime.beginSession();
    }

    @Test
    void learnsVanillaResultOnlyWhenTheWholeLoaderCompletes() throws Exception {
        String profile = "a".repeat(64);
        Path artifact = temporaryDirectory.resolve(profile + ".sprc");
        RulesCsvCacheRuntime.configure(artifact);

        assertTrue(RulesCsvCacheRuntime.ready());
        assertNull(RulesCsvCacheRuntime.cached());
        RulesCsvCacheRuntime.capture(new org.json.JSONArray()
                .put(new org.json.JSONObject().put("id", "example")));
        assertTrue(java.nio.file.Files.notExists(artifact));

        RulesCsvCacheRuntime.complete();
        assertEquals(1L, RulesCsvCacheRuntime.telemetry().get("misses"));
        assertEquals(1L, RulesCsvCacheRuntime.telemetry().get("captures"));
        assertEquals(1L, RulesCsvCacheRuntime.telemetry().get("writes"));
        RulesCsvCacheRuntime.beginSession();
        RulesCsvCacheRuntime.configure(artifact);
        assertExampleArray(RulesCsvCacheRuntime.cached());
    }

    @Test
    void reconstructsAFreshJsonArray() throws Exception {
        String profile = "b".repeat(64);
        Path artifact = temporaryDirectory.resolve(profile + ".sprc");
        PreparedRulesCsvCacheIO.write(artifact, new PreparedRulesCsvCache(
                profile, rulesTree("example")));
        RulesCsvCacheRuntime.configure(artifact);

        Object first = RulesCsvCacheRuntime.cached();
        Object second = RulesCsvCacheRuntime.cached();
        assertExampleArray(first);
        assertExampleArray(second);
        assertTrue(first != second);
        assertEquals(2L, RulesCsvCacheRuntime.telemetry().get("hits"));
    }

    @Test
    void rejectsWrongProfileAndFallsBackWhenCachedJsonCannotBeParsed() throws Exception {
        String expected = "c".repeat(64);
        Path wrong = temporaryDirectory.resolve(expected + ".sprc");
        PreparedRulesCsvCacheIO.write(wrong,
                new PreparedRulesCsvCache("d".repeat(64), rulesTree()));
        RulesCsvCacheRuntime.configure(wrong);
        assertNull(RulesCsvCacheRuntime.cached());

        Path invalid = temporaryDirectory.resolve("e".repeat(64) + ".sprc");
        PreparedRulesCsvCacheIO.write(invalid, new PreparedRulesCsvCache(
                "e".repeat(64), new byte[] {(byte) 0xff}));
        RulesCsvCacheRuntime.configure(invalid);
        assertNull(RulesCsvCacheRuntime.cached());

        Path wrongShape = temporaryDirectory.resolve("f".repeat(64) + ".sprc");
        PreparedRulesCsvCacheIO.write(wrongShape, new PreparedRulesCsvCache(
                "f".repeat(64), JsonTree.encode(Map.of("id", "not-an-array"))));
        RulesCsvCacheRuntime.configure(wrongShape);
        assertNull(RulesCsvCacheRuntime.cached());
    }

    private static byte[] rulesTree(String... ids) {
        return JsonTree.encode(java.util.Arrays.stream(ids)
                .map(id -> Map.<String, Object>of("id", id)).toList());
    }

    private static void assertExampleArray(Object value) {
        org.json.JSONArray array = (org.json.JSONArray) value;
        assertEquals(1, array.length());
        assertEquals("example", ((org.json.JSONObject) array.get(0)).get("id"));
    }
}

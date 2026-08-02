package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedRulesCsvCache;
import dev.starsector.preflight.core.PreparedRulesCsvCacheIO;
import java.nio.file.Path;
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
        RulesCsvCacheRuntime.capture(new org.json.JSONArray("[{\"id\":\"example\"}]"));
        assertTrue(java.nio.file.Files.notExists(artifact));

        RulesCsvCacheRuntime.complete();
        assertEquals("[{\"id\":\"example\"}]", PreparedRulesCsvCacheIO.read(artifact).mergedJson());
        assertEquals(1L, RulesCsvCacheRuntime.telemetry().get("misses"));
        assertEquals(1L, RulesCsvCacheRuntime.telemetry().get("captures"));
        assertEquals(1L, RulesCsvCacheRuntime.telemetry().get("writes"));
    }

    @Test
    void reconstructsAFreshJsonArray() throws Exception {
        String profile = "b".repeat(64);
        Path artifact = temporaryDirectory.resolve(profile + ".sprc");
        PreparedRulesCsvCacheIO.write(artifact, new PreparedRulesCsvCache(
                profile, "[{\"id\":\"example\"}]"));
        RulesCsvCacheRuntime.configure(artifact);

        Object first = RulesCsvCacheRuntime.cached();
        Object second = RulesCsvCacheRuntime.cached();
        assertEquals("[{\"id\":\"example\"}]", first.toString());
        assertEquals(first.toString(), second.toString());
        assertTrue(first != second);
        assertEquals(2L, RulesCsvCacheRuntime.telemetry().get("hits"));
    }

    @Test
    void rejectsWrongProfileAndFallsBackWhenCachedJsonCannotBeParsed() throws Exception {
        String expected = "c".repeat(64);
        Path wrong = temporaryDirectory.resolve(expected + ".sprc");
        PreparedRulesCsvCacheIO.write(wrong, new PreparedRulesCsvCache("d".repeat(64), "[]"));
        RulesCsvCacheRuntime.configure(wrong);
        assertNull(RulesCsvCacheRuntime.cached());

        Path invalid = temporaryDirectory.resolve("e".repeat(64) + ".sprc");
        PreparedRulesCsvCacheIO.write(invalid, new PreparedRulesCsvCache(
                "e".repeat(64), "[INVALID]"));
        RulesCsvCacheRuntime.configure(invalid);
        assertNull(RulesCsvCacheRuntime.cached());
    }
}

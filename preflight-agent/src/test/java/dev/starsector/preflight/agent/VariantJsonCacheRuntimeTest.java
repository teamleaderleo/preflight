package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedVariantJsonCache;
import dev.starsector.preflight.core.PreparedVariantJsonCacheIO;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VariantJsonCacheRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void reset() {
        VariantJsonCacheRuntime.beginSession();
    }

    @Test
    void learnsVanillaResultsOnlyWhenTheLoaderCompletes() throws Exception {
        String profile = "a".repeat(64);
        Path artifact = temporaryDirectory.resolve(profile + ".spvj");
        VariantJsonCacheRuntime.configure(artifact);

        assertTrue(VariantJsonCacheRuntime.ready());
        assertNull(VariantJsonCacheRuntime.cached("data/variants/example.variant"));
        VariantJsonCacheRuntime.capture(
                new org.json.JSONObject("{\"variantId\":\"example\"}"),
                "data/variants/example.variant");
        assertTrue(java.nio.file.Files.notExists(artifact));

        VariantJsonCacheRuntime.complete();
        assertEquals(
                "{\"variantId\":\"example\"}",
                PreparedVariantJsonCacheIO.read(artifact).entries()
                        .get("data/variants/example.variant"));
        assertEquals(1L, VariantJsonCacheRuntime.telemetry().get("misses"));
        assertEquals(1L, VariantJsonCacheRuntime.telemetry().get("captures"));
        assertEquals(1L, VariantJsonCacheRuntime.telemetry().get("writes"));
    }

    @Test
    void reconstructsAFreshJsonObjectOnEveryExactProfileHit() throws Exception {
        String profile = "b".repeat(64);
        Path artifact = temporaryDirectory.resolve(profile + ".spvj");
        PreparedVariantJsonCacheIO.write(artifact, new PreparedVariantJsonCache(
                profile, Map.of("data/variants/example.variant", "{\"variantId\":\"example\"}")));
        VariantJsonCacheRuntime.configure(artifact);

        Object first = VariantJsonCacheRuntime.cached("data/variants/example.variant");
        Object second = VariantJsonCacheRuntime.cached("data/variants/example.variant");
        assertEquals("{\"variantId\":\"example\"}", first.toString());
        assertEquals("{\"variantId\":\"example\"}", second.toString());
        assertTrue(first != second);
        assertEquals(2L, VariantJsonCacheRuntime.telemetry().get("hits"));
    }

    @Test
    void rejectsWrongProfileAndFallsBackWhenCachedJsonCannotBeParsed() throws Exception {
        String expected = "c".repeat(64);
        Path wrong = temporaryDirectory.resolve(expected + ".spvj");
        PreparedVariantJsonCacheIO.write(wrong, new PreparedVariantJsonCache(
                "d".repeat(64), Map.of("data/variants/example.variant", "{}")));
        VariantJsonCacheRuntime.configure(wrong);
        assertNull(VariantJsonCacheRuntime.cached("data/variants/example.variant"));

        Path invalid = temporaryDirectory.resolve("e".repeat(64) + ".spvj");
        PreparedVariantJsonCacheIO.write(invalid, new PreparedVariantJsonCache(
                "e".repeat(64), Map.of("data/variants/example.variant", "INVALID")));
        VariantJsonCacheRuntime.configure(invalid);
        assertNull(VariantJsonCacheRuntime.cached("data/variants/example.variant"));
    }
}

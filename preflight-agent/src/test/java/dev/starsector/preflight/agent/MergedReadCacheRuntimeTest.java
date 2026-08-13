package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedMergedReadCache;
import dev.starsector.preflight.core.PreparedMergedReadCacheIO;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergedReadCacheRuntimeTest {
    private static final AtomicInteger VANILLA_CALLS = new AtomicInteger();
    private static final MethodHandle JSON_VANILLA = handle(
            "vanillaJson", MethodType.methodType(Object.class, String.class, Object.class));
    private static final MethodHandle UNSTORABLE_VANILLA = handle(
            "unstorableJson", MethodType.methodType(Object.class, String.class, Object.class));
    private static final MethodHandle SINGLE_VANILLA = handle(
            "singleVanillaJson", MethodType.methodType(Object.class, String.class));

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void reset() {
        VANILLA_CALLS.set(0);
        MergedReadCacheRuntime.beginSession();
        LoadJsonMemoRuntime.reset();
        LoadJsonMemoRuntime.enable(true);
    }

    @AfterEach
    void disableMemo() {
        LoadJsonMemoRuntime.enable(false);
        LoadJsonMemoRuntime.reset();
    }

    @Test
    void learnsPublishesAndServesAFreshEquivalentGameObject() throws Throwable {
        Path artifact = artifact('a');
        MergedReadCacheRuntime.configure(artifact);

        JSONObject learned = (JSONObject) MergedReadCacheRuntime.mergedJsonRead(
                "data/config/engine_styles.json", Set.of("z", "a"), JSON_VANILLA);
        assertEquals(1, VANILLA_CALLS.get());
        assertSame(JSONObject.NULL, learned.get("presentNull"));
        assertEquals(1L, telemetry("captures"));
        assertEquals(1L, telemetry("misses"));

        MergedReadCacheRuntime.complete();
        assertTrue(Files.isRegularFile(artifact));
        PreparedMergedReadCache stored = PreparedMergedReadCacheIO.read(artifact);
        assertEquals(1, stored.entries().size());
        assertEquals(1L, telemetry("writes"));

        MergedReadCacheRuntime.beginSession();
        MergedReadCacheRuntime.configure(artifact);
        JSONObject restored = (JSONObject) MergedReadCacheRuntime.mergedJsonRead(
                "data/config/engine_styles.json", Set.of("a", "z"), JSON_VANILLA);

        assertEquals(1, VANILLA_CALLS.get(), "a warm hit must not enter vanilla");
        assertTrue(restored != learned, "each hit must return a fresh mutable container");
        assertTrue(restored.has("presentNull"));
        assertSame(JSONObject.NULL, restored.get("presentNull"));
        assertEquals("data/config/engine_styles.json", restored.get("path"));
        JSONArray nested = (JSONArray) restored.get("nested");
        assertEquals(2, nested.length());
        assertEquals(1L, telemetry("hits"));
        assertEquals(1, telemetry("preparedEntries"));
    }

    @Test
    void refusesAnAbsoluteKeyClaimedByTwoDifferentValues() throws Throwable {
        Path artifact = artifact('b');
        MergedReadCacheRuntime.configure(artifact);

        MergedReadCacheRuntime.mergedJsonRead(
                "/mods/one/data/config/settings.json", Set.of(), JSON_VANILLA);
        MergedReadCacheRuntime.mergedJsonRead(
                "/mods/two/data/config/settings.json", Set.of(), JSON_VANILLA);

        assertEquals(2, VANILLA_CALLS.get());
        assertEquals(1L, telemetry("keyCollisions"));
        MergedReadCacheRuntime.complete();
        assertFalse(Files.exists(artifact), "a collided key must never be published");
    }

    @Test
    void fallsBackWhenAValueCannotBeRepresentedExactly() throws Throwable {
        Path artifact = artifact('c');
        MergedReadCacheRuntime.configure(artifact);

        JSONObject result = (JSONObject) MergedReadCacheRuntime.mergedJsonRead(
                "data/config/engine_styles.json", Set.of(), UNSTORABLE_VANILLA);

        assertEquals(BigDecimal.ONE, result.get("decimal"));
        assertEquals(1, VANILLA_CALLS.get());
        assertEquals(1L, telemetry("unstorableReads"));
        MergedReadCacheRuntime.complete();
        assertFalse(Files.exists(artifact));
    }

    @Test
    void skipsNewDedicatedSpecCopiesAndPrunesOnesFromAnOlderArtifact() throws Throwable {
        Path fresh = artifact('d');
        MergedReadCacheRuntime.configure(fresh);
        MergedReadCacheRuntime.mergedJsonRead(
                "data/variants/example.variant", Set.of(), JSON_VANILLA);
        MergedReadCacheRuntime.complete();
        assertFalse(Files.exists(fresh));
        assertEquals(1L, telemetry("dedicatedSpecCapturesSkipped"));

        Path polluted = artifact('e');
        String generalKey = dev.starsector.preflight.core.MergedReadKey.json(
                "data/config/engine_styles.json", java.util.List.of());
        String specKey = dev.starsector.preflight.core.MergedReadKey.json(
                "data/variants/example.variant", java.util.List.of());
        GameJson bridge = GameJson.bridge();
        PreparedMergedReadCacheIO.write(polluted, new PreparedMergedReadCache(
                "e".repeat(64), Map.of(
                        generalKey, bridge.encode(new JSONObject().put("kind", "general")),
                        specKey, bridge.encode(new JSONObject().put("kind", "spec")))));

        MergedReadCacheRuntime.beginSession();
        MergedReadCacheRuntime.configure(polluted);
        assertEquals(1, telemetry("dedicatedSpecEntriesPruned"));
        MergedReadCacheRuntime.complete();

        PreparedMergedReadCache cleaned = PreparedMergedReadCacheIO.read(polluted);
        assertEquals(Set.of(generalKey), cleaned.entries().keySet());
        assertEquals(1L, telemetry("writes"));
    }

    @Test
    void publishesPostStartupSingleJsonAtShutdownAndServesItNextProcess() throws Throwable {
        Path artifact = artifact('f');
        MergedReadCacheRuntime.configure(artifact);

        MergedReadCacheRuntime.mergedJsonRead(
                "data/config/engine_styles.json", Set.of(), JSON_VANILLA);
        MergedReadCacheRuntime.complete();
        assertEquals(1L, telemetry("writes"));

        LoadJsonMemoRuntime.markStartupComplete();
        JSONObject first = (JSONObject) LoadJsonMemoRuntime.loadJsonResolved(
                "data/config/chatter/characters/default.json", SINGLE_VANILLA);
        assertEquals("data/config/chatter/characters/default.json", first.get("path"));
        assertEquals(2, VANILLA_CALLS.get());
        MergedReadCacheRuntime.complete();
        MergedReadCacheRuntime.complete();
        assertEquals(2L, telemetry("writes"), "an unchanged shutdown publication is a no-op");
        assertEquals(2, PreparedMergedReadCacheIO.read(artifact).entries().size());

        MergedReadCacheRuntime.beginSession();
        LoadJsonMemoRuntime.reset();
        LoadJsonMemoRuntime.enable(true);
        MergedReadCacheRuntime.configure(artifact);
        LoadJsonMemoRuntime.markStartupComplete();
        JSONObject restored = (JSONObject) LoadJsonMemoRuntime.loadJsonResolved(
                "data/config/chatter/characters/default.json", SINGLE_VANILLA);

        assertEquals(2, VANILLA_CALLS.get(), "a prepared single-file hit must not enter vanilla");
        assertEquals(first.get("path"), restored.get("path"));
        assertEquals(((JSONArray) first.get("lines")).toString(),
                ((JSONArray) restored.get("lines")).toString());
        assertTrue(first != restored);
        assertEquals(1L, telemetry("singleJsonHits"));
        assertEquals(1L, LoadJsonMemoRuntime.report().get("preparedHits"));
    }

    private Path artifact(char digit) {
        return temporaryDirectory.resolve(String.valueOf(digit).repeat(64) + ".spmr");
    }

    private static Object telemetry(String key) {
        return MergedReadCacheRuntime.telemetry().get(key);
    }

    private static Object vanillaJson(String path, Object ignored) {
        VANILLA_CALLS.incrementAndGet();
        return new JSONObject()
                .put("path", path)
                .put("presentNull", JSONObject.NULL)
                .put("nested", new JSONArray().put(true).put(7L));
    }

    private static Object unstorableJson(String ignoredPath, Object ignoredKeys) {
        VANILLA_CALLS.incrementAndGet();
        return new JSONObject().put("decimal", BigDecimal.ONE);
    }

    private static Object singleVanillaJson(String path) {
        VANILLA_CALLS.incrementAndGet();
        return new JSONObject().put("path", path).put("lines", new JSONArray().put("hello"));
    }

    private static MethodHandle handle(String name, MethodType type) {
        try {
            return MethodHandles.lookup().findStatic(MergedReadCacheRuntimeTest.class, name, type);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}

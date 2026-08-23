package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.GeneratedBytecodeCache;
import dev.starsector.preflight.core.GeneratedBytecodeContext;
import dev.starsector.preflight.core.GeneratedBytecodePack;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JaninoBytecodeCachePlanTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        JaninoBytecodeCacheRuntime.beginSession();
    }

    @Test
    void wrapsTheCompleteMapAndDeclinesASecondRewrite() throws Exception {
        byte[] original = fixtureBytes();
        ClassSignature signature = ClassSignature.parse(original);
        byte[] transformed = JaninoBytecodeCachePlan.transform(signature, original);

        assertNotNull(transformed);
        ClassSignature result = ClassSignature.parse(transformed);
        assertTrue(result.hasMethod(
                JaninoBytecodeCachePlan.GENERATE_METHOD,
                JaninoBytecodeCachePlan.GENERATE_DESCRIPTOR));
        assertTrue(result.hasMethod(
                JaninoBytecodeCachePlan.ORIGINAL_METHOD,
                JaninoBytecodeCachePlan.GENERATE_DESCRIPTOR));
        assertNull(JaninoBytecodeCachePlan.transform(result, transformed));
    }

    @Test
    void storesHitsAndRecoversFromCorruptionWithoutChangingTheReturnedMapShape() throws Exception {
        GeneratedBytecodeContext context = context();
        JaninoBytecodeCacheRuntime.configure(temporaryDirectory, context.portableToken());
        Object loader = transformedLoader();
        Method compile = loader.getClass().getMethod("compile", String.class);

        Map<?, ?> first = (Map<?, ?>) compile.invoke(loader, "scripts.Example");
        assertEquals(1, first.size());
        assertEquals(1, loader.getClass().getField("originalCalls").getInt(loader));
        assertEquals(1L, JaninoBytecodeCacheRuntime.telemetry().get("stored"));

        Map<?, ?> hit = (Map<?, ?>) compile.invoke(loader, "scripts.Example");
        assertEquals(first.keySet(), hit.keySet());
        assertEquals(1, loader.getClass().getField("originalCalls").getInt(loader));
        assertEquals(1L, JaninoBytecodeCacheRuntime.telemetry().get("hits"));
        assertFalse(hit.getClass().getName().contains("Immutable"), "Janino removes entries from this map");
        Map<String, Object> telemetry = JaninoBytecodeCacheRuntime.telemetry();
        long insideMillis = (Long) telemetry.get("insideMillis");
        assertTrue(insideMillis >= (Long) telemetry.get("hitInsideMillis"));
        assertTrue(insideMillis >= (Long) telemetry.get("originalInsideMillis"));
        assertEquals(0L, telemetry.get("livePolicyDeclinedInsideMillis"));

        Path bundle = GeneratedBytecodeCache.bundlePath(
                temporaryDirectory, context.keySha256(), "scripts.Example");
        Files.writeString(bundle, "corrupt");
        compile.invoke(loader, "scripts.Example");
        assertEquals(2, loader.getClass().getField("originalCalls").getInt(loader));
        assertEquals(1L, JaninoBytecodeCacheRuntime.telemetry().get("corrupt"));
        assertEquals(2L, JaninoBytecodeCacheRuntime.telemetry().get("stored"));
    }

    @Test
    void aLiveCompilerPolicyDifferenceAlwaysUsesTheOriginal() throws Exception {
        GeneratedBytecodeContext context = context();
        JaninoBytecodeCacheRuntime.configure(temporaryDirectory, context.portableToken());
        Object loader = transformedLoader();
        loader.getClass().getMethod(
                "setDebuggingInfo", boolean.class, boolean.class, boolean.class)
                .invoke(loader, true, false, true);
        Method compile = loader.getClass().getMethod("compile", String.class);

        compile.invoke(loader, "scripts.Example");
        compile.invoke(loader, "scripts.Example");

        assertEquals(2, loader.getClass().getField("originalCalls").getInt(loader));
        assertEquals(2L, JaninoBytecodeCacheRuntime.telemetry().get("livePolicyDeclined"));
        assertFalse(Files.exists(GeneratedBytecodeCache.bundlePath(
                temporaryDirectory, context.keySha256(), "scripts.Example")));
    }

    @Test
    void publishesAndThenServesOneDeduplicatedSessionPack() throws Exception {
        GeneratedBytecodeContext context = context();
        JaninoBytecodeCacheRuntime.configure(temporaryDirectory, context.portableToken());
        Object populationLoader = transformedLoader();
        Method populationCompile = populationLoader.getClass().getMethod("compile", String.class);

        Map<?, ?> populated = (Map<?, ?>) populationCompile.invoke(populationLoader, "scripts.Example");
        Path separateBundle = GeneratedBytecodeCache.bundlePath(
                temporaryDirectory, context.keySha256(), "scripts.Example");
        assertTrue(Files.isRegularFile(separateBundle));
        JaninoBytecodeCacheRuntime.complete();
        Path packPath = GeneratedBytecodePack.path(temporaryDirectory, context.keySha256());
        assertTrue(Files.isRegularFile(packPath));
        assertEquals(1, GeneratedBytecodePack.read(packPath).requestCount());
        assertFalse(Files.exists(separateBundle));
        assertEquals(1L, JaninoBytecodeCacheRuntime.telemetry().get("packReleasedBundles"));

        Map<?, ?> sameSessionHit = (Map<?, ?>) populationCompile.invoke(
                populationLoader, "scripts.Example");
        assertEquals(populated.keySet(), sameSessionHit.keySet());
        assertEquals(1, populationLoader.getClass().getField("originalCalls").getInt(populationLoader));

        JaninoBytecodeCacheRuntime.beginSession();
        JaninoBytecodeCacheRuntime.configure(temporaryDirectory, context.portableToken());
        Object packedLoader = transformedLoader();
        Map<?, ?> packed = (Map<?, ?>) packedLoader.getClass()
                .getMethod("compile", String.class).invoke(packedLoader, "scripts.Example");

        assertEquals(populated.keySet(), packed.keySet());
        assertEquals(0, packedLoader.getClass().getField("originalCalls").getInt(packedLoader));
        assertEquals(1L, JaninoBytecodeCacheRuntime.telemetry().get("packHits"));
        assertEquals("hit", JaninoBytecodeCacheRuntime.telemetry().get("packStatus"));
    }

    @Test
    void aSubclassDeclinesTheCacheButStillFindsTheRenamedSuperclassOriginal() throws Exception {
        GeneratedBytecodeContext context = context();
        JaninoBytecodeCacheRuntime.configure(temporaryDirectory, context.portableToken());
        Map<String, byte[]> generated = JaninoBytecodeCacheRuntime.generate(
                new RenamedOriginalSubclass(), "scripts.Unrelated");
        assertEquals(1, generated.get("scripts.Unrelated")[0]);
        assertEquals(1L, JaninoBytecodeCacheRuntime.telemetry().get("livePolicyDeclined"));
    }

    @Test
    void targetBindsTheExactJaninoArchiveAndRejectsFastRenderingsLoader() {
        AdapterTarget target = AdapterTargetRegistry.janinoBytecodeCacheTarget();
        List<ClassSignature.Method> methods = target.requiredMethods().stream()
                .map(method -> new ClassSignature.Method(method.name(), method.descriptor(), 0))
                .toList();
        ClassSignature signature = new ClassSignature(
                target.internalClassName(), target.sha256(), 50, 0, methods);
        AdapterSourceIdentity vanilla = new AdapterSourceIdentity(
                "file:/game/Contents/Resources/Java/janino.jar",
                "/game/Contents/Resources/Java/janino.jar",
                "STARSECTOR_CORE", target.sourceSha256(), "",
                "jdk/internal/loader/ClassLoaders$AppClassLoader", "app");
        assertTrue(target.match(signature, vanilla).exact());

        AdapterSourceIdentity fastRendering = new AdapterSourceIdentity(
                vanilla.codeSource(), vanilla.normalizedSource(), vanilla.sourceKind(),
                vanilla.sourceSha256(), "", "com/genir/renderer/loaders/AppClassLoader", "");
        assertFalse(target.match(signature, fastRendering).exact());
    }

    private Object transformedLoader() throws Exception {
        byte[] original = fixtureBytes();
        byte[] transformed = JaninoBytecodeCachePlan.transform(
                ClassSignature.parse(original), original);
        ClassLoader child = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals("org.codehaus.janino.JavaSourceClassLoader")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = defineClass(name, transformed, 0, transformed.length);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        };
        return child.loadClass("org.codehaus.janino.JavaSourceClassLoader")
                .getConstructor().newInstance();
    }

    private byte[] fixtureBytes() throws Exception {
        String resource = "/org/codehaus/janino/JavaSourceClassLoader.class";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    private static GeneratedBytecodeContext context() {
        return new GeneratedBytecodeContext(
                "01".repeat(32), "02".repeat(32), "03".repeat(32), "04".repeat(32),
                "05".repeat(32), "06".repeat(32), "07".repeat(32));
    }

    private static class RenamedOriginalBase {
        @SuppressWarnings("unused")
        protected Map<String, byte[]> preflightGenerateBytecodesOriginal(String name) {
            return Map.of(name, new byte[] {1});
        }
    }

    private static final class RenamedOriginalSubclass extends RenamedOriginalBase {
    }
}

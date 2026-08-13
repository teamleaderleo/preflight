package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GraphicsLibCompactReplayPlanTest {
    /**
     * States the ambient condition this class pins a hash against.
     *
     * <p>{@code transform} returns the instrumented class instead of the reviewed one whenever the
     * startup phase probe has a destination, and that destination is global to the JVM surefire runs
     * every class in. Asserting an exact SHA-256 while inheriting whatever ran first makes the result
     * depend on run order, which is the filesystem's by default and so differs per platform.
     */
    @BeforeEach
    void withoutThePhaseProbe() {
        StartupPhaseRuntime.beginSession(null);
    }

    @AfterEach
    void reset() {
        GraphicsLibCompactReplayPlan.beginSession();
    }

    @Test
    void embeddedReplacementIsExactSelfContainedJava17Class() throws Exception {
        GraphicsLibCompactReplayPlan.configure(true, null);
        assertTrue(GraphicsLibCompactReplayPlan.ready(), GraphicsLibCompactReplayPlan.status());

        byte[] replacement = GraphicsLibCompactReplayPlan.transform(originalSignature());
        assertNotNull(replacement);
        ClassSignature signature = ClassSignature.parse(replacement);
        assertEquals(GraphicsLibCompactReplayPlan.TARGET_CLASS, signature.internalName());
        assertEquals(GraphicsLibCompactReplayPlan.REPLACEMENT_SHA256, signature.sha256());
        assertEquals(61, signature.majorVersion());
        assertTrue(signature.hasMethod("autoGenMissingNormalMaps", "()V"));
        assertTrue(signature.hasMethod("autoGenMissingNormalMapsInner", "(Z)V"));
        assertFalse(new String(replacement, StandardCharsets.ISO_8859_1)
                .contains("org/dark/shaders/util/TextureData$AutoGenRequest"));

        byte[] second = GraphicsLibCompactReplayPlan.transform(originalSignature());
        assertArrayEquals(replacement, second);
        replacement[0] = 0;
        assertEquals(0xCA, Byte.toUnsignedInt(second[0]));
        assertEquals(2L, GraphicsLibCompactReplayPlan.telemetry().get("applications"));
    }

    @Test
    void disabledChangedAndWrongVersionTargetsKeepOriginalBytes() {
        ClassSignature exact = originalSignature();
        assertNull(GraphicsLibCompactReplayPlan.transform(exact));

        GraphicsLibCompactReplayPlan.configure(true, null);
        assertNull(GraphicsLibCompactReplayPlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), 61, exact.access(), exact.methods())));
        assertNull(GraphicsLibCompactReplayPlan.transform(new ClassSignature(
                "other/TextureData", exact.sha256(), 61, exact.access(), exact.methods())));
        assertNull(GraphicsLibCompactReplayPlan.transform(new ClassSignature(
                exact.internalName(), exact.sha256(), 65, exact.access(), exact.methods())));
        assertEquals(0L, GraphicsLibCompactReplayPlan.telemetry().get("applications"));
    }

    @Test
    void targetBindsExactModArchiveAndPortableModLoader() {
        AdapterTarget target = AdapterTargetRegistry.graphicsLibCompactReplayTarget();
        AdapterSourceIdentity exactSource = new AdapterSourceIdentity(
                "file:/game/mods/GraphicsLib/jars/Graphics.jar",
                "C:/game/mods/GraphicsLib/jars/Graphics.jar",
                "MOD",
                target.sourceSha256(),
                "",
                "java/net/URLClassLoader",
                "");
        assertTrue(target.match(originalSignature(), exactSource).exact());
        assertTrue(target.hasLiveSourceBinding());

        AdapterSourceIdentity changedArchive = new AdapterSourceIdentity(
                exactSource.codeSource(), exactSource.normalizedSource(), "MOD", "f".repeat(64), "",
                exactSource.loaderClass(), "");
        assertFalse(target.match(originalSignature(), changedArchive).exact());

        AdapterSourceIdentity foreignOwner = new AdapterSourceIdentity(
                "file:/game/fr.jar", "C:/game/fr.jar", "FAST_RENDERING", target.sourceSha256(), "",
                "java/net/URLClassLoader", "");
        assertFalse(target.match(originalSignature(), foreignOwner).exact());
        assertTrue(target.shadowed(originalSignature(), foreignOwner));
    }

    private static ClassSignature originalSignature() {
        AdapterTarget target = AdapterTargetRegistry.graphicsLibCompactReplayTarget();
        List<ClassSignature.Method> methods = target.requiredMethods().stream()
                .map(method -> new ClassSignature.Method(method.name(), method.descriptor(), 0))
                .toList();
        return new ClassSignature(
                GraphicsLibCompactReplayPlan.TARGET_CLASS,
                GraphicsLibCompactReplayPlan.ORIGINAL_SHA256,
                61,
                0,
                methods);
    }
}

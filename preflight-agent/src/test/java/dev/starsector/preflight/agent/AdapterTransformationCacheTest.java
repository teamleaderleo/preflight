package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class AdapterTransformationCacheTest {
    private static final String CLASS_NAME = "fixture/CachedAdapterTarget";

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void reset() {
        FrameTimeRuntime.beginSession(false);
        AdapterTransformationCache.beginSession();
        SourceHintIsolationRuntime.reset();
    }

    @Test
    void writesAndRestoresAnExactTransformedClass() throws Exception {
        byte[] original = fixture(false);
        byte[] transformed = fixture(true);
        ClassSignature signature = ClassSignature.parse(original);
        AdapterTarget target = target(signature);
        AgentOptions options = AgentOptions.parse("adapter=enabled");

        AdapterTransformationCache.configure(
                temporaryDirectory, options, AdapterTargetRegistry.empty(), new Properties(), identity('a'));
        assertNull(AdapterTransformationCache.lookup(target, signature));
        AdapterTransformationCache.record(target, signature, transformed);
        AdapterTransformationCache.complete();
        assertEquals(1L, AdapterTransformationCache.telemetry().get("records"));

        AdapterTransformationCache.beginSession();
        AdapterTransformationCache.configure(
                temporaryDirectory, options, AdapterTargetRegistry.empty(), new Properties(), identity('a'));
        assertArrayEquals(transformed, AdapterTransformationCache.lookup(target, signature));
        Map<String, Object> telemetry = AdapterTransformationCache.telemetry();
        assertEquals(1L, telemetry.get("hits"));
        assertEquals(1, telemetry.get("loadedClasses"));
    }

    @Test
    void implementationIdentitySelectsADifferentPack() throws Exception {
        byte[] original = fixture(false);
        byte[] transformed = fixture(true);
        ClassSignature signature = ClassSignature.parse(original);
        AdapterTarget target = target(signature);
        AgentOptions options = AgentOptions.parse("adapter=enabled");

        AdapterTransformationCache.configure(
                temporaryDirectory, options, AdapterTargetRegistry.empty(), new Properties(), identity('a'));
        AdapterTransformationCache.record(target, signature, transformed);
        AdapterTransformationCache.complete();

        AdapterTransformationCache.beginSession();
        AdapterTransformationCache.configure(
                temporaryDirectory, options, AdapterTargetRegistry.empty(), new Properties(), identity('b'));
        assertNull(AdapterTransformationCache.lookup(target, signature));
        assertEquals(1L, AdapterTransformationCache.telemetry().get("misses"));
    }

    @Test
    void cachedBytecodeReplaysItsRuntimeInstallationEffect() {
        assertEquals(false, SourceHintIsolationRuntime.telemetry().get("installed"));
        byte[] original = fixture(false);
        try {
            ClassSignature signature = ClassSignature.parse(original);
            AdapterInstallationEffects.replay(target(signature), signature, fixture(true));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        assertEquals(true, SourceHintIsolationRuntime.telemetry().get("installed"));
    }

    @Test
    void contextIncludesEffectivePreflightProperties() {
        AgentOptions options = AgentOptions.parse("adapter=enabled");
        Properties first = new Properties();
        Properties second = new Properties();
        first.setProperty("preflight.padding.unpadded", "false");
        second.setProperty("preflight.padding.unpadded", "true");
        String left = AdapterTransformationCache.contextKey(
                identity('a'), options, AdapterTargetRegistry.empty(), first);
        String right = AdapterTransformationCache.contextKey(
                identity('a'), options, AdapterTargetRegistry.empty(), second);
        assertTrue(!left.equals(right));
    }

    private static AdapterTarget target(ClassSignature signature) {
        return new AdapterTarget(
                "fixture-cache-target",
                CLASS_NAME,
                signature.sha256(),
                SourceHintIsolationRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod("run", "()V")),
                "MOD",
                "fixture.jar",
                identity('c'),
                "java/net/URLClassLoader",
                "");
    }

    private static byte[] fixture(boolean transformed) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CLASS_NAME, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor run = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        run.visitCode();
        if (transformed) {
            run.visitInsn(Opcodes.ACONST_NULL);
            run.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "dev/starsector/preflight/agent/SourceHintIsolationRuntime",
                    "set",
                    "(Ljava/lang/String;)V",
                    false);
        }
        run.visitInsn(Opcodes.RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String identity(char value) {
        return String.valueOf(value).repeat(64);
    }
}

package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class FrameTimeStartupCompletionPlanTest {
    private static final String RUNTIME = FrameTimeRuntime.class.getName().replace('.', '/');
    private static final String JSON_RUNTIME = LoadJsonMemoRuntime.class.getName().replace('.', '/');

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        FrameTimeRuntime.reset();
        LoadJsonMemoRuntime.enable(false);
        LoadJsonMemoRuntime.reset();
        RuntimeSemanticState.reset();
    }

    @Test
    void installsForSemanticAutomationWithoutTheOtherConsumers() throws Exception {
        FrameTimeRuntime.beginSession(false);
        LoadJsonMemoRuntime.enable(false);
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        byte[] original = fixture(1);

        byte[] transformed = FrameTimeStartupCompletionPlan.transform(
                exactSignature(original), original);

        assertEquals(1, calls(method(transformed), RUNTIME, "markStartupComplete", "()V"));
    }

    @Test
    void insertsOneCompletionMarkerBeforeTheUniqueReturn() throws Exception {
        FrameTimeRuntime.beginSession(true);
        byte[] original = fixture(1);
        byte[] transformed = FrameTimeStartupCompletionPlan.transform(
                exactSignature(original), original);

        MethodNode init = method(transformed);
        assertEquals(1, calls(init, RUNTIME, "markStartupComplete", "()V"));
        assertEquals(1, calls(init, JSON_RUNTIME, "markProfileStable", "()V"));
        assertNull(FrameTimeStartupCompletionPlan.transform(
                ClassSignature.parse(transformed), transformed));
    }

    @Test
    void transformedGameClassCanCallTheRuntimeAcrossPackageBoundaries() throws Exception {
        FrameTimeRuntime.beginSession(true);
        byte[] original = fixture(1);
        byte[] transformed = FrameTimeStartupCompletionPlan.transform(
                exactSignature(original), original);
        String binaryName = FrameTimeStartupCompletionPlan.TARGET_CLASS.replace('/', '.');
        Class<?> target = new ByteArrayLoader(binaryName, transformed).loadClass(binaryName);

        target.getMethod(FrameTimeStartupCompletionPlan.INIT_METHOD, Map.class)
                .invoke(target.getConstructor().newInstance(), Map.of());

        assertTrue((Boolean) FrameTimeRuntime.telemetry().get("startupComplete"));
    }

    @Test
    void declinesWhenDisabledHashChangedOrShapeIsAmbiguous() throws Exception {
        byte[] original = fixture(1);
        FrameTimeRuntime.beginSession(false);
        assertNull(FrameTimeStartupCompletionPlan.transform(exactSignature(original), original));

        FrameTimeRuntime.beginSession(true);
        assertNull(FrameTimeStartupCompletionPlan.transform(ClassSignature.parse(original), original));
        byte[] ambiguous = fixture(2);
        assertNull(FrameTimeStartupCompletionPlan.transform(
                exactSignature(ambiguous), ambiguous));
    }

    @Test
    void installsForThePostStartupJsonCacheWithoutFrameTelemetry() throws Exception {
        FrameTimeRuntime.beginSession(false);
        LoadJsonMemoRuntime.enable(true);
        byte[] original = fixture(1);

        byte[] transformed = FrameTimeStartupCompletionPlan.transform(
                exactSignature(original), original);

        assertEquals(1, calls(method(transformed), RUNTIME, "markStartupComplete", "()V"));
    }

    private static byte[] fixture(int returns) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                FrameTimeStartupCompletionPlan.TARGET_CLASS, null, "java/lang/Object", null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC,
                FrameTimeStartupCompletionPlan.INIT_METHOD,
                FrameTimeStartupCompletionPlan.INIT_DESCRIPTOR, null, null);
        if (returns == 1) {
            init.visitInsn(Opcodes.RETURN);
        } else {
            var second = new org.objectweb.asm.Label();
            init.visitInsn(Opcodes.ICONST_0);
            init.visitJumpInsn(Opcodes.IFEQ, second);
            init.visitInsn(Opcodes.RETURN);
            init.visitLabel(second);
            init.visitInsn(Opcodes.RETURN);
        }
        init.visitMaxs(1, 2);
        init.visitEnd();
        init.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(),
                FrameTimeStartupCompletionPlan.ORIGINAL_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
    }

    private static MethodNode method(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, 0);
        return owner.methods.stream()
                .filter(candidate -> FrameTimeStartupCompletionPlan.INIT_METHOD.equals(candidate.name)
                        && FrameTimeStartupCompletionPlan.INIT_DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private final String binaryName;
        private final byte[] bytes;

        private ByteArrayLoader(String binaryName, byte[] bytes) {
            super(FrameTimeStartupCompletionPlanTest.class.getClassLoader());
            this.binaryName = binaryName;
            this.bytes = bytes;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && binaryName.equals(name)) {
                loaded = defineClass(name, bytes, 0, bytes.length);
            }
            if (loaded == null) loaded = super.loadClass(name, false);
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}

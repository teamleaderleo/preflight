package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class TextureSpecStoreOverlapPlanTest {
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/DisplayThreadSpecStoreProbeRuntime";

    @AfterEach
    void clear() {
        System.clearProperty(DisplayThreadSpecStoreProbeRuntime.CANDIDATE_PROPERTY);
    }

    @Test
    void capturesTheExactLoaderAndObservesTheExactPathMethod() throws Exception {
        System.setProperty(DisplayThreadSpecStoreProbeRuntime.CANDIDATE_PROPERTY, "true");
        byte[] original = fixture();
        byte[] transformed = TextureSpecStoreOverlapPlan.transform(
                exactSignature(original), original);

        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        assertEquals(1, calls(method(owner, "<init>", "()V"), "captureTextureLoader"));
        assertEquals(1, calls(method(owner, TextureSpecStoreOverlapPlan.LOAD_METHOD,
                TextureSpecStoreOverlapPlan.LOAD_DESCRIPTOR), "observeTextureRequest"));
        assertNull(TextureSpecStoreOverlapPlan.transform(
                ClassSignature.parse(transformed), transformed));
    }

    @Test
    void declinesWithoutTheKillSwitchOrExactIdentity() throws Exception {
        byte[] original = fixture();
        assertNull(TextureSpecStoreOverlapPlan.transform(exactSignature(original), original));
        System.setProperty(DisplayThreadSpecStoreProbeRuntime.CANDIDATE_PROPERTY, "true");
        assertNull(TextureSpecStoreOverlapPlan.transform(
                ClassSignature.parse(original), original));
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                TextureSpecStoreOverlapPlan.TARGET_CLASS, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor load = writer.visitMethod(Opcodes.ACC_PUBLIC,
                TextureSpecStoreOverlapPlan.LOAD_METHOD,
                TextureSpecStoreOverlapPlan.LOAD_DESCRIPTOR, null, null);
        load.visitCode();
        load.visitInsn(Opcodes.ACONST_NULL);
        load.visitInsn(Opcodes.ARETURN);
        load.visitMaxs(1, 2);
        load.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), TextureSpecStoreOverlapPlan.ORIGINAL_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }
}

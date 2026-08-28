package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class GlIsEnabledStateCachePlanTest {
    private static final String TARGET = GlIsEnabledStateCachePlan.TARGET_CLASS;
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/GlIsEnabledStateCacheRuntime";

    @BeforeEach
    void enable() {
        GlIsEnabledStateCacheRuntime.beginSessionForTest(true);
    }

    @AfterEach
    void reset() {
        GlIsEnabledStateCacheRuntime.resetForTest();
    }

    @Test
    void addsGuardedGetterShortcutAndTracksMutators() throws Exception {
        byte[] original = fixture(TARGET, true);
        byte[] transformed = GlIsEnabledStateCachePlan.transform(
                ClassSignature.parse(original), original);
        assertNotNull(transformed);

        ClassNode owner = read(transformed);
        MethodNode getter = method(owner, "glIsEnabled", "(I)Z");
        assertEquals(1, calls(getter, RUNTIME, "cached"));
        assertEquals(1, calls(getter, RUNTIME, "observedQuery"));
        assertEquals(2, opcodeCount(getter, Opcodes.IRETURN));
        assertEquals(1, calls(method(owner, "glEnable", "(I)V"), RUNTIME, "enable"));
        assertEquals(1, calls(method(owner, "glDisable", "(I)V"), RUNTIME, "disable"));
        assertEquals(1, calls(method(owner, "glPushAttrib", "(I)V"), RUNTIME, "pushAttrib"));
        assertEquals(1, calls(method(owner, "glPopAttrib", "()V"), RUNTIME, "popAttrib"));
        assertEquals(1, calls(method(owner, "glNewList", "(II)V"), RUNTIME, "beginList"));
        assertEquals(1, calls(method(owner, "glEndList", "()V"), RUNTIME, "endList"));
        assertEquals(1, calls(method(owner, "glCallList", "(I)V"), RUNTIME, "callList"));
        assertEquals(1, calls(
                method(owner, "glCallLists", "(Ljava/nio/IntBuffer;)V"), RUNTIME, "callList"));
        assertEquals(true, GlIsEnabledStateCacheRuntime.telemetry().get("installed"));
    }

    @Test
    void dispatcherCarriesExactExternalGl11Target() throws Exception {
        byte[] original = fixture(TARGET, true);
        assertNotNull(FrameTimePlan.transform(ClassSignature.parse(original), original));
    }

    @Test
    void declinesDisabledWrongClassMissingRequiredMethodAndSecondApplication() throws Exception {
        byte[] original = fixture(TARGET, true);
        GlIsEnabledStateCacheRuntime.beginSessionForTest(false);
        assertNull(GlIsEnabledStateCachePlan.transform(ClassSignature.parse(original), original));

        GlIsEnabledStateCacheRuntime.beginSessionForTest(true);
        byte[] wrong = fixture("example/Other", true);
        assertNull(GlIsEnabledStateCachePlan.transform(ClassSignature.parse(wrong), wrong));
        byte[] missing = fixture(TARGET, false);
        assertNull(GlIsEnabledStateCachePlan.transform(ClassSignature.parse(missing), missing));

        byte[] transformed = GlIsEnabledStateCachePlan.transform(
                ClassSignature.parse(original), original);
        assertNotNull(transformed);
        assertNull(GlIsEnabledStateCachePlan.transform(
                ClassSignature.parse(transformed), transformed));
    }

    private static byte[] fixture(String className, boolean includeCallList) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        booleanMethod(writer, "glIsEnabled", "(I)Z");
        voidMethod(writer, "glEnable", "(I)V");
        voidMethod(writer, "glDisable", "(I)V");
        voidMethod(writer, "glPushAttrib", "(I)V");
        voidMethod(writer, "glPopAttrib", "()V");
        voidMethod(writer, "glNewList", "(II)V");
        voidMethod(writer, "glEndList", "()V");
        if (includeCallList) voidMethod(writer, "glCallList", "(I)V");
        voidMethod(writer, "glCallLists", "(Ljava/nio/IntBuffer;)V");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void booleanMethod(ClassWriter writer, String name, String descriptor) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void voidMethod(ClassWriter writer, String name, String descriptor) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst()
                .orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)) count++;
        }
        return count;
    }

    private static int opcodeCount(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }
}

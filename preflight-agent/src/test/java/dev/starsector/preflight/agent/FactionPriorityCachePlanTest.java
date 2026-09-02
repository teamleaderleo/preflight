package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class FactionPriorityCachePlanTest {
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/FactionPriorityCacheRuntime";

    @Test
    void wrapsTheReviewedThreeAddSingleReturnShape() throws Exception {
        byte[] original = fixture(3);
        byte[] rewritten = FactionPriorityCachePlan.transform(
                ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(1, calls(rewritten, "replayOrBegin"));
        assertEquals(3, calls(rewritten, "record"));
        assertEquals(1, calls(rewritten, "completeCall"));
        assertNull(FactionPriorityCachePlan.transform(ClassSignature.parse(rewritten), rewritten));
    }

    @Test
    void refusesShapeDriftRatherThanGuessing() throws Exception {
        byte[] drifted = fixture(2);
        assertNull(FactionPriorityCachePlan.transform(ClassSignature.parse(drifted), drifted));
    }

    private static byte[] fixture(int addCalls) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, FactionPriorityCachePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                FactionPriorityCachePlan.METHOD,
                FactionPriorityCachePlan.DESCRIPTOR,
                null,
                null);
        method.visitCode();
        for (int index = 0; index < addCalls; index++) {
            method.visitVarInsn(Opcodes.ALOAD, 4);
            method.visitLdcInsn("id-" + index);
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    "com/fs/starfarer/loading/SpecStore$Oo", "o00000",
                    "(Ljava/lang/String;)V", true);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int calls(byte[] bytes, String name) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = owner.methods.stream()
                .filter(candidate -> FactionPriorityCachePlan.METHOD.equals(candidate.name)
                        && FactionPriorityCachePlan.DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
        int calls = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner) && name.equals(call.name)) calls++;
        }
        return calls;
    }
}

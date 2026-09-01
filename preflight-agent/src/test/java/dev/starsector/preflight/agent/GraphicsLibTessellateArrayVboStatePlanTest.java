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

class GraphicsLibTessellateArrayVboStatePlanTest {
    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String GL15 = "org/lwjgl/opengl/GL15";
    private static final String HELPER = "preflight$drawCachedTessellation";
    private static final String HELPER_DESCRIPTOR =
            "(Lorg/dark/graphics/util/Tessellate$TessData;"
                    + "Lcom/fs/starfarer/api/combat/ShipAPI;)V";

    @Test
    void insertsArrayVboUnbindInsideSavedClientState() {
        byte[] repaired = GraphicsLibTessellateArrayVboStatePlan.transform(fixture(true));
        assertNotNull(repaired);
        MethodNode helper = helper(repaired);
        assertEquals(1, calls(helper, GL11, "glPushClientAttrib"));
        assertEquals(1, calls(helper, GL15, "glBindBuffer"));
        assertEquals(1, calls(helper, GL11, "glVertexPointer"));
        assertEquals(1, calls(helper, GL11, "glPopClientAttrib"));
        assertNull(GraphicsLibTessellateArrayVboStatePlan.transform(repaired));
    }

    @Test
    void declinesWrongClientAttributeMask() {
        assertNull(GraphicsLibTessellateArrayVboStatePlan.transform(fixture(false)));
    }

    private static byte[] fixture(boolean correctMask) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                GraphicsLibTessellateArrayPlan.TARGET_CLASS,
                null,
                "java/lang/Object",
                null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                HELPER,
                HELPER_DESCRIPTOR,
                null,
                null);
        method.visitCode();
        method.visitLdcInsn(correctMask ? 0x00000002 : 0x00000001);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC, GL11, "glPushClientAttrib", "(I)V", false);
        method.visitInsn(Opcodes.ICONST_2);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                GL11,
                "glVertexPointer",
                "(IILjava/nio/FloatBuffer;)V",
                false);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC, GL11, "glPopClientAttrib", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(3, 2);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodNode helper(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> HELPER.equals(method.name) && HELPER_DESCRIPTOR.equals(method.desc))
                .findFirst()
                .orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }
}

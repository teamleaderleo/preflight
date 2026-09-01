package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Makes the GraphicsLib client-array replay safe when another renderer left an array VBO bound.
 *
 * <p>LWJGL2 rejects the FloatBuffer glVertexPointer overload while GL_ARRAY_BUFFER is non-zero.
 * The generated replay already brackets client-array state with glPushClientAttrib/glPopClientAttrib,
 * and LWJGL's state tracker saves/restores arrayBuffer under GL_CLIENT_VERTEX_ARRAY_BIT. Bind zero
 * inside that bracket so client-memory submission works without leaking the caller's binding.
 */
final class GraphicsLibTessellateArrayVboStatePlan {
    private static final String TARGET_CLASS = GraphicsLibTessellateArrayPlan.TARGET_CLASS;
    private static final String HELPER = "preflight$drawCachedTessellation";
    private static final String HELPER_DESCRIPTOR =
            "(Lorg/dark/graphics/util/Tessellate$TessData;"
                    + "Lcom/fs/starfarer/api/combat/ShipAPI;)V";
    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String GL15 = "org/lwjgl/opengl/GL15";
    private static final int GL_CLIENT_VERTEX_ARRAY_BIT = 0x00000002;
    private static final int GL_ARRAY_BUFFER = 0x8892;

    private GraphicsLibTessellateArrayVboStatePlan() {
    }

    static byte[] transform(byte[] transformedBytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformedBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!TARGET_CLASS.equals(owner.name)) {
            return null;
        }

        MethodNode helper = unique(owner, HELPER, HELPER_DESCRIPTOR);
        if (helper == null || calls(helper, GL15, "glBindBuffer", "(II)V") != 0) {
            return null;
        }
        MethodInsnNode push = uniqueCall(helper, GL11, "glPushClientAttrib", "(I)V");
        MethodInsnNode pointer = uniqueCall(
                helper, GL11, "glVertexPointer", "(IILjava/nio/FloatBuffer;)V");
        MethodInsnNode pop = uniqueCall(helper, GL11, "glPopClientAttrib", "()V");
        if (push == null || pointer == null || pop == null
                || !precedes(push, pointer) || !precedes(pointer, pop)) {
            return null;
        }
        AbstractInsnNode mask = previousCode(push);
        if (!(mask instanceof LdcInsnNode constant)
                || !(constant.cst instanceof Integer value)
                || value != GL_CLIENT_VERTEX_ARRAY_BIT) {
            return null;
        }

        InsnList unbind = new InsnList();
        unbind.add(new LdcInsnNode(GL_ARRAY_BUFFER));
        unbind.add(new InsnNode(Opcodes.ICONST_0));
        unbind.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, GL15, "glBindBuffer", "(II)V", false));
        helper.instructions.insert(push, unbind);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String descriptor) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                if (found != null) return null;
                found = call;
            }
        }
        return found;
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static boolean precedes(AbstractInsnNode first, AbstractInsnNode second) {
        for (AbstractInsnNode cursor = first; cursor != null; cursor = cursor.getNext()) {
            if (cursor == second) return true;
        }
        return false;
    }
}

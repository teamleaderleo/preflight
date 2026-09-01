package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Adds a primitive-cache shortcut ahead of the reviewed GraphicsLib list replay fallback. */
final class GraphicsLibTessellatePackedReplayPlan {
    private static final String TARGET_CLASS = GraphicsLibTessellateArrayPlan.TARGET_CLASS;
    private static final String HELPER = "preflight$drawCachedTessellation";
    private static final String HELPER_DESCRIPTOR =
            "(Lorg/dark/graphics/util/Tessellate$TessData;"
                    + "Lcom/fs/starfarer/api/combat/ShipAPI;)V";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/GraphicsLibTessellateArrayRuntime";
    private static final String FLOAT_BUFFER = "java/nio/FloatBuffer";
    private static final String FILL_DESCRIPTOR =
            "(Ljava/lang/Object;Ljava/nio/FloatBuffer;FFFF)Z";

    private GraphicsLibTessellatePackedReplayPlan() {
    }

    static byte[] transform(byte[] transformedBytes) {
        if (!GraphicsLibTessellateArrayRuntime.packedReplayEnabled()) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformedBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!TARGET_CLASS.equals(owner.name)) {
            return null;
        }

        MethodNode helper = unique(owner, HELPER, HELPER_DESCRIPTOR);
        if (helper == null
                || calls(helper, RUNTIME, "fillPacked", FILL_DESCRIPTOR) != 0
                || calls(helper, FLOAT_BUFFER, "put", "(F)Ljava/nio/FloatBuffer;") != 2
                || calls(helper, FLOAT_BUFFER, "flip", "()Ljava/nio/FloatBuffer;") != 1) {
            return null;
        }

        MethodInsnNode iterator = uniqueCall(
                helper,
                "java/util/List",
                "iterator",
                "()Ljava/util/Iterator;");
        MethodInsnNode hasNext = uniqueCall(
                helper,
                "java/util/Iterator",
                "hasNext",
                "()Z");
        if (iterator == null || hasNext == null || !precedes(iterator, hasNext)) {
            return null;
        }

        AbstractInsnNode iteratorStart = previousCode(iterator);
        if (!(iteratorStart instanceof VarInsnNode listLoad)
                || listLoad.getOpcode() != Opcodes.ALOAD
                || listLoad.var != 2) {
            return null;
        }

        AbstractInsnNode afterHasNext = nextCode(hasNext);
        if (!(afterHasNext instanceof JumpInsnNode exhausted)
                || exhausted.getOpcode() != Opcodes.IFEQ) {
            return null;
        }
        LabelNode draw = exhausted.label;
        if (!precedes(hasNext, draw)) {
            return null;
        }

        LabelNode fallback = new LabelNode();
        InsnList shortcut = new InsnList();
        shortcut.add(new VarInsnNode(Opcodes.ALOAD, 0));
        shortcut.add(new VarInsnNode(Opcodes.ALOAD, 5));
        shortcut.add(new VarInsnNode(Opcodes.FLOAD, 8));
        shortcut.add(new VarInsnNode(Opcodes.FLOAD, 9));
        shortcut.add(new VarInsnNode(Opcodes.FLOAD, 11));
        shortcut.add(new VarInsnNode(Opcodes.FLOAD, 12));
        shortcut.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "fillPacked",
                FILL_DESCRIPTOR,
                false));
        shortcut.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        shortcut.add(new JumpInsnNode(Opcodes.GOTO, draw));
        shortcut.add(fallback);
        helper.instructions.insertBefore(iteratorStart, shortcut);

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
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
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

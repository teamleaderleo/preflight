package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Replaces only vanilla's literal-free-memory warning decision with a pressure-aware predicate. */
final class MacMemoryWarningPlan {
    static final String TARGET_CLASS = "com/fs/starfarer/campaign/C";
    static final String ORIGINAL_SHA256 =
            "3fbb94794496debd8401c8b2643d3cd58d0834d7d26694cad998cc08a65c3c99";
    static final String METHOD = "o00000";
    static final String DESCRIPTOR = "(Lcom/fs/starfarer/campaign/CampaignState;Z)V";
    static final String WARNING = "Warning: Low system RAM remaining, may result in poor "
            + "performance due to possible pagefile usage";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/MacMemoryWarningRuntime";

    private MacMemoryWarningPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(METHOD, DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = unique(owner, METHOD, DESCRIPTOR);
        LdcInsnNode warning = uniqueWarning(method);
        if (method == null || warning == null || calls(method, RUNTIME, "shouldWarn") != 0) {
            return null;
        }

        AbstractInsnNode state = previousCode(warning);
        AbstractInsnNode branchNode = previousCode(state);
        AbstractInsnNode compare = previousCode(branchNode);
        AbstractInsnNode threshold = previousCode(compare);
        AbstractInsnNode free = previousCode(threshold);
        if (!(state instanceof VarInsnNode stateLoad) || stateLoad.getOpcode() != Opcodes.ALOAD
                || stateLoad.var != 0
                || !(branchNode instanceof JumpInsnNode branch)
                || branch.getOpcode() != Opcodes.IFGE
                || compare == null || compare.getOpcode() != Opcodes.LCMP
                || !(threshold instanceof LdcInsnNode constant)
                || !(constant.cst instanceof Long value)
                || value != MacMemoryWarningRuntime.WARNING_THRESHOLD_MIB
                || !(free instanceof VarInsnNode freeLoad)
                || freeLoad.getOpcode() != Opcodes.LLOAD || freeLoad.var != 11) {
            return null;
        }

        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.LLOAD, 11));
        replacement.add(new VarInsnNode(Opcodes.LLOAD, 9));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "shouldWarn",
                "(JJ)Z", false));
        replacement.add(new JumpInsnNode(Opcodes.IFEQ, branch.label));
        method.instructions.insertBefore(free, replacement);
        method.instructions.remove(free);
        method.instructions.remove(threshold);
        method.instructions.remove(compare);
        method.instructions.remove(branch);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        MacMemoryWarningRuntime.installed();
        return writer.toByteArray();
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode result = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (result != null) return null;
                result = method;
            }
        }
        return result;
    }

    private static LdcInsnNode uniqueWarning(MethodNode method) {
        if (method == null) return null;
        LdcInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode constant && WARNING.equals(constant.cst)) {
                if (result != null) return null;
                result = constant;
            }
        }
        return result;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                result++;
            }
        }
        return result;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        AbstractInsnNode current = instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }
}

package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Observes the stock opponent dialog immediately after its reserve grid is populated. */
final class SimOpponentDialogProbePlan {
    static final String TARGET_CLASS = "com/fs/starfarer/ui/impl/M";
    static final String ORIGINAL_SHA256 =
            "6217ab4538886e2eaedff27aefb0ea51c027d3555b92828e09cb0df9d2f8fe57";
    static final String GRID_METHOD = "ÖØ0000";
    static final String GRID_DESCRIPTOR = "()V";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/SimOpponentSafetyRuntime";
    private static final String RECORD_DESCRIPTOR = "(Ljava/lang/Object;I)V";

    private SimOpponentDialogProbePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(GRID_METHOD, GRID_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode grid = uniqueMethod(owner, GRID_METHOD, GRID_DESCRIPTOR);
        if (!TARGET_CLASS.equals(owner.name) || grid == null
                || (grid.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0
                || hasRecordCall(grid)) {
            return null;
        }
        List<AbstractInsnNode> gridReturns = opcodes(grid, Opcodes.RETURN);
        if (gridReturns.size() != 1) {
            return null;
        }
        insertObservation(grid, gridReturns.get(0), 0);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static void insertObservation(MethodNode method, AbstractInsnNode before, int phase) {
        InsnList observation = new InsnList();
        observation.add(new VarInsnNode(Opcodes.ALOAD, 0));
        observation.add(new InsnNode(phase == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1));
        observation.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "recordDialog", RECORD_DESCRIPTOR, false));
        method.instructions.insertBefore(before, observation);
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static boolean hasRecordCall(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner) && "recordDialog".equals(call.name)) {
                return true;
            }
        }
        return false;
    }

    private static List<AbstractInsnNode> opcodes(MethodNode method, int opcode) {
        List<AbstractInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) {
                found.add(instruction);
            }
        }
        return found;
    }
}

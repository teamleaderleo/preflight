package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Installs one runtime-integrity observation and composes opt-in combat diagnostics. */
final class CombatRuntimeIntegrityPlan {
    static final String TARGET_CLASS = "com/fs/starfarer/combat/CombatEngine";
    static final String ORIGINAL_SHA256 =
            "17c1d7f1347d177d6fc36f560e903d50d9df5f1f945e5da9590f83e4fbac17f4";
    static final String ADVANCE_METHOD = "advance";
    static final String ADVANCE_DESCRIPTOR = "(FLcom/fs/starfarer/util/super/B;)V";
    static final String COMBAT_STATE_CLASS = "com/fs/starfarer/combat/CombatState";
    static final String COMBAT_STATE_SHA256 =
            "f1f815e07dd8acacc97455d8584eb1b89c1564ab469f48420fca0b5aeecbe372";
    static final String TRAVERSE_METHOD = "traverse";
    static final String TRAVERSE_DESCRIPTOR = "()Ljava/lang/String;";
    private static final String INPUT_FACTORY = "com/fs/starfarer/util/super/A";
    private static final String INPUT_BATCH = "com/fs/starfarer/util/super/B";

    private static final String INTEGRITY_RUNTIME =
            "dev/starsector/preflight/agent/CombatRuntimeIntegrityRuntime";
    private static final String FRAME_RUNTIME =
            "dev/starsector/preflight/agent/FrameTimeRuntime";
    private static final String CONTROL_RUNTIME =
            "dev/starsector/preflight/agent/InternalGameControlRuntime";
    private static final String WORKLOAD_RUNTIME =
            "dev/starsector/preflight/agent/CombatWorkloadRuntime";

    private CombatRuntimeIntegrityPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (COMBAT_STATE_CLASS.equals(signature.internalName())) {
            return transformCombatState(signature, originalBytes);
        }
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(ADVANCE_METHOD, ADVANCE_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode advance = unique(owner, ADVANCE_METHOD, ADVANCE_DESCRIPTOR);
        if (advance == null
                || (advance.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || advance.instructions.getFirst() == null
                || calls(advance, INTEGRITY_RUNTIME, "observe") != 0
                || calls(advance, FRAME_RUNTIME, "observeCombat") != 0
                || calls(advance, CONTROL_RUNTIME, "combatAdvance") != 0
                || calls(advance, CONTROL_RUNTIME, "combatAdvanceEnd") != 0
                || calls(advance, WORKLOAD_RUNTIME, "begin") != 0
                || calls(advance, WORKLOAD_RUNTIME, "end") != 0) {
            return null;
        }

        boolean workload = CombatWorkloadRuntime.enabled();
        int workloadStartedLocal = -1;
        InsnList observations = new InsnList();
        observations.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, INTEGRITY_RUNTIME, "observe", "()V", false));
        if (FrameTimeRuntime.enabled() || RuntimeSemanticState.enabled()) {
            observations.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, FRAME_RUNTIME, "observeCombat", "()V", false));
        }
        if (InternalGameControlRuntime.enabled()) {
            observations.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
            observations.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 2));
            observations.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, CONTROL_RUNTIME, "combatAdvance",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        }
        if (workload) {
            workloadStartedLocal = advance.maxLocals;
            advance.maxLocals += 2;
            observations.add(new VarInsnNode(Opcodes.ALOAD, 0));
            observations.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, WORKLOAD_RUNTIME, "begin", "(Ljava/lang/Object;)J", false));
            observations.add(new VarInsnNode(Opcodes.LSTORE, workloadStartedLocal));
        }
        advance.instructions.insertBefore(advance.instructions.getFirst(), observations);

        if (workload) {
            List<AbstractInsnNode> exits = new ArrayList<>();
            for (AbstractInsnNode instruction : advance.instructions) {
                if (instruction.getOpcode() == Opcodes.RETURN
                        || instruction.getOpcode() == Opcodes.ATHROW) exits.add(instruction);
            }
            if (exits.isEmpty()) return null;
            for (AbstractInsnNode exit : exits) {
                InsnList timing = new InsnList();
                timing.add(new VarInsnNode(Opcodes.LLOAD, workloadStartedLocal));
                timing.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, WORKLOAD_RUNTIME, "end", "(J)V", false));
                advance.instructions.insertBefore(exit, timing);
            }
        }

        if (InternalGameControlRuntime.enabled()) {
            for (AbstractInsnNode instruction : advance.instructions.toArray()) {
                if (instruction.getOpcode() != Opcodes.RETURN) continue;
                InsnList control = new InsnList();
                control.add(new VarInsnNode(Opcodes.ALOAD, 0));
                control.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, CONTROL_RUNTIME, "combatAdvanceEnd",
                        "(Ljava/lang/Object;)V", false));
                advance.instructions.insertBefore(instruction, control);
            }
        }

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        CombatRuntimeIntegrityRuntime.installed();
        if (workload) CombatWorkloadRuntime.installed();
        return writer.toByteArray();
    }

    private static byte[] transformCombatState(ClassSignature signature, byte[] originalBytes) {
        if (!InternalGameControlRuntime.enabled()
                || !COMBAT_STATE_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(TRAVERSE_METHOD, TRAVERSE_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode traverse = unique(owner, TRAVERSE_METHOD, TRAVERSE_DESCRIPTOR);
        if (traverse == null
                || (traverse.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || calls(traverse, CONTROL_RUNTIME, "combatInput") != 0) {
            return null;
        }

        AbstractInsnNode insertion = null;
        int matches = 0;
        for (AbstractInsnNode instruction : traverse.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !INPUT_FACTORY.equals(call.owner)
                    || !"Object".equals(call.name)
                    || !("()L" + INPUT_BATCH + ";").equals(call.desc)) continue;
            AbstractInsnNode next = nextReal(instruction);
            if (next != null && next.getOpcode() == Opcodes.ASTORE) {
                insertion = next;
                matches++;
            }
        }
        if (matches != 1 || !(insertion instanceof org.objectweb.asm.tree.VarInsnNode store)) {
            return null;
        }

        InsnList control = new InsnList();
        control.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        control.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, store.var));
        control.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, CONTROL_RUNTIME, "combatInput",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        traverse.instructions.insert(insertion, control);
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
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

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }
}

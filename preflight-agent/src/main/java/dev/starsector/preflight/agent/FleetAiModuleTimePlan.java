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
import org.objectweb.asm.tree.LdcInsnNode;

/** Times the five existing module seams inside exact vanilla {@code ModularFleetAI.advance}. */
final class FleetAiModuleTimePlan {
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/FleetAiModuleTimeRuntime";

    private static final List<TargetCall> TARGETS = List.of(
            new TargetCall("com/fs/starfarer/api/campaign/ai/AssignmentModulePlugin",
                    FleetAiModuleTimeRuntime.ASSIGNMENT),
            new TargetCall("com/fs/starfarer/api/campaign/ai/StrategicModulePlugin",
                    FleetAiModuleTimeRuntime.STRATEGIC),
            new TargetCall("com/fs/starfarer/api/campaign/ai/TacticalModulePlugin",
                    FleetAiModuleTimeRuntime.TACTICAL),
            new TargetCall("com/fs/starfarer/api/campaign/ai/NavigationModulePlugin",
                    FleetAiModuleTimeRuntime.NAVIGATION),
            new TargetCall("com/fs/starfarer/api/campaign/ai/AbilityAIPlugin",
                    FleetAiModuleTimeRuntime.ABILITY));

    private FleetAiModuleTimePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] currentBytes) {
        if (!FleetAiModuleTimeRuntime.enabled() || signature.majorVersion() != 61
                || !FleetAiProfilerPlan.FLEET_AI_CLASS.equals(signature.internalName())
                || !FleetAiProfilerPlan.FLEET_AI_SHA256.equals(signature.sha256())) return null;

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(currentBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = unique(owner, FleetAiProfilerPlan.ADVANCE,
                FleetAiProfilerPlan.ADVANCE_DESCRIPTOR);
        if (method == null || callsRuntime(method) != 0) return null;

        List<MatchedCall> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !"advance".equals(call.name) || !"(F)V".equals(call.desc)) continue;
            for (TargetCall target : TARGETS) {
                if (target.owner.equals(call.owner)) calls.add(new MatchedCall(call, target.phase));
            }
        }
        for (TargetCall target : TARGETS) {
            long count = calls.stream().filter(call -> call.phase == target.phase).count();
            if (count != 1L) return null;
        }

        int moduleLocal = method.maxLocals;
        int amountLocal = moduleLocal + 1;
        int startedLocal = amountLocal + 1;
        method.maxLocals = startedLocal + 2;
        for (MatchedCall matched : calls) weave(method, matched, moduleLocal, amountLocal, startedLocal);
        FleetAiModuleTimeRuntime.installed();
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static void weave(
            MethodNode method,
            MatchedCall matched,
            int moduleLocal,
            int amountLocal,
            int startedLocal) {
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.FSTORE, amountLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, moduleLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, moduleLocal));
        before.add(new LdcInsnNode(matched.phase));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "enter",
                "(Ljava/lang/Object;I)J", false));
        before.add(new VarInsnNode(Opcodes.LSTORE, startedLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, moduleLocal));
        before.add(new VarInsnNode(Opcodes.FLOAD, amountLocal));
        method.instructions.insertBefore(matched.call, before);

        InsnList after = new InsnList();
        after.add(new VarInsnNode(Opcodes.ALOAD, 0));
        after.add(new VarInsnNode(Opcodes.ALOAD, moduleLocal));
        after.add(new LdcInsnNode(matched.phase));
        after.add(new VarInsnNode(Opcodes.LLOAD, startedLocal));
        after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exit",
                "(Ljava/lang/Object;Ljava/lang/Object;IJ)V", false));
        method.instructions.insert(matched.call, after);
    }

    private static int callsRuntime(MethodNode method) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) result++;
        }
        return result;
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

    private record TargetCall(String owner, int phase) {
    }

    private record MatchedCall(MethodInsnNode call, int phase) {
    }
}

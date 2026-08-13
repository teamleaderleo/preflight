package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Avoids building per-ability profiler labels while Starsector's profiler is disabled. */
final class FleetAiProfilerPlan {
    static final String FLEET_AI_CLASS = "com/fs/starfarer/campaign/ai/ModularFleetAI";
    static final String FLEET_AI_SHA256 =
            "71117478e53743a6950b0062409d51b2194f6cb8a2588d7e1388c365752fdb13";
    static final String PROFILER_CLASS = "com/fs/profiler/Profiler";
    static final String PROFILER_SHA256 =
            "1aa03bca3fc5f39fe3bd7e8c1070be7bf7823336e1e6090916c3c5fcd44a04cf";
    static final String ADVANCE = "advance";
    static final String ADVANCE_DESCRIPTOR = "(F)V";
    static final String PROFILER_TOGGLE = "o00000";
    static final String PROFILER_TOGGLE_DESCRIPTOR = "(Z)V";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/FleetAiProfilerRuntime";
    private static final String STRING_BUILDER = "java/lang/StringBuilder";

    private FleetAiProfilerPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!FleetAiProfilerRuntime.enabled() || signature.majorVersion() != 61) return null;
        if (FLEET_AI_CLASS.equals(signature.internalName())
                && FLEET_AI_SHA256.equals(signature.sha256())) {
            return transformFleet(originalBytes);
        }
        if (PROFILER_CLASS.equals(signature.internalName())
                && PROFILER_SHA256.equals(signature.sha256())) {
            return transformProfiler(originalBytes);
        }
        return null;
    }

    private static byte[] transformFleet(byte[] originalBytes) {
        ClassNode owner = read(originalBytes);
        MethodNode method = unique(owner, ADVANCE, ADVANCE_DESCRIPTOR);
        if (method == null || callsRuntime(method) != 0) return null;
        List<MethodInsnNode> dynamicBegins = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && PROFILER_CLASS.equals(call.owner) && "new".equals(call.name)
                    && "(Ljava/lang/String;)V".equals(call.desc)
                    && isDynamicAbilityLabel(call)) dynamicBegins.add(call);
        }
        if (dynamicBegins.size() != 1) return null;
        MethodInsnNode begin = dynamicBegins.get(0);
        AbstractInsnNode allocation = abilityLabelAllocation(begin);
        if (allocation == null) return null;

        LabelNode originalLabel = new LabelNode();
        LabelNode labelReady = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "abilityLabelNeeded", "()Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, originalLabel));
        guard.add(new LdcInsnNode("Ability"));
        guard.add(new JumpInsnNode(Opcodes.GOTO, labelReady));
        guard.add(originalLabel);
        method.instructions.insertBefore(allocation, guard);
        method.instructions.insertBefore(begin, labelReady);
        FleetAiProfilerRuntime.fleetInstalled();
        return write(owner);
    }

    private static byte[] transformProfiler(byte[] originalBytes) {
        ClassNode owner = read(originalBytes);
        MethodNode method = unique(owner, PROFILER_TOGGLE, PROFILER_TOGGLE_DESCRIPTOR);
        if (method == null || callsRuntime(method) != 0) return null;
        List<AbstractInsnNode> meaningful = meaningful(method);
        if (meaningful.size() != 3
                || !(meaningful.get(0) instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ILOAD || load.var != 0
                || !(meaningful.get(1) instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.PUTSTATIC || !PROFILER_CLASS.equals(field.owner)
                || !"Z".equals(field.desc) || meaningful.get(2).getOpcode() != Opcodes.RETURN) {
            return null;
        }
        InsnList publish = new InsnList();
        publish.add(new VarInsnNode(Opcodes.ILOAD, 0));
        publish.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "profilerState", "(Z)V", false));
        method.instructions.insert(field, publish);
        FleetAiProfilerRuntime.profilerInstalled();
        return write(owner);
    }

    private static boolean isDynamicAbilityLabel(MethodInsnNode begin) {
        AbstractInsnNode toString = previousMeaningful(begin);
        return toString instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && STRING_BUILDER.equals(call.owner) && "toString".equals(call.name)
                && "()Ljava/lang/String;".equals(call.desc)
                && abilityLabelAllocation(begin) != null;
    }

    private static AbstractInsnNode abilityLabelAllocation(MethodInsnNode begin) {
        AbstractInsnNode toString = previousMeaningful(begin);
        AbstractInsnNode appendClose = previousMeaningful(toString);
        AbstractInsnNode close = previousMeaningful(appendClose);
        AbstractInsnNode appendId = previousMeaningful(close);
        AbstractInsnNode getId = previousMeaningful(appendId);
        AbstractInsnNode loadAbility = previousMeaningful(getId);
        AbstractInsnNode constructor = previousMeaningful(loadAbility);
        AbstractInsnNode prefix = previousMeaningful(constructor);
        AbstractInsnNode duplicate = previousMeaningful(prefix);
        AbstractInsnNode allocationNode = previousMeaningful(duplicate);
        if (!(toString instanceof MethodInsnNode stringCall)
                || !STRING_BUILDER.equals(stringCall.owner) || !"toString".equals(stringCall.name)
                || !(appendClose instanceof MethodInsnNode closeCall)
                || !STRING_BUILDER.equals(closeCall.owner) || !"append".equals(closeCall.name)
                || !(close instanceof LdcInsnNode closeText) || !"]".equals(closeText.cst)
                || !(appendId instanceof MethodInsnNode appendCall)
                || !STRING_BUILDER.equals(appendCall.owner) || !"append".equals(appendCall.name)
                || !(getId instanceof MethodInsnNode idCall) || !"getId".equals(idCall.name)
                || !(loadAbility instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                || !(constructor instanceof MethodInsnNode init)
                || !STRING_BUILDER.equals(init.owner) || !"<init>".equals(init.name)
                || !(prefix instanceof LdcInsnNode prefixText)
                || !"Ability [".equals(prefixText.cst)
                || duplicate == null || duplicate.getOpcode() != Opcodes.DUP
                || !(allocationNode instanceof TypeInsnNode allocation)
                || allocation.getOpcode() != Opcodes.NEW
                || !STRING_BUILDER.equals(allocation.desc)) return null;
        return allocation;
    }

    private static List<AbstractInsnNode> meaningful(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getType() != AbstractInsnNode.LABEL
                    && instruction.getType() != AbstractInsnNode.LINE
                    && instruction.getType() != AbstractInsnNode.FRAME) result.add(instruction);
        }
        return result;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && (current.getType() == AbstractInsnNode.LABEL
                || current.getType() == AbstractInsnNode.LINE
                || current.getType() == AbstractInsnNode.FRAME)) current = current.getPrevious();
        return current;
    }

    private static int callsRuntime(MethodNode method) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) result++;
        }
        return result;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static byte[] write(ClassNode owner) {
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
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
}

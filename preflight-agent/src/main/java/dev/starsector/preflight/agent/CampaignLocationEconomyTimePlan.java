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
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Attributes entity, location-script, and economy work below CampaignEngine.advance. */
final class CampaignLocationEconomyTimePlan {
    static final String LOCATION_CLASS = "com/fs/starfarer/campaign/BaseLocation";
    static final String LOCATION_SHA256 =
            "ab16080b8c40d8f61d522089f3c3696fe3b7c8d8f8b287f9c12a47fa449bae24";
    static final String LOCATION_ADVANCE = "advance";
    static final String LOCATION_PAUSED = "advanceEvenIfPaused";
    static final String LOCATION_DESCRIPTOR = "(FLcom/fs/starfarer/util/super/B;)V";

    static final String ECONOMY_CLASS = "com/fs/starfarer/campaign/econ/Economy";
    static final String ECONOMY_SHA256 =
            "e3c66eca6a70cdfb17b298f45b020994146a1c29cd64422401dbdf11107cb529";
    static final String ECONOMY_ADVANCE = "advance";
    static final String ECONOMY_DESCRIPTOR = "(F)V";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/CampaignLocationEconomyTimeRuntime";
    private static final String ENTITY = "com/fs/starfarer/campaign/CampaignEntity";
    private static final String SCRIPT = "com/fs/starfarer/api/EveryFrameScript";
    private static final String FLOAT_VOID = "(F)V";

    private static final List<FixedProbe> ECONOMY_PROBES = List.of(
            new FixedProbe("com/fs/starfarer/campaign/econ/reach/ReachEconomy",
                    "updateLocationMap", "()V",
                    CampaignLocationEconomyTimeRuntime.LOCATION_MAP),
            new FixedProbe("com/fs/starfarer/campaign/econ/reach/ReachEconomyStepper",
                    "nextFrame", FLOAT_VOID,
                    CampaignLocationEconomyTimeRuntime.ECONOMY_STEPPER),
            new FixedProbe("com/fs/starfarer/api/campaign/econ/MarketAPI",
                    "advance", FLOAT_VOID,
                    CampaignLocationEconomyTimeRuntime.MARKET_ADVANCE));

    private CampaignLocationEconomyTimePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!CampaignLocationEconomyTimeRuntime.enabled() || signature.majorVersion() != 61) {
            return null;
        }
        if (LOCATION_CLASS.equals(signature.internalName())
                && LOCATION_SHA256.equals(signature.sha256())) {
            return transformLocation(signature, originalBytes);
        }
        if (ECONOMY_CLASS.equals(signature.internalName())
                && ECONOMY_SHA256.equals(signature.sha256())) {
            return transformEconomy(signature, originalBytes);
        }
        return null;
    }

    static List<FixedProbe> economyProbes() {
        return ECONOMY_PROBES;
    }

    private static byte[] transformLocation(ClassSignature signature, byte[] originalBytes) {
        if (!signature.hasMethod(LOCATION_ADVANCE, LOCATION_DESCRIPTOR)
                || !signature.hasMethod(LOCATION_PAUSED, LOCATION_DESCRIPTOR)) return null;
        ClassNode owner = read(originalBytes);
        MethodNode active = unique(owner, LOCATION_ADVANCE, LOCATION_DESCRIPTOR);
        MethodNode paused = unique(owner, LOCATION_PAUSED, LOCATION_DESCRIPTOR);
        if (active == null || paused == null || callsRuntime(active) != 0 || callsRuntime(paused) != 0) {
            return null;
        }
        List<MethodInsnNode> activeEntities = matching(active, ENTITY, "advance", FLOAT_VOID);
        List<MethodInsnNode> pausedEntities = matching(
                paused, ENTITY, "advanceEvenIfPaused", FLOAT_VOID);
        List<MethodInsnNode> pausedScripts = matching(paused, SCRIPT, "advance", FLOAT_VOID);
        if (activeEntities.size() != 1 || pausedEntities.size() != 1 || pausedScripts.size() != 1) {
            return null;
        }
        instrumentClass(active, activeEntities.get(0), CampaignLocationEconomyTimeRuntime.ENTITY_ACTIVE);
        instrumentClass(paused, pausedEntities.get(0), CampaignLocationEconomyTimeRuntime.ENTITY_PAUSED);
        instrumentClass(paused, pausedScripts.get(0), CampaignLocationEconomyTimeRuntime.LOCATION_SCRIPT);
        CampaignLocationEconomyTimeRuntime.locationInstalled();
        return write(owner);
    }

    private static byte[] transformEconomy(ClassSignature signature, byte[] originalBytes) {
        if (!signature.hasMethod(ECONOMY_ADVANCE, ECONOMY_DESCRIPTOR)) return null;
        ClassNode owner = read(originalBytes);
        MethodNode method = unique(owner, ECONOMY_ADVANCE, ECONOMY_DESCRIPTOR);
        if (method == null || callsRuntime(method) != 0) return null;
        for (FixedProbe probe : ECONOMY_PROBES) {
            List<MethodInsnNode> calls = matching(method, probe.owner(), probe.name(), probe.descriptor());
            if (calls.size() != 1) return null;
        }
        for (FixedProbe probe : ECONOMY_PROBES) {
            instrumentFixed(method,
                    matching(method, probe.owner(), probe.name(), probe.descriptor()).get(0), probe.id());
        }
        CampaignLocationEconomyTimeRuntime.economyInstalled();
        return write(owner);
    }

    private static void instrumentClass(MethodNode method, MethodInsnNode call, int group) {
        int receiverLocal = method.maxLocals++;
        int amountLocal = method.maxLocals++;
        int startedLocal = method.maxLocals;
        method.maxLocals += 2;
        int throwableLocal = method.maxLocals++;

        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode continuation = new LabelNode();
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.FSTORE, amountLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        before.add(new LdcInsnNode(group));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "enterClass",
                "(Ljava/lang/Object;I)J", false));
        before.add(new VarInsnNode(Opcodes.LSTORE, startedLocal));
        before.add(start);
        before.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        before.add(new VarInsnNode(Opcodes.FLOAD, amountLocal));
        method.instructions.insertBefore(call, before);

        InsnList after = new InsnList();
        after.add(end);
        after.add(exitClass(receiverLocal, group, startedLocal));
        after.add(new JumpInsnNode(Opcodes.GOTO, continuation));
        after.add(handler);
        after.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        after.add(exitClass(receiverLocal, group, startedLocal));
        after.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        after.add(new InsnNode(Opcodes.ATHROW));
        after.add(continuation);
        method.instructions.insert(call, after);
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
    }

    private static InsnList exitClass(int receiverLocal, int group, int startedLocal) {
        InsnList result = new InsnList();
        result.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        result.add(new LdcInsnNode(group));
        result.add(new VarInsnNode(Opcodes.LLOAD, startedLocal));
        result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exitClass",
                "(Ljava/lang/Object;IJ)V", false));
        return result;
    }

    private static void instrumentFixed(MethodNode method, MethodInsnNode call, int id) {
        boolean hasFloat = FLOAT_VOID.equals(call.desc);
        int receiverLocal = method.maxLocals++;
        int amountLocal = hasFloat ? method.maxLocals++ : -1;
        int startedLocal = method.maxLocals;
        method.maxLocals += 2;
        int throwableLocal = method.maxLocals++;

        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode continuation = new LabelNode();
        InsnList before = new InsnList();
        if (hasFloat) before.add(new VarInsnNode(Opcodes.FSTORE, amountLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));
        before.add(new LdcInsnNode(id));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "enter", "(I)J", false));
        before.add(new VarInsnNode(Opcodes.LSTORE, startedLocal));
        before.add(start);
        before.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        if (hasFloat) before.add(new VarInsnNode(Opcodes.FLOAD, amountLocal));
        method.instructions.insertBefore(call, before);

        InsnList after = new InsnList();
        after.add(end);
        after.add(exitFixed(id, startedLocal));
        after.add(new JumpInsnNode(Opcodes.GOTO, continuation));
        after.add(handler);
        after.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        after.add(exitFixed(id, startedLocal));
        after.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        after.add(new InsnNode(Opcodes.ATHROW));
        after.add(continuation);
        method.instructions.insert(call, after);
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
    }

    private static InsnList exitFixed(int id, int startedLocal) {
        InsnList result = new InsnList();
        result.add(new LdcInsnNode(id));
        result.add(new VarInsnNode(Opcodes.LLOAD, startedLocal));
        result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exit", "(IJ)V", false));
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

    private static List<MethodInsnNode> matching(
            MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) result.add(call);
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

    private static int callsRuntime(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) count++;
        }
        return count;
    }

    record FixedProbe(String owner, String name, String descriptor, int id) {
    }
}

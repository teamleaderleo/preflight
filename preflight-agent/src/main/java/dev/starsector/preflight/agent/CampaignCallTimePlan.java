package dev.starsector.preflight.agent;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Adds exception-safe inclusive timers to a small exact list of campaign catch-up methods. */
final class CampaignCallTimePlan {
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/CampaignCallTimeRuntime";

    private static final List<Probe> PROBES = List.of(
            new Probe(
                    "exerelin/campaign/fleets/utils/FleetPoolHelperListener",
                    "7d5d5e5652a4a7cdfaef86fb7e9fde09cae2a0ba1059edb3b73616e892f57b78",
                    "advance", "(F)V", CampaignCallTimeRuntime.NEX_FLEET_POOL_ADVANCE),
            new Probe(
                    "exerelin/campaign/fleets/NexRouteManager",
                    "5e0a5304ec0a68cdccc54eddc16d177962de8e38da2b5c45077e7bdef07d2ca1",
                    "spawnAndDespawn", "()V", CampaignCallTimeRuntime.NEX_ROUTE_SPAWN_DESPAWN),
            new Probe(
                    "exerelin/campaign/econ/ResourcePoolManager",
                    "725c769d9d91d0cc8202f6aee0269a52ab5ef787ecb1afdc87acb9a6892824e6",
                    "updatePoints", "()V", CampaignCallTimeRuntime.NEX_RESOURCE_POOL_UPDATE),
            new Probe(
                    "exerelin/campaign/DiplomacyManager",
                    "a72ba028408575ee05c602de67f779e1a461eb7cd719473395090bc3ccbb0ec4",
                    "advance", "(F)V", CampaignCallTimeRuntime.NEX_DIPLOMACY_ADVANCE),
            new Probe(
                    "com/fs/starfarer/api/impl/campaign/events/RepTrackerEvent",
                    "d0b8f4c6f2a293539f1dcd1d5a217091b06ef0b043467b38c8cab5cb33cd3ca9",
                    "advance", "(F)V", CampaignCallTimeRuntime.VANILLA_REP_TRACKER_ADVANCE),
            new Probe(
                    "com/fs/starfarer/api/impl/campaign/fleets/EconomyFleetRouteManager",
                    "640d2e56b5b8c25288c971896cd42e619f9b4c59a746e48430bacfdc519ea175",
                    "advance", "(F)V", CampaignCallTimeRuntime.VANILLA_ECONOMY_FLEET_ADVANCE));

    private CampaignCallTimePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!CampaignCallTimeRuntime.enabled()) return null;
        Probe probe = probe(signature);
        if (probe == null || !signature.hasMethod(probe.method(), probe.descriptor())) return null;

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = unique(owner, probe.method(), probe.descriptor());
        if (method == null || exits(method) == 0 || callsRuntime(method) != 0) return null;

        int startLocal = method.maxLocals;
        int throwableLocal = startLocal + 2;
        method.maxLocals = throwableLocal + 1;
        LabelNode protectedStart = new LabelNode();
        LabelNode protectedEnd = new LabelNode();
        LabelNode handler = new LabelNode();

        InsnList entry = new InsnList();
        entry.add(new LdcInsnNode(probe.id()));
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "enter", "(I)J", false));
        entry.add(new VarInsnNode(Opcodes.LSTORE, startLocal));
        entry.add(protectedStart);
        method.instructions.insert(entry);

        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() >= Opcodes.IRETURN
                    && instruction.getOpcode() <= Opcodes.RETURN) {
                method.instructions.insertBefore(instruction, exitCall(probe.id(), startLocal));
            }
        }

        InsnList exceptionalExit = new InsnList();
        exceptionalExit.add(protectedEnd);
        exceptionalExit.add(handler);
        exceptionalExit.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        exceptionalExit.add(exitCall(probe.id(), startLocal));
        exceptionalExit.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        exceptionalExit.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(exceptionalExit);
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                protectedStart, protectedEnd, handler, "java/lang/Throwable"));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        CampaignCallTimeRuntime.installed(probe.id());
        return writer.toByteArray();
    }

    static Probe probe(ClassSignature signature) {
        for (Probe probe : PROBES) {
            if (probe.className().equals(signature.internalName())
                    && probe.sha256().equals(signature.sha256())) return probe;
        }
        return null;
    }

    static List<Probe> probes() {
        return PROBES;
    }

    private static InsnList exitCall(int id, int startLocal) {
        InsnList exit = new InsnList();
        exit.add(new LdcInsnNode(id));
        exit.add(new VarInsnNode(Opcodes.LLOAD, startLocal));
        exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exit", "(IJ)V", false));
        return exit;
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

    private static int exits(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW) {
                count++;
            }
        }
        return count;
    }

    private static int callsRuntime(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) count++;
        }
        return count;
    }

    record Probe(String className, String sha256, String method, String descriptor, int id) {
    }
}

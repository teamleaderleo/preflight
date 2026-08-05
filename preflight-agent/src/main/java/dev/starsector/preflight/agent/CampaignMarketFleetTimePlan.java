package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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

/** Samples reviewed subcalls of vanilla Market.advance and CampaignFleet.advance. */
final class CampaignMarketFleetTimePlan {
    static final String MARKET_CLASS = "com/fs/starfarer/campaign/econ/Market";
    static final String MARKET_SHA256 =
            "8e9c1e400b1491406836378df83976c31eec453d2a77f8f13c6d030a52bbc0ae";
    static final String FLEET_CLASS = "com/fs/starfarer/campaign/fleet/CampaignFleet";
    static final String FLEET_SHA256 =
            "e407bde405304b4915a709ca15ec82b9d9098a6c0ad5a6acbed1f358d656b8b2";
    static final String ADVANCE = "advance";
    static final String DESCRIPTOR = "(F)V";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/CampaignMarketFleetTimeRuntime";

    private CampaignMarketFleetTimePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!CampaignMarketFleetTimeRuntime.enabled() || signature.majorVersion() != 61
                || !signature.hasMethod(ADVANCE, DESCRIPTOR)) return null;
        if (MARKET_CLASS.equals(signature.internalName()) && MARKET_SHA256.equals(signature.sha256())) {
            return transformMarket(originalBytes);
        }
        if (FLEET_CLASS.equals(signature.internalName()) && FLEET_SHA256.equals(signature.sha256())) {
            return transformFleet(originalBytes);
        }
        return null;
    }

    private static byte[] transformMarket(byte[] originalBytes) {
        ClassNode owner = read(originalBytes);
        MethodNode method = unique(owner, ADVANCE, DESCRIPTOR);
        if (method == null || callsRuntime(method) != 0) return null;

        List<Probe> probes = new ArrayList<>();
        addExactly(probes, method, "com/fs/starfarer/api/campaign/econ/MarketConditionPlugin",
                "advance", "(F)V", 1, CampaignMarketFleetTimeRuntime.MARKET_CONDITIONS, true);
        addExactly(probes, method, "com/fs/starfarer/api/campaign/SubmarketPlugin",
                "advance", "(F)V", 1, CampaignMarketFleetTimeRuntime.MARKET_SUBMARKETS, true);
        addExactly(probes, method, "com/fs/starfarer/campaign/rules/Memory",
                "advance", "(F)V", 1, CampaignMarketFleetTimeRuntime.MARKET_MEMORY, false);
        addExactly(probes, method, "com/fs/starfarer/rpg/Person",
                "advance", "(F)V", 1, CampaignMarketFleetTimeRuntime.MARKET_PEOPLE, false);
        List<MethodInsnNode> stats = matching(method,
                "com/fs/starfarer/api/combat/MutableStatWithTempMods", "advance", "(F)V");
        if (stats.size() != 5) return null;
        probes.add(new Probe(stats.get(0), CampaignMarketFleetTimeRuntime.MARKET_POWER, false));
        for (int index = 1; index < stats.size(); index++) {
            probes.add(new Probe(stats.get(index),
                    CampaignMarketFleetTimeRuntime.MARKET_COMMODITY_STATS, false));
        }
        addExactly(probes, method, "com/fs/starfarer/campaign/econ/CommodityOnMarket",
                "reapplyEventMod", "()V", 1, CampaignMarketFleetTimeRuntime.MARKET_EVENT_MODS, false);
        addExactly(probes, method, "com/fs/starfarer/api/campaign/econ/Industry",
                "advance", "(F)V", 1, CampaignMarketFleetTimeRuntime.MARKET_INDUSTRIES, true);
        if (probes.stream().anyMatch(probe -> probe.call == null)) return null;
        for (Probe probe : probes) instrument(method, probe);
        CampaignMarketFleetTimeRuntime.marketInstalled();
        return write(owner);
    }

    private static byte[] transformFleet(byte[] originalBytes) {
        ClassNode owner = read(originalBytes);
        MethodNode method = unique(owner, ADVANCE, DESCRIPTOR);
        if (method == null || callsRuntime(method) != 0) return null;

        List<Probe> probes = new ArrayList<>();
        addExactly(probes, method, "com/fs/starfarer/api/campaign/ai/CampaignFleetAIAPI",
                "advance", "(F)V", 1, CampaignMarketFleetTimeRuntime.FLEET_AI, true);
        addExactly(probes, method, "com/fs/starfarer/rpg/Person", "advance", "(F)V", 2,
                CampaignMarketFleetTimeRuntime.FLEET_OFFICERS, false);
        addExactly(probes, method, "com/fs/starfarer/campaign/BaseCampaignEntity", "advance", "(F)V", 1,
                CampaignMarketFleetTimeRuntime.FLEET_BASE_ENTITY, false);
        addExactly(probes, method, "com/fs/starfarer/api/combat/HullModFleetEffect",
                "advanceInCampaign", "(Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;)V", 1,
                CampaignMarketFleetTimeRuntime.FLEET_HULLMOD_FLEET, true);
        addExactly(probes, method, "com/fs/starfarer/campaign/fleet/MutableFleetStats", "advance", "(F)V", 1,
                CampaignMarketFleetTimeRuntime.FLEET_STATS, false);
        addExactly(probes, method, "com/fs/starfarer/campaign/accidents/AccidentManager", "advance", "(F)V", 1,
                CampaignMarketFleetTimeRuntime.FLEET_ACCIDENTS, false);
        addExactly(probes, method, "com/fs/starfarer/campaign/fleet/LogisticsModule", "advance", "(F)V", 1,
                CampaignMarketFleetTimeRuntime.FLEET_LOGISTICS, false);
        addExactly(probes, method, FLEET_CLASS, "updateCounts", "()V", 1,
                CampaignMarketFleetTimeRuntime.FLEET_UPDATE_COUNTS, false);
        addExactly(probes, method, "com/fs/starfarer/campaign/fleet/SmoothMovementModule", "advance",
                "(Lcom/fs/starfarer/campaign/fleet/CampaignFleet;Lorg/lwjgl/util/vector/Vector2f;"
                        + "Lorg/lwjgl/util/vector/Vector2f;F)V", 1,
                CampaignMarketFleetTimeRuntime.FLEET_MOVEMENT, false);
        addExactly(probes, method, "com/fs/starfarer/api/combat/HullModEffect", "advanceInCampaign",
                "(Lcom/fs/starfarer/api/fleet/FleetMemberAPI;F)V", 1,
                CampaignMarketFleetTimeRuntime.FLEET_HULLMOD_SHIP, true);
        addExactly(probes, method, "com/fs/starfarer/campaign/BuffManager", "advance", "(F)V", 1,
                CampaignMarketFleetTimeRuntime.FLEET_BUFFS, false);
        addExactly(probes, method, "com/fs/starfarer/campaign/fleet/CampaignFleetView", "advance", "(F)V", 1,
                CampaignMarketFleetTimeRuntime.FLEET_VIEW, false);
        if (probes.stream().anyMatch(probe -> probe.call == null)) return null;
        for (Probe probe : probes) instrument(method, probe);
        CampaignMarketFleetTimeRuntime.fleetInstalled();
        return write(owner);
    }

    private static void addExactly(List<Probe> probes, MethodNode method, String owner, String name,
            String descriptor, int expected, int id, boolean classReceiver) {
        List<MethodInsnNode> calls = matching(method, owner, name, descriptor);
        if (calls.size() != expected) {
            probes.add(new Probe(null, id, classReceiver));
            return;
        }
        for (MethodInsnNode call : calls) probes.add(new Probe(call, id, classReceiver));
    }

    private static void instrument(MethodNode method, Probe probe) {
        MethodInsnNode call = probe.call;
        Type[] arguments = Type.getArgumentTypes(call.desc);
        int[] argumentLocals = new int[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            argumentLocals[index] = method.maxLocals;
            method.maxLocals += arguments[index].getSize();
        }
        int receiverLocal = call.getOpcode() == Opcodes.INVOKESTATIC ? -1 : method.maxLocals++;
        int startedLocal = method.maxLocals;
        method.maxLocals += 2;
        int throwableLocal = method.maxLocals++;

        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode continuation = new LabelNode();
        InsnList before = new InsnList();
        for (int index = arguments.length - 1; index >= 0; index--) {
            before.add(new VarInsnNode(arguments[index].getOpcode(Opcodes.ISTORE), argumentLocals[index]));
        }
        if (receiverLocal >= 0) before.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));
        if (probe.classReceiver) before.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        before.add(new LdcInsnNode(probe.id));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                probe.classReceiver ? "enterClass" : "enter",
                probe.classReceiver ? "(Ljava/lang/Object;I)J" : "(I)J", false));
        before.add(new VarInsnNode(Opcodes.LSTORE, startedLocal));
        before.add(start);
        if (receiverLocal >= 0) before.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        for (int index = 0; index < arguments.length; index++) {
            before.add(new VarInsnNode(arguments[index].getOpcode(Opcodes.ILOAD), argumentLocals[index]));
        }
        method.instructions.insertBefore(call, before);

        InsnList after = new InsnList();
        after.add(end);
        after.add(exit(probe, receiverLocal, startedLocal));
        after.add(new JumpInsnNode(Opcodes.GOTO, continuation));
        after.add(handler);
        after.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        after.add(exit(probe, receiverLocal, startedLocal));
        after.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        after.add(new InsnNode(Opcodes.ATHROW));
        after.add(continuation);
        method.instructions.insert(call, after);
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
    }

    private static InsnList exit(Probe probe, int receiverLocal, int startedLocal) {
        InsnList result = new InsnList();
        if (probe.classReceiver) result.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        result.add(new LdcInsnNode(probe.id));
        result.add(new VarInsnNode(Opcodes.LLOAD, startedLocal));
        result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                probe.classReceiver ? "exitClass" : "exit",
                probe.classReceiver ? "(Ljava/lang/Object;IJ)V" : "(IJ)V", false));
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

    private static List<MethodInsnNode> matching(MethodNode method, String owner, String name,
            String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name) && descriptor.equals(call.desc)) result.add(call);
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
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) result++;
        }
        return result;
    }

    private record Probe(MethodInsnNode call, int id, boolean classReceiver) {
    }
}

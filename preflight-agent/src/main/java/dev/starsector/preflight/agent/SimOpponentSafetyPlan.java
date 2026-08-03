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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Filters invalid merged sim-opponent ids at the exact refit-simulator consumption seam. */
final class SimOpponentSafetyPlan {
    static final String TARGET_CLASS = "com/fs/starfarer/coreui/refit/OOOo";
    static final String ORIGINAL_SHA256 =
            "b995be01a6700ce6a6b672f0fd7f4edb37214ff7a3e24fda0ae5f365729ab3f3";
    static final String SIMULATION_METHOD = "Oø0000";
    static final String SIMULATION_DESCRIPTOR = "()Lcom/fs/starfarer/title/C/OO0O;";
    static final String LAUNCH_METHOD = "new.null";
    static final String LAUNCH_DESCRIPTOR = "()V";

    private static final String SPEC_STORE = "com/fs/starfarer/loading/SpecStore";
    private static final String MISSION = "com/fs/starfarer/title/C/OO0O";
    private static final String COMBAT_ENGINE = "com/fs/starfarer/combat/CombatEngine";
    private static final String OPPONENTS_METHOD = "o00000";
    private static final String OPPONENTS_DESCRIPTOR = "()Ljava/util/List;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/SimOpponentSafetyRuntime";
    private static final String FILTER_DESCRIPTOR =
            "(Ljava/util/List;Ljava/lang/Class;)Ljava/util/List;";
    private static final String ADD_METHOD = "addToFleet";
    private static final String ADD_DESCRIPTOR =
            "(Lcom/fs/starfarer/api/mission/FleetSide;Ljava/lang/String;"
                    + "Lcom/fs/starfarer/api/fleet/FleetMemberType;Z)"
                    + "Lcom/fs/starfarer/api/fleet/FleetMemberAPI;";
    private static final int EXPECTED_CONSUMPTION_SITES = 2;
    private static final int EXPECTED_ADD_SITES = 4;

    private SimOpponentSafetyPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(SIMULATION_METHOD, SIMULATION_DESCRIPTOR)
                || !signature.hasMethod(LAUNCH_METHOD, LAUNCH_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode simulation = uniqueMethod(owner, SIMULATION_METHOD, SIMULATION_DESCRIPTOR);
        MethodNode launch = uniqueMethod(owner, LAUNCH_METHOD, LAUNCH_DESCRIPTOR);
        if (!TARGET_CLASS.equals(owner.name) || simulation == null || launch == null
                || (simulation.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0
                || (launch.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0
                || hasFilterCall(simulation)) {
            return null;
        }

        List<MethodInsnNode> consumers = new ArrayList<>();
        for (AbstractInsnNode instruction : simulation.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && SPEC_STORE.equals(call.owner)
                    && OPPONENTS_METHOD.equals(call.name)
                    && OPPONENTS_DESCRIPTOR.equals(call.desc)) {
                consumers.add(call);
            }
        }
        if (consumers.size() != EXPECTED_CONSUMPTION_SITES) {
            return null;
        }

        List<MethodInsnNode> adds = calls(
                simulation, MISSION, ADD_METHOD, ADD_DESCRIPTOR, Opcodes.INVOKEVIRTUAL);
        List<AbstractInsnNode> returns = opcodes(simulation, Opcodes.ARETURN);
        List<MethodInsnNode> loads = calls(
                launch, MISSION, "load", "()V", Opcodes.INVOKEVIRTUAL);
        List<MethodInsnNode> combatInitializations = calls(
                launch, COMBAT_ENGINE, "init", "()V", Opcodes.INVOKEVIRTUAL);
        if (adds.size() != EXPECTED_ADD_SITES || returns.size() != 1 || loads.size() != 1
                || combatInitializations.size() != 1) {
            return null;
        }
        for (MethodInsnNode consumer : consumers) {
            InsnList filter = new InsnList();
            filter.add(new LdcInsnNode(Type.getObjectType(SPEC_STORE)));
            filter.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RUNTIME, "filter", FILTER_DESCRIPTOR, false));
            simulation.instructions.insert(consumer, filter);
        }
        for (MethodInsnNode add : adds) {
            InsnList record = new InsnList();
            record.add(new InsnNode(Opcodes.DUP));
            record.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RUNTIME, "recordAdded", "(Ljava/lang/Object;)V", false));
            simulation.instructions.insert(add, record);
        }
        InsnList beforeLoad = new InsnList();
        beforeLoad.add(new InsnNode(Opcodes.DUP));
        launch.instructions.insertBefore(loads.get(0), beforeLoad);
        InsnList afterLoad = new InsnList();
        afterLoad.add(new InsnNode(Opcodes.ICONST_1));
        afterLoad.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "recordMission",
                "(Ljava/lang/Object;Z)V", false));
        launch.instructions.insert(loads.get(0), afterLoad);

        InsnList afterCombatInitialization = new InsnList();
        afterCombatInitialization.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, COMBAT_ENGINE, "getInstance",
                "()Lcom/fs/starfarer/combat/CombatEngine;", false));
        afterCombatInitialization.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "recordCombatEngine",
                "(Ljava/lang/Object;)V", false));
        launch.instructions.insert(combatInitializations.get(0), afterCombatInitialization);

        InsnList beforeReturn = new InsnList();
        beforeReturn.add(new InsnNode(Opcodes.DUP));
        beforeReturn.add(new InsnNode(Opcodes.ICONST_0));
        beforeReturn.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "recordMission",
                "(Ljava/lang/Object;Z)V", false));
        simulation.instructions.insertBefore(returns.get(0), beforeReturn);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        SimOpponentSafetyRuntime.installed();
        return writer.toByteArray();
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

    private static boolean hasFilterCall(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner) && "filter".equals(call.name)) {
                return true;
            }
        }
        return false;
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String descriptor, int opcode) {
        List<MethodInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == opcode
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                found.add(call);
            }
        }
        return found;
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

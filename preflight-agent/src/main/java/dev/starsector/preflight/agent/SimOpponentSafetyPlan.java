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

    private static final String SPEC_STORE = "com/fs/starfarer/loading/SpecStore";
    private static final String OPPONENTS_METHOD = "o00000";
    private static final String OPPONENTS_DESCRIPTOR = "()Ljava/util/List;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/SimOpponentSafetyRuntime";
    private static final String FILTER_DESCRIPTOR =
            "(Ljava/util/List;Ljava/lang/Class;)Ljava/util/List;";
    private static final int EXPECTED_CONSUMPTION_SITES = 2;

    private SimOpponentSafetyPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(SIMULATION_METHOD, SIMULATION_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode simulation = uniqueMethod(owner, SIMULATION_METHOD, SIMULATION_DESCRIPTOR);
        if (!TARGET_CLASS.equals(owner.name) || simulation == null
                || (simulation.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
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
        for (MethodInsnNode consumer : consumers) {
            InsnList filter = new InsnList();
            filter.add(new LdcInsnNode(Type.getObjectType(SPEC_STORE)));
            filter.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RUNTIME, "filter", FILTER_DESCRIPTOR, false));
            simulation.instructions.insert(consumer, filter);
        }

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
}

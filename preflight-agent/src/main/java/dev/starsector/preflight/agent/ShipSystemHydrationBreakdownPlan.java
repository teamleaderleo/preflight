package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Samples the repeated hull resolution inside the exact Windows ship-system loader. */
final class ShipSystemHydrationBreakdownPlan {
    static final String WINDOWS_ORIGINAL_SHA256 =
            "011125fae8e21c0c1618d50258e9cf4b2292f0179093b3659ddc4f9a2555a5d8";
    static final String METHOD = "new.super";
    static final String DESCRIPTOR = SpecStorePhasePlan.INIT_DESCRIPTOR;
    static final int SAMPLE_SLOT = 6;
    private static final String HULL_STORE = "com/fs/starfarer/loading/oO0O";
    private static final String HULL_LOOKUP = "super";
    private static final String HULL_LOOKUP_DESCRIPTOR =
            "(Ljava/lang/String;)Lcom/fs/starfarer/loading/specs/g;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/StartupPhaseRuntime";

    private ShipSystemHydrationBreakdownPlan() {
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!SpecStorePhasePlan.TARGET_CLASS.equals(signature.internalName())
                || !WINDOWS_ORIGINAL_SHA256.equals(signature.sha256())
                || !signature.hasMethod(METHOD, DESCRIPTOR)) return false;
        MethodNode method = uniqueMethod(owner);
        if (method == null || hasRuntimeCalls(method)) return false;
        List<MethodInsnNode> lookups = calls(method);
        if (lookups.size() != 1) return false;

        int tokenLocal = method.maxLocals;
        method.maxLocals += 2;
        MethodInsnNode lookup = lookups.get(0);
        InsnList before = new InsnList();
        before.add(new LdcInsnNode(SAMPLE_SLOT));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "sampledHotCallStart", "(I)J", false));
        before.add(new VarInsnNode(Opcodes.LSTORE, tokenLocal));
        method.instructions.insertBefore(lookup, before);

        InsnList after = new InsnList();
        after.add(new LdcInsnNode(SAMPLE_SLOT));
        after.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
        after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "sampledHotCallEnd", "(IJ)V", false));
        method.instructions.insert(lookup, after);
        return true;
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && HULL_STORE.equals(call.owner) && HULL_LOOKUP.equals(call.name)
                    && HULL_LOOKUP_DESCRIPTOR.equals(call.desc)) result.add(call);
        }
        return result;
    }

    private static MethodNode uniqueMethod(ClassNode owner) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (METHOD.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static boolean hasRuntimeCalls(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)
                    && ("sampledHotCallStart".equals(call.name)
                        || "sampledHotCallEnd".equals(call.name))) return true;
        }
        return false;
    }
}

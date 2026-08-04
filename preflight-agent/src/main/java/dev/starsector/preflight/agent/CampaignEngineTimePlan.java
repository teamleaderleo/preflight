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

/** Times reviewed call sites inside the exact vanilla campaign-engine advance method. */
final class CampaignEngineTimePlan {
    static final String TARGET_CLASS = "com/fs/starfarer/campaign/CampaignEngine";
    static final String ORIGINAL_SHA256 =
            "99cb7c6a7aa026ec3f2fe4439d66b7b8cd24e4068ca24ffae89eac7421cabf6d";
    static final String METHOD = "advance";
    static final String DESCRIPTOR = "(FLcom/fs/starfarer/util/super/B;)V";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/CampaignEngineTimeRuntime";
    private static final String FLOAT_VOID = "(F)V";
    private static final String LOCATION_ADVANCE =
            "(FLcom/fs/starfarer/util/super/B;)V";
    private static final String SCRIPT_OWNER = "com/fs/starfarer/api/EveryFrameScript";

    private static final List<CallProbe> PROBES = List.of(
            new CallProbe("com/fs/starfarer/campaign/comms/v2/IntelManager", "advance",
                    FLOAT_VOID, CampaignEngineTimeRuntime.INTEL, 1),
            new CallProbe("com/fs/starfarer/campaign/events/CampaignEventManager", "advance",
                    FLOAT_VOID, CampaignEngineTimeRuntime.EVENTS, 1),
            new CallProbe("com/fs/starfarer/rpg/ImportantPeople", "advance",
                    FLOAT_VOID, CampaignEngineTimeRuntime.IMPORTANT_PEOPLE, 1),
            new CallProbe("com/fs/starfarer/campaign/CampaignUIPersistentData", "advance",
                    FLOAT_VOID, CampaignEngineTimeRuntime.UI_DATA, 1),
            new CallProbe("com/fs/starfarer/campaign/econ/Economy", "advance",
                    FLOAT_VOID, CampaignEngineTimeRuntime.ECONOMY, 1),
            new CallProbe("com/fs/starfarer/campaign/rules/Memory", "advance",
                    FLOAT_VOID, CampaignEngineTimeRuntime.MEMORY, 2),
            new CallProbe("com/fs/starfarer/campaign/Faction", "advance",
                    FLOAT_VOID, CampaignEngineTimeRuntime.FACTIONS, 1),
            new CallProbe("com/fs/starfarer/campaign/Hyperspace", "advanceEvenIfPaused",
                    LOCATION_ADVANCE, CampaignEngineTimeRuntime.LOCATIONS, 2),
            new CallProbe("com/fs/starfarer/campaign/Hyperspace", "advance",
                    LOCATION_ADVANCE, CampaignEngineTimeRuntime.LOCATIONS, 2),
            new CallProbe("com/fs/starfarer/campaign/BaseLocation", "advanceEvenIfPaused",
                    LOCATION_ADVANCE, CampaignEngineTimeRuntime.LOCATIONS, 3),
            new CallProbe("com/fs/starfarer/campaign/BaseLocation", "advance",
                    LOCATION_ADVANCE, CampaignEngineTimeRuntime.LOCATIONS, 3),
            new CallProbe("com/fs/starfarer/campaign/CampaignHelpManager", "advance",
                    FLOAT_VOID, CampaignEngineTimeRuntime.CAMPAIGN_HELP, 1));

    private CampaignEngineTimePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!CampaignEngineTimeRuntime.enabled()
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(METHOD, DESCRIPTOR)) return null;

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = unique(owner, METHOD, DESCRIPTOR);
        if (method == null || callsRuntime(method) != 0) return null;
        for (CallProbe probe : PROBES) {
            if (matching(method, probe).size() != probe.expectedCalls()) return null;
        }
        List<MethodInsnNode> scriptCalls = matching(
                method, new CallProbe(SCRIPT_OWNER, "advance", FLOAT_VOID, -1, 2));
        if (scriptCalls.size() != 2) return null;

        for (CallProbe probe : PROBES) {
            for (MethodInsnNode call : matching(method, probe)) {
                instrument(method, call, probe.id(), false);
            }
        }
        for (MethodInsnNode call : scriptCalls) instrument(method, call, -1, true);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        CampaignEngineTimeRuntime.installed();
        return writer.toByteArray();
    }

    static List<CallProbe> probes() {
        return PROBES;
    }

    private static void instrument(
            MethodNode method, MethodInsnNode call, int id, boolean script) {
        boolean location = LOCATION_ADVANCE.equals(call.desc);
        int receiverLocal = method.maxLocals++;
        int amountLocal = method.maxLocals++;
        int contextLocal = location ? method.maxLocals++ : -1;
        int startedLocal = method.maxLocals;
        method.maxLocals += 2;
        int throwableLocal = method.maxLocals++;

        LabelNode protectedStart = new LabelNode();
        LabelNode protectedEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode continuation = new LabelNode();

        InsnList before = new InsnList();
        if (location) before.add(new VarInsnNode(Opcodes.ASTORE, contextLocal));
        before.add(new VarInsnNode(Opcodes.FSTORE, amountLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));
        if (script) {
            before.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
            before.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RUNTIME, "enterScript", "(Ljava/lang/Object;)J", false));
        } else {
            before.add(new LdcInsnNode(id));
            before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "enter", "(I)J", false));
        }
        before.add(new VarInsnNode(Opcodes.LSTORE, startedLocal));
        before.add(protectedStart);
        before.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        before.add(new VarInsnNode(Opcodes.FLOAD, amountLocal));
        if (location) before.add(new VarInsnNode(Opcodes.ALOAD, contextLocal));
        method.instructions.insertBefore(call, before);

        InsnList after = new InsnList();
        after.add(protectedEnd);
        after.add(exit(id, script, receiverLocal, startedLocal));
        after.add(new JumpInsnNode(Opcodes.GOTO, continuation));
        after.add(handler);
        after.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        after.add(exit(id, script, receiverLocal, startedLocal));
        after.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        after.add(new InsnNode(Opcodes.ATHROW));
        after.add(continuation);
        method.instructions.insert(call, after);
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                protectedStart, protectedEnd, handler, "java/lang/Throwable"));
    }

    private static InsnList exit(int id, boolean script, int receiverLocal, int startedLocal) {
        InsnList exit = new InsnList();
        if (script) {
            exit.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
            exit.add(new VarInsnNode(Opcodes.LLOAD, startedLocal));
            exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exitScript",
                    "(Ljava/lang/Object;J)V", false));
        } else {
            exit.add(new LdcInsnNode(id));
            exit.add(new VarInsnNode(Opcodes.LLOAD, startedLocal));
            exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exit", "(IJ)V", false));
        }
        return exit;
    }

    private static List<MethodInsnNode> matching(MethodNode method, CallProbe probe) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && probe.owner().equals(call.owner)
                    && probe.name().equals(call.name)
                    && probe.descriptor().equals(call.desc)) result.add(call);
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

    record CallProbe(String owner, String name, String descriptor, int id, int expectedCalls) {
    }
}

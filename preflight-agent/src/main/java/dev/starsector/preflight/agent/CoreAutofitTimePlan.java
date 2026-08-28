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
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Times reviewed semantic regions and exact helper families in vanilla autofit. */
final class CoreAutofitTimePlan {
    static final String TARGET_CLASS =
            "com/fs/starfarer/api/plugins/impl/CoreAutofitPlugin";
    static final String ORIGINAL_SHA256 =
            "5ccef552d487617232057f17a5b009566179b53862aade7bee8aabccff703b5c";
    static final String METHOD = "doFit";
    static final String DESCRIPTOR =
            "(Lcom/fs/starfarer/api/combat/ShipVariantAPI;"
                    + "Lcom/fs/starfarer/api/combat/ShipVariantAPI;I"
                    + "Lcom/fs/starfarer/api/plugins/AutofitPlugin$AutofitPluginDelegate;)V";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/CoreAutofitTimeRuntime";
    private static final String VARIANT =
            "Lcom/fs/starfarer/api/combat/ShipVariantAPI;";
    private static final String DELEGATE =
            "Lcom/fs/starfarer/api/plugins/AutofitPlugin$AutofitPluginDelegate;";
    private static final String DELEGATE_OWNER =
            "com/fs/starfarer/api/plugins/AutofitPlugin$AutofitPluginDelegate";

    private static final List<LineBlock> BLOCKS = List.of(
            new LineBlock(271, 327, CoreAutofitTimeRuntime.SETUP_MODULES),
            new LineBlock(327, 342, CoreAutofitTimeRuntime.FIT_PREPARATION),
            new LineBlock(342, 375, CoreAutofitTimeRuntime.STRIP_OR_PRESERVE),
            new LineBlock(375, 404, CoreAutofitTimeRuntime.HULLMOD_SEED),
            new LineBlock(404, 418, CoreAutofitTimeRuntime.PRIMARY_FIT),
            new LineBlock(418, 443, CoreAutofitTimeRuntime.RANDOMIZE_REFIT),
            new LineBlock(443, 502, CoreAutofitTimeRuntime.FINALIZE_ORDNANCE),
            new LineBlock(502, 523, CoreAutofitTimeRuntime.PHASE_SPECIALIZATION),
            new LineBlock(523, 549, CoreAutofitTimeRuntime.WEAPON_GROUPS));

    private static final List<CallSpec> CALLS = List.of(
            new CallSpec(TARGET_CLASS, METHOD, DESCRIPTOR,
                    CoreAutofitTimeRuntime.MODULE_AUTOFIT, 1),
            new CallSpec(TARGET_CLASS, "stripWeapons", "(" + VARIANT + DELEGATE + ")V",
                    CoreAutofitTimeRuntime.STRIP_CALLS, 1),
            new CallSpec(TARGET_CLASS, "stripFighters", "(" + VARIANT + DELEGATE + ")V",
                    CoreAutofitTimeRuntime.STRIP_CALLS, 1),
            new CallSpec(TARGET_CLASS, "addHullmods",
                    "(" + VARIANT + DELEGATE + "[Ljava/lang/String;)I",
                    CoreAutofitTimeRuntime.HULLMOD_CALLS, 9),
            new CallSpec(TARGET_CLASS, "fitFighters",
                    "(" + VARIANT + VARIANT + "Z" + DELEGATE + ")V",
                    CoreAutofitTimeRuntime.FIGHTER_FIT_CALLS, 2),
            new CallSpec(TARGET_CLASS, "fitWeapons",
                    "(" + VARIANT + VARIANT + "Z" + DELEGATE + ")V",
                    CoreAutofitTimeRuntime.WEAPON_FIT_CALLS, 2),
            new CallSpec(TARGET_CLASS, "addRandomizedHullmodsPre",
                    "(" + VARIANT + DELEGATE + ")I",
                    CoreAutofitTimeRuntime.RANDOM_HULLMOD_CALLS, 1),
            new CallSpec(TARGET_CLASS, "addRandomizedHullmodsPost",
                    "(" + VARIANT + DELEGATE + ")I",
                    CoreAutofitTimeRuntime.RANDOM_HULLMOD_CALLS, 2),
            new CallSpec(TARGET_CLASS, "addVentsAndCaps",
                    "(" + VARIANT + VARIANT + "F)V",
                    CoreAutofitTimeRuntime.VENT_CAP_CALLS, 2),
            new CallSpec(TARGET_CLASS, "addExtraVentsAndCaps",
                    "(" + VARIANT + VARIANT + ")V",
                    CoreAutofitTimeRuntime.VENT_CAP_CALLS, 1),
            new CallSpec(TARGET_CLASS, "addExtraVents", "(" + VARIANT + ")V",
                    CoreAutofitTimeRuntime.VENT_CAP_CALLS, 3),
            new CallSpec(TARGET_CLASS, "addExtraCaps", "(" + VARIANT + ")V",
                    CoreAutofitTimeRuntime.VENT_CAP_CALLS, 3),
            new CallSpec(TARGET_CLASS, "addDistributor", "(" + VARIANT + DELEGATE + ")V",
                    CoreAutofitTimeRuntime.VENT_CAP_CALLS, 1),
            new CallSpec(TARGET_CLASS, "addCoil", "(" + VARIANT + DELEGATE + ")V",
                    CoreAutofitTimeRuntime.VENT_CAP_CALLS, 1),
            new CallSpec(TARGET_CLASS, "addModsWithSpareOPIfAny",
                    "(" + VARIANT + VARIANT + "Z" + DELEGATE + ")V",
                    CoreAutofitTimeRuntime.SPARE_OP_SMOD_CALLS, 1),
            new CallSpec(TARGET_CLASS, "convertToSMods", "(" + VARIANT + "I)I",
                    CoreAutofitTimeRuntime.SPARE_OP_SMOD_CALLS, 2),
            new CallSpec(DELEGATE_OWNER, "syncUIWithVariant", "(" + VARIANT + ")V",
                    CoreAutofitTimeRuntime.SYNC_UI_CALLS, 2));

    private CoreAutofitTimePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!CoreAutofitTimeRuntime.enabled() || signature.majorVersion() != 61
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())) return null;

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = unique(owner, METHOD, DESCRIPTOR);
        if (method == null || callsRuntime(method) != 0) return null;

        List<RegionMatch> regions = new ArrayList<>();
        for (LineBlock block : BLOCKS) {
            LineNumberNode start = uniqueLine(method, block.startLine);
            LineNumberNode end = uniqueLine(method, block.endLine);
            if (start == null || end == null || !before(start, end)) return null;
            regions.add(new RegionMatch(start, end, block.phase));
        }
        LineNumberNode finalStart = uniqueLine(method, 549);
        InsnNode methodReturn = uniqueReturn(method);
        if (finalStart == null || methodReturn == null || !before(finalStart, methodReturn)) {
            return null;
        }

        List<CallMatch> calls = new ArrayList<>();
        for (CallSpec spec : CALLS) {
            List<MethodInsnNode> matches = matchingCalls(method, spec);
            if (matches.size() != spec.expectedCount) return null;
            for (MethodInsnNode call : matches) calls.add(new CallMatch(call, spec.phase));
        }

        int nextLocal = method.maxLocals;
        int totalStartedLocal = nextLocal;
        nextLocal += 2;
        for (RegionMatch region : regions) {
            weaveRegion(method, region.start, region.end, region.phase, nextLocal);
            nextLocal += 2;
        }
        weaveRegion(method, finalStart, methodReturn,
                CoreAutofitTimeRuntime.FINAL_SYNC, nextLocal);
        nextLocal += 2;
        for (CallMatch match : calls) {
            nextLocal = weaveCall(method, match.call, match.phase, nextLocal);
        }
        // Weave the outer timer last so its exit follows the final semantic-region exit.
        weaveTotal(method, methodReturn, totalStartedLocal);
        method.maxLocals = nextLocal;

        CoreAutofitTimeRuntime.installed();
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static void weaveTotal(MethodNode method, InsnNode methodReturn, int startedLocal) {
        method.instructions.insertBefore(method.instructions.getFirst(),
                enterInstructions(CoreAutofitTimeRuntime.TOTAL, startedLocal));
        method.instructions.insertBefore(methodReturn,
                exitInstructions(CoreAutofitTimeRuntime.TOTAL, startedLocal));
    }

    private static void weaveRegion(
            MethodNode method,
            AbstractInsnNode startBoundary,
            AbstractInsnNode endBoundary,
            int phase,
            int startedLocal) {
        method.instructions.insertBefore(startBoundary, enterInstructions(phase, startedLocal));
        method.instructions.insertBefore(endBoundary, exitInstructions(phase, startedLocal));
    }

    private static int weaveCall(
            MethodNode method, MethodInsnNode call, int phase, int nextLocal) {
        int startedLocal = nextLocal;
        nextLocal += 2;
        Type returnType = Type.getReturnType(call.desc);
        int resultLocal = -1;
        if (returnType != Type.VOID_TYPE) {
            resultLocal = nextLocal;
            nextLocal += returnType.getSize();
        }
        method.instructions.insertBefore(call, enterInstructions(phase, startedLocal));
        InsnList finish = new InsnList();
        if (resultLocal >= 0) {
            finish.add(new VarInsnNode(returnType.getOpcode(Opcodes.ISTORE), resultLocal));
        }
        finish.add(exitInstructions(phase, startedLocal));
        if (resultLocal >= 0) {
            finish.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), resultLocal));
        }
        method.instructions.insert(call, finish);
        return nextLocal;
    }

    private static InsnList enterInstructions(int phase, int startedLocal) {
        InsnList result = new InsnList();
        result.add(new LdcInsnNode(phase));
        result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "enter", "(I)J", false));
        result.add(new VarInsnNode(Opcodes.LSTORE, startedLocal));
        return result;
    }

    private static InsnList exitInstructions(int phase, int startedLocal) {
        InsnList result = new InsnList();
        result.add(new VarInsnNode(Opcodes.ALOAD, 0));
        result.add(new LdcInsnNode(phase));
        result.add(new VarInsnNode(Opcodes.LLOAD, startedLocal));
        result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exit",
                "(Ljava/lang/Object;IJ)V", false));
        return result;
    }

    private static List<MethodInsnNode> matchingCalls(MethodNode method, CallSpec spec) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && spec.owner.equals(call.owner)
                    && spec.name.equals(call.name)
                    && spec.descriptor.equals(call.desc)) result.add(call);
        }
        return result;
    }

    private static LineNumberNode uniqueLine(MethodNode method, int line) {
        LineNumberNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof LineNumberNode candidate) || candidate.line != line) continue;
            if (result != null) return null;
            result = candidate;
        }
        return result;
    }

    private static InsnNode uniqueReturn(MethodNode method) {
        InsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof InsnNode candidate)
                    || candidate.getOpcode() != Opcodes.RETURN) continue;
            if (result != null) return null;
            result = candidate;
        }
        return result;
    }

    private static boolean before(AbstractInsnNode left, AbstractInsnNode right) {
        for (AbstractInsnNode instruction = left; instruction != null;
                instruction = instruction.getNext()) {
            if (instruction == right) return true;
        }
        return false;
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
            if (!name.equals(method.name) || !descriptor.equals(method.desc)) continue;
            if (result != null) return null;
            result = method;
        }
        return result;
    }

    private record LineBlock(int startLine, int endLine, int phase) {
    }

    private record RegionMatch(LineNumberNode start, LineNumberNode end, int phase) {
    }

    private record CallSpec(
            String owner, String name, String descriptor, int phase, int expectedCount) {
    }

    private record CallMatch(MethodInsnNode call, int phase) {
    }
}

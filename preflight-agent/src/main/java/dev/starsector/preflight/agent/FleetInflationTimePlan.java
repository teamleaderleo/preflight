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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Times reviewed semantic regions in exact vanilla {@code DefaultFleetInflater.inflate(...)}. */
final class FleetInflationTimePlan {
    static final String TARGET_CLASS =
            "com/fs/starfarer/api/impl/campaign/fleets/DefaultFleetInflater";
    static final String ORIGINAL_SHA256 =
            "80a07787e75edbdd5ae0b80da023aeaa59f43d08263de10b77af4595452b08ae";
    static final String METHOD = "inflate";
    static final String DESCRIPTOR =
            "(Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;)V";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/FleetInflationTimeRuntime";
    private static final String MEMBER = "com/fs/starfarer/api/fleet/FleetMemberAPI";
    private static final String AUTOFIT =
            "com/fs/starfarer/api/plugins/impl/CoreAutofitPlugin";
    private static final String AUTOFIT_DESCRIPTOR =
            "(Lcom/fs/starfarer/api/combat/ShipVariantAPI;"
                    + "Lcom/fs/starfarer/api/combat/ShipVariantAPI;I"
                    + "Lcom/fs/starfarer/api/plugins/AutofitPlugin$AutofitPluginDelegate;)V";
    private static final String FLEET_DATA =
            "com/fs/starfarer/api/campaign/FleetDataAPI";

    private static final List<LineBlock> BLOCKS = List.of(
            new LineBlock(226, 255, FleetInflationTimeRuntime.INITIAL_SETUP),
            new LineBlock(255, 271, FleetInflationTimeRuntime.HULLMOD_POOL),
            new LineBlock(271, 299, FleetInflationTimeRuntime.WEAPON_POOL),
            new LineBlock(299, 326, FleetInflationTimeRuntime.FIGHTER_POOL),
            new LineBlock(326, 520, FleetInflationTimeRuntime.MEMBER_WORK));

    private FleetInflationTimePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!FleetInflationTimeRuntime.enabled() || signature.majorVersion() != 61
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

        LineNumberNode dmodStart = uniqueLine(method, 510);
        LineNumberNode dmodEnd = firstLineAfter(dmodStart, 332);
        LineNumberNode finalStart = uniqueLine(method, 520);
        InsnNode methodReturn = uniqueReturn(method);
        MethodInsnNode autofit = uniqueCall(
                method, AUTOFIT, "doFit", AUTOFIT_DESCRIPTOR);
        MethodInsnNode sync = uniqueCall(method, FLEET_DATA, "syncIfNeeded", "()V");
        VarInsnNode memberStore = uniqueMemberStore(method);
        if (dmodStart == null || dmodEnd == null || finalStart == null || methodReturn == null
                || autofit == null || sync == null || memberStore == null
                || !before(dmodStart, dmodEnd) || !before(finalStart, methodReturn)) return null;

        int nextLocal = method.maxLocals;
        weaveTotal(method, methodReturn, nextLocal);
        nextLocal += 2;
        for (RegionMatch region : regions) {
            weaveRegion(method, region.start, region.end, region.phase, nextLocal);
            nextLocal += 2;
        }
        weaveRegion(method, dmodStart, dmodEnd,
                FleetInflationTimeRuntime.DMOD_WORK, nextLocal);
        nextLocal += 2;
        weaveRegion(method, finalStart, methodReturn,
                FleetInflationTimeRuntime.FINAL_SYNC, nextLocal);
        nextLocal += 2;
        weaveVoidCall(method, autofit, FleetInflationTimeRuntime.AUTOFIT, nextLocal);
        nextLocal += 2;
        weaveVoidCall(method, sync, FleetInflationTimeRuntime.SYNC_CALL, nextLocal);
        nextLocal += 2;
        weaveMemberVisit(method, memberStore);
        method.maxLocals = nextLocal;

        FleetInflationTimeRuntime.installed();
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static void weaveTotal(MethodNode method, InsnNode methodReturn, int startedLocal) {
        InsnList start = enterInstructions(FleetInflationTimeRuntime.TOTAL, startedLocal);
        method.instructions.insertBefore(method.instructions.getFirst(), start);
        method.instructions.insertBefore(methodReturn,
                exitInstructions(FleetInflationTimeRuntime.TOTAL, startedLocal));
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

    private static void weaveVoidCall(
            MethodNode method, MethodInsnNode call, int phase, int startedLocal) {
        method.instructions.insertBefore(call, enterInstructions(phase, startedLocal));
        method.instructions.insert(call, exitInstructions(phase, startedLocal));
    }

    private static void weaveMemberVisit(MethodNode method, VarInsnNode memberStore) {
        method.instructions.insert(memberStore, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "memberVisited", "()V", false));
    }

    private static InsnList enterInstructions(int phase, int startedLocal) {
        InsnList start = new InsnList();
        start.add(new LdcInsnNode(phase));
        start.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "enter", "(I)J", false));
        start.add(new VarInsnNode(Opcodes.LSTORE, startedLocal));
        return start;
    }

    private static InsnList exitInstructions(int phase, int startedLocal) {
        InsnList finish = new InsnList();
        finish.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finish.add(new LdcInsnNode(phase));
        finish.add(new VarInsnNode(Opcodes.LLOAD, startedLocal));
        finish.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "exit", "(Ljava/lang/Object;IJ)V", false));
        return finish;
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String descriptor) {
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !owner.equals(call.owner) || !name.equals(call.name)
                    || !descriptor.equals(call.desc)) continue;
            if (result != null) return null;
            result = call;
        }
        return result;
    }

    private static VarInsnNode uniqueMemberStore(MethodNode method) {
        VarInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof TypeInsnNode cast)
                    || cast.getOpcode() != Opcodes.CHECKCAST || !MEMBER.equals(cast.desc)) continue;
            AbstractInsnNode previous = previousMeaningful(cast);
            AbstractInsnNode next = nextMeaningful(cast);
            if (!(previous instanceof MethodInsnNode call)
                    || !"java/util/Iterator".equals(call.owner) || !"next".equals(call.name)
                    || !"()Ljava/lang/Object;".equals(call.desc)
                    || !(next instanceof VarInsnNode store)
                    || store.getOpcode() != Opcodes.ASTORE) continue;
            if (result != null) return null;
            result = store;
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

    private static LineNumberNode firstLineAfter(AbstractInsnNode start, int line) {
        if (start == null) return null;
        for (AbstractInsnNode instruction = start.getNext(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof LineNumberNode candidate && candidate.line == line) {
                return candidate;
            }
        }
        return null;
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

    private static AbstractInsnNode nextMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getNext();
        while (current != null && (current.getType() == AbstractInsnNode.LABEL
                || current.getType() == AbstractInsnNode.LINE
                || current.getType() == AbstractInsnNode.FRAME)) current = current.getNext();
        return current;
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
}

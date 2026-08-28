package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Times reviewed semantic regions in exact vanilla {@code TacticalModule.advance(float)}. */
final class TacticalFleetAiTimePlan {
    static final String TARGET_CLASS = "com/fs/starfarer/campaign/ai/TacticalModule";
    static final String ORIGINAL_SHA256 =
            "53d6b876055d44a1dd97c9bf66561d974e102116c818aac654baf5ba1d70531c";
    static final String METHOD = "advance";
    static final String DESCRIPTOR = "(F)V";
    static final String NEARBY_METHOD = "hasEnoughStuffAround";
    static final String NEARBY_DESCRIPTOR =
            "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;FZZZ"
                    + "Lcom/fs/starfarer/api/campaign/BattleAPI;)Z";

    private static final String PROFILER = "com/fs/profiler/Profiler";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/TacticalFleetAiTimeRuntime";
    private static final String REPOSITORY = "com/fs/util/container/repo/ObjectRepository";
    private static final String CAMPAIGN_FLEET =
            "com/fs/starfarer/campaign/fleet/CampaignFleet";
    private static final String CAMPAIGN_FLEET_API =
            "com/fs/starfarer/api/campaign/CampaignFleetAPI";
    private static final String CAMPAIGN_FLEET_AI =
            "com/fs/starfarer/api/campaign/ai/CampaignFleetAIAPI";
    private static final String COMPUTE_STRENGTH_DESCRIPTOR =
            "(L" + CAMPAIGN_FLEET_API + ";Z)F";

    private static final List<Block> BLOCKS = List.of(
            new Block("Every frame stuff", TacticalFleetAiTimeRuntime.EVERY_FRAME),
            new Block("Updating avoid list", TacticalFleetAiTimeRuntime.AVOID_LIST),
            new Block("Looking at other fleets", TacticalFleetAiTimeRuntime.OTHER_FLEETS),
            new Block("Picking encounter option", TacticalFleetAiTimeRuntime.ENCOUNTER_OPTION),
            new Block("Every frame stuff, post", TacticalFleetAiTimeRuntime.POST_SCAN),
            new Block("Checking visibility level", TacticalFleetAiTimeRuntime.VISIBILITY));

    private static final List<CallGroup> DECISION_CALLS = List.of(
            new CallGroup(TacticalFleetAiTimeRuntime.HOSTILITY, 4, List.of(
                    new CallSite(CAMPAIGN_FLEET_AI, "isHostileTo",
                            "(L" + CAMPAIGN_FLEET_API + ";)Z"),
                    new CallSite(CAMPAIGN_FLEET, "isHostileTo",
                            "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;)Z"),
                    new CallSite(TARGET_CLASS, "isHostileTo",
                            "(L" + CAMPAIGN_FLEET_API + ";Z)Z"))),
            new CallGroup(TacticalFleetAiTimeRuntime.PURSUIT, 3, List.of(
                    new CallSite(TARGET_CLASS, "isOkToPursue",
                            "(L" + CAMPAIGN_FLEET + ";)Z"))),
            new CallGroup(TacticalFleetAiTimeRuntime.BATTLE_JOIN, 2, List.of(
                    new CallSite(TARGET_CLASS, "wantsToJoin",
                            "(Lcom/fs/starfarer/api/campaign/BattleAPI;Z)Z"))),
            new CallGroup(TacticalFleetAiTimeRuntime.NEARBY_FLEETS, 1, List.of(
                    new CallSite(TARGET_CLASS, "hasEnoughStuffAround",
                            NEARBY_DESCRIPTOR))));

    private TacticalFleetAiTimePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TacticalFleetAiTimeRuntime.enabled() || signature.majorVersion() != 61
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())) return null;
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = unique(owner, METHOD, DESCRIPTOR);
        MethodNode nearbyMethod = unique(owner, NEARBY_METHOD, NEARBY_DESCRIPTOR);
        if (method == null || nearbyMethod == null
                || callsRuntime(method) != 0 || callsRuntime(nearbyMethod) != 0) return null;

        List<BlockMatch> matches = new ArrayList<>();
        for (Block block : BLOCKS) {
            MethodInsnNode begin = uniqueProfilerBegin(method, block.label);
            MethodInsnNode end = matchingProfilerEnd(begin);
            if (begin == null || end == null) return null;
            matches.add(new BlockMatch(begin, end, block.phase));
        }
        MethodInsnNode fleetList = uniqueCall(
                method, REPOSITORY, "getList", "(Ljava/lang/Class;)Ljava/util/List;");
        if (fleetList == null) return null;
        BlockMatch otherFleets = matches.stream()
                .filter(match -> match.phase == TacticalFleetAiTimeRuntime.OTHER_FLEETS)
                .findFirst().orElse(null);
        VarInsnNode candidateStore = uniqueCandidateStore(otherFleets);
        if (candidateStore == null) return null;
        List<DecisionMatch> decisions = new ArrayList<>();
        for (CallGroup group : DECISION_CALLS) {
            List<MethodInsnNode> calls = matchingCalls(otherFleets, group);
            if (calls.size() != group.expectedCalls) return null;
            for (MethodInsnNode call : calls) decisions.add(new DecisionMatch(call, group.phase));
        }
        MethodInsnNode inflation = uniqueCall(
                nearbyMethod, CAMPAIGN_FLEET, "inflateIfNeeded", "()V");
        MethodInsnNode nearbyFleetList = uniqueCall(
                nearbyMethod, REPOSITORY, "getList", "(Ljava/lang/Class;)Ljava/util/List;");
        List<MethodInsnNode> strengthCalls = matchingCalls(
                nearbyMethod, TARGET_CLASS, "computeFleetStrength", COMPUTE_STRENGTH_DESCRIPTOR);
        VarInsnNode nearbyCandidateStore = uniqueCandidateStore(nearbyMethod);
        if (inflation == null || nearbyFleetList == null || strengthCalls.size() != 5
                || nearbyCandidateStore == null) return null;

        int nextLocal = method.maxLocals;
        for (BlockMatch match : matches) {
            weaveBlock(method, match, nextLocal);
            nextLocal += 2;
        }
        weaveFleetListCall(method, fleetList, nextLocal);
        nextLocal += 5;
        weaveCandidateVisit(method, candidateStore);
        for (DecisionMatch decision : decisions) {
            weaveBooleanCall(method, decision, nextLocal);
            nextLocal += 3;
        }
        method.maxLocals = nextLocal;

        int nearbyNextLocal = nearbyMethod.maxLocals;
        weaveNearbyMode(nearbyMethod);
        weaveVoidCall(nearbyMethod, inflation,
                TacticalFleetAiTimeRuntime.FLEET_INFLATION, nearbyNextLocal);
        nearbyNextLocal += 2;
        weaveReferenceCall(nearbyMethod, nearbyFleetList,
                TacticalFleetAiTimeRuntime.NEARBY_FLEET_LIST, nearbyNextLocal);
        nearbyNextLocal += 3;
        for (MethodInsnNode strengthCall : strengthCalls) {
            weaveFloatCall(nearbyMethod, strengthCall,
                    TacticalFleetAiTimeRuntime.FLEET_STRENGTH, nearbyNextLocal);
            nearbyNextLocal += 3;
        }
        weaveNearbyCandidateVisit(nearbyMethod, nearbyCandidateStore);
        nearbyMethod.maxLocals = nearbyNextLocal;

        TacticalFleetAiTimeRuntime.installed();
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static void weaveBlock(MethodNode method, BlockMatch match, int startedLocal) {
        InsnList start = new InsnList();
        start.add(new LdcInsnNode(match.phase));
        start.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "enter", "(I)J", false));
        start.add(new VarInsnNode(Opcodes.LSTORE, startedLocal));
        method.instructions.insert(match.begin, start);

        InsnList finish = new InsnList();
        finish.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finish.add(new LdcInsnNode(match.phase));
        finish.add(new VarInsnNode(Opcodes.LLOAD, startedLocal));
        finish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exit",
                "(Ljava/lang/Object;IJ)V", false));
        method.instructions.insertBefore(match.end, finish);
    }

    private static void weaveFleetListCall(MethodNode method, MethodInsnNode call, int nextLocal) {
        int repositoryLocal = nextLocal;
        int classLocal = nextLocal + 1;
        int startedLocal = nextLocal + 2;
        int resultLocal = nextLocal + 4;

        InsnList start = new InsnList();
        start.add(new VarInsnNode(Opcodes.ASTORE, classLocal));
        start.add(new VarInsnNode(Opcodes.ASTORE, repositoryLocal));
        start.add(new LdcInsnNode(TacticalFleetAiTimeRuntime.FLEET_LIST));
        start.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "enter", "(I)J", false));
        start.add(new VarInsnNode(Opcodes.LSTORE, startedLocal));
        start.add(new VarInsnNode(Opcodes.ALOAD, repositoryLocal));
        start.add(new VarInsnNode(Opcodes.ALOAD, classLocal));
        method.instructions.insertBefore(call, start);

        InsnList finish = new InsnList();
        finish.add(new VarInsnNode(Opcodes.ASTORE, resultLocal));
        finish.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finish.add(new LdcInsnNode(TacticalFleetAiTimeRuntime.FLEET_LIST));
        finish.add(new VarInsnNode(Opcodes.LLOAD, startedLocal));
        finish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exit",
                "(Ljava/lang/Object;IJ)V", false));
        finish.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        method.instructions.insert(call, finish);
    }

    private static void weaveCandidateVisit(MethodNode method, VarInsnNode candidateStore) {
        method.instructions.insert(candidateStore, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "candidateVisited", "()V", false));
    }

    private static void weaveBooleanCall(
            MethodNode method, DecisionMatch match, int startedLocal) {
        int resultLocal = startedLocal + 2;
        InsnList start = new InsnList();
        start.add(new LdcInsnNode(match.phase));
        start.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "enter", "(I)J", false));
        start.add(new VarInsnNode(Opcodes.LSTORE, startedLocal));
        method.instructions.insertBefore(match.call, start);

        InsnList finish = new InsnList();
        finish.add(new VarInsnNode(Opcodes.ISTORE, resultLocal));
        finish.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finish.add(new LdcInsnNode(match.phase));
        finish.add(new VarInsnNode(Opcodes.LLOAD, startedLocal));
        finish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exit",
                "(Ljava/lang/Object;IJ)V", false));
        finish.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
        method.instructions.insert(match.call, finish);
    }

    private static void weaveNearbyMode(MethodNode method) {
        InsnList marker = new InsnList();
        marker.add(new VarInsnNode(Opcodes.ILOAD, 5));
        marker.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "nearbyMode", "(Z)V", false));
        method.instructions.insert(marker);
    }

    private static void weaveVoidCall(
            MethodNode method, MethodInsnNode call, int phase, int startedLocal) {
        weaveCallStart(method, call, phase, startedLocal);
        method.instructions.insert(call, exitInstructions(phase, startedLocal));
    }

    private static void weaveReferenceCall(
            MethodNode method, MethodInsnNode call, int phase, int startedLocal) {
        int resultLocal = startedLocal + 2;
        weaveCallStart(method, call, phase, startedLocal);
        InsnList finish = new InsnList();
        finish.add(new VarInsnNode(Opcodes.ASTORE, resultLocal));
        finish.add(exitInstructions(phase, startedLocal));
        finish.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        method.instructions.insert(call, finish);
    }

    private static void weaveFloatCall(
            MethodNode method, MethodInsnNode call, int phase, int startedLocal) {
        int resultLocal = startedLocal + 2;
        weaveCallStart(method, call, phase, startedLocal);
        InsnList finish = new InsnList();
        finish.add(new VarInsnNode(Opcodes.FSTORE, resultLocal));
        finish.add(exitInstructions(phase, startedLocal));
        finish.add(new VarInsnNode(Opcodes.FLOAD, resultLocal));
        method.instructions.insert(call, finish);
    }

    private static void weaveCallStart(
            MethodNode method, MethodInsnNode call, int phase, int startedLocal) {
        InsnList start = new InsnList();
        start.add(new LdcInsnNode(phase));
        start.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "enter", "(I)J", false));
        start.add(new VarInsnNode(Opcodes.LSTORE, startedLocal));
        method.instructions.insertBefore(call, start);
    }

    private static InsnList exitInstructions(int phase, int startedLocal) {
        InsnList finish = new InsnList();
        finish.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finish.add(new LdcInsnNode(phase));
        finish.add(new VarInsnNode(Opcodes.LLOAD, startedLocal));
        finish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exit",
                "(Ljava/lang/Object;IJ)V", false));
        return finish;
    }

    private static void weaveNearbyCandidateVisit(
            MethodNode method, VarInsnNode candidateStore) {
        method.instructions.insert(candidateStore, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "nearbyCandidateVisited", "()V", false));
    }

    private static MethodInsnNode uniqueProfilerBegin(MethodNode method, String label) {
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof LdcInsnNode text) || !label.equals(text.cst)) continue;
            AbstractInsnNode next = nextMeaningful(instruction);
            if (!(next instanceof MethodInsnNode call) || !isProfilerBegin(call)) continue;
            if (result != null) return null;
            result = call;
        }
        return result;
    }

    private static MethodInsnNode matchingProfilerEnd(MethodInsnNode begin) {
        if (begin == null) return null;
        int depth = 1;
        for (AbstractInsnNode instruction = begin.getNext(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call) || !PROFILER.equals(call.owner)) continue;
            if (isProfilerBegin(call)) {
                depth++;
            } else if (call.getOpcode() == Opcodes.INVOKESTATIC
                    && "o00000".equals(call.name) && "()V".equals(call.desc)) {
                depth--;
                if (depth == 0) return call;
            }
        }
        return null;
    }

    private static boolean isProfilerBegin(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC && PROFILER.equals(call.owner)
                && "new".equals(call.name) && "(Ljava/lang/String;)V".equals(call.desc);
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String descriptor) {
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !owner.equals(call.owner)
                    || !name.equals(call.name) || !descriptor.equals(call.desc)) continue;
            if (result != null) return null;
            result = call;
        }
        return result;
    }

    private static List<MethodInsnNode> matchingCalls(BlockMatch boundary, CallGroup group) {
        List<MethodInsnNode> result = new ArrayList<>();
        if (boundary == null) return result;
        for (AbstractInsnNode instruction = boundary.begin.getNext();
                instruction != null && instruction != boundary.end;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (group.calls.stream().anyMatch(candidate -> candidate.matches(call))) result.add(call);
        }
        return result;
    }

    private static List<MethodInsnNode> matchingCalls(
            MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name) && descriptor.equals(call.desc)) result.add(call);
        }
        return result;
    }

    private static VarInsnNode uniqueCandidateStore(BlockMatch boundary) {
        if (boundary == null) return null;
        VarInsnNode result = null;
        for (AbstractInsnNode instruction = boundary.begin.getNext();
                instruction != null && instruction != boundary.end;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof TypeInsnNode cast)
                    || cast.getOpcode() != Opcodes.CHECKCAST
                    || !CAMPAIGN_FLEET.equals(cast.desc)) continue;
            AbstractInsnNode previous = previousMeaningful(cast);
            AbstractInsnNode next = nextMeaningful(cast);
            if (!(previous instanceof MethodInsnNode call)
                    || !"java/util/Iterator".equals(call.owner)
                    || !"next".equals(call.name)
                    || !"()Ljava/lang/Object;".equals(call.desc)
                    || !(next instanceof VarInsnNode store)
                    || store.getOpcode() != Opcodes.ASTORE) continue;
            if (result != null) return null;
            result = store;
        }
        return result;
    }

    private static VarInsnNode uniqueCandidateStore(MethodNode method) {
        VarInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof TypeInsnNode cast)
                    || cast.getOpcode() != Opcodes.CHECKCAST
                    || !CAMPAIGN_FLEET.equals(cast.desc)) continue;
            AbstractInsnNode previous = previousMeaningful(cast);
            AbstractInsnNode next = nextMeaningful(cast);
            if (!(previous instanceof MethodInsnNode call)
                    || !"java/util/Iterator".equals(call.owner)
                    || !"next".equals(call.name)
                    || !"()Ljava/lang/Object;".equals(call.desc)
                    || !(next instanceof VarInsnNode store)
                    || store.getOpcode() != Opcodes.ASTORE) continue;
            if (result != null) return null;
            result = store;
        }
        return result;
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
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (result != null) return null;
                result = method;
            }
        }
        return result;
    }

    private record Block(String label, int phase) {
    }

    private record BlockMatch(MethodInsnNode begin, MethodInsnNode end, int phase) {
    }

    private record CallSite(String owner, String name, String descriptor) {
        boolean matches(MethodInsnNode call) {
            return owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc);
        }
    }

    private record CallGroup(int phase, int expectedCalls, List<CallSite> calls) {
    }

    private record DecisionMatch(MethodInsnNode call, int phase) {
    }
}

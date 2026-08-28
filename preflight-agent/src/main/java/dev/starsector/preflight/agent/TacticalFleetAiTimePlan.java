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
import org.objectweb.asm.tree.VarInsnNode;

/** Times reviewed semantic regions in exact vanilla {@code TacticalModule.advance(float)}. */
final class TacticalFleetAiTimePlan {
    static final String TARGET_CLASS = "com/fs/starfarer/campaign/ai/TacticalModule";
    static final String ORIGINAL_SHA256 =
            "53d6b876055d44a1dd97c9bf66561d974e102116c818aac654baf5ba1d70531c";
    static final String METHOD = "advance";
    static final String DESCRIPTOR = "(F)V";

    private static final String PROFILER = "com/fs/profiler/Profiler";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/TacticalFleetAiTimeRuntime";
    private static final String REPOSITORY = "com/fs/util/container/repo/ObjectRepository";

    private static final List<Block> BLOCKS = List.of(
            new Block("Every frame stuff", TacticalFleetAiTimeRuntime.EVERY_FRAME),
            new Block("Updating avoid list", TacticalFleetAiTimeRuntime.AVOID_LIST),
            new Block("Looking at other fleets", TacticalFleetAiTimeRuntime.OTHER_FLEETS),
            new Block("Picking encounter option", TacticalFleetAiTimeRuntime.ENCOUNTER_OPTION),
            new Block("Every frame stuff, post", TacticalFleetAiTimeRuntime.POST_SCAN));

    private TacticalFleetAiTimePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TacticalFleetAiTimeRuntime.enabled() || signature.majorVersion() != 61
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())) return null;
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = unique(owner, METHOD, DESCRIPTOR);
        if (method == null || callsRuntime(method) != 0) return null;

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

        int nextLocal = method.maxLocals;
        for (BlockMatch match : matches) {
            weaveBlock(method, match, nextLocal);
            nextLocal += 2;
        }
        weaveFleetListCall(method, fleetList, nextLocal);
        method.maxLocals = nextLocal + 5;

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

    private static AbstractInsnNode nextMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getNext();
        while (current != null && (current.getType() == AbstractInsnNode.LABEL
                || current.getType() == AbstractInsnNode.LINE
                || current.getType() == AbstractInsnNode.FRAME)) current = current.getNext();
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
}

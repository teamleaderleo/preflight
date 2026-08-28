package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Times reviewed semantic regions in Nexerelin's exact economy-info cache rebuild. */
final class NexEconomyInfoTimePlan {
    static final String TARGET_CLASS = "exerelin/campaign/econ/EconomyInfoHelper";
    static final String ORIGINAL_SHA256 =
            "ac5bab8ab5ff887b6ec3bda9ced6db7d2d68051773ada6a4aed826d2a6343673";
    static final String SOURCE_SHA256 =
            "3d3bb30c44eec9060a7777317af519dd695a1aa31d75f478036fc338870b3b71";
    static final String METHOD = "collectEconomicData";
    static final String DESCRIPTOR = "(Z)V";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/NexEconomyInfoTimeRuntime";

    private static final List<LineBlock> BLOCKS = List.of(
            new LineBlock(122, 134, NexEconomyInfoTimeRuntime.CACHE_RESET),
            new LineBlock(134, 140, NexEconomyInfoTimeRuntime.MARKET_SNAPSHOT),
            new LineBlock(153, 196, NexEconomyInfoTimeRuntime.PRODUCER_PASS),
            new LineBlock(196, 228, NexEconomyInfoTimeRuntime.IMPORTER_PASS),
            new LineBlock(228, 235, NexEconomyInfoTimeRuntime.DEMAND_PASS),
            // Weave the enclosing scan after its inner passes so shared end-boundary exits retain
            // proper inner-before-outer ordering.
            new LineBlock(140, 235, NexEconomyInfoTimeRuntime.COMMODITY_SCAN),
            new LineBlock(235, 270, NexEconomyInfoTimeRuntime.HEAVY_INDUSTRY),
            new LineBlock(270, 280, NexEconomyInfoTimeRuntime.MARKET_SUMMARY));

    private static final List<LineCounter> COUNTERS = List.of(
            new LineCounter(142, NexEconomyInfoTimeRuntime.COMMODITIES_VISITED),
            new LineCounter(155, NexEconomyInfoTimeRuntime.PRODUCER_CANDIDATES),
            new LineCounter(198, NexEconomyInfoTimeRuntime.IMPORTER_CANDIDATES),
            new LineCounter(229, NexEconomyInfoTimeRuntime.DEMAND_CANDIDATES),
            new LineCounter(241, NexEconomyInfoTimeRuntime.FIRST_RUN_MARKETS),
            new LineCounter(258, NexEconomyInfoTimeRuntime.REFRESH_FACTIONS),
            new LineCounter(272, NexEconomyInfoTimeRuntime.SUMMARY_MARKETS));

    private NexEconomyInfoTimePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!NexEconomyInfoTimeRuntime.enabled() || signature.majorVersion() != 61
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

        List<CounterMatch> counterMatches = new ArrayList<>();
        for (LineCounter counter : COUNTERS) {
            LineNumberNode line = uniqueLine(method, counter.line);
            if (line == null) return null;
            counterMatches.add(new CounterMatch(line, counter.counter));
        }

        List<AbstractInsnNode> returns = returns(method);
        if (returns.size() != 2) return null;

        int nextLocal = method.maxLocals;
        int totalStartedLocal = nextLocal;
        nextLocal += 2;
        for (RegionMatch region : regions) {
            weaveRegion(method, region.start, region.end, region.phase, nextLocal);
            nextLocal += 2;
        }
        for (CounterMatch counter : counterMatches) weaveCounter(method, counter);
        weaveTotal(method, returns, totalStartedLocal);
        method.maxLocals = nextLocal;

        NexEconomyInfoTimeRuntime.installed();
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static void weaveTotal(
            MethodNode method, List<AbstractInsnNode> returns, int startedLocal) {
        InsnList start = new InsnList();
        start.add(new VarInsnNode(Opcodes.ILOAD, 1));
        start.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "beginCall", "(Z)J", false));
        start.add(new VarInsnNode(Opcodes.LSTORE, startedLocal));
        method.instructions.insertBefore(method.instructions.getFirst(), start);
        for (AbstractInsnNode methodReturn : returns) {
            method.instructions.insertBefore(methodReturn,
                    exitInstructions(NexEconomyInfoTimeRuntime.TOTAL, startedLocal));
        }
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

    private static void weaveCounter(MethodNode method, CounterMatch counter) {
        InsnList visit = new InsnList();
        visit.add(new org.objectweb.asm.tree.LdcInsnNode(counter.counter));
        visit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "visit", "(I)V", false));
        method.instructions.insertBefore(counter.line, visit);
    }

    private static InsnList enterInstructions(int phase, int startedLocal) {
        InsnList start = new InsnList();
        start.add(new org.objectweb.asm.tree.LdcInsnNode(phase));
        start.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "enter", "(I)J", false));
        start.add(new VarInsnNode(Opcodes.LSTORE, startedLocal));
        return start;
    }

    private static InsnList exitInstructions(int phase, int startedLocal) {
        InsnList finish = new InsnList();
        finish.add(new org.objectweb.asm.tree.LdcInsnNode(phase));
        finish.add(new VarInsnNode(Opcodes.LLOAD, startedLocal));
        finish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exit", "(IJ)V", false));
        return finish;
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

    private static List<AbstractInsnNode> returns(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) result.add(instruction);
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

    private record LineCounter(int line, int counter) {
    }

    private record RegionMatch(LineNumberNode start, LineNumberNode end, int phase) {
    }

    private record CounterMatch(LineNumberNode line, int counter) {
    }
}

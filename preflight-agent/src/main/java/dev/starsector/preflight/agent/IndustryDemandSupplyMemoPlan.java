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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Reuses vanilla's applied synthetic industry for Codex's immediately adjacent supply query. */
final class IndustryDemandSupplyMemoPlan {
    static final String SETTINGS_CLASS = "com/fs/starfarer/settings/StarfarerSettings$1";
    static final String SETTINGS_SHA256 =
            "f9d0637fc8c8912e43fda9d61745efa0d79b40f4e2c4a80e1f55ac044b526796";
    static final String CODEX_CLASS = "com/fs/starfarer/api/impl/codex/CodexDataV2";
    static final String CODEX_SHA256 =
            "0a2fbd188fa2937ec279d4ee41dea9ffa75f833ec7622a357857d70696f42896";
    static final String QUERY_DESCRIPTOR = "(Ljava/lang/String;)Ljava/util/Set;";
    static final String LINK_METHOD = "linkRelatedEntries";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/IndustryDemandSupplyMemoRuntime";
    private static final String INDUSTRY = "com/fs/starfarer/api/campaign/econ/Industry";

    private IndustryDemandSupplyMemoPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (SETTINGS_CLASS.equals(signature.internalName())
                && SETTINGS_SHA256.equals(signature.sha256())
                && signature.majorVersion() == 61) {
            return transformSettings(originalBytes);
        }
        if (CODEX_CLASS.equals(signature.internalName())
                && CODEX_SHA256.equals(signature.sha256())
                && signature.majorVersion() == 61) {
            return transformCodex(originalBytes);
        }
        return null;
    }

    private static byte[] transformSettings(byte[] originalBytes) {
        ClassNode owner = read(originalBytes);
        MethodNode demand = unique(owner, "getIndustryDemand", QUERY_DESCRIPTOR);
        MethodNode supply = unique(owner, "getIndustrySupply", QUERY_DESCRIPTOR);
        if (demand == null || supply == null || calls(demand, RUNTIME) != 0
                || calls(supply, RUNTIME) != 0) return null;

        List<AbstractInsnNode> demandReturns = opcodes(demand, Opcodes.ARETURN);
        List<AbstractInsnNode> supplyReturns = opcodes(supply, Opcodes.ARETURN);
        if (demandReturns.size() != 1 || supplyReturns.size() != 1
                || calls(demand, INDUSTRY, "apply", "()V") != 1
                || calls(supply, INDUSTRY, "apply", "()V") != 1) return null;

        VarInsnNode specStore = null;
        for (AbstractInsnNode instruction : supply.instructions) {
            if (instruction instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ASTORE && store.var == 2
                    && previousCode(store) instanceof MethodInsnNode call
                    && "getIndustrySpec".equals(call.name)) {
                if (specStore != null) return null;
                specStore = store;
            }
        }
        List<TypeInsnNode> arrays = new ArrayList<>();
        for (AbstractInsnNode instruction : supply.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && "java/util/ArrayList".equals(type.desc)) arrays.add(type);
        }
        if (specStore == null || arrays.size() != 1) return null;

        InsnList record = new InsnList();
        record.add(new VarInsnNode(Opcodes.ALOAD, 1));
        record.add(new VarInsnNode(Opcodes.ALOAD, 5));
        record.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "record",
                "(Ljava/lang/String;Ljava/lang/Object;)V", false));
        demand.instructions.insertBefore(demandReturns.get(0), record);

        LabelNode reuse = new LabelNode();
        supply.instructions.insertBefore(arrays.get(0), reuse);
        LabelNode miss = new LabelNode();
        InsnList lookup = new InsnList();
        lookup.add(new VarInsnNode(Opcodes.ALOAD, 1));
        lookup.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "lookup",
                "(Ljava/lang/String;)Ljava/lang/Object;", false));
        lookup.add(new InsnNode(Opcodes.DUP));
        lookup.add(new JumpInsnNode(Opcodes.IFNULL, miss));
        lookup.add(new TypeInsnNode(Opcodes.CHECKCAST, INDUSTRY));
        lookup.add(new VarInsnNode(Opcodes.ASTORE, 5));
        lookup.add(new JumpInsnNode(Opcodes.GOTO, reuse));
        lookup.add(miss);
        lookup.add(new InsnNode(Opcodes.POP));
        supply.instructions.insert(specStore, lookup);

        IndustryDemandSupplyMemoRuntime.settingsInstalled();
        return write(owner);
    }

    private static byte[] transformCodex(byte[] originalBytes) {
        ClassNode owner = read(originalBytes);
        MethodNode link = unique(owner, LINK_METHOD, "()V");
        if (link == null || calls(link, RUNTIME) != 0) return null;
        List<AbstractInsnNode> returns = opcodes(link, Opcodes.RETURN);
        if (returns.size() != 1) return null;
        AbstractInsnNode first = firstCode(link);
        if (first == null) return null;

        LabelNode protectedStart = new LabelNode();
        InsnList enter = new InsnList();
        enter.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "enterCodex", "()V", false));
        enter.add(protectedStart);
        link.instructions.insertBefore(first, enter);

        LabelNode protectedEnd = new LabelNode();
        InsnList exit = new InsnList();
        exit.add(protectedEnd);
        exit.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "exitCodex", "()V", false));
        link.instructions.insertBefore(returns.get(0), exit);

        int throwableLocal = link.maxLocals++;
        LabelNode handler = new LabelNode();
        InsnList exceptionalExit = new InsnList();
        exceptionalExit.add(handler);
        exceptionalExit.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        exceptionalExit.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "exitCodex", "()V", false));
        exceptionalExit.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        exceptionalExit.add(new InsnNode(Opcodes.ATHROW));
        link.instructions.add(exceptionalExit);
        link.tryCatchBlocks.add(
                new TryCatchBlockNode(protectedStart, protectedEnd, handler, null));

        IndustryDemandSupplyMemoRuntime.codexInstalled();
        return write(owner);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static byte[] write(ClassNode owner) {
        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
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

    private static List<AbstractInsnNode> opcodes(MethodNode method, int opcode) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) result.add(instruction);
        }
        return result;
    }

    private static int calls(MethodNode method, String owner) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)) count++;
        }
        return count;
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name) && descriptor.equals(call.desc)) count++;
        }
        return count;
    }

    private static AbstractInsnNode firstCode(MethodNode method) {
        AbstractInsnNode current = method.instructions.getFirst();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }
}

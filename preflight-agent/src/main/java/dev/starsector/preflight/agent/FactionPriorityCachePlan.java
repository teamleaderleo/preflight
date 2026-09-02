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
import org.objectweb.asm.tree.VarInsnNode;

/** Replays exact-profile faction priority IDs, otherwise observes the untouched original walk. */
final class FactionPriorityCachePlan {
    static final String TARGET_CLASS = SpecStorePhasePlan.TARGET_CLASS;
    static final String METHOD = "o00000";
    static final String DESCRIPTOR = "(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;Z"
            + "Lcom/fs/starfarer/loading/SpecStore$Oo;)V";
    private static final String CALLBACK = "com/fs/starfarer/loading/SpecStore$Oo";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/FactionPriorityCacheRuntime";

    private FactionPriorityCachePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!apply(signature, owner)) return null;
        return write(owner);
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(METHOD, DESCRIPTOR)) return false;
        MethodNode method = uniqueMethod(owner);
        if (method == null || (method.access & Opcodes.ACC_STATIC) == 0 || hasRuntimeCall(method)) {
            return false;
        }
        List<MethodInsnNode> adds = new ArrayList<>();
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && CALLBACK.equals(call.owner) && METHOD.equals(call.name)
                    && "(Ljava/lang/String;)V".equals(call.desc)) {
                adds.add(call);
            }
            if (instruction.getOpcode() == Opcodes.RETURN) returns.add(instruction);
        }
        if (adds.size() != 3 || returns.size() != 1) return false;

        LabelNode original = new LabelNode();
        LabelNode replayLoop = new LabelNode();
        LabelNode replayComplete = new LabelNode();
        int replayIds = method.maxLocals;
        int replayIndex = replayIds + 1;
        InsnList entry = new InsnList();
        entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 4));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 1));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 2));
        entry.add(new VarInsnNode(Opcodes.ILOAD, 3));
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "replayOrBegin",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Z)"
                        + "[Ljava/lang/String;", false));
        entry.add(new InsnNode(Opcodes.DUP));
        entry.add(new JumpInsnNode(Opcodes.IFNULL, original));
        entry.add(new VarInsnNode(Opcodes.ASTORE, replayIds));
        entry.add(new InsnNode(Opcodes.ICONST_0));
        entry.add(new VarInsnNode(Opcodes.ISTORE, replayIndex));
        entry.add(replayLoop);
        entry.add(new VarInsnNode(Opcodes.ILOAD, replayIndex));
        entry.add(new VarInsnNode(Opcodes.ALOAD, replayIds));
        entry.add(new InsnNode(Opcodes.ARRAYLENGTH));
        entry.add(new JumpInsnNode(Opcodes.IF_ICMPGE, replayComplete));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 4));
        entry.add(new VarInsnNode(Opcodes.ALOAD, replayIds));
        entry.add(new VarInsnNode(Opcodes.ILOAD, replayIndex));
        entry.add(new InsnNode(Opcodes.AALOAD));
        entry.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, CALLBACK, METHOD,
                "(Ljava/lang/String;)V", true));
        entry.add(new org.objectweb.asm.tree.IincInsnNode(replayIndex, 1));
        entry.add(new JumpInsnNode(Opcodes.GOTO, replayLoop));
        entry.add(replayComplete);
        entry.add(new InsnNode(Opcodes.RETURN));
        entry.add(original);
        entry.add(new InsnNode(Opcodes.POP));
        method.instructions.insertBefore(method.instructions.getFirst(), entry);

        for (MethodInsnNode add : adds) {
            InsnList observe = new InsnList();
            observe.add(new InsnNode(Opcodes.DUP));
            observe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "record",
                    "(Ljava/lang/String;)V", false));
            method.instructions.insertBefore(add, observe);
        }
        method.instructions.insertBefore(returns.get(0), new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "completeCall", "()V", false));
        return true;
    }

    static byte[] write(ClassNode owner) {
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
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

    private static boolean hasRuntimeCall(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) return true;
        }
        return false;
    }
}

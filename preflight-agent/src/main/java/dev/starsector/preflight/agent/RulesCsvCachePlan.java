package dev.starsector.preflight.agent;

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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Uses prepared merged rules rows while retaining the original LoadingUtils call as the miss path. */
final class RulesCsvCachePlan {
    private static final String TARGET = RulesLoaderPhasePlan.TARGET_CLASS;
    private static final String RUNTIME = "dev/starsector/preflight/agent/RulesCsvCacheRuntime";
    private static final String LOADING_UTILS = "com/fs/starfarer/loading/LoadingUtils";
    private static final String JSON = "org/json/JSONArray";
    private static final String JSON_DESCRIPTOR =
            "(Ljava/util/List;Ljava/lang/String;ZZ)Lorg/json/JSONArray;";

    private RulesCsvCachePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        String loadMethod = RulesLoaderPhasePlan.loadMethod(signature);
        if (!TARGET.equals(signature.internalName()) || loadMethod == null) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode load = uniqueMethod(
                owner, loadMethod, RulesLoaderPhasePlan.LOAD_DESCRIPTOR);
        MethodInsnNode originalCall = uniqueJsonCall(load);
        AbstractInsnNode onlyReturn = uniqueReturn(load);
        if (load == null || originalCall == null || onlyReturn == null || hasRuntimeCalls(load)) {
            return null;
        }

        int secondFlagLocal = load.maxLocals++;
        int firstFlagLocal = load.maxLocals++;
        int pathLocal = load.maxLocals++;
        int rootsLocal = load.maxLocals++;
        LabelNode hit = new LabelNode();
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.ISTORE, secondFlagLocal));
        before.add(new VarInsnNode(Opcodes.ISTORE, firstFlagLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, pathLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, rootsLocal));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "cached", "()Ljava/lang/Object;", false));
        before.add(new InsnNode(Opcodes.DUP));
        before.add(new JumpInsnNode(Opcodes.IFNONNULL, hit));
        before.add(new InsnNode(Opcodes.POP));
        before.add(new VarInsnNode(Opcodes.ALOAD, rootsLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, pathLocal));
        before.add(new VarInsnNode(Opcodes.ILOAD, firstFlagLocal));
        before.add(new VarInsnNode(Opcodes.ILOAD, secondFlagLocal));
        load.instructions.insertBefore(originalCall, before);

        InsnList after = new InsnList();
        after.add(new InsnNode(Opcodes.DUP));
        after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "capture", "(Ljava/lang/Object;)V", false));
        after.add(hit);
        after.add(new TypeInsnNode(Opcodes.CHECKCAST, JSON));
        load.instructions.insert(originalCall, after);
        load.instructions.insertBefore(onlyReturn, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "complete", "()V", false));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static MethodInsnNode uniqueJsonCall(MethodNode method) {
        if (method == null) {
            return null;
        }
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && LOADING_UTILS.equals(call.owner)
                    && "super".equals(call.name)
                    && JSON_DESCRIPTOR.equals(call.desc)) {
                if (found != null) {
                    return null;
                }
                found = call;
            }
        }
        return found;
    }

    private static AbstractInsnNode uniqueReturn(MethodNode method) {
        if (method == null) {
            return null;
        }
        AbstractInsnNode found = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                if (found != null) {
                    return null;
                }
                found = instruction;
            }
        }
        return found;
    }

    private static boolean hasRuntimeCalls(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }
}

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

/** Uses prepared merged JSON while retaining the original LoadingUtils call as the miss path. */
final class VariantJsonCachePlan {
    private static final String TARGET = SpecStorePhasePlan.TARGET_CLASS;
    private static final String RUNTIME = "dev/starsector/preflight/agent/VariantJsonCacheRuntime";
    private static final String LOADING_UTILS = "com/fs/starfarer/loading/LoadingUtils";
    private static final String JSON = "org/json/JSONObject";
    private static final String JSON_DESCRIPTOR = "(Ljava/lang/String;)Lorg/json/JSONObject;";

    private VariantJsonCachePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET.equals(signature.internalName())
                || !signature.hasMethod(VariantLoaderPhasePlan.METHOD, VariantLoaderPhasePlan.DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = uniqueMethod(
                owner, VariantLoaderPhasePlan.METHOD, VariantLoaderPhasePlan.DESCRIPTOR);
        MethodInsnNode originalCall = uniqueJsonCall(method);
        AbstractInsnNode methodReturn = uniqueReturn(method);
        if (method == null || originalCall == null || methodReturn == null || hasRuntimeCalls(method)) {
            return null;
        }

        int pathLocal = method.maxLocals++;
        LabelNode hit = new LabelNode();
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.ASTORE, pathLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, pathLocal));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "cached", "(Ljava/lang/String;)Ljava/lang/Object;", false));
        before.add(new InsnNode(Opcodes.DUP));
        before.add(new JumpInsnNode(Opcodes.IFNONNULL, hit));
        before.add(new InsnNode(Opcodes.POP));
        before.add(new VarInsnNode(Opcodes.ALOAD, pathLocal));
        method.instructions.insertBefore(originalCall, before);

        InsnList after = new InsnList();
        after.add(new InsnNode(Opcodes.DUP));
        after.add(new VarInsnNode(Opcodes.ALOAD, pathLocal));
        after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "capture", "(Ljava/lang/Object;Ljava/lang/String;)V", false));
        after.add(hit);
        after.add(new TypeInsnNode(Opcodes.CHECKCAST, JSON));
        method.instructions.insert(originalCall, after);
        method.instructions.insertBefore(methodReturn, new MethodInsnNode(
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
                    && LOADING_UTILS.equals(call.owner)
                    && "Ó00000".equals(call.name)
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

package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Replaces five replaceAll and five split calls with equivalent precompiled-Pattern operations. */
final class RulesRegexCachePlan {
    static final String TARGET_CLASS = RulesLoaderPhasePlan.TARGET_CLASS;
    static final String LOAD_METHOD = RulesLoaderPhasePlan.LOAD_METHOD;
    static final String LOAD_DESCRIPTOR = RulesLoaderPhasePlan.LOAD_DESCRIPTOR;
    private static final String RUNTIME = "dev/starsector/preflight/agent/RulesRegexCacheRuntime";
    private static final String STRING = "java/lang/String";
    private static final String REPLACE = "replaceAll";
    private static final String REPLACE_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
    private static final String SPLIT = "split";
    private static final String SPLIT_DESCRIPTOR = "(Ljava/lang/String;)[Ljava/lang/String;";
    private static final String STATIC_REPLACE_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
    private static final String STATIC_SPLIT_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;";

    private RulesRegexCachePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        String loadMethod = RulesLoaderPhasePlan.loadMethod(signature);
        if (!TARGET_CLASS.equals(signature.internalName()) || loadMethod == null) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode load = uniqueMethod(owner, loadMethod, LOAD_DESCRIPTOR);
        if (load == null || hasRuntimeCalls(load)) {
            return null;
        }
        List<MethodInsnNode> replacements = calls(load, REPLACE, REPLACE_DESCRIPTOR);
        List<MethodInsnNode> splits = calls(load, SPLIT, SPLIT_DESCRIPTOR);
        if (replacements.size() != 5 || splits.size() != 5) {
            return null;
        }

        replacements.forEach(call -> load.instructions.set(call, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, REPLACE, STATIC_REPLACE_DESCRIPTOR, false)));
        splits.forEach(call -> load.instructions.set(call, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, SPLIT, STATIC_SPLIT_DESCRIPTOR, false)));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && STRING.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                result.add(call);
            }
        }
        return result;
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

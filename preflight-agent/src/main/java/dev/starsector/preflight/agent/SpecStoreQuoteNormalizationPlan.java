package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Replaces the exact two fixed smart-quote regex calls with the shared linear fast path. */
final class SpecStoreQuoteNormalizationPlan {
    static final String PLAN_ID = "vanilla-spec-store-quote-normalization-v1";
    static final String TARGET_CLASS = SpecStorePhasePlan.TARGET_CLASS;
    static final String METHOD = "\u00d300000";
    static final String DESCRIPTOR = "(Ljava/lang/String;)Ljava/lang/String;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/RulesRegexCacheRuntime";
    private static final String STRING = "java/lang/String";
    private static final String REPLACE = "replaceAll";
    private static final String INSTANCE_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
    private static final String STATIC_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
    private static final String DOUBLE_REGEX = "[\\u201c\\u201d]+";
    private static final String SINGLE_REGEX = "[\\u2018\\u2019\\ufffd]+";

    private SpecStoreQuoteNormalizationPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!apply(signature, owner)) {
            return null;
        }
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(METHOD, DESCRIPTOR)) {
            return false;
        }
        MethodNode method = uniqueMethod(owner);
        if (method == null || hasRuntimeCall(method)) {
            return false;
        }
        List<MethodInsnNode> calls = replacementCalls(method);
        if (calls.size() != 2
                || !hasConstants(calls.get(0), DOUBLE_REGEX, "\"")
                || !hasConstants(calls.get(1), SINGLE_REGEX, "'")) {
            return false;
        }
        calls.forEach(call -> method.instructions.set(call, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, REPLACE, STATIC_DESCRIPTOR, false)));

        return true;
    }

    private static boolean hasConstants(MethodInsnNode call, String regex, String replacement) {
        AbstractInsnNode replacementNode = previousOpcode(call);
        AbstractInsnNode regexNode = previousOpcode(replacementNode);
        return replacementNode instanceof LdcInsnNode replacementConstant
                && replacement.equals(replacementConstant.cst)
                && regexNode instanceof LdcInsnNode regexConstant
                && regex.equals(regexConstant.cst);
    }

    private static List<MethodInsnNode> replacementCalls(MethodNode method) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && STRING.equals(call.owner) && REPLACE.equals(call.name)
                    && INSTANCE_DESCRIPTOR.equals(call.desc)) {
                result.add(call);
            }
        }
        return result;
    }

    private static boolean hasRuntimeCall(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode uniqueMethod(ClassNode owner) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (METHOD.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }
}

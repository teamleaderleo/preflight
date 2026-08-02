package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Routes the expression constructor's single {@code Misc.tokenize} call through
 * {@link RuleTokenCacheRuntime}.
 *
 * <p>The rewrite is one instruction wide and adds no branches, so the method keeps its original
 * stack map and needs no frame recomputation on a class whose obfuscated field names a modern
 * verifier will not load. Two constants are pushed ahead of the replaced call:
 *
 * <ul>
 *   <li>a {@code MethodHandle} constant for {@code Misc.tokenize(String)}, which the JVM resolves at
 *       this call site exactly as it resolved the {@code invokestatic} being replaced -- so the
 *       runtime always has vanilla to fall back to, and a game that no longer offers that method
 *       fails to link here rather than silently losing the tokenizer;
 *   <li>a class constant for {@code Misc$Token}, so the runtime can read and rebuild tokens without
 *       guessing which loader holds the game.
 * </ul>
 *
 * <p>The class is version 61, so both constant forms are available to it.
 */
final class RuleTokenCachePlan {
    static final String TARGET_CLASS = RuleExpressionPhasePlan.TARGET_CLASS;
    static final String LOAD_METHOD = RuleExpressionPhasePlan.LOAD_METHOD;
    static final String LOAD_DESCRIPTOR = RuleExpressionPhasePlan.LOAD_DESCRIPTOR;
    static final String TOKENIZE_OWNER = "com/fs/starfarer/api/util/Misc";
    static final String TOKENIZE_NAME = "tokenize";
    static final String TOKENIZE_DESCRIPTOR = "(Ljava/lang/String;)Ljava/util/List;";
    static final String TOKEN_CLASS = "com/fs/starfarer/api/util/Misc$Token";
    private static final String RUNTIME = "dev/starsector/preflight/agent/RuleTokenCacheRuntime";
    private static final String RUNTIME_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/invoke/MethodHandle;Ljava/lang/Class;)Ljava/util/List;";

    private RuleTokenCachePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(LOAD_METHOD, LOAD_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode constructor = uniqueMethod(owner, LOAD_METHOD, LOAD_DESCRIPTOR);
        if (constructor == null || hasRuntimeCalls(constructor)) {
            return null;
        }
        // A class file older than 51 cannot carry a MethodHandle constant, and raising its version
        // to make room would change how it is verified. Decline instead.
        if ((owner.version & 0xFFFF) < Opcodes.V1_7) {
            return null;
        }

        List<MethodInsnNode> tokenize = calls(constructor);
        if (tokenize.size() != 1) {
            return null;
        }

        MethodInsnNode call = tokenize.get(0);
        InsnList constants = new InsnList();
        constants.add(new LdcInsnNode(new Handle(
                Opcodes.H_INVOKESTATIC, TOKENIZE_OWNER, TOKENIZE_NAME, TOKENIZE_DESCRIPTOR, false)));
        constants.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(TOKEN_CLASS)));
        constructor.instructions.insertBefore(call, constants);
        constructor.instructions.set(call, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, TOKENIZE_NAME, RUNTIME_DESCRIPTOR, false));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && TOKENIZE_OWNER.equals(call.owner)
                    && TOKENIZE_NAME.equals(call.name)
                    && TOKENIZE_DESCRIPTOR.equals(call.desc)) {
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

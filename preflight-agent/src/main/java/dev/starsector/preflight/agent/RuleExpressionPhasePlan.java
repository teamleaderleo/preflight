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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Splits the campaign-rules expression constructor without changing its control flow.
 *
 * <p>The rules-loader probe attributes 1.575s to 62,340 expression constructions but cannot say
 * what inside one costs anything. The reviewed constructor does exactly two things that are not
 * field stores and list access: it calls {@code Misc.tokenize} once, and for a command invocation
 * it calls {@code getCommandClass}, which walks every declared rule-command package in order with
 * {@code Class.forName} until one resolves. Those are unrelated levers with unrelated fixes, so
 * they have to be measured apart before either is worth building.
 *
 * <p>{@link StartupPhaseRuntime}'s subphase slot is deliberately flat: starting a label ends
 * whatever was running. This plan therefore emits an exclusive partition rather than a nesting --
 * it opens {@code rules-expression-residual} at method entry, switches to a specific label around
 * each reviewed call, and switches back afterwards. The three labels sum to the constructor.
 *
 * <p>One consequence worth stating: the outer {@code rules-expression-parse} label, opened by
 * {@link RulesLoaderPhasePlan} at the {@code NEW}, is closed by this plan's first switch. With this
 * probe installed that label measures only argument evaluation up to the constructor's first
 * instruction, not the construction. The two probes are readable together, not comparable.
 */
final class RuleExpressionPhasePlan {
    static final String TARGET_CLASS = "com/fs/starfarer/campaign/rules/super";
    static final String LINUX_TARGET_CLASS = "com/fs/starfarer/campaign/rules/A";
    static final String WINDOWS_TARGET_CLASS = "com/fs/starfarer/campaign/rules/oOOO";
    static final String LOAD_METHOD = "<init>";
    static final String LOAD_DESCRIPTOR = "(Ljava/lang/String;)V";
    static final String RESIDUAL_LABEL = "rules-expression-residual";
    static final String TOKENIZE_LABEL = "rules-expression-tokenize";
    static final String COMMAND_CLASS_LABEL = "rules-expression-command-class";
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";
    private static final String TOKENIZE_OWNER = "com/fs/starfarer/api/util/Misc";
    private static final String TOKENIZE_NAME = "tokenize";
    private static final String TOKENIZE_DESCRIPTOR = "(Ljava/lang/String;)Ljava/util/List;";
    private static final String COMMAND_CLASS_NAME = "getCommandClass";
    private static final String COMMAND_CLASS_DESCRIPTOR = "(Ljava/lang/String;)Ljava/lang/String;";

    private RuleExpressionPhasePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!isTarget(signature.internalName())
                || !signature.hasMethod(LOAD_METHOD, LOAD_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode constructor = uniqueMethod(owner, LOAD_METHOD, LOAD_DESCRIPTOR);
        if (constructor == null || hasRuntimeCalls(constructor)) {
            return null;
        }

        List<MethodInsnNode> tokenize = calls(constructor, Opcodes.INVOKESTATIC,
                TOKENIZE_OWNER, TOKENIZE_NAME, TOKENIZE_DESCRIPTOR);
        List<MethodInsnNode> commandClass = calls(constructor, Opcodes.INVOKESTATIC,
                signature.internalName(), COMMAND_CLASS_NAME, COMMAND_CLASS_DESCRIPTOR);
        List<AbstractInsnNode> returns = returns(constructor);
        // The reviewed constructor tokenizes once and resolves a command class on each of its two
        // command-invocation branches. Any other shape is a different method than the one reviewed.
        if (tokenize.size() != 1 || commandClass.size() != 2 || returns.size() != 1) {
            return null;
        }

        AbstractInsnNode first = constructor.instructions.getFirst();
        if (first == null) {
            return null;
        }
        constructor.instructions.insertBefore(first, start(RESIDUAL_LABEL));
        wrap(constructor, tokenize.get(0), TOKENIZE_LABEL);
        for (MethodInsnNode call : commandClass) {
            wrap(constructor, call, COMMAND_CLASS_LABEL);
        }
        constructor.instructions.insertBefore(returns.get(0), end());

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    static boolean isTarget(String internalName) {
        return TARGET_CLASS.equals(internalName)
                || LINUX_TARGET_CLASS.equals(internalName)
                || WINDOWS_TARGET_CLASS.equals(internalName);
    }

    /**
     * Switches to {@code label} for the duration of one call and back to the residual label after.
     *
     * <p>Switching rather than ending is what keeps the partition gapless: an {@code end} would
     * leave the rest of the constructor unattributed until the caller opened its next label.
     */
    private static void wrap(MethodNode method, MethodInsnNode call, String label) {
        method.instructions.insertBefore(call, start(label));
        method.instructions.insert(call, start(RESIDUAL_LABEL));
    }

    private static InsnList start(String label) {
        InsnList instructions = new InsnList();
        instructions.add(new LdcInsnNode(label));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "specSubphaseStart", "(Ljava/lang/String;)V", false));
        return instructions;
    }

    private static MethodInsnNode end() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "specSubphaseEnd", "()V", false);
    }

    /**
     * Only the normal return is closed. The constructor's other exits all throw
     * {@code RuleException}, and on that path the caller's next {@code specSubphaseStart} closes the
     * open label anyway; the measured corpus produces zero such throws.
     */
    private static List<AbstractInsnNode> returns(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof InsnNode && instruction.getOpcode() == Opcodes.RETURN) {
                result.add(instruction);
            }
        }
        return result;
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, int opcode, String owner, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && owner.equals(call.owner) && name.equals(call.name)
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

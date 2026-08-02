package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class RuleExpressionPhasePlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";

    @Test
    void splitsTokenizingFromCommandClassResolutionWithoutRemovingVanillaCalls() throws Exception {
        byte[] original = fixture(1, 2);
        byte[] rewritten =
                RuleExpressionPhasePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(1, calls(rewritten, "com/fs/starfarer/api/util/Misc", "tokenize"),
                "the tokenizer call must survive");
        assertEquals(2, calls(rewritten, RuleExpressionPhasePlan.TARGET_CLASS, "getCommandClass"),
                "both command-class resolutions must survive");
        // One at method entry, one before each of the three wrapped calls, one after each.
        assertEquals(7, calls(rewritten, RUNTIME, "specSubphaseStart"));
        assertEquals(1, calls(rewritten, RUNTIME, "specSubphaseEnd"));
    }

    @Test
    void everyReviewedCallRunsUnderItsOwnLabelAndTheRestUnderTheResidual() throws Exception {
        byte[] original = fixture(1, 2);
        byte[] rewritten =
                RuleExpressionPhasePlan.transform(ClassSignature.parse(original), original);

        List<String> sequence = labelSequence(rewritten);
        // The partition has to be gapless: the residual opens the method, each reviewed call is
        // bracketed by its own label, and the residual reopens immediately afterwards.
        assertEquals(List.of(
                RuleExpressionPhasePlan.RESIDUAL_LABEL,
                RuleExpressionPhasePlan.TOKENIZE_LABEL,
                RuleExpressionPhasePlan.RESIDUAL_LABEL,
                RuleExpressionPhasePlan.COMMAND_CLASS_LABEL,
                RuleExpressionPhasePlan.RESIDUAL_LABEL,
                RuleExpressionPhasePlan.COMMAND_CLASS_LABEL,
                RuleExpressionPhasePlan.RESIDUAL_LABEL),
                sequence);
        assertTrue(endsBeforeReturn(rewritten), "the open label must be closed before returning");
    }

    @Test
    void refusesChangedShapesAndDoubleWeaving() throws Exception {
        byte[] noTokenizer = fixture(0, 2);
        assertNull(RuleExpressionPhasePlan.transform(
                ClassSignature.parse(noTokenizer), noTokenizer));

        byte[] oneCommandBranch = fixture(1, 1);
        assertNull(RuleExpressionPhasePlan.transform(
                ClassSignature.parse(oneCommandBranch), oneCommandBranch));

        byte[] original = fixture(1, 2);
        byte[] once = RuleExpressionPhasePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(RuleExpressionPhasePlan.transform(ClassSignature.parse(once), once),
                "weaving twice would double-count every expression");
    }

    @Test
    void declinesAClassThatIsNotTheExpressionConstructor() throws Exception {
        byte[] other = otherClass();
        assertNull(RuleExpressionPhasePlan.transform(ClassSignature.parse(other), other));
    }

    // -----------------------------------------------------------------------------------------

    /** The reviewed constructor's shape: one tokenize call and one per command-invocation branch. */
    private static byte[] fixture(int tokenizeCalls, int commandClassCalls) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RuleExpressionPhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
                RuleExpressionPhasePlan.LOAD_METHOD, RuleExpressionPhasePlan.LOAD_DESCRIPTOR,
                null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        for (int index = 0; index < tokenizeCalls; index++) {
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc",
                    "tokenize", "(Ljava/lang/String;)Ljava/util/List;", false);
            method.visitInsn(Opcodes.POP);
        }
        for (int index = 0; index < commandClassCalls; index++) {
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, RuleExpressionPhasePlan.TARGET_CLASS,
                    "getCommandClass", "(Ljava/lang/String;)Ljava/lang/String;", false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] otherClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/fs/starfarer/campaign/rules/ooOO",
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
                RuleExpressionPhasePlan.LOAD_METHOD, RuleExpressionPhasePlan.LOAD_DESCRIPTOR,
                null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Every label pushed to {@code specSubphaseStart}, in instruction order. */
    private static List<String> labelSequence(byte[] bytes) {
        List<String> labels = new ArrayList<>();
        MethodNode constructor = constructor(bytes);
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner) && "specSubphaseStart".equals(call.name)
                    && instruction.getPrevious() instanceof LdcInsnNode label
                    && label.cst instanceof String value) {
                labels.add(value);
            }
        }
        return labels;
    }

    private static boolean endsBeforeReturn(byte[] bytes) {
        MethodNode constructor = constructor(bytes);
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                AbstractInsnNode previous = instruction.getPrevious();
                return previous instanceof MethodInsnNode call
                        && RUNTIME.equals(call.owner) && "specSubphaseEnd".equals(call.name);
            }
        }
        return false;
    }

    private static MethodNode constructor(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : owner.methods) {
            if (RuleExpressionPhasePlan.LOAD_METHOD.equals(method.name)
                    && RuleExpressionPhasePlan.LOAD_DESCRIPTOR.equals(method.desc)) {
                return method;
            }
        }
        throw new AssertionError("no constructor in the rewritten class");
    }

    private static int calls(byte[] bytes, String ownerName, String methodName) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int count = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && ownerName.equals(call.owner) && methodName.equals(call.name)) {
                    count++;
                }
            }
        }
        return count;
    }
}

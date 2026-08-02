package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class RulesDuplicateIndexPlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/RulesDuplicateIndexRuntime";

    @Test
    void replacesTheLinearScanButKeepsTheOrderedVanillaInsertion() throws Exception {
        byte[] original = fixture(false, 3);
        byte[] rewritten = RulesDuplicateIndexPlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(1, calls(rewritten, RulesLoaderPhasePlan.TARGET_CLASS, "super"));
        assertEquals(0, calls(rewritten, "java/util/Iterator", "next"));
        assertEquals(0, calls(rewritten, "java/util/Iterator", "hasNext"));
        assertEquals(1, calls(rewritten, "java/util/List", "add"));
        assertEquals(1, calls(rewritten, RUNTIME, "begin"));
        assertEquals(1, calls(rewritten, RUNTIME, "check"));
        assertEquals(1, calls(rewritten, RUNTIME, "complete"));
    }

    @Test
    void composesAfterRulesAttributionAndRefusesChangedShapes() throws Exception {
        byte[] attributed = fixture(true, 3);
        assertNotNull(RulesDuplicateIndexPlan.transform(ClassSignature.parse(attributed), attributed));

        byte[] changed = fixture(false, 2);
        assertNull(RulesDuplicateIndexPlan.transform(ClassSignature.parse(changed), changed));
        byte[] once = RulesDuplicateIndexPlan.transform(
                ClassSignature.parse(fixture(false, 3)), fixture(false, 3));
        assertNotNull(once);
        assertNull(RulesDuplicateIndexPlan.transform(ClassSignature.parse(once), once));
    }

    private static byte[] fixture(boolean attributed, int idCalls) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RulesLoaderPhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                RulesLoaderPhasePlan.LOAD_METHOD, RulesLoaderPhasePlan.LOAD_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitLdcInsn("rule");
        method.visitVarInsn(Opcodes.ASTORE, 7);
        method.visitLdcInsn("trigger");
        method.visitVarInsn(Opcodes.ASTORE, 8);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitVarInsn(Opcodes.ASTORE, 10);

        method.visitVarInsn(Opcodes.ALOAD, 8);
        if (attributed) {
            method.visitLdcInsn("rules-duplicate-scan");
            method.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "dev/starsector/preflight/agent/StartupPhaseRuntime",
                    "specSubphaseStart", "(Ljava/lang/String;)V", false);
        }
        method.visitMethodInsn(Opcodes.INVOKESTATIC, RulesLoaderPhasePlan.TARGET_CLASS,
                "super", "(Ljava/lang/String;)Ljava/util/List;", false);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        method.visitVarInsn(Opcodes.ASTORE, 16);
        Label check = new Label();
        Label body = new Label();
        method.visitJumpInsn(Opcodes.GOTO, check);
        method.visitLabel(body);
        method.visitVarInsn(Opcodes.ALOAD, 16);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        method.visitTypeInsn(Opcodes.CHECKCAST, "com/fs/starfarer/campaign/rules/ooOO");
        method.visitVarInsn(Opcodes.ASTORE, 15);
        for (int index = 0; index < idCalls; index++) {
            method.visitVarInsn(Opcodes.ALOAD, index == 0 ? 15 : 10);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "com/fs/starfarer/campaign/rules/ooOO", "getId", "()Ljava/lang/String;", false);
            if (index >= 2) {
                method.visitInsn(Opcodes.POP);
            }
        }
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
        method.visitInsn(Opcodes.POP);
        method.visitLabel(check);
        method.visitVarInsn(Opcodes.ALOAD, 16);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/Iterator", "hasNext", "()Z", true);
        method.visitJumpInsn(Opcodes.IFNE, body);
        if (attributed) {
            method.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "dev/starsector/preflight/agent/StartupPhaseRuntime",
                    "specSubphaseEnd", "()V", false);
        }

        method.visitVarInsn(Opcodes.ALOAD, 8);
        if (attributed) {
            method.visitLdcInsn("rules-trigger-registration");
            method.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "dev/starsector/preflight/agent/StartupPhaseRuntime",
                    "specSubphaseStart", "(Ljava/lang/String;)V", false);
        }
        method.visitMethodInsn(Opcodes.INVOKESTATIC, RulesLoaderPhasePlan.TARGET_CLASS,
                "super", "(Ljava/lang/String;)Ljava/util/List;", false);
        if (attributed) {
            method.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "dev/starsector/preflight/agent/StartupPhaseRuntime",
                    "specSubphaseEnd", "()V", false);
        }
        method.visitVarInsn(Opcodes.ALOAD, 10);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
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

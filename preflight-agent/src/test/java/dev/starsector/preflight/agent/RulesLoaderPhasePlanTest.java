package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class RulesLoaderPhasePlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";

    @Test
    void wrapsEveryReviewedRulesBoundaryWithoutRemovingVanillaCalls() throws Exception {
        byte[] original = fixture(2);
        byte[] rewritten = RulesLoaderPhasePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(1, calls(rewritten, "com/fs/starfarer/loading/LoadingUtils", "super"));
        assertEquals(1, calls(rewritten, "com/fs/starfarer/campaign/rules/ooOO", "<init>"));
        assertEquals(2, calls(rewritten, "com/fs/starfarer/campaign/rules/super", "<init>"));
        assertEquals(1, calls(rewritten, "com/fs/starfarer/api/campaign/rules/Option", "<init>"));
        assertEquals(1, calls(rewritten, "com/fs/starfarer/loading/scripts/ScriptStore", "Object"));
        assertEquals(2, calls(rewritten, RulesLoaderPhasePlan.TARGET_CLASS, "super"));
        assertEquals(17, calls(rewritten, RUNTIME, "specSubphaseStart"));
        assertEquals(17, calls(rewritten, RUNTIME, "specSubphaseEnd"));
    }

    @Test
    void refusesChangedShapesAndDoubleWeaving() throws Exception {
        byte[] changed = fixture(1);
        assertNull(RulesLoaderPhasePlan.transform(ClassSignature.parse(changed), changed));

        byte[] original = fixture(2);
        byte[] once = RulesLoaderPhasePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(RulesLoaderPhasePlan.transform(ClassSignature.parse(once), once));
    }

    private static byte[] fixture(int expressionConstructors) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RulesLoaderPhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                RulesLoaderPhasePlan.LOAD_METHOD, RulesLoaderPhasePlan.LOAD_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitLdcInsn("data/campaign/rules.csv");
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/fs/starfarer/loading/LoadingUtils", "super",
                "(Ljava/util/List;Ljava/lang/String;ZZ)Lorg/json/JSONArray;", false);
        method.visitInsn(Opcodes.POP);

        method.visitTypeInsn(Opcodes.NEW, "com/fs/starfarer/campaign/rules/ooOO");
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("id");
        method.visitLdcInsn("trigger");
        method.visitLdcInsn("conditions");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/fs/starfarer/campaign/rules/ooOO", "<init>",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);
        method.visitInsn(Opcodes.POP);

        for (int index = 0; index < expressionConstructors; index++) {
            method.visitTypeInsn(Opcodes.NEW, "com/fs/starfarer/campaign/rules/super");
            method.visitInsn(Opcodes.DUP);
            method.visitLdcInsn("expression");
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/fs/starfarer/campaign/rules/super", "<init>",
                    "(Ljava/lang/String;)V", false);
            method.visitInsn(Opcodes.POP);
        }

        method.visitTypeInsn(Opcodes.NEW, "com/fs/starfarer/api/campaign/rules/Option");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "com/fs/starfarer/api/campaign/rules/Option", "<init>", "()V", false);
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn("command.Class");
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/fs/starfarer/loading/scripts/ScriptStore", "Object", "(Ljava/lang/String;)V", false);
        for (int index = 0; index < 5; index++) {
            method.visitLdcInsn("value");
            method.visitLdcInsn("pattern");
            method.visitLdcInsn("replacement");
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replaceAll",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
            method.visitInsn(Opcodes.POP);
            method.visitLdcInsn("value");
            method.visitLdcInsn("pattern");
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "split",
                    "(Ljava/lang/String;)[Ljava/lang/String;", false);
            method.visitInsn(Opcodes.POP);
        }
        for (int index = 0; index < 2; index++) {
            method.visitLdcInsn("trigger");
            method.visitMethodInsn(Opcodes.INVOKESTATIC, RulesLoaderPhasePlan.TARGET_CLASS,
                    "super", "(Ljava/lang/String;)Ljava/util/List;", false);
            method.visitInsn(Opcodes.POP);
        }
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

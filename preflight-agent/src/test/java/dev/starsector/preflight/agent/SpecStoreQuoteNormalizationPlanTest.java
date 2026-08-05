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

class SpecStoreQuoteNormalizationPlanTest {
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/RulesRegexCacheRuntime";

    @Test
    void replacesOnlyTheTwoReviewedSmartQuoteCalls() throws Exception {
        byte[] original = fixture("[\\u201c\\u201d]+", "[\\u2018\\u2019\\ufffd]+");
        byte[] rewritten = SpecStoreQuoteNormalizationPlan.transform(
                ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(2, runtimeCalls(method(rewritten)));
        assertNull(SpecStoreQuoteNormalizationPlan.transform(
                ClassSignature.parse(rewritten), rewritten));
    }

    @Test
    void refusesChangedPatterns() throws Exception {
        byte[] changed = fixture("[\\u201c]+", "[\\u2018\\u2019\\ufffd]+");
        assertNull(SpecStoreQuoteNormalizationPlan.transform(
                ClassSignature.parse(changed), changed));
    }

    private static byte[] fixture(String doubleRegex, String singleRegex) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                SpecStoreQuoteNormalizationPlan.TARGET_CLASS, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                SpecStoreQuoteNormalizationPlan.METHOD,
                SpecStoreQuoteNormalizationPlan.DESCRIPTOR, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn(doubleRegex);
        method.visitLdcInsn("\"");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replaceAll",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        method.visitVarInsn(Opcodes.ASTORE, 0);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn(singleRegex);
        method.visitLdcInsn("'");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replaceAll",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodNode method(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> SpecStoreQuoteNormalizationPlan.METHOD.equals(method.name)
                        && SpecStoreQuoteNormalizationPlan.DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static int runtimeCalls(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) {
                count++;
            }
        }
        return count;
    }
}

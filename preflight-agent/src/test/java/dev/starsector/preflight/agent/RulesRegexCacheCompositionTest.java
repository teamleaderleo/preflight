package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class RulesRegexCacheCompositionTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/RulesRegexCacheRuntime";

    @Test
    void ordinaryExactTargetReachesTheRegexPlanWithoutTheStartupProbe() throws Exception {
        byte[] original = fixture();
        AdapterTarget target = AdapterTargetRegistry.rulesRegexCacheTarget();

        assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
        byte[] rewritten = AdapterTransformationRegistry.transform(
                target, ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(rewritten).accept(owner, ClassReader.EXPAND_FRAMES);
        long calls = 0;
        for (var method : owner.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) {
                    calls++;
                }
            }
        }
        assertEquals(10L, calls);
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RulesRegexCachePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                RulesRegexCachePlan.LOAD_METHOD, RulesRegexCachePlan.LOAD_DESCRIPTOR, null, null);
        method.visitCode();
        for (int index = 0; index < 5; index++) {
            method.visitLdcInsn("text");
            method.visitLdcInsn("\\r");
            method.visitLdcInsn("");
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replaceAll",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
            method.visitInsn(Opcodes.POP);
        }
        for (int index = 0; index < 5; index++) {
            method.visitLdcInsn("text");
            method.visitLdcInsn("\\n");
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "split",
                    "(Ljava/lang/String;)[Ljava/lang/String;", false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}

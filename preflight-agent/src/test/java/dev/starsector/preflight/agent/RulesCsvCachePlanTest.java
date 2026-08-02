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

class RulesCsvCachePlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/RulesCsvCacheRuntime";

    @Test
    void keepsTheOriginalCallAndAddsHitCaptureAndCommitHooks() throws Exception {
        byte[] original = fixture(1, false);
        byte[] rewritten = RulesCsvCachePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(1, calls(rewritten, "com/fs/starfarer/loading/LoadingUtils", "super"));
        assertEquals(1, calls(rewritten, RUNTIME, "cached"));
        assertEquals(1, calls(rewritten, RUNTIME, "capture"));
        assertEquals(1, calls(rewritten, RUNTIME, "complete"));
    }

    @Test
    void composesAfterAttributionAndRefusesChangedShapesAndDoubleWeaving() throws Exception {
        byte[] attributed = fixture(1, true);
        assertNotNull(RulesCsvCachePlan.transform(ClassSignature.parse(attributed), attributed));

        byte[] changed = fixture(2, false);
        assertNull(RulesCsvCachePlan.transform(ClassSignature.parse(changed), changed));
        byte[] original = fixture(1, false);
        byte[] once = RulesCsvCachePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(RulesCsvCachePlan.transform(ClassSignature.parse(once), once));
    }

    private static byte[] fixture(int jsonCalls, boolean attributed) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RulesLoaderPhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                RulesLoaderPhasePlan.LOAD_METHOD, RulesLoaderPhasePlan.LOAD_DESCRIPTOR, null, null);
        method.visitCode();
        for (int index = 0; index < jsonCalls; index++) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitLdcInsn("data/campaign/rules.csv");
            method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.ICONST_1);
            if (attributed) {
                method.visitLdcInsn("rules-csv-merge-parse");
                method.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "dev/starsector/preflight/agent/StartupPhaseRuntime",
                        "specSubphaseStart", "(Ljava/lang/String;)V", false);
            }
            method.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/fs/starfarer/loading/LoadingUtils", "super",
                    "(Ljava/util/List;Ljava/lang/String;ZZ)Lorg/json/JSONArray;", false);
            if (attributed) {
                method.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "dev/starsector/preflight/agent/StartupPhaseRuntime",
                        "specSubphaseEnd", "()V", false);
            }
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

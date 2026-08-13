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

class RulesRegexCachePlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/RulesRegexCacheRuntime";

    @Test
    void replacesTheTenReviewedStringCalls() throws Exception {
        byte[] original = fixture(5, 5);
        byte[] rewritten = RulesRegexCachePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        MethodNode load = load(rewritten);
        assertEquals(5, runtimeCalls(load, "replaceAll"));
        assertEquals(5, runtimeCalls(load, "split"));
    }

    @Test
    void refusesShapeDriftAndDoubleWeaving() throws Exception {
        byte[] changed = fixture(4, 5);
        assertNull(RulesRegexCachePlan.transform(ClassSignature.parse(changed), changed));

        byte[] original = fixture(5, 5);
        byte[] once = RulesRegexCachePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(RulesRegexCachePlan.transform(ClassSignature.parse(once), once));
    }

    private static byte[] fixture(int replacements, int splits) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RulesRegexCachePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                RulesRegexCachePlan.LOAD_METHOD, RulesRegexCachePlan.LOAD_DESCRIPTOR, null, null);
        method.visitCode();
        for (int index = 0; index < replacements; index++) {
            method.visitLdcInsn("text");
            method.visitLdcInsn("\\r");
            method.visitLdcInsn("");
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replaceAll",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
            method.visitInsn(Opcodes.POP);
        }
        for (int index = 0; index < splits; index++) {
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

    private static MethodNode load(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> RulesRegexCachePlan.LOAD_METHOD.equals(method.name)
                        && RulesRegexCachePlan.LOAD_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static int runtimeCalls(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner) && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }
}

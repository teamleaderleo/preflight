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

class ResourcePriorityPlanTest {
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/ResourcePriorityRuntime";

    @Test
    void rewritesOnlyTheReviewedRemoveThenPrependShape() throws Exception {
        byte[] original = fixture(true);
        byte[] rewritten = ResourcePriorityPlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(1, runtimeCalls(rewritten));
        assertNull(ResourcePriorityPlan.transform(ClassSignature.parse(rewritten), rewritten));
    }

    @Test
    void refusesARemoveAllWithoutTheFollowingStablePrepend() throws Exception {
        byte[] drifted = fixture(false);
        assertNull(ResourcePriorityPlan.transform(ClassSignature.parse(drifted), drifted));
    }

    private static byte[] fixture(boolean prepend) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ResourcePriorityPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "resources", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, ResourcePriorityPlan.INIT_METHOD,
                ResourcePriorityPlan.INIT_DESCRIPTOR, null, null);
        init.visitCode();
        init.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ASTORE, 2);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitFieldInsn(Opcodes.GETFIELD, ResourcePriorityPlan.TARGET_CLASS,
                "resources", "Ljava/util/List;");
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "removeAll",
                "(Ljava/util/Collection;)Z", true);
        init.visitInsn(Opcodes.POP);
        if (prepend) {
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitFieldInsn(Opcodes.GETFIELD, ResourcePriorityPlan.TARGET_CLASS,
                    "resources", "Ljava/util/List;");
            init.visitInsn(Opcodes.ICONST_0);
            init.visitVarInsn(Opcodes.ALOAD, 2);
            init.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "addAll",
                    "(ILjava/util/Collection;)Z", true);
            init.visitInsn(Opcodes.POP);
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int runtimeCalls(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode init = owner.methods.stream()
                .filter(method -> ResourcePriorityPlan.INIT_METHOD.equals(method.name)
                        && ResourcePriorityPlan.INIT_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
        int calls = 0;
        for (AbstractInsnNode instruction : init.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)
                    && "removeAll".equals(call.name)) {
                calls++;
            }
        }
        return calls;
    }
}

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

class SpecStorePhasePlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";

    @Test
    void timesEachReviewedTopLevelLoaderExactlyOnce() throws Exception {
        byte[] original = fixture(41);
        byte[] rewritten = SpecStorePhasePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        MethodNode init = init(rewritten);
        assertEquals(41, runtimeCalls(init, "specLoaderStart"));
        assertEquals(41, runtimeCalls(init, "specLoaderEnd"));
    }

    @Test
    void refusesAChangedCoordinatorShape() throws Exception {
        byte[] changed = fixture(40);
        assertNull(SpecStorePhasePlan.transform(ClassSignature.parse(changed), changed));
    }

    @Test
    void refusesToWeaveTwice() throws Exception {
        byte[] original = fixture(41);
        byte[] once = SpecStorePhasePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(SpecStorePhasePlan.transform(ClassSignature.parse(once), once));
    }

    private static byte[] fixture(int loaderCount) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SpecStorePhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                SpecStorePhasePlan.INIT_METHOD, SpecStorePhasePlan.INIT_DESCRIPTOR, null, null);
        method.visitCode();

        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, SpecStorePhasePlan.TARGET_CLASS,
                "new", SpecStorePhasePlan.INIT_DESCRIPTOR, false);
        for (int index = 1; index < loaderCount - 1; index++) {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "fixture/Loader" + index,
                    "load", "()V", false);
        }
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/fs/starfarer/campaign/rules/Rules",
                "super", SpecStorePhasePlan.INIT_DESCRIPTOR, false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodNode init(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> SpecStorePhasePlan.INIT_METHOD.equals(method.name)
                        && SpecStorePhasePlan.INIT_DESCRIPTOR.equals(method.desc))
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

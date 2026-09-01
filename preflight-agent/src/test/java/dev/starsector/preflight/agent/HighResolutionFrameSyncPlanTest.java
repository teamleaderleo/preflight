package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

class HighResolutionFrameSyncPlanTest {
    private static final String RUNTIME = HighResolutionFrameSyncRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void enable() {
        HighResolutionFrameSyncRuntime.beginSessionForTest(true, 2_000_000L);
    }

    @AfterEach
    void reset() {
        HighResolutionFrameSyncRuntime.resetForTest();
    }

    @Test
    void replacesOnlyReviewedMillisecondSleepBlock() throws Exception {
        byte[] original = fixture(1000f, 1);
        byte[] transformed = HighResolutionFrameSyncPlan.transform(ClassSignature.parse(original), original);
        assertNotNull(transformed);

        MethodNode traverse = method(read(transformed));
        assertEquals(1, calls(traverse, RUNTIME, "sleepSeconds"));
        assertEquals(0, calls(traverse, "java/lang/Thread", "sleep"));
        assertEquals(true, HighResolutionFrameSyncRuntime.telemetry().get("installed"));
    }

    @Test
    void declinesDisabledChangedDuplicateWrongClassAndSecondTransform() throws Exception {
        byte[] original = fixture(1000f, 1);
        HighResolutionFrameSyncRuntime.beginSessionForTest(false, 2_000_000L);
        assertNull(HighResolutionFrameSyncPlan.transform(ClassSignature.parse(original), original));

        HighResolutionFrameSyncRuntime.beginSessionForTest(true, 2_000_000L);
        byte[] changed = fixture(999f, 1);
        assertNull(HighResolutionFrameSyncPlan.transform(ClassSignature.parse(changed), changed));
        byte[] duplicate = fixture(1000f, 2);
        assertNull(HighResolutionFrameSyncPlan.transform(ClassSignature.parse(duplicate), duplicate));
        byte[] unsafeEntry = fixtureWithEntryIntoSleepBlock();
        assertNull(HighResolutionFrameSyncPlan.transform(
                ClassSignature.parse(unsafeEntry), unsafeEntry));

        ClassWriter other = new ClassWriter(0);
        other.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Other", null, "java/lang/Object", null);
        other.visitEnd();
        byte[] otherBytes = other.toByteArray();
        assertNull(HighResolutionFrameSyncPlan.transform(ClassSignature.parse(otherBytes), otherBytes));

        byte[] transformed = HighResolutionFrameSyncPlan.transform(ClassSignature.parse(original), original);
        assertNotNull(transformed);
        assertNull(HighResolutionFrameSyncPlan.transform(ClassSignature.parse(transformed), transformed));
    }

    @Test
    void frameTimeDispatcherCarriesExactExternalBaseGameStateTarget() throws Exception {
        byte[] original = fixture(1000f, 1);
        byte[] transformed = FrameTimePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(transformed);
        assertEquals(1, calls(method(read(transformed)), RUNTIME, "sleepSeconds"));
    }

    private static byte[] fixture(float multiplier, int sleepBlocks) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HighResolutionFrameSyncPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor traverse = writer.visitMethod(Opcodes.ACC_PUBLIC,
                HighResolutionFrameSyncPlan.TRAVERSE_METHOD,
                HighResolutionFrameSyncPlan.TRAVERSE_DESCRIPTOR,
                null, new String[] {"java/lang/Exception"});
        traverse.visitCode();
        traverse.visitLdcInsn(0.016666668f);
        traverse.visitVarInsn(Opcodes.FSTORE, 1);
        for (int i = 0; i < sleepBlocks; i++) {
            traverse.visitVarInsn(Opcodes.FLOAD, 1);
            traverse.visitLdcInsn(multiplier);
            traverse.visitInsn(Opcodes.FMUL);
            traverse.visitInsn(Opcodes.F2I);
            traverse.visitVarInsn(Opcodes.ISTORE, 2);
            traverse.visitVarInsn(Opcodes.ILOAD, 2);
            traverse.visitInsn(Opcodes.I2L);
            traverse.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(J)V", false);
        }
        traverse.visitInsn(Opcodes.ACONST_NULL);
        traverse.visitInsn(Opcodes.ARETURN);
        traverse.visitMaxs(2, 3);
        traverse.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] fixtureWithEntryIntoSleepBlock() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HighResolutionFrameSyncPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor traverse = writer.visitMethod(Opcodes.ACC_PUBLIC,
                HighResolutionFrameSyncPlan.TRAVERSE_METHOD,
                HighResolutionFrameSyncPlan.TRAVERSE_DESCRIPTOR,
                null, new String[] {"java/lang/Exception"});
        traverse.visitCode();
        traverse.visitLdcInsn(0.016666668f);
        traverse.visitVarInsn(Opcodes.FSTORE, 1);
        traverse.visitInsn(Opcodes.ICONST_0);
        traverse.visitVarInsn(Opcodes.ISTORE, 2);
        Label inside = new Label();
        traverse.visitInsn(Opcodes.ICONST_0);
        traverse.visitJumpInsn(Opcodes.IFNE, inside);
        traverse.visitVarInsn(Opcodes.FLOAD, 1);
        traverse.visitLdcInsn(1000f);
        traverse.visitInsn(Opcodes.FMUL);
        traverse.visitInsn(Opcodes.F2I);
        traverse.visitVarInsn(Opcodes.ISTORE, 2);
        traverse.visitLabel(inside);
        traverse.visitVarInsn(Opcodes.ILOAD, 2);
        traverse.visitInsn(Opcodes.I2L);
        traverse.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(J)V", false);
        traverse.visitInsn(Opcodes.ACONST_NULL);
        traverse.visitInsn(Opcodes.ARETURN);
        traverse.visitMaxs(2, 3);
        traverse.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner) {
        return owner.methods.stream()
                .filter(candidate -> HighResolutionFrameSyncPlan.TRAVERSE_METHOD.equals(candidate.name)
                        && HighResolutionFrameSyncPlan.TRAVERSE_DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                result++;
            }
        }
        return result;
    }
}

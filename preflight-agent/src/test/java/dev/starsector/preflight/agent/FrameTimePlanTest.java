package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class FrameTimePlanTest {
    private static final String RUNTIME = FrameTimeRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void enable() {
        FrameTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        FrameTimeRuntime.reset();
    }

    @Test
    void observesExistingFocusResultAndDisplayBoundary() throws Exception {
        byte[] original = fixture(false);
        byte[] transformed = FrameTimePlan.transform(exactSignature(original), original);
        assertNotNull(transformed);

        ClassNode owner = read(transformed);
        assertEquals(1, calls(method(owner, FrameTimePlan.UPDATE_METHOD,
                FrameTimePlan.UPDATE_DESCRIPTOR), RUNTIME, "boundary"));
        assertEquals(1, calls(method(owner, FrameTimePlan.UPDATE_METHOD,
                FrameTimePlan.UPDATE_DESCRIPTOR), RUNTIME, "beforeSwap"));
        assertEquals(1, calls(method(owner, FrameTimePlan.UPDATE_METHOD,
                FrameTimePlan.UPDATE_DESCRIPTOR), RUNTIME, "afterSwap"));
        assertEquals(1, calls(method(owner, FrameTimePlan.UPDATE_METHOD,
                FrameTimePlan.UPDATE_DESCRIPTOR), RUNTIME, "beforeMessages"));
        assertEquals(1, calls(method(owner, FrameTimePlan.UPDATE_METHOD,
                FrameTimePlan.UPDATE_DESCRIPTOR), RUNTIME, "afterMessages"));
        assertEquals(1, calls(method(owner, FrameTimePlan.ACTIVE_METHOD,
                FrameTimePlan.ACTIVE_DESCRIPTOR), RUNTIME, "observeActive"));
        assertEquals(1, calls(method(owner, FrameTimePlan.VSYNC_METHOD,
                FrameTimePlan.VSYNC_DESCRIPTOR), RUNTIME, "requestedVsync"));
        assertEquals(true, FrameTimeRuntime.telemetry().get("installed"));
    }

    @Test
    void declinesDisabledWrongIdentityChangedShapeAndSecondTransform() throws Exception {
        byte[] original = fixture(false);
        FrameTimeRuntime.beginSession(false);
        assertNull(FrameTimePlan.transform(exactSignature(original), original));

        FrameTimeRuntime.beginSession(true);
        assertNull(FrameTimePlan.transform(ClassSignature.parse(original), original));
        byte[] changed = fixture(true);
        assertNull(FrameTimePlan.transform(exactSignature(changed), changed));

        byte[] transformed = FrameTimePlan.transform(exactSignature(original), original);
        assertNotNull(transformed);
        assertNull(FrameTimePlan.transform(ClassSignature.parse(transformed), transformed));
    }

    @Test
    void smoothPresentationAloneInstallsOnlyTheRequiredExactPlan() throws Exception {
        FrameTimeRuntime.beginSession(false, true);
        byte[] original = fixture(false);
        byte[] transformed = FrameTimePlan.transform(exactSignature(original), original);
        assertNotNull(transformed);
        assertEquals(false, FrameTimeRuntime.telemetry().get("enabled"));
        assertEquals(true, FrameTimeRuntime.telemetry().get("installed"));
    }

    private static byte[] fixture(boolean omitMessages) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, FrameTimePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        staticVoid(writer, "swapBuffers");
        staticVoid(writer, "processMessages");

        MethodVisitor active = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                FrameTimePlan.ACTIVE_METHOD, FrameTimePlan.ACTIVE_DESCRIPTOR, null, null);
        active.visitCode();
        active.visitInsn(Opcodes.ICONST_1);
        active.visitInsn(Opcodes.IRETURN);
        active.visitMaxs(1, 0);
        active.visitEnd();

        MethodVisitor update = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                FrameTimePlan.UPDATE_METHOD, FrameTimePlan.UPDATE_DESCRIPTOR, null, null);
        update.visitCode();
        update.visitMethodInsn(Opcodes.INVOKESTATIC, FrameTimePlan.TARGET_CLASS,
                "swapBuffers", "()V", false);
        if (!omitMessages) {
            update.visitMethodInsn(Opcodes.INVOKESTATIC, FrameTimePlan.TARGET_CLASS,
                    "processMessages", "()V", false);
        }
        update.visitInsn(Opcodes.RETURN);
        update.visitMaxs(0, 1);
        update.visitEnd();

        MethodVisitor vsync = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                FrameTimePlan.VSYNC_METHOD, FrameTimePlan.VSYNC_DESCRIPTOR, null, null);
        vsync.visitCode();
        vsync.visitInsn(Opcodes.RETURN);
        vsync.visitMaxs(0, 1);
        vsync.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void staticVoid(ClassWriter writer, String name) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), FrameTimePlan.ORIGINAL_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name)
                        && descriptor.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }
}

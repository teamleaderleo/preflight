package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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

class FramePresentationTimingPlanTest {
    private static final String RUNTIME = FrameTimeRuntime.class.getName().replace('.', '/');
    private static final String CPU_RUNTIME = FrameCpuTimeRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void enable() {
        FrameTimeRuntime.beginSession(true);
        FrameCpuTimeRuntime.reset();
    }

    @AfterEach
    void reset() {
        FrameTimeRuntime.reset();
        FrameCpuTimeRuntime.reset();
    }

    @Test
    void wrapsTheExistingSwapAndMeasuresTheWholeDisplayUpdate() throws Exception {
        byte[] original = fixture();
        byte[] transformed = FrameTimePlan.transform(exactSignature(original), original);
        assertNotNull(transformed);

        ClassNode owner = read(transformed);
        MethodNode update = method(owner, FrameTimePlan.UPDATE_METHOD,
                FrameTimePlan.UPDATE_DESCRIPTOR);
        MethodNode active = method(owner, FrameTimePlan.ACTIVE_METHOD,
                FrameTimePlan.ACTIVE_DESCRIPTOR);
        List<String> calls = calls(update);
        List<String> activeCalls = calls(active);

        assertEquals(1, count(calls, RUNTIME + ".displayUpdateStart"));
        assertEquals(1, count(calls, RUNTIME + ".swapBuffersStart"));
        assertEquals(1, count(calls, FrameTimePlan.TARGET_CLASS + ".swapBuffers"));
        assertEquals(1, count(calls, RUNTIME + ".swapBuffersEnd"));
        assertEquals(1, count(calls, CPU_RUNTIME + ".boundary"));
        assertEquals(1, count(calls, RUNTIME + ".boundary"));
        assertEquals(1, count(activeCalls, CPU_RUNTIME + ".observeActive"));
        assertEquals(1, count(activeCalls, RUNTIME + ".observeActive"));
        assertTrue(calls.indexOf(RUNTIME + ".displayUpdateStart")
                < calls.indexOf(RUNTIME + ".swapBuffersStart"));
        assertTrue(calls.indexOf(RUNTIME + ".swapBuffersStart")
                < calls.indexOf(FrameTimePlan.TARGET_CLASS + ".swapBuffers"));
        assertTrue(calls.indexOf(FrameTimePlan.TARGET_CLASS + ".swapBuffers")
                < calls.indexOf(RUNTIME + ".swapBuffersEnd"));
        assertTrue(calls.indexOf(RUNTIME + ".swapBuffersEnd")
                < calls.indexOf(CPU_RUNTIME + ".boundary"));
        assertTrue(calls.indexOf(CPU_RUNTIME + ".boundary")
                < calls.indexOf(RUNTIME + ".boundary"));
    }

    private static byte[] fixture() {
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
        update.visitMethodInsn(Opcodes.INVOKESTATIC, FrameTimePlan.TARGET_CLASS,
                "processMessages", "()V", false);
        update.visitInsn(Opcodes.RETURN);
        update.visitMaxs(0, 1);
        update.visitEnd();
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

    private static List<String> calls(MethodNode method) {
        List<String> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                result.add(call.owner + "." + call.name);
            }
        }
        return result;
    }

    private static int count(List<String> calls, String value) {
        int result = 0;
        for (String call : calls) {
            if (value.equals(call)) result++;
        }
        return result;
    }
}

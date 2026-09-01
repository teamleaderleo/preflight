package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class FrameLimiterTimePlanTest {
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
    void bracketsOnlyTheExactSecondLimiterSleepAndPreservesBothOriginalCalls() throws Exception {
        byte[] original = fixture(50L, true);
        byte[] transformed = FrameLimiterTimePlan.transform(exactSignature(original), original);
        assertNotNull(transformed);

        MethodNode method = method(read(transformed));
        List<MethodInsnNode> sleeps = calls(method, "java/lang/Thread", "sleep");
        assertEquals(2, sleeps.size());
        assertEquals(1, calls(method, RUNTIME, "beforeLimiterSleep").size());
        assertEquals(1, calls(method, RUNTIME, "afterLimiterSleep").size());
        MethodInsnNode limiter = sleeps.get(1);
        assertEquals("beforeLimiterSleep", previousCall(limiter).name);
        assertEquals("afterLimiterSleep", nextCall(limiter).name);
        assertEquals(true, map(FrameTimeRuntime.telemetry().get("frameLimiter")).get("installed"));
    }

    @Test
    void declinesDisabledWrongIdentityChangedShapeAndSecondTransform() throws Exception {
        byte[] original = fixture(50L, true);
        FrameTimeRuntime.beginSession(false);
        assertNull(FrameLimiterTimePlan.transform(exactSignature(original), original));

        FrameTimeRuntime.beginSession(true);
        assertNull(FrameLimiterTimePlan.transform(ClassSignature.parse(original), original));
        byte[] wrongIdleSleep = fixture(40L, true);
        assertNull(FrameLimiterTimePlan.transform(exactSignature(wrongIdleSleep), wrongIdleSleep));
        byte[] missingLimiter = fixture(50L, false);
        assertNull(FrameLimiterTimePlan.transform(exactSignature(missingLimiter), missingLimiter));

        byte[] transformed = FrameLimiterTimePlan.transform(exactSignature(original), original);
        assertNotNull(transformed);
        assertNull(FrameLimiterTimePlan.transform(ClassSignature.parse(transformed), transformed));
    }

    private static byte[] fixture(long idleSleepMillis, boolean includeLimiter) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, FrameLimiterTimePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
                FrameLimiterTimePlan.METHOD, FrameLimiterTimePlan.DESCRIPTOR,
                null, new String[] {"java/lang/Exception"});
        method.visitCode();
        method.visitLdcInsn(idleSleepMillis);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "java/lang/Thread", "sleep", "(J)V", false);
        if (includeLimiter) {
            method.visitInsn(Opcodes.ICONST_2);
            method.visitVarInsn(Opcodes.ISTORE, 1);
            method.visitVarInsn(Opcodes.ILOAD, 1);
            method.visitInsn(Opcodes.I2L);
            method.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Thread", "sleep", "(J)V", false);
        }
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(2, 2);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), FrameLimiterTimePlan.ORIGINAL_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner) {
        return owner.methods.stream()
                .filter(candidate -> FrameLimiterTimePlan.METHOD.equals(candidate.name)
                        && FrameLimiterTimePlan.DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result.add(call);
        }
        return result;
    }

    private static MethodInsnNode previousCall(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getPrevious();
        while (current != null) {
            if (current instanceof MethodInsnNode call) return call;
            current = current.getPrevious();
        }
        throw new AssertionError("missing previous call");
    }

    private static MethodInsnNode nextCall(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getNext();
        while (current != null) {
            if (current instanceof MethodInsnNode call) return call;
            current = current.getNext();
        }
        throw new AssertionError("missing next call");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> map(Object value) {
        return (java.util.Map<String, Object>) value;
    }
}

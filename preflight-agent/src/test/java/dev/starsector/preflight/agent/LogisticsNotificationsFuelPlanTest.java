package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class LogisticsNotificationsFuelPlanTest {
    private static final String TARGET = LogisticsNotificationsFuelPlan.TARGET_CLASS;

    @AfterEach
    void reset() {
        LogisticsNotificationsFuelRuntime.reset();
    }

    @Test
    void constructorTakesTheRealFuelSnapshotBeforeReturning() throws Exception {
        byte[] transformed = LogisticsNotificationsFuelPlan.transform(signature(), fixture(true));
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        assertEquals(1, calls(method(owner, "<init>"), "updateFuelLY"));
        assertEquals(true, LogisticsNotificationsFuelRuntime.telemetry().get("installed"));

        DynamicLoader loader = new DynamicLoader(getClass().getClassLoader());
        Class<?> type = loader.define(TARGET.replace('/', '.'), transformed);
        Object tracker = type.getConstructor().newInstance();
        Method fuel = type.getMethod("getFuelLYRemaining");
        assertEquals(42f, (float) fuel.invoke(tracker));
    }

    @Test
    void changedBodyWrongHashAndSecondRewriteFailClosed() {
        ClassSignature exact = signature();
        assertNull(LogisticsNotificationsFuelPlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), fixture(true)));
        assertNull(LogisticsNotificationsFuelPlan.transform(exact, fixture(false)));
        byte[] once = LogisticsNotificationsFuelPlan.transform(exact, fixture(true));
        assertNotNull(once);
        assertNull(LogisticsNotificationsFuelPlan.transform(exact, once));
    }

    @Test
    void targetPinsTheReviewedModArchiveAndLoader() {
        AdapterTarget target = AdapterTargetRegistry.logisticsNotificationsFuelTarget();
        assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                "file:/game/mods/Log%20Not/jars/LogNot.jar",
                "/game/mods/log not/jars/lognot.jar",
                "MOD",
                target.sourceSha256(),
                "",
                "java/net/URLClassLoader",
                null);
        assertTrue(target.match(signature(), source).exact());
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                TARGET,
                LogisticsNotificationsFuelPlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method("<init>", "()V", Opcodes.ACC_PUBLIC)));
    }

    private static byte[] fixture(boolean includeUpdater) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                TARGET, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "_fuelLYRemaining", "F", null, null).visitEnd();

        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        if (includeUpdater) {
            MethodVisitor update = writer.visitMethod(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "updateFuelLY", "()V", null, null);
            update.visitCode();
            update.visitVarInsn(Opcodes.ALOAD, 0);
            update.visitLdcInsn(42f);
            update.visitFieldInsn(Opcodes.PUTFIELD, TARGET, "_fuelLYRemaining", "F");
            update.visitInsn(Opcodes.RETURN);
            update.visitMaxs(0, 0);
            update.visitEnd();
        }

        MethodVisitor get = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "getFuelLYRemaining", "()F", null, null);
        get.visitCode();
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitFieldInsn(Opcodes.GETFIELD, TARGET, "_fuelLYRemaining", "F");
        get.visitInsn(Opcodes.FRETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode result = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(result, ClassReader.EXPAND_FRAMES);
        return result;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(candidate -> name.equals(candidate.name))
                .findFirst().orElse(null);
    }

    private static int calls(MethodNode method, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && TARGET.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }

    private static final class DynamicLoader extends ClassLoader {
        private DynamicLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

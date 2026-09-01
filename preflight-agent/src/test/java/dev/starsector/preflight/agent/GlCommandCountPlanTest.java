package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.Set;
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

final class GlCommandCountPlanTest {
    private static final String RUNTIME = GlCommandCountRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void enable() {
        System.setProperty(GlCommandCountRuntime.ENABLE_PROPERTY, "true");
        GpuFrameTimeRuntime.beginSession(false);
        GlCommandCountRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        System.clearProperty(GlCommandCountRuntime.ENABLE_PROPERTY);
        GpuFrameTimeRuntime.beginSession(false);
        GlCommandCountRuntime.reset();
    }

    @Test
    void instrumentsEveryReviewedFamilyAndOnlyOnce() throws Exception {
        for (GlCommandCountPlan.Target target : GlCommandCountPlan.targets()) {
            byte[] original = fixture(target, false);
            byte[] transformed = GlCommandCountPlan.transform(
                    exactSignature(target, original), original);
            assertNotNull(transformed, target.idSuffix());
            assertEquals(target.expectedMethods(), runtimeCalls(transformed), target.idSuffix());
            assertNull(GlCommandCountPlan.transform(
                    ClassSignature.parse(transformed), transformed), target.idSuffix());
        }
    }

    @Test
    void declinesDisabledWrongIdentityAndChangedMethodCount() throws Exception {
        GlCommandCountPlan.Target target = GlCommandCountPlan.targets().get(0);
        byte[] original = fixture(target, false);
        GlCommandCountRuntime.beginSession(false);
        assertNull(GlCommandCountPlan.transform(exactSignature(target, original), original));

        GlCommandCountRuntime.beginSession(true);
        assertNull(GlCommandCountPlan.transform(ClassSignature.parse(original), original));
        byte[] changed = fixture(target, true);
        assertNull(GlCommandCountPlan.transform(exactSignature(target, changed), changed));
    }

    private static byte[] fixture(GlCommandCountPlan.Target target, boolean omitOne) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, target.internalName(),
                null, "java/lang/Object", null);
        Set<String> signatures = new HashSet<>();
        boolean omitted = false;
        for (GlCommandCountPlan.Rule rule : target.rules()) {
            String methodName = rule.matches(target.requiredMethod())
                    ? target.requiredMethod()
                    : rule.names().isEmpty()
                            ? rule.prefix() + "Synthetic" : rule.names().iterator().next();
            for (int index = 0; index < rule.expectedMethods(); index++) {
                if (omitOne && !omitted && !methodName.equals(target.requiredMethod())) {
                    omitted = true;
                    continue;
                }
                String descriptor;
                if (methodName.equals(target.requiredMethod())
                        && !signatures.contains(methodName + target.requiredDescriptor())) {
                    descriptor = target.requiredDescriptor();
                } else {
                    int arguments = index + 1;
                    do {
                        descriptor = "(" + "I".repeat(arguments++) + ")V";
                    } while (signatures.contains(methodName + descriptor));
                }
                signatures.add(methodName + descriptor);
                MethodVisitor method = writer.visitMethod(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        methodName, descriptor, null, null);
                method.visitCode();
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(0, argumentSlots(descriptor));
                method.visitEnd();
            }
        }
        if (!signatures.contains(target.requiredMethod() + target.requiredDescriptor())) {
            MethodVisitor required = writer.visitMethod(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    target.requiredMethod(), target.requiredDescriptor(), null, null);
            required.visitCode();
            required.visitInsn(Opcodes.RETURN);
            required.visitMaxs(0, argumentSlots(target.requiredDescriptor()));
            required.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int argumentSlots(String descriptor) {
        return org.objectweb.asm.Type.getArgumentTypes(descriptor).length;
    }

    private static ClassSignature exactSignature(
            GlCommandCountPlan.Target target, byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), target.sha256(),
                parsed.majorVersion(), parsed.access(), parsed.methods());
    }

    private static int runtimeCalls(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int result = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && RUNTIME.equals(call.owner) && "record".equals(call.name)) result++;
            }
        }
        return result;
    }
}

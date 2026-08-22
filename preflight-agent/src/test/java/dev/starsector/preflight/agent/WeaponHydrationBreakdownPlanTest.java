package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class WeaponHydrationBreakdownPlanTest {
    @Test
    void wrapsEveryReviewedNumericCallSite() throws Exception {
        byte[] original = fixture(24, 23);
        ClassSignature parsed = ClassSignature.parse(original);
        ClassSignature exact = new ClassSignature(parsed.internalName(),
                WeaponHydrationBreakdownPlan.ORIGINAL_SHA256, parsed.majorVersion(),
                parsed.access(), parsed.methods());
        ClassNode owner = node(original);

        assertTrue(WeaponHydrationBreakdownPlan.apply(exact, owner));
        assertEquals(94, runtimeCalls(owner));
        assertFalse(WeaponHydrationBreakdownPlan.apply(exact, owner));
    }

    @Test
    void refusesChangedCallShapeBeforeEditingTheClass() throws Exception {
        byte[] original = fixture(23, 23);
        ClassSignature parsed = ClassSignature.parse(original);
        ClassSignature exact = new ClassSignature(parsed.internalName(),
                WeaponHydrationBreakdownPlan.ORIGINAL_SHA256, parsed.majorVersion(),
                parsed.access(), parsed.methods());
        ClassNode owner = node(original);

        assertFalse(WeaponHydrationBreakdownPlan.apply(exact, owner));
        assertEquals(0, runtimeCalls(owner));
    }

    private static byte[] fixture(int weaponCalls, int projectileCalls) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                WeaponLoaderPhasePlan.TARGET_CLASS, null, "java/lang/Object", null);
        method(writer, WeaponLoaderPhasePlan.LOAD_ONE_METHOD,
                WeaponLoaderPhasePlan.LOAD_ONE_DESCRIPTOR, weaponCalls);
        method(writer, ProjectileLoaderPhasePlan.LOAD_ONE_METHOD,
                ProjectileLoaderPhasePlan.LOAD_ONE_DESCRIPTOR, projectileCalls);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void method(ClassWriter writer, String name, String descriptor, int calls) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
        method.visitCode();
        for (int index = 0; index < calls; index++) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitLdcInsn("value" + index);
            method.visitInsn(Opcodes.DCONST_0);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/json/JSONObject", "optDouble",
                    "(Ljava/lang/String;D)D", false);
            method.visitInsn(Opcodes.POP2);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static long runtimeCalls(ClassNode owner) {
        return owner.methods.stream()
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> "dev/starsector/preflight/agent/StartupPhaseRuntime"
                        .equals(call.owner))
                .count();
    }
}

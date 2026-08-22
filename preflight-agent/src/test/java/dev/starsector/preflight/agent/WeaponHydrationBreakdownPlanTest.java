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
        assertEquals(424, runtimeCalls(owner));
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

    @Test
    void refusesChangedProjectileCategoryBeforeEditingTheClass() throws Exception {
        byte[] original = fixture(24, 23, 54, 21, 78, 11);
        ClassSignature parsed = ClassSignature.parse(original);
        ClassSignature exact = new ClassSignature(parsed.internalName(),
                WeaponHydrationBreakdownPlan.ORIGINAL_SHA256, parsed.majorVersion(),
                parsed.access(), parsed.methods());
        ClassNode owner = node(original);

        assertFalse(WeaponHydrationBreakdownPlan.apply(exact, owner));
        assertEquals(0, runtimeCalls(owner));
    }

    private static byte[] fixture(int weaponCalls, int projectileCalls) {
        return fixture(weaponCalls, projectileCalls, 55, 21, 78, 11);
    }

    private static byte[] fixture(int weaponCalls, int projectileCalls,
            int jsonOtherCalls, int schemaCalls, int specCalls, int helperCalls) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                WeaponLoaderPhasePlan.TARGET_CLASS, null, "java/lang/Object", null);
        method(writer, WeaponLoaderPhasePlan.LOAD_ONE_METHOD,
                WeaponLoaderPhasePlan.LOAD_ONE_DESCRIPTOR, weaponCalls, 0, 0, 0, 0);
        method(writer, ProjectileLoaderPhasePlan.LOAD_ONE_METHOD,
                ProjectileLoaderPhasePlan.LOAD_ONE_DESCRIPTOR, projectileCalls,
                jsonOtherCalls, schemaCalls, specCalls, helperCalls);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void method(
            ClassWriter writer, String name, String descriptor, int calls,
            int jsonOtherCalls, int schemaCalls, int specCalls, int helperCalls) {
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
        calls(method, "org/json/JSONObject", "has", jsonOtherCalls);
        calls(method, "com/fs/starfarer/loading/D", "decode", schemaCalls);
        calls(method, "com/fs/starfarer/loading/specs/Example", "setValue", specCalls);
        calls(method, "com/fs/starfarer/loading/scripts/ScriptStore", "load", helperCalls);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void calls(MethodVisitor method, String owner, String name, int count) {
        for (int index = 0; index < count; index++) {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, "()V", false);
        }
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

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

class ShipSystemHydrationBreakdownPlanTest {
    @Test
    void exactWindowsShapeInstallsOneSampledHullLookup() throws Exception {
        byte[] bytes = fixture(1);
        ClassSignature parsed = ClassSignature.parse(bytes);
        ClassSignature windows = new ClassSignature(
                parsed.internalName(), ShipSystemHydrationBreakdownPlan.WINDOWS_ORIGINAL_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
        ClassNode owner = read(bytes);

        assertTrue(ShipSystemHydrationBreakdownPlan.apply(windows, owner));
        assertEquals(1, calls(owner, "sampledHotCallStart"));
        assertEquals(1, calls(owner, "sampledHotCallEnd"));
        assertFalse(ShipSystemHydrationBreakdownPlan.apply(windows, owner));
    }

    @Test
    void refusesLookupShapeDrift() throws Exception {
        byte[] bytes = fixture(2);
        ClassSignature parsed = ClassSignature.parse(bytes);
        ClassSignature windows = new ClassSignature(
                parsed.internalName(), ShipSystemHydrationBreakdownPlan.WINDOWS_ORIGINAL_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
        assertFalse(ShipSystemHydrationBreakdownPlan.apply(windows, read(bytes)));
    }

    private static byte[] fixture(int lookups) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SpecStorePhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                ShipSystemHydrationBreakdownPlan.METHOD,
                ShipSystemHydrationBreakdownPlan.DESCRIPTOR, null, null);
        method.visitCode();
        for (int index = 0; index < lookups; index++) {
            method.visitLdcInsn("hull-" + index);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/fs/starfarer/loading/oO0O", "super",
                    "(Ljava/lang/String;)Lcom/fs/starfarer/loading/specs/g;", false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static long calls(ClassNode owner, String name) {
        String runtime = StartupPhaseRuntime.class.getName().replace('.', '/');
        return owner.methods.stream()
                .flatMap(method -> java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                method.instructions.iterator(), 0), false))
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> runtime.equals(call.owner) && name.equals(call.name))
                .count();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }
}

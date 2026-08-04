package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

class MutableStatDirtyAccessorPlanTest {
    @Test
    void exactGetterAddsOnlyReadOnlySyntheticAccessors() {
        byte[] transformed = MutableStatDirtyAccessorPlan.transform(signature(), fixture(true));
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        MethodNode accessor = method(
                owner,
                MutableStatDirtyAccessorPlan.ACCESSOR,
                MutableStatDirtyAccessorPlan.ACCESSOR_DESCRIPTOR);
        assertTrue((accessor.access & Opcodes.ACC_PUBLIC) != 0);
        assertTrue((accessor.access & Opcodes.ACC_FINAL) != 0);
        assertTrue((accessor.access & Opcodes.ACC_SYNTHETIC) != 0);
        int dirtyReads = 0;
        int returns = 0;
        for (var instruction : accessor.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && "needsRecompute".equals(field.name)) dirtyReads++;
            if (instruction.getOpcode() == Opcodes.IRETURN) returns++;
        }
        assertEquals(1, dirtyReads);
        assertEquals(1, returns);

        MethodNode flatModsAccessor = method(
                owner,
                MutableStatDirtyAccessorPlan.FLAT_MODS_ACCESSOR,
                MutableStatDirtyAccessorPlan.FLAT_MODS_ACCESSOR_DESCRIPTOR);
        assertTrue((flatModsAccessor.access & Opcodes.ACC_PUBLIC) != 0);
        assertTrue((flatModsAccessor.access & Opcodes.ACC_FINAL) != 0);
        assertTrue((flatModsAccessor.access & Opcodes.ACC_SYNTHETIC) != 0);
        int mapReads = 0;
        int objectReturns = 0;
        for (var instruction : flatModsAccessor.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && "flatMods".equals(field.name)) mapReads++;
            if (instruction.getOpcode() == Opcodes.ARETURN) objectReturns++;
        }
        assertEquals(1, mapReads);
        assertEquals(1, objectReturns);
    }

    @Test
    void changedGetterWrongHashAndSecondRewriteFailClosed() {
        ClassSignature exact = signature();
        assertNull(MutableStatDirtyAccessorPlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), fixture(true)));
        assertNull(MutableStatDirtyAccessorPlan.transform(exact, fixture(false)));
        byte[] once = MutableStatDirtyAccessorPlan.transform(exact, fixture(true));
        assertNotNull(once);
        assertNull(MutableStatDirtyAccessorPlan.transform(exact, once));
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                MutableStatDirtyAccessorPlan.TARGET_CLASS,
                MutableStatDirtyAccessorPlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        MutableStatDirtyAccessorPlan.VALUE_METHOD,
                        MutableStatDirtyAccessorPlan.VALUE_DESCRIPTOR,
                        Opcodes.ACC_PUBLIC)));
    }

    private static byte[] fixture(boolean reviewedGetter) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                MutableStatDirtyAccessorPlan.TARGET_CLASS,
                null,
                "java/lang/Object",
                null);
        writer.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT,
                "needsRecompute",
                "Z",
                null,
                null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "modified", "F", null, null).visitEnd();
        writer.visitField(
                Opcodes.ACC_PRIVATE,
                "flatMods",
                "Ljava/util/LinkedHashMap;",
                null,
                null).visitEnd();

        MethodNode recompute = new MethodNode(Opcodes.ACC_PRIVATE, "recompute", "()V", null, null);
        recompute.instructions.add(new InsnNode(Opcodes.RETURN));
        recompute.accept(writer);

        MethodNode getter = new MethodNode(
                Opcodes.ACC_PUBLIC,
                MutableStatDirtyAccessorPlan.VALUE_METHOD,
                MutableStatDirtyAccessorPlan.VALUE_DESCRIPTOR,
                null,
                null);
        if (reviewedGetter) {
            LabelNode clean = new LabelNode();
            getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getter.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    MutableStatDirtyAccessorPlan.TARGET_CLASS,
                    "needsRecompute",
                    "Z"));
            getter.instructions.add(new JumpInsnNode(Opcodes.IFEQ, clean));
            getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getter.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    MutableStatDirtyAccessorPlan.TARGET_CLASS,
                    "recompute",
                    "()V",
                    false));
            getter.instructions.add(clean);
        }
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                MutableStatDirtyAccessorPlan.TARGET_CLASS,
                "modified",
                "F"));
        getter.instructions.add(new InsnNode(Opcodes.FRETURN));
        getter.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst().orElseThrow();
    }
}

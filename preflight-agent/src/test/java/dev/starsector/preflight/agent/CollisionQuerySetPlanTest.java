package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

class CollisionQuerySetPlanTest {
    private static final String LINKED_SET = "java/util/LinkedHashSet";
    private static final String REPLACEMENT =
            CollisionQuerySet.class.getName().replace('.', '/');

    @Test
    void rewritesOnlyTheReviewedConstructorAllocation() throws Exception {
        byte[] original = fixture(false, true);
        byte[] transformed = CollisionQuerySetPlan.transform(exactSignature(original), original);

        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        MethodNode constructor = method(owner, CollisionQuerySetPlan.CONSTRUCTOR,
                CollisionQuerySetPlan.CONSTRUCTOR_DESCRIPTOR);
        MethodNode copy = method(owner, CollisionQuerySetPlan.COPY_METHOD,
                CollisionQuerySetPlan.COPY_DESCRIPTOR);
        assertEquals(0, allocations(constructor, LINKED_SET));
        assertEquals(1, allocations(constructor, REPLACEMENT));
        assertEquals(1, calls(constructor, REPLACEMENT, "<init>"));
        assertEquals(0, calls(constructor, "java/util/Set", "addAll"));
        assertEquals(1, calls(constructor, REPLACEMENT, "addAllFrom"));
        assertEquals(1, calls(constructor, "java/util/Set", "iterator"));
        assertEquals(1, calls(copy, "java/util/Set", "iterator"));
        assertTrue(AdapterTransformationRegistry.hasPlan(CollisionQuerySetPlan.PLAN_ID));
        assertEquals(CollisionQuerySetPlan.PLAN_ID,
                AdapterTargetRegistry.collisionQuerySetTarget().planId());
        assertNull(CollisionQuerySetPlan.transform(ClassSignature.parse(transformed), transformed));
    }

    @Test
    void declinesWrongIdentityAndStructuralDrift() throws Exception {
        byte[] exact = fixture(false, true);
        assertNull(CollisionQuerySetPlan.transform(ClassSignature.parse(exact), exact));

        byte[] duplicateAllocation = fixture(true, true);
        assertNull(CollisionQuerySetPlan.transform(
                exactSignature(duplicateAllocation), duplicateAllocation));

        byte[] missingAddAll = fixture(false, false);
        assertNull(CollisionQuerySetPlan.transform(exactSignature(missingAddAll), missingAddAll));
    }

    private static byte[] fixture(boolean duplicateAllocation, boolean addAll) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CollisionQuerySetPlan.TARGET_CLASS, null, "java/lang/Object", null);
        FieldVisitor set = writer.visitField(Opcodes.ACC_PRIVATE, "set", "Ljava/util/Set;", null, null);
        set.visitEnd();
        FieldVisitor iterator = writer.visitField(
                Opcodes.ACC_PRIVATE, "iterator", "Ljava/util/Iterator;", null, null);
        iterator.visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CollisionQuerySetPlan.CONSTRUCTOR,
                CollisionQuerySetPlan.CONSTRUCTOR_DESCRIPTOR, null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitTypeInsn(Opcodes.NEW, LINKED_SET);
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, LINKED_SET, "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ASTORE, 6);
        if (duplicateAllocation) {
            constructor.visitTypeInsn(Opcodes.NEW, LINKED_SET);
            constructor.visitInsn(Opcodes.DUP);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, LINKED_SET, "<init>", "()V", false);
            constructor.visitInsn(Opcodes.POP);
        }
        if (addAll) {
            constructor.visitVarInsn(Opcodes.ALOAD, 6);
            constructor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List", "of",
                    "()Ljava/util/List;", true);
            constructor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "addAll",
                    "(Ljava/util/Collection;)Z", true);
            constructor.visitInsn(Opcodes.POP);
        }
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 6);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, CollisionQuerySetPlan.TARGET_CLASS,
                "set", "Ljava/util/Set;");
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 6);
        constructor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator",
                "()Ljava/util/Iterator;", true);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, CollisionQuerySetPlan.TARGET_CLASS,
                "iterator", "Ljava/util/Iterator;");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(3, 7);
        constructor.visitEnd();

        MethodVisitor copy = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CollisionQuerySetPlan.COPY_METHOD, CollisionQuerySetPlan.COPY_DESCRIPTOR, null, null);
        copy.visitCode();
        copy.visitVarInsn(Opcodes.ALOAD, 0);
        copy.visitFieldInsn(Opcodes.GETFIELD, CollisionQuerySetPlan.TARGET_CLASS,
                "set", "Ljava/util/Set;");
        copy.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator",
                "()Ljava/util/Iterator;", true);
        copy.visitInsn(Opcodes.POP);
        copy.visitInsn(Opcodes.ACONST_NULL);
        copy.visitInsn(Opcodes.ARETURN);
        copy.visitMaxs(1, 1);
        copy.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), CollisionQuerySetPlan.ORIGINAL_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int allocations(MethodNode method, String type) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW && type.equals(allocation.desc)) count++;
        }
        return count;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }
}

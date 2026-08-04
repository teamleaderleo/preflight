package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Adds a read-only dirty-state accessor to the exact MutableStat used by the commodity memo. */
final class MutableStatDirtyAccessorPlan {
    static final String TARGET_CLASS = "com/fs/starfarer/api/combat/MutableStat";
    static final String ORIGINAL_SHA256 =
            "f16516f0f15da4026297f13cbc406a980ae89cdf721f5a40b3fae4385828f9b0";
    static final String VALUE_METHOD = "getModifiedValue";
    static final String VALUE_DESCRIPTOR = "()F";
    static final String ACCESSOR = "preflight$needsRecompute";
    static final String ACCESSOR_DESCRIPTOR = "()Z";

    private static final String DIRTY_FIELD = "needsRecompute";

    private MutableStatDirtyAccessorPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(VALUE_METHOD, VALUE_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (hasMethod(owner, ACCESSOR, ACCESSOR_DESCRIPTOR)
                || !reviewDirtyField(owner)
                || !reviewValueGetter(owner)) {
            return null;
        }

        MethodNode accessor = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                ACCESSOR,
                ACCESSOR_DESCRIPTOR,
                null,
                null);
        accessor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        accessor.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, DIRTY_FIELD, "Z"));
        accessor.instructions.add(new InsnNode(Opcodes.IRETURN));
        owner.methods.add(accessor);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static boolean reviewDirtyField(ClassNode owner) {
        FieldNode match = null;
        for (FieldNode field : owner.fields) {
            if (DIRTY_FIELD.equals(field.name) && "Z".equals(field.desc)) {
                if (match != null) return false;
                match = field;
            }
        }
        return match != null
                && (match.access & Opcodes.ACC_PRIVATE) != 0
                && (match.access & Opcodes.ACC_TRANSIENT) != 0
                && (match.access & Opcodes.ACC_STATIC) == 0;
    }

    private static boolean reviewValueGetter(ClassNode owner) {
        MethodNode method = unique(owner, VALUE_METHOD, VALUE_DESCRIPTOR);
        if (method == null) return false;
        int dirtyReads = 0;
        int modifiedReads = 0;
        int recomputes = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && instruction.getOpcode() == Opcodes.GETFIELD
                    && TARGET_CLASS.equals(field.owner)) {
                if (DIRTY_FIELD.equals(field.name) && "Z".equals(field.desc)) dirtyReads++;
                if ("modified".equals(field.name) && "F".equals(field.desc)) modifiedReads++;
            } else if (instruction instanceof MethodInsnNode call
                    && instruction.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && TARGET_CLASS.equals(call.owner)
                    && "recompute".equals(call.name)
                    && "()V".equals(call.desc)) {
                recomputes++;
            }
        }
        return dirtyReads == 1 && modifiedReads == 1 && recomputes == 1;
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode result = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (result != null) return null;
                result = method;
            }
        }
        return result;
    }

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .anyMatch(method -> name.equals(method.name) && descriptor.equals(method.desc));
    }
}

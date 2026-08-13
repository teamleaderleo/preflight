package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Gives the reviewed repository's entity list an O(1) mutation generation. */
final class EntityRepositoryListPlan {
    static final String TARGET_CLASS = "com/fs/util/container/repo/ObjectRepository";
    static final String ORIGINAL_SHA256 =
            "f265705e58950a2b24b9f0c51491624e0452558b3215af6cc280f3e566869248";
    static final String GET_LIST_METHOD = "getList";
    static final String GET_LIST_DESCRIPTOR = "(Ljava/lang/Class;)Ljava/util/List;";
    static final String ADD_METHOD = "add";
    static final String ADD_DESCRIPTOR = "(Ljava/lang/Object;)Z";

    private static final String ARRAY_LIST = "java/util/ArrayList";
    private static final String RUNTIME = "dev/starsector/preflight/agent/EntityLookupRuntime";

    private EntityRepositoryListPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(GET_LIST_METHOD, GET_LIST_DESCRIPTOR)
                || !signature.hasMethod(ADD_METHOD, ADD_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode getList = uniqueMethod(owner, GET_LIST_METHOD, GET_LIST_DESCRIPTOR);
        MethodNode add = uniqueMethod(owner, ADD_METHOD, ADD_DESCRIPTOR);
        if (!eligible(getList) || !eligible(add)
                || !replaceAllocation(getList, 1)
                || !replaceAllocation(add, 5)) {
            return null;
        }

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        EntityLookupRuntime.repositoryInstalled();
        return writer.toByteArray();
    }

    /** Replaces the exact method's sole empty ArrayList allocation with the tracked-list factory. */
    private static boolean replaceAllocation(MethodNode method, int classifiedTypeLocal) {
        TypeInsnNode allocation = null;
        AbstractInsnNode duplicate = null;
        MethodInsnNode constructor = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof TypeInsnNode type)
                    || type.getOpcode() != Opcodes.NEW
                    || !ARRAY_LIST.equals(type.desc)) {
                continue;
            }
            AbstractInsnNode next = nextReal(type);
            AbstractInsnNode after = nextReal(next);
            if (next != null && next.getOpcode() == Opcodes.DUP
                    && after instanceof MethodInsnNode init
                    && init.getOpcode() == Opcodes.INVOKESPECIAL
                    && ARRAY_LIST.equals(init.owner)
                    && "<init>".equals(init.name)
                    && "()V".equals(init.desc)) {
                if (allocation != null) {
                    return false;
                }
                allocation = type;
                duplicate = next;
                constructor = init;
            }
        }
        if (allocation == null) {
            return false;
        }

        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, classifiedTypeLocal));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "newRepositoryList",
                "(Ljava/lang/Object;)Ljava/util/List;",
                false));
        method.instructions.insertBefore(allocation, replacement);
        method.instructions.remove(allocation);
        method.instructions.remove(duplicate);
        method.instructions.remove(constructor);
        return true;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        for (AbstractInsnNode cursor = instruction == null ? null : instruction.getNext();
                cursor != null;
                cursor = cursor.getNext()) {
            if (cursor.getOpcode() >= 0) {
                return cursor;
            }
        }
        return null;
    }

    private static boolean eligible(MethodNode method) {
        return method != null && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0;
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }
}

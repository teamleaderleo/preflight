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

    private static final String ARRAY_LIST = "java/util/ArrayList";
    private static final String RUNTIME = "dev/starsector/preflight/agent/EntityLookupRuntime";

    private EntityRepositoryListPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(GET_LIST_METHOD, GET_LIST_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = uniqueMethod(owner, GET_LIST_METHOD, GET_LIST_DESCRIPTOR);
        if (!eligible(method)) {
            return null;
        }

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
                    return null;
                }
                allocation = type;
                duplicate = next;
                constructor = init;
            }
        }
        if (allocation == null) {
            return null;
        }

        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "newRepositoryList",
                "(Ljava/lang/Class;)Ljava/util/List;",
                false));
        method.instructions.insertBefore(allocation, replacement);
        method.instructions.remove(allocation);
        method.instructions.remove(duplicate);
        method.instructions.remove(constructor);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        EntityLookupRuntime.repositoryInstalled();
        return writer.toByteArray();
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

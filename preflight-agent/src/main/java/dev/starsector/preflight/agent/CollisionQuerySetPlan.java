package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Replaces one exact collision-query LinkedHashSet with its node-free ordered equivalent. */
final class CollisionQuerySetPlan {
    static final String PLAN_ID = "collision-query-open-set-v1";
    static final String TARGET_CLASS = "com/fs/starfarer/combat/o0OO/G$o";
    static final String ORIGINAL_SHA256 =
            "fd932939e0a61ebf73e56e48e06e66b18dcb311ca6a355a274a1df974173dd28";
    static final String CONSTRUCTOR = "<init>";
    static final String CONSTRUCTOR_DESCRIPTOR =
            "(Lcom/fs/starfarer/combat/o0OO/G;IIII)V";
    static final String COPY_METHOD = "o00000";
    static final String COPY_DESCRIPTOR = "()Lcom/fs/starfarer/combat/o0OO/G$o;";

    private static final String LINKED_SET = "java/util/LinkedHashSet";
    private static final String SET = "java/util/Set";
    private static final String REPLACEMENT =
            "dev/starsector/preflight/agent/CollisionQuerySet";
    private static final String ADD_ALL_FROM_DESCRIPTOR =
            "(Ljava/util/Set;Ljava/util/Collection;)Z";

    private CollisionQuerySetPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(CONSTRUCTOR, CONSTRUCTOR_DESCRIPTOR)
                || !signature.hasMethod(COPY_METHOD, COPY_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode constructor = unique(owner, CONSTRUCTOR, CONSTRUCTOR_DESCRIPTOR);
        MethodNode copy = unique(owner, COPY_METHOD, COPY_DESCRIPTOR);
        if (constructor == null || copy == null) return null;

        TypeInsnNode allocation = null;
        MethodInsnNode initialization = null;
        MethodInsnNode addAll = null;
        int constructorIteratorCalls = 0;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW && LINKED_SET.equals(type.desc)) {
                if (allocation != null) return null;
                allocation = type;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && LINKED_SET.equals(call.owner)
                    && CONSTRUCTOR.equals(call.name) && "()V".equals(call.desc)) {
                if (initialization != null) return null;
                initialization = call;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && SET.equals(call.owner) && "addAll".equals(call.name)
                    && "(Ljava/util/Collection;)Z".equals(call.desc)) {
                if (addAll != null) return null;
                addAll = call;
            }
            if (isSetIterator(instruction)) constructorIteratorCalls++;
        }
        int copyIteratorCalls = 0;
        for (AbstractInsnNode instruction : copy.instructions) {
            if (isSetIterator(instruction)) copyIteratorCalls++;
        }
        if (allocation == null || initialization == null || addAll == null
                || constructorIteratorCalls != 1 || copyIteratorCalls != 1
                || previousCode(initialization) == null
                || previousCode(initialization).getOpcode() != Opcodes.DUP
                || previousCode(previousCode(initialization)) != allocation) {
            return null;
        }

        allocation.desc = REPLACEMENT;
        initialization.owner = REPLACEMENT;
        addAll.setOpcode(Opcodes.INVOKESTATIC);
        addAll.owner = REPLACEMENT;
        addAll.name = "addAllFrom";
        addAll.desc = ADD_ALL_FROM_DESCRIPTOR;
        addAll.itf = false;
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static boolean isSetIterator(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEINTERFACE
                && SET.equals(call.owner) && "iterator".equals(call.name)
                && "()Ljava/util/Iterator;".equals(call.desc);
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }
}

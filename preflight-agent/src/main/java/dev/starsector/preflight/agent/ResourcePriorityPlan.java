package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Indexes the one large removeAll used to move prioritized resources to the front. */
final class ResourcePriorityPlan {
    static final String TARGET_CLASS = StartupPhasePlan.TARGET_CLASS;
    static final String ORIGINAL_SHA256 = FrameTimeStartupCompletionPlan.ORIGINAL_SHA256;
    static final String INIT_METHOD = StartupPhasePlan.INIT_METHOD;
    static final String INIT_DESCRIPTOR = StartupPhasePlan.INIT_DESCRIPTOR;
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/ResourcePriorityRuntime";
    private static final String LIST = "java/util/List";
    private static final String REMOVE_ALL = "removeAll";
    private static final String REMOVE_DESCRIPTOR = "(Ljava/util/Collection;)Z";
    private static final String STATIC_DESCRIPTOR =
            "(Ljava/util/List;Ljava/util/Collection;)Z";

    private ResourcePriorityPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!apply(signature, owner)) return null;
        return write(owner);
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(INIT_METHOD, INIT_DESCRIPTOR)) {
            return false;
        }
        MethodNode init = uniqueMethod(owner, INIT_METHOD, INIT_DESCRIPTOR);
        if (init == null || hasRuntimeCall(init)) {
            return false;
        }
        List<MethodInsnNode> removals = calls(
                init, Opcodes.INVOKEINTERFACE, LIST, REMOVE_ALL, REMOVE_DESCRIPTOR);
        if (removals.size() != 1 || !isReviewedPriorityMove(removals.get(0))) {
            return false;
        }
        init.instructions.set(removals.get(0), new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, REMOVE_ALL, STATIC_DESCRIPTOR, false));
        return true;
    }

    static byte[] write(ClassNode owner) {
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static boolean isReviewedPriorityMove(MethodInsnNode removal) {
        AbstractInsnNode argument = previousOpcode(removal);
        AbstractInsnNode field = previousOpcode(argument);
        AbstractInsnNode receiver = previousOpcode(field);
        if (!(argument instanceof VarInsnNode selected) || selected.getOpcode() != Opcodes.ALOAD
                || !(field instanceof FieldInsnNode resources) || resources.getOpcode() != Opcodes.GETFIELD
                || !TARGET_CLASS.equals(resources.owner) || !"resources".equals(resources.name)
                || !"Ljava/util/List;".equals(resources.desc)
                || !(receiver instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD
                || self.var != 0) {
            return false;
        }

        AbstractInsnNode pop = nextOpcode(removal);
        AbstractInsnNode nextSelf = nextOpcode(pop);
        AbstractInsnNode nextField = nextOpcode(nextSelf);
        AbstractInsnNode zero = nextOpcode(nextField);
        AbstractInsnNode nextArgument = nextOpcode(zero);
        AbstractInsnNode addAll = nextOpcode(nextArgument);
        return pop != null && pop.getOpcode() == Opcodes.POP
                && nextSelf instanceof VarInsnNode selfAgain && selfAgain.getOpcode() == Opcodes.ALOAD
                && selfAgain.var == 0
                && nextField instanceof FieldInsnNode resourcesAgain
                && resourcesAgain.getOpcode() == Opcodes.GETFIELD
                && TARGET_CLASS.equals(resourcesAgain.owner)
                && "resources".equals(resourcesAgain.name)
                && "Ljava/util/List;".equals(resourcesAgain.desc)
                && zero != null && zero.getOpcode() == Opcodes.ICONST_0
                && nextArgument instanceof VarInsnNode selectedAgain
                && selectedAgain.getOpcode() == Opcodes.ALOAD && selectedAgain.var == selected.var
                && addAll instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEINTERFACE
                && LIST.equals(call.owner) && "addAll".equals(call.name)
                && "(ILjava/util/Collection;)Z".equals(call.desc);
    }

    private static boolean hasRuntimeCall(MethodNode method) {
        return !calls(method, Opcodes.INVOKESTATIC, RUNTIME, REMOVE_ALL, STATIC_DESCRIPTOR).isEmpty();
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, int opcode, String owner, String name, String descriptor) {
        List<MethodInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                matches.add(call);
            }
        }
        return matches;
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getPrevious();
        return cursor;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getNext();
        return cursor;
    }
}

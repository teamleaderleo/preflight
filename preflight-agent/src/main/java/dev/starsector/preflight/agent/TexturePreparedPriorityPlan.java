package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Starts the exact Windows prepared worker after stock resource prioritization. */
final class TexturePreparedPriorityPlan {
    private static final String GRAPHICS = TexturePreparedPrefetchPlan.TARGET_CLASS;
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/TexturePreparedPixelRuntime";
    private static final String LIST = "java/util/List";

    private TexturePreparedPriorityPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!apply(signature, owner)) {
            return null;
        }
        return ResourcePriorityPlan.write(owner);
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!Boolean.getBoolean(TexturePreparedPrefetchPlan.WINDOWS_RESOURCE_ORDER_PROPERTY)
                || !FrameTimeStartupCompletionPlan.WINDOWS_ORIGINAL_SHA256.equals(
                        signature.sha256())
                || !ResourcePriorityPlan.TARGET_CLASS.equals(signature.internalName())) {
            return false;
        }
        MethodNode init = uniqueMethod(
                owner, ResourcePriorityPlan.INIT_METHOD, ResourcePriorityPlan.INIT_DESCRIPTOR);
        MethodInsnNode workerStart = uniqueCall(init, GRAPHICS, "o00000", "()V");
        MethodInsnNode prepend = uniqueCall(
                init, LIST, "addAll", "(ILjava/util/Collection;)Z");
        AbstractInsnNode prependResult = nextOpcode(prepend);
        FieldInsnNode resources = resourcesReceiver(prepend);
        if (init == null || workerStart == null || prepend == null || resources == null
                || prependResult == null || prependResult.getOpcode() != Opcodes.POP
                || !comesBefore(workerStart, prepend)
                || hasRuntimeCall(init)) {
            return false;
        }

        init.instructions.remove(workerStart);
        InsnList delayedStart = new InsnList();
        delayedStart.add(new VarInsnNode(Opcodes.ALOAD, 0));
        delayedStart.add(new FieldInsnNode(
                Opcodes.GETFIELD, resources.owner, resources.name, resources.desc));
        delayedStart.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "rememberPreparedPrefetchOrder",
                "(Ljava/util/List;)V",
                false));
        delayedStart.add(workerStart);
        init.instructions.insert(prependResult, delayedStart);
        return true;
    }

    private static FieldInsnNode resourcesReceiver(MethodInsnNode prepend) {
        AbstractInsnNode selected = previousOpcode(prepend);
        AbstractInsnNode zero = previousOpcode(selected);
        AbstractInsnNode field = previousOpcode(zero);
        AbstractInsnNode self = previousOpcode(field);
        if (!(selected instanceof VarInsnNode variable) || variable.getOpcode() != Opcodes.ALOAD
                || zero == null || zero.getOpcode() != Opcodes.ICONST_0
                || !(field instanceof FieldInsnNode resources)
                || resources.getOpcode() != Opcodes.GETFIELD
                || !ResourcePriorityPlan.TARGET_CLASS.equals(resources.owner)
                || !"resources".equals(resources.name)
                || !"Ljava/util/List;".equals(resources.desc)
                || !(self instanceof VarInsnNode receiver)
                || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 0) {
            return null;
        }
        return resources;
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

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String descriptor) {
        if (method == null) return null;
        List<MethodInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                matches.add(call);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean hasRuntimeCall(MethodNode method) {
        return uniqueCall(
                method, RUNTIME, "rememberPreparedPrefetchOrder", "(Ljava/util/List;)V") != null;
    }

    private static boolean comesBefore(AbstractInsnNode first, AbstractInsnNode second) {
        for (AbstractInsnNode cursor = first; cursor != null; cursor = cursor.getNext()) {
            if (cursor == second) return true;
        }
        return false;
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

package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Caps loading-screen redraws while preserving the final 100% frame. */
final class ResourceProgressRateLimitPlan {
    private static final String TARGET = ResourcePriorityPlan.TARGET_CLASS;
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/ResourcePriorityRuntime";
    private static final String RENDER = "renderProgress";
    private static final String RENDER_DESCRIPTOR = "(F)V";

    private ResourceProgressRateLimitPlan() {
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!TARGET.equals(signature.internalName())
                || !signature.hasMethod(ResourcePriorityPlan.INIT_METHOD,
                        ResourcePriorityPlan.INIT_DESCRIPTOR)) {
            return false;
        }
        MethodNode init = unique(owner, ResourcePriorityPlan.INIT_METHOD,
                ResourcePriorityPlan.INIT_DESCRIPTOR);
        MethodNode render = unique(owner, RENDER, RENDER_DESCRIPTOR);
        if (init == null || render == null || alreadyApplied(render)) {
            return false;
        }
        List<MethodInsnNode> shutdowns = calls(init, "java/util/concurrent/ExecutorService",
                "shutdown", "()V");
        if (shutdowns.size() != 1 || calls(init, TARGET, RENDER, RENDER_DESCRIPTOR).size() != 4) {
            return false;
        }

        LabelNode draw = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.FLOAD, 1));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "shouldRenderProgress", "(F)Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, draw));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(draw);
        render.instructions.insertBefore(render.instructions.getFirst(), guard);

        InsnList finalFrame = new InsnList();
        finalFrame.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finalFrame.add(new InsnNode(Opcodes.FCONST_1));
        finalFrame.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, TARGET, RENDER, RENDER_DESCRIPTOR, false));
        init.instructions.insertBefore(shutdowns.get(0), finalFrame);
        return true;
    }

    static byte[] transform(ClassSignature signature, byte[] input) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(input).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!apply(signature, owner)) {
            return null;
        }
        return ResourcePriorityPlan.write(owner);
    }

    private static boolean alreadyApplied(MethodNode method) {
        return !calls(method, RUNTIME, "shouldRenderProgress", "(F)Z").isEmpty();
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
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

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                matches.add(call);
            }
        }
        return matches;
    }
}

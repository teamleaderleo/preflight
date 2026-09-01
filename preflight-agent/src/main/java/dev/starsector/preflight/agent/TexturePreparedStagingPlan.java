package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Starts bounded prepared-carrier staging before SpecStore and settles it before init returns. */
final class TexturePreparedStagingPlan {
    static final String TARGET_CLASS = StartupPhasePlan.TARGET_CLASS;
    static final String INIT_METHOD = StartupPhasePlan.INIT_METHOD;
    static final String INIT_DESCRIPTOR = StartupPhasePlan.INIT_DESCRIPTOR;
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/TexturePreparedStagingRuntime";

    private TexturePreparedStagingPlan() {
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
        if (!Boolean.getBoolean(TexturePreparedStagingRuntime.ENABLED_PROPERTY)
                || !TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(INIT_METHOD, INIT_DESCRIPTOR)) {
            return false;
        }
        MethodNode init = uniqueMethod(owner, INIT_METHOD, INIT_DESCRIPTOR);
        MethodInsnNode specStore = uniqueCall(
                init,
                "com/fs/starfarer/loading/SpecStore",
                "(Lcom/fs/starfarer/loading/ResourceLoaderState;)V");
        AbstractInsnNode onlyReturn = uniqueReturn(init);
        if (init == null || specStore == null || onlyReturn == null || hasRuntimeCall(init)) {
            return false;
        }
        init.instructions.insertBefore(specStore, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "start", "()V", false));
        init.instructions.insertBefore(onlyReturn, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "stop", "()V", false));
        return true;
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        if (owner == null) return null;
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner, String descriptor) {
        if (method == null) return null;
        List<MethodInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && descriptor.equals(call.desc)) {
                matches.add(call);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static AbstractInsnNode uniqueReturn(MethodNode method) {
        if (method == null) return null;
        AbstractInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                if (found != null) return null;
                found = instruction;
            }
        }
        return found;
    }

    private static boolean hasRuntimeCall(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) {
                return true;
            }
        }
        return false;
    }
}

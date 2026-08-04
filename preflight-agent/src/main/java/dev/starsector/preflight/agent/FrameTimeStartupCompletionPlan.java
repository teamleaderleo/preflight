package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Marks the end of resource loading when full startup-phase attribution is not installed. */
final class FrameTimeStartupCompletionPlan {
    static final String PLAN_ID = "vanilla-frame-time-startup-completion-v1";
    static final String TARGET_CLASS = StartupPhasePlan.TARGET_CLASS;
    static final String ORIGINAL_SHA256 =
            "a64927cec70db4e15d54b8611073b4008ba62878e0208f30ca338d66377214ab";
    static final String INIT_METHOD = StartupPhasePlan.INIT_METHOD;
    static final String INIT_DESCRIPTOR = StartupPhasePlan.INIT_DESCRIPTOR;
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/FrameTimeRuntime";

    private FrameTimeStartupCompletionPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!FrameTimeRuntime.enabled()
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || !signature.hasMethod(INIT_METHOD, INIT_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode init = uniqueMethod(owner, INIT_METHOD, INIT_DESCRIPTOR);
        AbstractInsnNode onlyReturn = init == null ? null : uniqueReturn(init);
        if (onlyReturn == null || callsMarker(init) != 0) {
            return null;
        }

        init.instructions.insertBefore(onlyReturn, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "markStartupComplete", "()V", false));
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
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

    private static AbstractInsnNode uniqueReturn(MethodNode method) {
        AbstractInsnNode found = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                if (found != null) return null;
                found = instruction;
            }
        }
        return found;
    }

    private static int callsMarker(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner)
                    && "markStartupComplete".equals(call.name)
                    && "()V".equals(call.desc)) {
                count++;
            }
        }
        return count;
    }
}

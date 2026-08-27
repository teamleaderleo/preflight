package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Observes LWJGL's existing focus result, presentation phases, and display-update boundary. */
final class FrameTimePlan {
    static final String TARGET_CLASS = "org/lwjgl/opengl/Display";
    static final String ORIGINAL_SHA256 =
            "054d89b13a904edd041df8ceba72c068070d7c9cc2dbfdd20d5acb3cdf109526";
    static final String UPDATE_METHOD = "update";
    static final String UPDATE_DESCRIPTOR = "(Z)V";
    static final String ACTIVE_METHOD = "isActive";
    static final String ACTIVE_DESCRIPTOR = "()Z";
    static final String VSYNC_METHOD = "setVSyncEnabled";
    static final String VSYNC_DESCRIPTOR = "(Z)V";
    static final String SWAP_INTERVAL_METHOD = "setSwapInterval";
    static final String SWAP_INTERVAL_DESCRIPTOR = "(I)V";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/FrameTimeRuntime";

    private FrameTimePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!FrameTimeRuntime.planEnabled()
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 49
                || !signature.hasMethod(UPDATE_METHOD, UPDATE_DESCRIPTOR)
                || !signature.hasMethod(ACTIVE_METHOD, ACTIVE_DESCRIPTOR)
                || !signature.hasMethod(VSYNC_METHOD, VSYNC_DESCRIPTOR)
                || !signature.hasMethod(SWAP_INTERVAL_METHOD, SWAP_INTERVAL_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode update = unique(owner, UPDATE_METHOD, UPDATE_DESCRIPTOR);
        MethodNode active = unique(owner, ACTIVE_METHOD, ACTIVE_DESCRIPTOR);
        MethodNode vsync = unique(owner, VSYNC_METHOD, VSYNC_DESCRIPTOR);
        MethodNode swapInterval = unique(owner, SWAP_INTERVAL_METHOD, SWAP_INTERVAL_DESCRIPTOR);
        if (update == null || active == null || vsync == null || swapInterval == null
                || returns(update, Opcodes.RETURN) != 1
                || returns(active, Opcodes.IRETURN) != 1
                || calls(update, TARGET_CLASS, "swapBuffers") != 1
                || calls(update, TARGET_CLASS, "processMessages") != 1
                || calls(update, RUNTIME, "boundary") != 0
                || calls(update, RUNTIME, "beforeSwap") != 0
                || calls(update, RUNTIME, "afterSwap") != 0
                || calls(update, RUNTIME, "beforeMessages") != 0
                || calls(update, RUNTIME, "afterMessages") != 0
                || calls(active, RUNTIME, "observeActive") != 0
                || calls(vsync, RUNTIME, "requestedVsync") != 0
                || calls(vsync, TARGET_CLASS, SWAP_INTERVAL_METHOD) != 1
                || calls(swapInterval, RUNTIME, "observeSwapInterval") != 0) {
            return null;
        }

        AbstractInsnNode updateReturn = uniqueReturn(update, Opcodes.RETURN);
        AbstractInsnNode activeReturn = uniqueReturn(active, Opcodes.IRETURN);
        MethodInsnNode swap = uniqueCall(update, TARGET_CLASS, "swapBuffers");
        MethodInsnNode messages = uniqueCall(update, TARGET_CLASS, "processMessages");
        if (swap == null || messages == null) return null;

        update.instructions.insertBefore(swap, runtimeCall("beforeSwap"));
        update.instructions.insert(swap, runtimeCall("afterSwap"));
        update.instructions.insertBefore(messages, runtimeCall("beforeMessages"));
        update.instructions.insert(messages, runtimeCall("afterMessages"));
        InsnList boundary = new InsnList();
        boundary.add(runtimeCall("boundary"));
        update.instructions.insertBefore(updateReturn, boundary);

        InsnList focus = new InsnList();
        focus.add(new InsnNode(Opcodes.DUP));
        focus.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "observeActive", "(Z)V", false));
        active.instructions.insertBefore(activeReturn, focus);

        InsnList vsyncPolicy = new InsnList();
        vsyncPolicy.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 0));
        vsyncPolicy.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "requestedVsync", "(Z)Z", false));
        vsyncPolicy.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ISTORE, 0));
        vsync.instructions.insert(vsyncPolicy);

        InsnList swapIntervalObservation = new InsnList();
        swapIntervalObservation.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 0));
        swapIntervalObservation.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "observeSwapInterval", "(I)V", false));
        swapInterval.instructions.insert(swapIntervalObservation);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        FrameTimeRuntime.installed();
        return writer.toByteArray();
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

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                result++;
            }
        }
        return result;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner, String name) {
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                if (result != null) return null;
                result = call;
            }
        }
        return result;
    }

    private static MethodInsnNode runtimeCall(String name) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, name, "()V", false);
    }

    private static int returns(MethodNode method, int opcode) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) result++;
        }
        return result;
    }

    private static AbstractInsnNode uniqueReturn(MethodNode method, int opcode) {
        AbstractInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) {
                if (result != null) return null;
                result = instruction;
            }
        }
        return result;
    }
}

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

/** Observes LWJGL's existing focus result and measures display/presentation spans per update. */
final class FrameTimePlan {
    static final String TARGET_CLASS = "org/lwjgl/opengl/Display";
    static final String ORIGINAL_SHA256 =
            "054d89b13a904edd041df8ceba72c068070d7c9cc2dbfdd20d5acb3cdf109526";
    static final String UPDATE_METHOD = "update";
    static final String UPDATE_DESCRIPTOR = "(Z)V";
    static final String ACTIVE_METHOD = "isActive";
    static final String ACTIVE_DESCRIPTOR = "()Z";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/FrameTimeRuntime";
    private static final String CPU_RUNTIME =
            "dev/starsector/preflight/agent/FrameCpuTimeRuntime";
    private static final String PACING_RUNTIME =
            "dev/starsector/preflight/agent/FramePacingRuntime";

    private FrameTimePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!FrameTimeRuntime.enabled()
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 49
                || !signature.hasMethod(UPDATE_METHOD, UPDATE_DESCRIPTOR)
                || !signature.hasMethod(ACTIVE_METHOD, ACTIVE_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode update = unique(owner, UPDATE_METHOD, UPDATE_DESCRIPTOR);
        MethodNode active = unique(owner, ACTIVE_METHOD, ACTIVE_DESCRIPTOR);
        if (update == null || active == null
                || returns(update, Opcodes.RETURN) != 1
                || returns(active, Opcodes.IRETURN) != 1
                || calls(update, TARGET_CLASS, "swapBuffers") != 1
                || calls(update, TARGET_CLASS, "processMessages") != 1
                || calls(update, RUNTIME, "displayUpdateStart") != 0
                || calls(update, RUNTIME, "swapBuffersStart") != 0
                || calls(update, RUNTIME, "swapBuffersEnd") != 0
                || calls(update, CPU_RUNTIME, "boundary") != 0
                || calls(update, RUNTIME, "boundary") != 0
                || calls(update, PACING_RUNTIME, "awaitNextFrame") != 0
                || calls(active, CPU_RUNTIME, "observeActive") != 0
                || calls(active, RUNTIME, "observeActive") != 0) {
            return null;
        }

        AbstractInsnNode updateReturn = uniqueReturn(update, Opcodes.RETURN);
        AbstractInsnNode activeReturn = uniqueReturn(active, Opcodes.IRETURN);
        MethodInsnNode swapBuffers = uniqueCall(update, TARGET_CLASS, "swapBuffers", "()V");
        if (updateReturn == null || activeReturn == null || swapBuffers == null) return null;

        InsnList updateStart = new InsnList();
        updateStart.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "displayUpdateStart", "()V", false));
        update.instructions.insertBefore(update.instructions.getFirst(), updateStart);

        InsnList swapStart = new InsnList();
        swapStart.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "swapBuffersStart", "()V", false));
        update.instructions.insertBefore(swapBuffers, swapStart);

        InsnList swapEnd = new InsnList();
        swapEnd.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "swapBuffersEnd", "()V", false));
        update.instructions.insert(swapBuffers, swapEnd);

        FrameCpuTimeRuntime.beginSession(true);
        InsnList boundary = new InsnList();
        boundary.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, CPU_RUNTIME, "boundary", "()V", false));
        boundary.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "boundary", "()V", false));
        update.instructions.insertBefore(updateReturn, boundary);

        if (FramePacingRuntime.enabled()) {
            InsnList pacing = new InsnList();
            pacing.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PACING_RUNTIME, "awaitNextFrame", "()V", false));
            update.instructions.insertBefore(updateReturn, pacing);
        }

        InsnList focus = new InsnList();
        focus.add(new InsnNode(Opcodes.DUP));
        focus.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, CPU_RUNTIME, "observeActive", "(Z)V", false));
        focus.add(new InsnNode(Opcodes.DUP));
        focus.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "observeActive", "(Z)V", false));
        active.instructions.insertBefore(activeReturn, focus);

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

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String descriptor) {
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                if (result != null) return null;
                result = call;
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

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

/** Observes LWJGL frame boundaries and hosts the guarded BaseGameState sync experiment. */
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

    private FrameTimePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        // #1153 experiment: use the already-compiled frame-time plan ID as an external exact-target
        // carrier so this candidate stays isolated from the central registry until live results say
        // it deserves a permanent plan ID. AdapterTarget still supplies the exact class/source gate.
        if (HighResolutionFrameSyncPlan.TARGET_CLASS.equals(signature.internalName())) {
            return HighResolutionFrameSyncPlan.transform(signature, originalBytes);
        }

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
                || calls(update, RUNTIME, "boundary") != 0
                || calls(active, RUNTIME, "observeActive") != 0) {
            return null;
        }

        AbstractInsnNode updateReturn = uniqueReturn(update, Opcodes.RETURN);
        AbstractInsnNode activeReturn = uniqueReturn(active, Opcodes.IRETURN);
        InsnList boundary = new InsnList();
        boundary.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "boundary", "()V", false));
        update.instructions.insertBefore(updateReturn, boundary);

        InsnList focus = new InsnList();
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

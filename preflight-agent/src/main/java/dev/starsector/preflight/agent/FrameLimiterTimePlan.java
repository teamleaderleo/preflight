package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Brackets the exact campaign main-loop FPS-cap sleep without changing its argument or control flow. */
final class FrameLimiterTimePlan {
    static final String PLAN_ID = "campaign-frame-limiter-time-v1";
    static final String TARGET_CLASS = "com/fs/starfarer/BaseGameState";
    static final String ORIGINAL_SHA256 =
            "cc5ef1d187dae1ca1017f6d40dae8576b88603a28a8d1c008e2d2aa2516c7c4d";
    static final String METHOD = "traverse";
    static final String DESCRIPTOR = "()Ljava/lang/String;";

    private static final String THREAD = "java/lang/Thread";
    private static final String SLEEP = "sleep";
    private static final String SLEEP_DESCRIPTOR = "(J)V";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/FrameTimeRuntime";

    private FrameLimiterTimePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!FrameTimeRuntime.enabled()
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != Opcodes.V17
                || !signature.hasMethod(METHOD, DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = unique(owner, METHOD, DESCRIPTOR);
        if (method == null
                || calls(method, RUNTIME, "beforeLimiterSleep") != 0
                || calls(method, RUNTIME, "afterLimiterSleep") != 0) {
            return null;
        }

        List<MethodInsnNode> sleeps = calls(method, THREAD, SLEEP, SLEEP_DESCRIPTOR);
        if (sleeps.size() != 2) return null;
        MethodInsnNode inactiveSleep = sleeps.get(0);
        MethodInsnNode limiterSleep = sleeps.get(1);
        AbstractInsnNode inactiveValue = previousOpcode(inactiveSleep);
        AbstractInsnNode limiterConversion = previousOpcode(limiterSleep);
        AbstractInsnNode limiterLoad = previousOpcode(limiterConversion);
        if (!(inactiveValue instanceof LdcInsnNode literal)
                || !Long.valueOf(50L).equals(literal.cst)
                || limiterConversion == null
                || limiterConversion.getOpcode() != Opcodes.I2L
                || !(limiterLoad instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ILOAD) {
            return null;
        }

        InsnList before = new InsnList();
        before.add(new InsnNode(Opcodes.DUP2));
        before.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "beforeLimiterSleep", "(J)V", false));
        method.instructions.insertBefore(limiterSleep, before);
        method.instructions.insert(limiterSleep, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "afterLimiterSleep", "()V", false));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        FrameTimeRuntime.limiterInstalled();
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

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                result.add(call);
            }
        }
        return result;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }
}

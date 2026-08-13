package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Installs one runtime-integrity observation and composes the opt-in combat state marker. */
final class CombatRuntimeIntegrityPlan {
    static final String TARGET_CLASS = "com/fs/starfarer/combat/CombatEngine";
    static final String ORIGINAL_SHA256 =
            "17c1d7f1347d177d6fc36f560e903d50d9df5f1f945e5da9590f83e4fbac17f4";
    static final String ADVANCE_METHOD = "advance";
    static final String ADVANCE_DESCRIPTOR = "(FLcom/fs/starfarer/util/super/B;)V";

    private static final String INTEGRITY_RUNTIME =
            "dev/starsector/preflight/agent/CombatRuntimeIntegrityRuntime";
    private static final String FRAME_RUNTIME =
            "dev/starsector/preflight/agent/FrameTimeRuntime";

    private CombatRuntimeIntegrityPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(ADVANCE_METHOD, ADVANCE_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode advance = unique(owner, ADVANCE_METHOD, ADVANCE_DESCRIPTOR);
        if (advance == null
                || (advance.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || advance.instructions.getFirst() == null
                || calls(advance, INTEGRITY_RUNTIME, "observe") != 0
                || calls(advance, FRAME_RUNTIME, "observeCombat") != 0) {
            return null;
        }

        InsnList observations = new InsnList();
        observations.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, INTEGRITY_RUNTIME, "observe", "()V", false));
        if (FrameTimeRuntime.enabled() || RuntimeSemanticState.enabled()) {
            observations.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, FRAME_RUNTIME, "observeCombat", "()V", false));
        }
        advance.instructions.insertBefore(advance.instructions.getFirst(), observations);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        CombatRuntimeIntegrityRuntime.installed();
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
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }
}

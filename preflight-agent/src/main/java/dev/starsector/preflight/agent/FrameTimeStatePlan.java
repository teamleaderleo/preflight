package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Marks display intervals as campaign or combat without putting a clock in either hot loop. */
final class FrameTimeStatePlan {
    static final String PLAN_ID = "vanilla-game-state-frame-time-segments-v1";
    static final String CAMPAIGN_CLASS = "com/fs/starfarer/campaign/CampaignState";
    static final String CAMPAIGN_SHA256 =
            "bdd3e9801c6bd8ae216fc40510d7f9f33fa16a540426cd137ca85dc640163372";
    static final String COMBAT_CLASS = "com/fs/starfarer/combat/CombatEngine";
    static final String COMBAT_SHA256 =
            "17c1d7f1347d177d6fc36f560e903d50d9df5f1f945e5da9590f83e4fbac17f4";
    static final String ADVANCE_METHOD = "advance";
    static final String ADVANCE_DESCRIPTOR = "(FLcom/fs/starfarer/util/super/B;)V";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/FrameTimeRuntime";

    private FrameTimeStatePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        String observer;
        String expectedHash;
        if (CAMPAIGN_CLASS.equals(signature.internalName())) {
            observer = "observeCampaign";
            expectedHash = CAMPAIGN_SHA256;
        } else if (COMBAT_CLASS.equals(signature.internalName())) {
            observer = "observeCombat";
            expectedHash = COMBAT_SHA256;
        } else {
            return null;
        }
        if (!FrameTimeRuntime.enabled()
                || !expectedHash.equals(signature.sha256())
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
                || calls(advance, RUNTIME, observer) != 0) {
            return null;
        }

        InsnList state = new InsnList();
        state.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, observer, "()V", false));
        advance.instructions.insertBefore(advance.instructions.getFirst(), state);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
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
}

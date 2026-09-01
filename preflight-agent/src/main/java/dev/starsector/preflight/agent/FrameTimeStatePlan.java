package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Marks display intervals as campaign without putting a clock in the hot loop. */
final class FrameTimeStatePlan {
    static final String PLAN_ID = "vanilla-game-state-frame-time-segments-v3";
    static final String CAMPAIGN_CLASS = "com/fs/starfarer/campaign/CampaignState";
    static final String CAMPAIGN_SHA256 =
            "bdd3e9801c6bd8ae216fc40510d7f9f33fa16a540426cd137ca85dc640163372";
    static final String ADVANCE_METHOD = "advance";
    static final String ADVANCE_DESCRIPTOR = "(FLcom/fs/starfarer/util/super/B;)V";
    static final String PROCESS_INPUT_METHOD = "processInput";
    static final String PROCESS_INPUT_DESCRIPTOR = "(Lcom/fs/starfarer/util/super/B;F)V";
    static final String ENGINE_FIELD = "engine";
    static final String ENGINE_DESCRIPTOR = "Lcom/fs/starfarer/campaign/CampaignEngine;";

    private static final String CAMPAIGN_ENGINE = "com/fs/starfarer/campaign/CampaignEngine";
    private static final String IS_PAUSED = "isPaused";
    private static final String IS_PAUSED_DESCRIPTOR = "()Z";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/FrameTimeRuntime";
    private static final String CONTROL_RUNTIME =
            "dev/starsector/preflight/agent/InternalGameControlRuntime";

    private FrameTimeStatePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        String observer = "observeCampaign";
        if ((!FrameTimeRuntime.enabled() && !RuntimeSemanticState.enabled())
                || !CAMPAIGN_CLASS.equals(signature.internalName())
                || !CAMPAIGN_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(ADVANCE_METHOD, ADVANCE_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode advance = unique(owner, ADVANCE_METHOD, ADVANCE_DESCRIPTOR);
        MethodNode processInput = unique(owner, PROCESS_INPUT_METHOD, PROCESS_INPUT_DESCRIPTOR);
        if (advance == null
                || processInput == null
                || (advance.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || (processInput.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || advance.instructions.getFirst() == null
                || processInput.instructions.getFirst() == null
                || !hasUniqueField(owner, ENGINE_FIELD, ENGINE_DESCRIPTOR)
                || calls(advance, CAMPAIGN_ENGINE, IS_PAUSED, IS_PAUSED_DESCRIPTOR) < 1
                || calls(advance, RUNTIME, observer, "()V") != 0
                || calls(advance, RUNTIME, "observeCampaignPaused", "(Z)V") != 0
                || calls(processInput, CONTROL_RUNTIME, "campaignInput",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V") != 0
                || calls(processInput, CONTROL_RUNTIME, "campaignInputComplete",
                        "(Ljava/lang/Object;)V") != 0) {
            return null;
        }

        InsnList state = new InsnList();
        state.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, observer, "()V", false));
        LabelNode engineMissing = new LabelNode();
        LabelNode observed = new LabelNode();
        state.add(new VarInsnNode(Opcodes.ALOAD, 0));
        state.add(new FieldInsnNode(Opcodes.GETFIELD, CAMPAIGN_CLASS, ENGINE_FIELD,
                ENGINE_DESCRIPTOR));
        state.add(new InsnNode(Opcodes.DUP));
        state.add(new JumpInsnNode(Opcodes.IFNULL, engineMissing));
        state.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CAMPAIGN_ENGINE, IS_PAUSED,
                IS_PAUSED_DESCRIPTOR, false));
        state.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "observeCampaignPaused", "(Z)V", false));
        state.add(new JumpInsnNode(Opcodes.GOTO, observed));
        state.add(engineMissing);
        state.add(new InsnNode(Opcodes.POP));
        state.add(observed);
        advance.instructions.insertBefore(advance.instructions.getFirst(), state);

        InsnList control = new InsnList();
        control.add(new VarInsnNode(Opcodes.ALOAD, 0));
        control.add(new VarInsnNode(Opcodes.ALOAD, 1));
        control.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTROL_RUNTIME,
                "campaignInput", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        processInput.instructions.insertBefore(processInput.instructions.getFirst(), control);
        for (AbstractInsnNode instruction : processInput.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            InsnList complete = new InsnList();
            complete.add(new VarInsnNode(Opcodes.ALOAD, 0));
            complete.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTROL_RUNTIME,
                    "campaignInputComplete", "(Ljava/lang/Object;)V", false));
            processInput.instructions.insertBefore(instruction, complete);
        }

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

    private static boolean hasUniqueField(ClassNode owner, String name, String descriptor) {
        int matches = 0;
        for (FieldNode field : owner.fields) {
            if (name.equals(field.name) && descriptor.equals(field.desc)) matches++;
        }
        return matches == 1;
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                result++;
            }
        }
        return result;
    }
}

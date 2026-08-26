package dev.starsector.preflight.agent;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Removes LunaLib's dead frame copy and caches the renderer entity's private snapshots. */
final class LunaCampaignRendererSnapshotPlan {
    static final String SCRIPT_CLASS = "lunalib/lunaUtil/campaign/LunaCampaignRenderer";
    static final String SCRIPT_SHA256 =
            "3dad087902de6f12622b5a78753ee58bfc85862e5a2a562823b879eac28834c8";
    static final String ENTITY_CLASS = "lunalib/backend/scripts/LunaCampaignRendererEntity";
    static final String ENTITY_SHA256 =
            "e395f5414a84ba5f2e9f54fe7e776ab6837f48f519267a10473ee6e52a18f723";

    static final String ADVANCE = "advance";
    static final String ADVANCE_DESCRIPTOR = "(F)V";
    static final String RENDER = "render";
    static final String RENDER_DESCRIPTOR =
            "(Lcom/fs/starfarer/api/campaign/CampaignEngineLayers;"
                    + "Lcom/fs/starfarer/api/combat/ViewportAPI;)V";
    static final String GET_RENDERERS = "getRenderers";
    static final String GET_RENDERERS_DESCRIPTOR = "()Ljava/util/ArrayList;";
    static final String GET_TRANSIENT = "getTransientRenderers";
    static final String GET_PERSISTENT = "getNonTransientRenderers";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/LunaCampaignRendererSnapshotRuntime";

    private LunaCampaignRendererSnapshotPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (signature.majorVersion() != 61) return null;
        if (SCRIPT_CLASS.equals(signature.internalName())) {
            return transformScript(signature, originalBytes);
        }
        if (ENTITY_CLASS.equals(signature.internalName())) {
            return transformEntity(signature, originalBytes);
        }
        return null;
    }

    private static byte[] transformScript(ClassSignature signature, byte[] originalBytes) {
        if (!SCRIPT_SHA256.equals(signature.sha256())
                || !signature.hasMethod(ADVANCE, ADVANCE_DESCRIPTOR)
                || !signature.hasMethod(GET_RENDERERS, GET_RENDERERS_DESCRIPTOR)
                || !signature.hasMethod(GET_TRANSIENT, GET_RENDERERS_DESCRIPTOR)
                || !signature.hasMethod(GET_PERSISTENT, GET_RENDERERS_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = read(originalBytes);
        MethodNode advance = unique(owner, ADVANCE, ADVANCE_DESCRIPTOR);
        if (advance == null || calls(advance, SCRIPT_CLASS, GET_RENDERERS,
                GET_RENDERERS_DESCRIPTOR) != 1) {
            return null;
        }
        MethodInsnNode copy = call(
                advance, SCRIPT_CLASS, GET_RENDERERS, GET_RENDERERS_DESCRIPTOR);
        AbstractInsnNode receiver = previousCode(copy);
        AbstractInsnNode store = nextCode(copy);
        if (!(receiver instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD || load.var != 0
                || !(store instanceof VarInsnNode local)
                || local.getOpcode() != Opcodes.ASTORE
                || usesLocalAfter(store, local.var)) {
            return null;
        }
        advance.instructions.remove(receiver);
        advance.instructions.remove(copy);
        advance.instructions.remove(store);
        LunaCampaignRendererSnapshotRuntime.scriptInstalled();
        return write(owner);
    }

    private static byte[] transformEntity(ClassSignature signature, byte[] originalBytes) {
        if (!ENTITY_SHA256.equals(signature.sha256())
                || !signature.hasMethod(ADVANCE, ADVANCE_DESCRIPTOR)
                || !signature.hasMethod(RENDER, RENDER_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = read(originalBytes);
        MethodNode render = unique(owner, RENDER, RENDER_DESCRIPTOR);
        MethodNode advance = unique(owner, ADVANCE, ADVANCE_DESCRIPTOR);
        if (render == null || advance == null
                || calls(render, SCRIPT_CLASS, GET_RENDERERS, GET_RENDERERS_DESCRIPTOR) != 1
                || calls(advance, SCRIPT_CLASS, GET_RENDERERS, GET_RENDERERS_DESCRIPTOR) != 1
                || calls(owner, RUNTIME, "snapshot",
                        "(Ljava/lang/Object;Ljava/util/List;Ljava/util/List;)Ljava/util/ArrayList;") != 0) {
            return null;
        }
        rewriteSnapshotCall(render);
        rewriteSnapshotCall(advance);
        LunaCampaignRendererSnapshotRuntime.entityInstalled();
        return write(owner);
    }

    private static void rewriteSnapshotCall(MethodNode method) {
        MethodInsnNode original = call(
                method, SCRIPT_CLASS, GET_RENDERERS, GET_RENDERERS_DESCRIPTOR);
        InsnList replacement = new InsnList();
        replacement.add(new InsnNode(Opcodes.DUP));
        replacement.add(new InsnNode(Opcodes.DUP));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, SCRIPT_CLASS, GET_TRANSIENT,
                GET_RENDERERS_DESCRIPTOR, false));
        replacement.add(new InsnNode(Opcodes.SWAP));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, SCRIPT_CLASS, GET_PERSISTENT,
                GET_RENDERERS_DESCRIPTOR, false));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "snapshot",
                "(Ljava/lang/Object;Ljava/util/List;Ljava/util/List;)Ljava/util/ArrayList;",
                false));
        method.instructions.insertBefore(original, replacement);
        method.instructions.remove(original);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static byte[] write(ClassNode owner) {
        ClassWriter writer =
                new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
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

    private static int calls(
            ClassNode owner, String callOwner, String name, String descriptor) {
        return owner.methods.stream()
                .mapToInt(method -> calls(method, callOwner, name, descriptor))
                .sum();
    }

    private static int calls(
            MethodNode method, String callOwner, String name, String descriptor) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && callOwner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                result++;
            }
        }
        return result;
    }

    private static MethodInsnNode call(
            MethodNode method, String owner, String name, String descriptor) {
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode candidate
                    && owner.equals(candidate.owner)
                    && name.equals(candidate.name)
                    && descriptor.equals(candidate.desc)) {
                if (result != null) return null;
                result = candidate;
            }
        }
        return result;
    }

    private static boolean usesLocalAfter(AbstractInsnNode instruction, int local) {
        for (AbstractInsnNode current = instruction.getNext(); current != null;
                current = current.getNext()) {
            if (current instanceof VarInsnNode variable && variable.var == local) return true;
        }
        return false;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }
}

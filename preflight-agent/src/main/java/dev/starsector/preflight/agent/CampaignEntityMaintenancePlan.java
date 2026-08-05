package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Removes two redundant vanilla campaign-entity maintenance operations. */
final class CampaignEntityMaintenancePlan {
    static final String ENTITY_CLASS = "com/fs/starfarer/campaign/BaseCampaignEntity";
    static final String ENTITY_SHA256 =
            "7e4683903f4ad35219912dfd7aecb0db1e302957bb7d896e09620b1a8135b18b";
    static final String SCRIPT_METHOD = "runScripts";
    static final String SCRIPT_DESCRIPTOR = "(F)V";

    static final String FLEET_VIEW_CLASS = "com/fs/starfarer/campaign/fleet/CampaignFleetView";
    static final String FLEET_VIEW_SHA256 =
            "55ad696cd51ec7b39a1d34797c77d78dfaebc386af6bb4d12015b235d3c28b2c";
    static final String ADVANCE_METHOD = "advance";
    static final String ADVANCE_DESCRIPTOR = "(F)V";

    private static final String ORIGINAL_SCRIPT = "preflight$original$runScripts";
    private static final String SCRIPTS_FIELD = "scripts";
    private static final String SCRIPTS_DESCRIPTOR = "Ljava/util/List;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/CampaignEntityMaintenanceRuntime";
    private static final String CAMPAIGN_FLEET = "com/fs/starfarer/campaign/fleet/CampaignFleet";
    private static final String SORTED_MEMBERS = "getSortedMembers";
    private static final String SORTED_DESCRIPTOR = "()Ljava/util/List;";

    private CampaignEntityMaintenancePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!CampaignEntityMaintenanceRuntime.enabled() || signature.majorVersion() != 61) return null;
        if (ENTITY_CLASS.equals(signature.internalName()) && ENTITY_SHA256.equals(signature.sha256())) {
            return transformEntity(originalBytes);
        }
        if (FLEET_VIEW_CLASS.equals(signature.internalName())
                && FLEET_VIEW_SHA256.equals(signature.sha256())) {
            return transformFleetView(originalBytes);
        }
        return null;
    }

    private static byte[] transformEntity(byte[] originalBytes) {
        ClassNode owner = read(originalBytes);
        MethodNode original = unique(owner, SCRIPT_METHOD, SCRIPT_DESCRIPTOR);
        if (original == null || unique(owner, ORIGINAL_SCRIPT, SCRIPT_DESCRIPTOR) != null
                || !reviewScriptShape(original)) return null;
        int access = original.access;
        original.name = ORIGINAL_SCRIPT;

        MethodNode wrapper = new MethodNode(
                Opcodes.ASM9, access, SCRIPT_METHOD, SCRIPT_DESCRIPTOR, null, null);
        LabelNode nonEmpty = new LabelNode();
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, ENTITY_CLASS, SCRIPTS_FIELD, SCRIPTS_DESCRIPTOR));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, nonEmpty));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "emptyScriptList", "()V", false));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        wrapper.instructions.add(nonEmpty);
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "nonEmptyScriptList", "()V", false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, ENTITY_CLASS, ORIGINAL_SCRIPT, SCRIPT_DESCRIPTOR, false));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        owner.methods.add(wrapper);
        CampaignEntityMaintenanceRuntime.entityScriptsInstalled();
        return write(owner);
    }

    private static byte[] transformFleetView(byte[] originalBytes) {
        ClassNode owner = read(originalBytes);
        MethodNode method = unique(owner, ADVANCE_METHOD, ADVANCE_DESCRIPTOR);
        if (method == null || callsRuntime(method) != 0) return null;
        List<MethodInsnNode> snapshots = matching(
                method, CAMPAIGN_FLEET, SORTED_MEMBERS, SORTED_DESCRIPTOR);
        if (snapshots.size() != 2) return null;
        MethodInsnNode second = snapshots.get(1);
        AbstractInsnNode fieldNode = previousMeaningful(second);
        AbstractInsnNode loadThis = previousMeaningful(fieldNode);
        if (!(fieldNode instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.GETFIELD
                || !FLEET_VIEW_CLASS.equals(field.owner)
                || !"fleet".equals(field.name)
                || !(loadThis instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD || load.var != 0) return null;
        method.instructions.insertBefore(loadThis, new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.remove(loadThis);
        method.instructions.remove(fieldNode);
        method.instructions.remove(second);
        CampaignEntityMaintenanceRuntime.fleetViewInstalled();
        return write(owner);
    }

    private static boolean reviewScriptShape(MethodNode method) {
        int snapshots = 0;
        int advances = 0;
        int scriptReads = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && "java/util/ArrayList".equals(type.desc)) snapshots++;
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && ENTITY_CLASS.equals(field.owner)
                    && SCRIPTS_FIELD.equals(field.name)
                    && SCRIPTS_DESCRIPTOR.equals(field.desc)) scriptReads++;
            if (instruction instanceof MethodInsnNode call
                    && "com/fs/starfarer/api/EveryFrameScript".equals(call.owner)
                    && "advance".equals(call.name) && "(F)V".equals(call.desc)) advances++;
        }
        return snapshots == 1 && scriptReads == 3 && advances == 1;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && (current.getType() == AbstractInsnNode.LABEL
                || current.getType() == AbstractInsnNode.LINE
                || current.getType() == AbstractInsnNode.FRAME)) current = current.getPrevious();
        return current;
    }

    private static List<MethodInsnNode> matching(
            MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name) && descriptor.equals(call.desc)) result.add(call);
        }
        return result;
    }

    private static int callsRuntime(MethodNode method) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) result++;
        }
        return result;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static byte[] write(ClassNode owner) {
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
}

package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Stops three exact IndEvo industries from performing world mutations on Codex's fake market. */
final class IndEvoSyntheticMarketPlan {
    static final String RELAY_CLASS = "indevo/industries/relay/industry/MilitaryRelay";
    static final String RELAY_SHA256 =
            "e5ef895100b203be3fc1f42daf4f1327e4a3055977d27e66919d85462a3ab482";
    static final String ARTILLERY_CLASS =
            "indevo/industries/artillery/industry/ArtilleryStation";
    static final String ARTILLERY_SHA256 =
            "89eb15933735788cae84c1b9d41252801395d0af54c1250d7a485b7e5614554f";
    static final String WONDER_CLASS = "indevo/industries/worldwonder/industry/WorldWonder";
    static final String WONDER_SHA256 =
            "b0b0c234b5856645a1882c6fefd047638c11b0fb309223d882b75328854657df";
    private static final String MARKET = "com/fs/starfarer/api/campaign/econ/MarketAPI";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/IndEvoSyntheticMarketRuntime";

    private IndEvoSyntheticMarketPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (signature.majorVersion() != 61) return null;
        ClassNode owner = read(originalBytes);
        boolean changed;
        if (RELAY_CLASS.equals(signature.internalName())
                && RELAY_SHA256.equals(signature.sha256())) {
            changed = guardRelay(owner);
        } else if (ARTILLERY_CLASS.equals(signature.internalName())
                && ARTILLERY_SHA256.equals(signature.sha256())) {
            changed = guardArtillery(owner);
        } else if (WONDER_CLASS.equals(signature.internalName())
                && WONDER_SHA256.equals(signature.sha256())) {
            changed = guardWonder(owner);
        } else {
            return null;
        }
        if (!changed) return null;
        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        IndEvoSyntheticMarketRuntime.installed();
        return writer.toByteArray();
    }

    private static boolean guardRelay(ClassNode owner) {
        MethodNode create = unique(owner, "createCommRelayStation", "(Ljava/lang/String;)V");
        MethodNode remove = unique(owner, "removeCommRelayStation", "()V");
        if (create == null || remove == null || calls(create, RUNTIME) != 0
                || calls(remove, RUNTIME) != 0) return false;
        return guardLocation(create, "relay.create") && guardLocation(remove, "relay.remove");
    }

    private static boolean guardLocation(MethodNode method, String site) {
        FieldInsnNode market = uniqueMarketField(method);
        if (market == null || calls(method, MARKET, "getContainingLocation") != 1) return false;
        AbstractInsnNode first = firstCode(method);
        if (first == null) return false;
        LabelNode present = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(copy(market));
        guard.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MARKET, "getContainingLocation",
                "()Lcom/fs/starfarer/api/campaign/LocationAPI;", true));
        guard.add(new JumpInsnNode(Opcodes.IFNONNULL, present));
        guard.add(new LdcInsnNode(site));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "bypass",
                "(Ljava/lang/String;)V", false));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(present);
        method.instructions.insertBefore(first, guard);
        return true;
    }

    private static boolean guardArtillery(ClassNode owner) {
        MethodNode apply = unique(owner, "apply", "()V");
        MethodNode unapply = unique(owner, "unapply", "()V");
        if (apply == null || unapply == null || calls(apply, RUNTIME) != 0
                || calls(unapply, RUNTIME) != 0
                || calls(apply, MARKET, "getStarSystem") != 1
                || calls(unapply, MARKET, "getStarSystem") != 1) return false;
        MethodInsnNode applyGetSystem = uniqueCall(apply, MARKET, "getStarSystem");
        MethodInsnNode addTag = uniqueCall(
                apply, "com/fs/starfarer/api/campaign/StarSystemAPI", "addTag");
        MethodInsnNode unapplyGetSystem = uniqueCall(unapply, MARKET, "getStarSystem");
        MethodInsnNode removeTag = uniqueCall(
                unapply, "com/fs/starfarer/api/campaign/StarSystemAPI", "removeTag");
        if (applyGetSystem == null || addTag == null || !before(applyGetSystem, addTag)
                || unapplyGetSystem == null || removeTag == null
                || !before(unapplyGetSystem, removeTag)) return false;
        guardNullableReceiver(apply, applyGetSystem, addTag, "artillery.addTag");
        guardNullableReceiver(
                unapply, unapplyGetSystem, removeTag, "artillery.removeTag");
        return true;
    }

    private static void guardNullableReceiver(
            MethodNode method, MethodInsnNode producer, MethodInsnNode call, String site) {
        LabelNode present = new LabelNode();
        LabelNode after = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new InsnNode(Opcodes.DUP));
        guard.add(new JumpInsnNode(Opcodes.IFNONNULL, present));
        guard.add(new InsnNode(Opcodes.POP));
        guard.add(new LdcInsnNode(site));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "bypass",
                "(Ljava/lang/String;)V", false));
        guard.add(new JumpInsnNode(Opcodes.GOTO, after));
        guard.add(present);
        method.instructions.insert(producer, guard);
        method.instructions.insert(call, after);
    }

    private static boolean guardWonder(ClassNode owner) {
        MethodNode apply = unique(owner, "apply", "()V");
        if (apply == null || calls(apply, RUNTIME) != 0
                || calls(apply, MARKET, "getStarSystem") != 2) return false;
        FieldInsnNode market = uniqueMarketField(apply);
        List<AbstractInsnNode> returns = opcodes(apply, Opcodes.RETURN);
        MethodInsnNode baseApply = uniqueCall(
                apply, "com/fs/starfarer/api/impl/campaign/econ/impl/BaseIndustry", "apply");
        if (market == null || returns.size() != 1 || baseApply == null) return false;
        LabelNode present = new LabelNode();
        LabelNode done = new LabelNode();
        apply.instructions.insertBefore(returns.get(0), done);
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(copy(market));
        guard.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MARKET, "getStarSystem",
                "()Lcom/fs/starfarer/api/campaign/StarSystemAPI;", true));
        guard.add(new JumpInsnNode(Opcodes.IFNONNULL, present));
        guard.add(new LdcInsnNode("worldWonder.worldEffects"));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "bypass",
                "(Ljava/lang/String;)V", false));
        guard.add(new JumpInsnNode(Opcodes.GOTO, done));
        guard.add(present);
        apply.instructions.insert(baseApply, guard);
        return true;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
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

    private static FieldInsnNode uniqueMarketField(MethodNode method) {
        FieldInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD && "market".equals(field.name)) {
                if (result == null) result = field;
                else if (!result.owner.equals(field.owner) || !result.desc.equals(field.desc)) return null;
            }
        }
        return result;
    }

    private static FieldInsnNode copy(FieldInsnNode field) {
        return new FieldInsnNode(field.getOpcode(), field.owner, field.name, field.desc);
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner, String name) {
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name)) {
                if (result != null) return null;
                result = call;
            }
        }
        return result;
    }

    private static int calls(MethodNode method, String owner) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)) count++;
        }
        return count;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name)) count++;
        }
        return count;
    }

    private static List<AbstractInsnNode> opcodes(MethodNode method, int opcode) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) result.add(instruction);
        }
        return result;
    }

    private static AbstractInsnNode firstCode(MethodNode method) {
        AbstractInsnNode current = method.instructions.getFirst();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static boolean before(AbstractInsnNode first, AbstractInsnNode second) {
        for (AbstractInsnNode current = first; current != null; current = current.getNext()) {
            if (current == second) return true;
        }
        return false;
    }
}

package dev.starsector.preflight.agent;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.IincInsnNode;

/** Uses one mutation-safe listener snapshot array instead of an ArrayList copy and iterator. */
final class CombatListenerRangeSnapshotPlan {
    static final String PLAN_ID = "vanilla-combat-listener-range-snapshot-v1";
    static final String ENABLED_PROPERTY = "preflight.combat.listenerRangeSnapshotArray";
    static final String TARGET_CLASS =
            "com/fs/starfarer/api/combat/listeners/CombatListenerUtil";
    static final String ORIGINAL_SHA256 =
            "2cffd915a76555a002fde4f717a5fad4fd72e093f948cb3e9eb801da48ec2dbc";
    static final String DESCRIPTOR =
            "(Lcom/fs/starfarer/api/combat/ShipAPI;"
                    + "Lcom/fs/starfarer/api/combat/WeaponAPI;)F";

    private static final String SHIP = "com/fs/starfarer/api/combat/ShipAPI";
    private static final String LISTENER_MANAGER =
            "com/fs/starfarer/api/combat/listeners/CombatListenerManagerAPI";
    private static final String LIST = "java/util/List";
    private static final String ARRAY_LIST = "java/util/ArrayList";
    private static final String COLLECTION_CONSTRUCTOR = "(Ljava/util/Collection;)V";
    private static final String WEAPON_RANGE_MODIFIER =
            "com/fs/starfarer/api/combat/listeners/WeaponRangeModifier";
    private static final String WEAPON_BASE_RANGE_MODIFIER =
            "com/fs/starfarer/api/combat/listeners/WeaponBaseRangeModifier";
    private static final String CALLBACK_DESCRIPTOR =
            "(Lcom/fs/starfarer/api/combat/ShipAPI;"
                    + "Lcom/fs/starfarer/api/combat/WeaponAPI;)F";

    static final List<RangeMethod> METHODS = List.of(
            new RangeMethod("getWeaponRangePercentMod", WEAPON_RANGE_MODIFIER,
                    "getWeaponRangePercentMod", false, false),
            new RangeMethod("getWeaponRangeMultMod", WEAPON_RANGE_MODIFIER,
                    "getWeaponRangeMultMod", true, true),
            new RangeMethod("getWeaponRangeFlatMod", WEAPON_RANGE_MODIFIER,
                    "getWeaponRangeFlatMod", false, true),
            new RangeMethod("getWeaponBaseRangePercentMod", WEAPON_BASE_RANGE_MODIFIER,
                    "getWeaponBaseRangePercentMod", false, false),
            new RangeMethod("getWeaponBaseRangeMultMod", WEAPON_BASE_RANGE_MODIFIER,
                    "getWeaponBaseRangeMultMod", true, true),
            new RangeMethod("getWeaponBaseRangeFlatMod", WEAPON_BASE_RANGE_MODIFIER,
                    "getWeaponBaseRangeFlatMod", false, true));

    private CombatListenerRangeSnapshotPlan() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!enabled()
                || signature.majorVersion() != 61
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())) {
            return null;
        }
        for (RangeMethod spec : METHODS) {
            if (!signature.hasMethod(spec.name(), DESCRIPTOR)) return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        for (RangeMethod spec : METHODS) {
            MethodNode method = unique(owner, spec.name(), DESCRIPTOR);
            if (method == null || !reviewedShape(method, spec)) return null;
        }
        for (RangeMethod spec : METHODS) {
            rewrite(unique(owner, spec.name(), DESCRIPTOR), spec);
        }

        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static boolean reviewedShape(MethodNode method, RangeMethod spec) {
        return method.access == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                && calls(method, SHIP, "getListeners", "(Ljava/lang/Class;)Ljava/util/List;") == 1
                && calls(method, ARRAY_LIST, "<init>", COLLECTION_CONSTRUCTOR) == 1
                && calls(method, ARRAY_LIST, "iterator", "()Ljava/util/Iterator;") == 1
                && calls(method, spec.listener(), spec.callback(), CALLBACK_DESCRIPTOR) == 1
                && allocations(method, ARRAY_LIST) == 1
                && calls(method, LIST, "toArray", "()[Ljava/lang/Object;") == 0;
    }

    private static void rewrite(MethodNode method, RangeMethod spec) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();

        LabelNode loop = new LabelNode();
        LabelNode end = new LabelNode();

        method.instructions.add(new InsnNode(spec.multiply() ? Opcodes.FCONST_1 : Opcodes.FCONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.FSTORE, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, end));
        if (spec.requiresManager()) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    SHIP,
                    "getListenerManager",
                    "()L" + LISTENER_MANAGER + ";",
                    true));
            method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, end));
        }

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new LdcInsnNode(Type.getObjectType(spec.listener())));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                SHIP,
                "getListeners",
                "(Ljava/lang/Class;)Ljava/util/List;",
                true));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, LIST, "toArray", "()[Ljava/lang/Object;", true));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 4));

        method.instructions.add(loop);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));
        method.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        method.instructions.add(new InsnNode(Opcodes.AALOAD));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, spec.listener()));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                spec.listener(),
                spec.callback(),
                CALLBACK_DESCRIPTOR,
                true));
        method.instructions.add(new InsnNode(spec.multiply() ? Opcodes.FMUL : Opcodes.FADD));
        method.instructions.add(new VarInsnNode(Opcodes.FSTORE, 2));
        method.instructions.add(new IincInsnNode(4, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, loop));

        method.instructions.add(end);
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.FRETURN));
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode result = null;
        for (MethodNode method : owner.methods) {
            if (!name.equals(method.name) || !descriptor.equals(method.desc)) continue;
            if (result != null) return null;
            result = method;
        }
        return result;
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) count++;
        }
        return count;
    }

    private static int allocations(MethodNode method, String type) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode allocation
                    && instruction.getOpcode() == Opcodes.NEW
                    && type.equals(allocation.desc)) count++;
        }
        return count;
    }

    record RangeMethod(
            String name,
            String listener,
            String callback,
            boolean multiply,
            boolean requiresManager) {
    }
}

package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Skips vanilla's per-frame event-mod rewrite when every input and its prior output are unchanged. */
final class CommodityEventModMemoPlan {
    static final String TARGET_CLASS = "com/fs/starfarer/campaign/econ/CommodityOnMarket";
    static final String ORIGINAL_SHA256 =
            "0d4157d29532ef969b0d61a52783a4fc3846c758d73409141915c2807e3c83e4";
    static final String METHOD = "reapplyEventMod";
    static final String DESCRIPTOR = "()V";
    static final String QUANTITY_METHOD = "getCombinedTradeModQuantity";
    static final String QUANTITY_DESCRIPTOR = "()F";
    static final String MOD_VALUE_METHOD = "getModValueForQuantity";
    static final String MOD_VALUE_DESCRIPTOR = "(F)F";
    static final String AVAILABLE_METHOD = "getAvailableStat";
    static final String AVAILABLE_DESCRIPTOR =
            "()Lcom/fs/starfarer/api/combat/MutableStatWithTempMods;";
    static final String COMMODITY_METHOD = "getCommodity";
    static final String COMMODITY_DESCRIPTOR = "()Lcom/fs/starfarer/loading/return;";

    private static final String ORIGINAL = "preflight$original$reapplyEventMod";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/CommodityEventModMemoRuntime";
    private static final String MUTABLE = "com/fs/starfarer/api/combat/MutableStat";
    private static final String MUTABLE_TEMP =
            "com/fs/starfarer/api/combat/MutableStatWithTempMods";
    private static final String STAT_MOD = "com/fs/starfarer/api/combat/MutableStat$StatMod";
    private static final String COMMODITY = "com/fs/starfarer/loading/return";
    private static final String TRADE_FIELD = "tradeMod";
    private static final String TRADE_PLUS_FIELD = "tradeModPlus";
    private static final String TRADE_MINUS_FIELD = "tradeModMinus";
    private static final String AVAILABLE_FIELD = "available";
    private static final String VALID = "preflight$eventModMemoValid";
    private static final String QUANTITY = "preflight$eventModQuantityBits";
    private static final String AVAILABLE = "preflight$eventModAvailableBits";
    private static final String TRADE = "preflight$eventModTradeBits";
    private static final String TRADE_PLUS = "preflight$eventModTradePlusBits";
    private static final String TRADE_MINUS = "preflight$eventModTradeMinusBits";
    private static final String EVENT_MOD = "preflight$eventModValueBits";
    private static final String ECON_UNIT = "preflight$eventModEconUnitBits";
    private static final String AVAILABLE_STAT_REF = "preflight$eventModAvailableStatRef";
    private static final String TRADE_STAT_REF = "preflight$eventModTradeStatRef";
    private static final String TRADE_PLUS_STAT_REF = "preflight$eventModTradePlusStatRef";
    private static final String TRADE_MINUS_STAT_REF = "preflight$eventModTradeMinusStatRef";
    private static final String EVENT_MOD_REF = "preflight$eventModRef";
    private static final String EVENT_MOD_DESC = "preflight$eventModDescriptionRef";

    private CommodityEventModMemoPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(METHOD, DESCRIPTOR)
                || !signature.hasMethod(QUANTITY_METHOD, QUANTITY_DESCRIPTOR)
                || !signature.hasMethod(MOD_VALUE_METHOD, MOD_VALUE_DESCRIPTOR)
                || !signature.hasMethod(AVAILABLE_METHOD, AVAILABLE_DESCRIPTOR)
                || !signature.hasMethod(COMMODITY_METHOD, COMMODITY_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode original = unique(owner, METHOD, DESCRIPTOR);
        if (original == null || hasMethod(owner, ORIGINAL, DESCRIPTOR) || hasMemoFields(owner)
                || !reviewOriginal(original)) {
            return null;
        }

        int access = original.access;
        original.name = ORIGINAL;
        addMemoFields(owner);
        owner.methods.add(wrapper(access));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        CommodityEventModMemoRuntime.installed();
        return writer.toByteArray();
    }

    private static MethodNode wrapper(int access) {
        MethodNode method = new MethodNode(Opcodes.ASM9, access, METHOD, DESCRIPTOR, null, null);
        LabelNode enabled = new LabelNode();
        LabelNode delegated = new LabelNode();
        LabelNode fastStart = new LabelNode();
        LabelNode fastEnd = new LabelNode();
        LabelNode linkageFailure = new LabelNode();

        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "enabled", "()Z", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNE, enabled));
        invokeOriginal(method);
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        method.instructions.add(enabled);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_CLASS, VALID, "Z"));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, delegated));

        // A clean MutableStat's public modified value is authoritative. Compare those values and
        // their owning objects directly, avoiding four getModifiedValue calls and the combined
        // quantity arithmetic on the overwhelmingly common unchanged path. The accessor is added
        // only to the exact reviewed MutableStat; any missing linkage disables this memo and
        // delegates forever.
        method.instructions.add(fastStart);
        loadStat(method, TRADE_FIELD, 8);
        loadStat(method, TRADE_PLUS_FIELD, 9);
        loadStat(method, TRADE_MINUS_FIELD, 10);
        loadStat(method, AVAILABLE_FIELD, 2);
        compareReference(method, TRADE_STAT_REF, 8, delegated);
        compareReference(method, TRADE_PLUS_STAT_REF, 9, delegated);
        compareReference(method, TRADE_MINUS_STAT_REF, 10, delegated);
        compareReference(method, AVAILABLE_STAT_REF, 2, delegated);
        compareClean(method, 8, delegated);
        compareClean(method, 9, delegated);
        compareClean(method, 10, delegated);
        compareClean(method, 2, delegated);
        compareStatValue(method, TRADE, 8, delegated);
        compareStatValue(method, TRADE_PLUS, 9, delegated);
        compareStatValue(method, TRADE_MINUS, 10, delegated);
        compareStatValue(method, AVAILABLE, 2, delegated);

        captureEventMod(method);
        compareReference(method, EVENT_MOD_REF, 4, delegated);
        compare(method, EVENT_MOD, 5, delegated);
        compareReference(method, EVENT_MOD_DESC, 7, delegated);
        compareEconUnitIfRelevant(method, delegated);
        method.instructions.add(fastEnd);
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "hit", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        method.instructions.add(linkageFailure);
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "fastValidationUnavailable", "()V", false));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, delegated));
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                fastStart, fastEnd, linkageFailure, "java/lang/LinkageError"));

        method.instructions.add(delegated);
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "delegated", "()V", false));
        invokeOriginal(method);
        // Store the state after vanilla has authored eMod. This also resolves MutableStat's dirty
        // bit once, so the next unchanged frame can compare the actual stable output directly.
        captureFingerprint(method);
        store(method, QUANTITY, 1);
        store(method, AVAILABLE, 3);
        storeStatValue(method, TRADE, 8);
        storeStatValue(method, TRADE_PLUS, 9);
        storeStatValue(method, TRADE_MINUS, 10);
        store(method, EVENT_MOD, 5);
        store(method, ECON_UNIT, 6);
        storeReference(method, AVAILABLE_STAT_REF, 2);
        storeReference(method, TRADE_STAT_REF, 8);
        storeReference(method, TRADE_PLUS_STAT_REF, 9);
        storeReference(method, TRADE_MINUS_STAT_REF, 10);
        storeReference(method, EVENT_MOD_REF, 4);
        storeReference(method, EVENT_MOD_DESC, 7);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_CLASS, VALID, "Z"));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    /** Captures quantity, current available value, eMod value, and the relevant econ unit. */
    private static void captureFingerprint(MethodNode method) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, TARGET_CLASS, QUANTITY_METHOD, QUANTITY_DESCRIPTOR, false));
        method.instructions.add(new VarInsnNode(Opcodes.FSTORE, 1));
        loadStat(method, TRADE_FIELD, 8);
        loadStat(method, TRADE_PLUS_FIELD, 9);
        loadStat(method, TRADE_MINUS_FIELD, 10);
        loadStat(method, AVAILABLE_FIELD, 2);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, MUTABLE_TEMP, "getModifiedValue", "()F", false));
        method.instructions.add(new VarInsnNode(Opcodes.FSTORE, 3));
        captureEventMod(method);

        method.instructions.add(new InsnNode(Opcodes.FCONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.FSTORE, 6));
        LabelNode noQuantity = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.FCONST_0));
        method.instructions.add(new InsnNode(Opcodes.FCMPL));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, noQuantity));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, TARGET_CLASS, COMMODITY_METHOD, COMMODITY_DESCRIPTOR, false));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, COMMODITY, "getEconUnit", "()F", false));
        method.instructions.add(new VarInsnNode(Opcodes.FSTORE, 6));
        method.instructions.add(noQuantity);
    }

    /** Captures the current eMod object, value, and description into locals 4, 5, and 7. */
    private static void captureEventMod(MethodNode method) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new LdcInsnNode("eMod"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, MUTABLE, "getFlatStatMod",
                "(Ljava/lang/String;)L" + STAT_MOD + ";", false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        method.instructions.add(new InsnNode(Opcodes.FCONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.FSTORE, 5));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 7));
        LabelNode noEventMod = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, noEventMod));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, STAT_MOD, "getValue", "()F", false));
        method.instructions.add(new VarInsnNode(Opcodes.FSTORE, 5));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, STAT_MOD, "getDesc", "()Ljava/lang/String;", false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 7));
        method.instructions.add(noEventMod);
    }

    private static void compareEconUnitIfRelevant(MethodNode method, LabelNode mismatch) {
        LabelNode noQuantity = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_CLASS, QUANTITY, "I"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false));
        method.instructions.add(new InsnNode(Opcodes.FCONST_0));
        method.instructions.add(new InsnNode(Opcodes.FCMPL));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, noQuantity));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, TARGET_CLASS, COMMODITY_METHOD, COMMODITY_DESCRIPTOR, false));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, COMMODITY, "getEconUnit", "()F", false));
        method.instructions.add(new VarInsnNode(Opcodes.FSTORE, 6));
        compare(method, ECON_UNIT, 6, mismatch);
        method.instructions.add(noQuantity);
    }

    private static void loadStat(MethodNode method, String field, int local) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, field, "L" + MUTABLE_TEMP + ";"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, local));
    }

    private static void compareClean(MethodNode method, int local, LabelNode mismatch) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, local));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                MUTABLE,
                MutableStatDirtyAccessorPlan.ACCESSOR,
                MutableStatDirtyAccessorPlan.ACCESSOR_DESCRIPTOR,
                false));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNE, mismatch));
    }

    private static void compareStatValue(
            MethodNode method, String memoField, int local, LabelNode mismatch) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_CLASS, memoField, "I"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, local));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, MUTABLE, "modified", "F"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Float", "floatToIntBits", "(F)I", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPNE, mismatch));
    }

    private static void storeStatValue(MethodNode method, String memoField, int local) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, local));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, MUTABLE, "modified", "F"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Float", "floatToIntBits", "(F)I", false));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_CLASS, memoField, "I"));
    }

    private static void compare(MethodNode method, String field, int local, LabelNode mismatch) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_CLASS, field, "I"));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, local));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Float", "floatToIntBits", "(F)I", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPNE, mismatch));
    }

    private static void store(MethodNode method, String field, int local) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, local));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Float", "floatToIntBits", "(F)I", false));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_CLASS, field, "I"));
    }

    private static void compareReference(
            MethodNode method, String field, int local, LabelNode mismatch) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, field, "Ljava/lang/Object;"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, local));
        method.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPNE, mismatch));
    }

    private static void storeReference(MethodNode method, String field, int local) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, local));
        method.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, TARGET_CLASS, field, "Ljava/lang/Object;"));
    }

    private static void invokeOriginal(MethodNode method) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, TARGET_CLASS, ORIGINAL, DESCRIPTOR, false));
    }

    private static void addMemoFields(ClassNode owner) {
        int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT;
        owner.fields.add(new FieldNode(access, VALID, "Z", null, null));
        owner.fields.add(new FieldNode(access, QUANTITY, "I", null, null));
        owner.fields.add(new FieldNode(access, AVAILABLE, "I", null, null));
        owner.fields.add(new FieldNode(access, TRADE, "I", null, null));
        owner.fields.add(new FieldNode(access, TRADE_PLUS, "I", null, null));
        owner.fields.add(new FieldNode(access, TRADE_MINUS, "I", null, null));
        owner.fields.add(new FieldNode(access, EVENT_MOD, "I", null, null));
        owner.fields.add(new FieldNode(access, ECON_UNIT, "I", null, null));
        owner.fields.add(new FieldNode(access, AVAILABLE_STAT_REF, "Ljava/lang/Object;", null, null));
        owner.fields.add(new FieldNode(access, TRADE_STAT_REF, "Ljava/lang/Object;", null, null));
        owner.fields.add(new FieldNode(access, TRADE_PLUS_STAT_REF, "Ljava/lang/Object;", null, null));
        owner.fields.add(new FieldNode(access, TRADE_MINUS_STAT_REF, "Ljava/lang/Object;", null, null));
        owner.fields.add(new FieldNode(access, EVENT_MOD_REF, "Ljava/lang/Object;", null, null));
        owner.fields.add(new FieldNode(access, EVENT_MOD_DESC, "Ljava/lang/Object;", null, null));
    }

    private static boolean hasMemoFields(ClassNode owner) {
        return owner.fields.stream().anyMatch(field -> field.name.startsWith("preflight$eventMod"));
    }

    private static boolean reviewOriginal(MethodNode method) {
        return calls(method, TARGET_CLASS, QUANTITY_METHOD, QUANTITY_DESCRIPTOR) == 1
                && calls(method, TARGET_CLASS, MOD_VALUE_METHOD, MOD_VALUE_DESCRIPTOR) == 1
                && calls(method, MUTABLE_TEMP, "unmodifyFlat", "(Ljava/lang/String;)V") == 2
                && calls(method, MUTABLE_TEMP, "modifyFlat",
                        "(Ljava/lang/String;FLjava/lang/String;)V") == 1
                && returns(method) == 1
                && constants(method, "eMod") == 3
                && constants(method, "Recent trade/events") == 1;
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static int constants(MethodNode method, String value) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode constant && value.equals(constant.cst)) count++;
        }
        return count;
    }

    private static int returns(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) count++;
        }
        return count;
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

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .anyMatch(method -> name.equals(method.name) && descriptor.equals(method.desc));
    }
}

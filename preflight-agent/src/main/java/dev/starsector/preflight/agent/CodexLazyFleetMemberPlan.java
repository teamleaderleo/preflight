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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Defers Codex-only FleetMembers until the corresponding ship detail is actually opened. */
final class CodexLazyFleetMemberPlan {
    static final String CODEX_CLASS = IndustryDemandSupplyMemoPlan.CODEX_CLASS;
    static final String CODEX_SHA256 = IndustryDemandSupplyMemoPlan.CODEX_SHA256;
    static final String ENTRY_CLASS = "com/fs/starfarer/api/impl/codex/CodexEntryV2";
    static final String ENTRY_SHA256 =
            "28cb83ba722061c5d7948896b47e4edd51992b3b963cd313242d30f533862fa6";
    static final String POPULATE_METHOD = "populateShipsAndStations";
    static final String POPULATE_DESCRIPTOR =
            "(Lcom/fs/starfarer/api/impl/codex/CodexEntryPlugin;"
                    + "Lcom/fs/starfarer/api/impl/codex/CodexEntryPlugin;)V";
    static final String MODULES_METHOD = "addModulesForVariant";
    static final String MODULES_DESCRIPTOR =
            "(Lcom/fs/starfarer/api/combat/ShipVariantAPI;Z"
                    + "Lcom/fs/starfarer/api/impl/codex/CodexEntryPlugin;"
                    + "Lcom/fs/starfarer/api/impl/codex/CodexEntryPlugin;)Ljava/util/List;";
    static final String ACCESSOR_METHOD = "getParam2";
    static final String ACCESSOR_DESCRIPTOR = "()Ljava/lang/Object;";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/CodexLazyFleetMemberRuntime";
    private static final String GLOBAL = "com/fs/starfarer/api/Global";
    private static final String SETTINGS = "com/fs/starfarer/api/SettingsAPI";
    private static final String VARIANT = "com/fs/starfarer/api/combat/ShipVariantAPI";
    private static final String HULL_SPEC = "com/fs/starfarer/api/combat/ShipHullSpecAPI";
    private static final String MEMBER = "com/fs/starfarer/api/fleet/FleetMemberAPI";
    private static final String MEMBER_TYPE = "com/fs/starfarer/api/fleet/FleetMemberType";
    private static final String ENTRY = "com/fs/starfarer/api/impl/codex/CodexEntryPlugin";
    private static final String ENTRY_V2 = "com/fs/starfarer/api/impl/codex/CodexEntryV2";
    private static final String MODULE_ENTRY = CODEX_CLASS + "$16";
    private static final String CREATE_MEMBER_DESCRIPTOR =
            "(L" + MEMBER_TYPE + ";L" + VARIANT + ";)L" + MEMBER + ";";
    private static final String LAZY_MODULE_HELPER = "preflight$addLazyModuleEntry";
    private static final String LAZY_MODULE_DESCRIPTOR =
            "(L" + ENTRY + ";L" + ENTRY + ";L" + VARIANT + ";)L" + ENTRY + ";";

    private CodexLazyFleetMemberPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] bytes) {
        if (signature.majorVersion() != 61) return null;
        if (CODEX_CLASS.equals(signature.internalName())
                && CODEX_SHA256.equals(signature.sha256())) {
            return transformCodex(bytes);
        }
        if (ENTRY_CLASS.equals(signature.internalName())
                && ENTRY_SHA256.equals(signature.sha256())) {
            return transformEntryAccessor(bytes);
        }
        return null;
    }

    private static byte[] transformCodex(byte[] bytes) {
        ClassNode owner = read(bytes);
        MethodNode populate = unique(owner, POPULATE_METHOD, POPULATE_DESCRIPTOR);
        MethodNode modules = unique(owner, MODULES_METHOD, MODULES_DESCRIPTOR);
        if (populate == null || modules == null || unique(owner, LAZY_MODULE_HELPER,
                LAZY_MODULE_DESCRIPTOR) != null || calls(owner, RUNTIME) != 0) return null;

        MethodInsnNode topMember = uniqueCall(populate, SETTINGS, "createFleetMember",
                CREATE_MEMBER_DESCRIPTOR);
        MethodInsnNode moduleMember = uniqueCall(modules, SETTINGS, "createFleetMember",
                CREATE_MEMBER_DESCRIPTOR);
        MethodInsnNode moduleEntry = uniqueCall(modules, CODEX_CLASS, "addModuleEntry",
                "(L" + ENTRY + ";L" + ENTRY + ";L" + MEMBER + ";Z)L" + ENTRY + ";");
        if (topMember == null || moduleMember == null || moduleEntry == null) return null;

        AbstractInsnNode topVariant = previousCode(topMember);
        AbstractInsnNode topType = previousCode(topVariant);
        AbstractInsnNode topSettings = previousCode(topType);
        if (!(topVariant instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                || load.var != 10 || !(topType instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.GETSTATIC || !MEMBER_TYPE.equals(field.owner)
                || !"SHIP".equals(field.name) || !isSettingsLookup(topSettings)) return null;

        LabelNode vanillaTop = new LabelNode();
        LabelNode joinedTop = new LabelNode();
        InsnList deferredTop = new InsnList();
        deferredTop.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "active",
                "()Z", false));
        deferredTop.add(new JumpInsnNode(Opcodes.IFEQ, vanillaTop));
        deferredTop.add(new VarInsnNode(Opcodes.ALOAD, 10));
        deferredTop.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "defer",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        deferredTop.add(new JumpInsnNode(Opcodes.GOTO, joinedTop));
        deferredTop.add(vanillaTop);
        deferredTop.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GLOBAL, "getSettings",
                "()L" + SETTINGS + ";", false));
        deferredTop.add(new FieldInsnNode(Opcodes.GETSTATIC, MEMBER_TYPE, "SHIP",
                "L" + MEMBER_TYPE + ";"));
        deferredTop.add(new VarInsnNode(Opcodes.ALOAD, 10));
        deferredTop.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, SETTINGS,
                "createFleetMember", CREATE_MEMBER_DESCRIPTOR, true));
        deferredTop.add(joinedTop);
        populate.instructions.insertBefore(topSettings, deferredTop);
        populate.instructions.remove(topSettings);
        populate.instructions.remove(topType);
        populate.instructions.remove(topVariant);
        populate.instructions.remove(topMember);

        AbstractInsnNode moduleSettings = previousCode(previousCode(previousCode(moduleMember)));
        AbstractInsnNode memberStore = nextCode(moduleMember);
        AbstractInsnNode entryStore = nextCode(moduleEntry);
        if (!isSettingsLookup(moduleSettings)
                || !(memberStore instanceof VarInsnNode storedMember)
                || storedMember.getOpcode() != Opcodes.ASTORE || storedMember.var != 10
                || !(entryStore instanceof VarInsnNode storedEntry)
                || storedEntry.getOpcode() != Opcodes.ASTORE || storedEntry.var != 11) return null;

        LabelNode vanilla = new LabelNode();
        LabelNode joined = new LabelNode();
        InsnList lazyBranch = new InsnList();
        lazyBranch.add(new VarInsnNode(Opcodes.ILOAD, 1));
        lazyBranch.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));
        lazyBranch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "active",
                "()Z", false));
        lazyBranch.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));
        lazyBranch.add(new VarInsnNode(Opcodes.ALOAD, 3));
        lazyBranch.add(new VarInsnNode(Opcodes.ALOAD, 2));
        lazyBranch.add(new VarInsnNode(Opcodes.ALOAD, 8));
        lazyBranch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CODEX_CLASS,
                LAZY_MODULE_HELPER, LAZY_MODULE_DESCRIPTOR, false));
        lazyBranch.add(new VarInsnNode(Opcodes.ASTORE, 11));
        // Null merges with FleetMemberAPI, keeping the later vanilla-only branch verifiable.
        lazyBranch.add(new InsnNode(Opcodes.ACONST_NULL));
        lazyBranch.add(new VarInsnNode(Opcodes.ASTORE, 10));
        lazyBranch.add(new JumpInsnNode(Opcodes.GOTO, joined));
        lazyBranch.add(vanilla);
        modules.instructions.insertBefore(moduleSettings, lazyBranch);
        modules.instructions.insert(entryStore, joined);
        owner.methods.add(lazyModuleHelper());

        CodexLazyFleetMemberRuntime.codexInstalled();
        return write(owner);
    }

    private static MethodNode lazyModuleHelper() {
        MethodNode method = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                LAZY_MODULE_HELPER, LAZY_MODULE_DESCRIPTOR, null, null);
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, VARIANT, "getHullSpec",
                "()L" + HULL_SPEC + ";", true));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, HULL_SPEC, "getHullId",
                "()Ljava/lang/String;", true));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CODEX_CLASS, "getShipEntryId",
                "(Ljava/lang/String;)Ljava/lang/String;", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new TypeInsnNode(Opcodes.NEW, MODULE_ENTRY));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, HULL_SPEC, "getHullName",
                "()Ljava/lang/String;", true));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MODULE_ENTRY, "<init>",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;L"
                        + HULL_SPEC + ";L" + ENTRY + ";)V", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "defer",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTRY_V2, "setParam2",
                "(Ljava/lang/Object;)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, CODEX_CLASS, "TAG_EMPTY_MODULE",
                "Ljava/lang/String;"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTRY_V2, "addTag",
                "(Ljava/lang/String;)V", false));
        LabelNode noParent = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new JumpInsnNode(Opcodes.IFNULL, noParent));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "addChild",
                "(L" + ENTRY + ";)V", true));
        code.add(noParent);
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static byte[] transformEntryAccessor(byte[] bytes) {
        ClassNode owner = read(bytes);
        MethodNode accessor = unique(owner, ACCESSOR_METHOD, ACCESSOR_DESCRIPTOR);
        if (accessor == null || calls(owner, RUNTIME) != 0) return null;
        List<AbstractInsnNode> returns = opcodes(accessor, Opcodes.ARETURN);
        List<org.objectweb.asm.tree.FieldInsnNode> fields = new ArrayList<>();
        for (AbstractInsnNode instruction : accessor.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD && ENTRY_V2.equals(field.owner)
                    && "param2".equals(field.name) && "Ljava/lang/Object;".equals(field.desc)) {
                fields.add(field);
            }
        }
        if (returns.size() != 1 || fields.size() != 1 || nextCode(fields.get(0)) != returns.get(0)) {
            return null;
        }

        LabelNode vanillaReturn = new LabelNode();
        InsnList materialize = new InsnList();
        // The original field value remains on the stack for the untouched ARETURN miss path.
        materialize.add(new InsnNode(Opcodes.DUP));
        materialize.add(new TypeInsnNode(Opcodes.INSTANCEOF, VARIANT));
        materialize.add(new JumpInsnNode(Opcodes.IFEQ, vanillaReturn));
        materialize.add(new TypeInsnNode(Opcodes.CHECKCAST, VARIANT));
        materialize.add(new VarInsnNode(Opcodes.ASTORE, 1));
        materialize.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GLOBAL, "getSettings",
                "()L" + SETTINGS + ";", false));
        materialize.add(new FieldInsnNode(Opcodes.GETSTATIC, MEMBER_TYPE, "SHIP",
                "L" + MEMBER_TYPE + ";"));
        materialize.add(new VarInsnNode(Opcodes.ALOAD, 1));
        materialize.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, SETTINGS,
                "createFleetMember", CREATE_MEMBER_DESCRIPTOR, true));
        materialize.add(new VarInsnNode(Opcodes.ASTORE, 2));
        materialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        materialize.add(new VarInsnNode(Opcodes.ALOAD, 2));
        materialize.add(new FieldInsnNode(Opcodes.PUTFIELD, ENTRY_V2, "param2",
                "Ljava/lang/Object;"));
        materialize.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "materialized",
                "()V", false));
        materialize.add(new VarInsnNode(Opcodes.ALOAD, 2));
        materialize.add(new InsnNode(Opcodes.ARETURN));
        materialize.add(vanillaReturn);
        accessor.instructions.insert(fields.get(0), materialize);

        CodexLazyFleetMemberRuntime.entryAccessorInstalled();
        return write(owner);
    }

    private static List<AbstractInsnNode> opcodes(MethodNode method, int opcode) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) result.add(instruction);
        }
        return result;
    }

    private static boolean isSettingsLookup(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC && GLOBAL.equals(call.owner)
                && "getSettings".equals(call.name) && ("()L" + SETTINGS + ";").equals(call.desc);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static byte[] write(ClassNode owner) {
        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
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

    private static MethodInsnNode uniqueCall(MethodNode method, String owner, String name,
            String descriptor) {
        List<MethodInsnNode> matches = calls(method, owner, name, descriptor);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name,
            String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name) && descriptor.equals(call.desc)) result.add(call);
        }
        return result;
    }

    private static int calls(ClassNode owner, String callOwner) {
        int count = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && callOwner.equals(call.owner)) count++;
            }
        }
        return count;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }
}

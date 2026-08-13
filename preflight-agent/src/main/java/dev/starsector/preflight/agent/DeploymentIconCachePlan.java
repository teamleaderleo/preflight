package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Memoizes positive deployment member-to-icon scans behind exact owner mutation hooks. */
final class DeploymentIconCachePlan {
    static final String TARGET_CLASS = "com/fs/starfarer/campaign/ui/supersuper";
    static final String ORIGINAL_SHA256 =
            "33a24411ff5ab5782199b5ac3bac664d72dbeb1fae50c15b94e7a6c5491b75cd";
    static final String LOOKUP_METHOD = "getIconForMember";
    static final String MEMBER = "com/fs/starfarer/campaign/fleet/FleetMember";
    static final String ICON = "com/fs/starfarer/ui/super/new";
    static final String LOOKUP_DESCRIPTOR = "(L" + MEMBER + ";)L" + ICON + ";";
    static final String MEMBERS_METHOD = "getMembers";
    static final String MEMBERS_DESCRIPTOR = "()Ljava/util/List;";
    static final String LIST_METHOD = "getList";
    static final String GRID = "com/fs/starfarer/coreui/Oo00";
    static final String LIST_DESCRIPTOR = "()L" + GRID + ";";
    static final String CLEAR_METHOD = "clear";
    static final String CLEAR_DESCRIPTOR = "()V";
    static final String ADD_METHOD = "addIconFor";
    static final String ADD_MEMBER_DESCRIPTOR = "(L" + MEMBER + ";)V";
    static final String COMBAT_ENTRY = "com/fs/starfarer/combat/CombatFleetManager$O0";
    static final String ADD_COMBAT_ENTRY_DESCRIPTOR = "(L" + COMBAT_ENTRY + ";)V";
    static final String REMOVE_METHOD = "removeIconFor";
    static final String REMOVE_DESCRIPTOR = "(L" + MEMBER + ";)V";

    private static final String ORIGINAL = "preflight$original$getIconForMember";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/DeploymentIconCacheRuntime";
    private static final String MEMBER_ACCESSOR = "Öo0000";
    private static final String MEMBER_ACCESSOR_DESCRIPTOR = "()L" + MEMBER + ";";
    private static final String COMBAT_MEMBER_ACCESSOR = "getMember";

    private DeploymentIconCachePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(LOOKUP_METHOD, LOOKUP_DESCRIPTOR)
                || !signature.hasMethod(MEMBERS_METHOD, MEMBERS_DESCRIPTOR)
                || !signature.hasMethod(LIST_METHOD, LIST_DESCRIPTOR)
                || !signature.hasMethod(CLEAR_METHOD, CLEAR_DESCRIPTOR)
                || !signature.hasMethod(ADD_METHOD, ADD_MEMBER_DESCRIPTOR)
                || !signature.hasMethod(ADD_METHOD, ADD_COMBAT_ENTRY_DESCRIPTOR)
                || !signature.hasMethod(REMOVE_METHOD, REMOVE_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!TARGET_CLASS.equals(owner.name) || hasMethod(owner, ORIGINAL, LOOKUP_DESCRIPTOR)) {
            return null;
        }
        MethodNode lookup = uniqueMethod(owner, LOOKUP_METHOD, LOOKUP_DESCRIPTOR);
        MethodNode clear = uniqueMethod(owner, CLEAR_METHOD, CLEAR_DESCRIPTOR);
        MethodNode addMember = uniqueMethod(owner, ADD_METHOD, ADD_MEMBER_DESCRIPTOR);
        MethodNode addCombatEntry = uniqueMethod(owner, ADD_METHOD, ADD_COMBAT_ENTRY_DESCRIPTOR);
        MethodNode remove = uniqueMethod(owner, REMOVE_METHOD, REMOVE_DESCRIPTOR);
        AbstractInsnNode clearReturn = singleReturn(clear, 1);
        AbstractInsnNode addMemberReturn = singleReturn(addMember, 2);
        AbstractInsnNode addCombatEntryReturn = singleReturn(addCombatEntry, 2);
        AbstractInsnNode removeReturn = singleReturn(remove, 2);
        if (!eligible(lookup) || clearReturn == null || addMemberReturn == null
                || addCombatEntryReturn == null || removeReturn == null) {
            return null;
        }
        int access = lookup.access;
        lookup.name = ORIGINAL;
        owner.methods.add(wrapper(owner.name, access));
        instrumentClear(clear, clearReturn);
        instrumentMemberAdd(addMember, addMemberReturn);
        instrumentCombatEntryAdd(addCombatEntry, addCombatEntryReturn);
        instrumentRemove(remove, removeReturn);
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        DeploymentIconCacheRuntime.installed();
        return writer.toByteArray();
    }

    private static MethodNode wrapper(String owner, int access) {
        MethodNode method = new MethodNode(
                Opcodes.ASM9, access, LOOKUP_METHOD, LOOKUP_DESCRIPTOR, null, null);
        LabelNode cacheEnabled = new LabelNode();
        LabelNode delegate = new LabelNode();

        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "enabled", "()Z", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNE, cacheEnabled));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, owner, ORIGINAL, LOOKUP_DESCRIPTOR, false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));

        method.instructions.add(cacheEnabled);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "lookup",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ICON));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, delegate));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ICON, MEMBER_ACCESSOR, MEMBER_ACCESSOR_DESCRIPTOR, false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPNE, delegate));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));

        method.instructions.add(delegate);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, owner, ORIGINAL, LOOKUP_DESCRIPTOR, false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "record",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static void instrumentClear(MethodNode method, AbstractInsnNode returnInstruction) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "cleared", "(Ljava/lang/Object;)V", false));
        method.instructions.insertBefore(returnInstruction, hook);
    }

    private static void instrumentMemberAdd(
            MethodNode method, AbstractInsnNode returnInstruction) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "added",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        method.instructions.insertBefore(returnInstruction, hook);
    }

    private static void instrumentCombatEntryAdd(
            MethodNode method, AbstractInsnNode returnInstruction) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, COMBAT_ENTRY,
                COMBAT_MEMBER_ACCESSOR, MEMBER_ACCESSOR_DESCRIPTOR, false));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "added",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        method.instructions.insertBefore(returnInstruction, hook);
    }

    private static void instrumentRemove(MethodNode method, AbstractInsnNode returnInstruction) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "removed",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        method.instructions.insertBefore(returnInstruction, hook);
    }

    private static AbstractInsnNode singleReturn(MethodNode method, int minimumLocals) {
        if (!eligible(method) || method.maxLocals < minimumLocals) return null;
        AbstractInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            if (found != null) return null;
            found = instruction;
        }
        return found;
    }

    private static boolean eligible(MethodNode method) {
        return method != null && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0;
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .anyMatch(method -> name.equals(method.name) && descriptor.equals(method.desc));
    }
}

package dev.starsector.preflight.agent;

import java.util.List;
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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Shares an exact AI Tweaks weapon location only inside one synchronous target selection. */
final class AiTweaksWeaponLocationSnapshotPlan {
    static final String PLAN_ID = AiTweaksWeaponLocationSnapshotRuntime.PLAN_ID;
    static final String ENABLED_PROPERTY = AiTweaksWeaponLocationSnapshotRuntime.ENABLED_PROPERTY;
    static final String SOURCE_SHA256 =
            "9f6179bcd2df2e3ce8cea2da79051c9f1be3c9b71712c6c28d7568b777ecf5b2";
    static final String SOURCE_FILE = "aitweaks-core.jar";
    static final String LOADER = "com/genir/aitweaks/launcher/loading/CoreLoader";

    static final String AUTOFIRE_CLASS =
            "com/genir/aitweaks/core/shipai/autofire/AutofireAI";
    static final String AUTOFIRE_SHA256 =
            "f8bf1794c6277b5d7a64f206b62e8ac78ec7c35ce90c50dfecd5ca66a5119b56";
    static final String AUTOFIRE_METHOD = "updateTarget";
    static final String AUTOFIRE_DESCRIPTOR = "(F)V";

    static final String WEAPON_HANDLE_CLASS = "com/genir/aitweaks/core/handles/WeaponHandle";
    static final String WEAPON_HANDLE_ARCHIVE_ENTRY_SHA256 =
            "a5b29faf9870d98a9d3275e0b5d50025b456bdd0e3396f7e4f77d3a4bc9c8282";
    // CoreLoader resolves private engine symbols before defineClass, so admission pins the bytes
    // observed at the JVM transform boundary rather than the still-obfuscated archive entry.
    static final String WEAPON_HANDLE_SHA256 =
            "0c574bd722f62c04b543d2ee0f9e8276776ecb3f26e91ddc06dfc868ba4afb21";
    static final String LOCATION_METHOD = "getLocation-impl";
    static final String LOCATION_DESCRIPTOR = "(Lcom/fs/starfarer/api/combat/WeaponAPI;)"
            + "Lorg/lwjgl/util/vector/Vector2f;";

    private static final String SELECT_TARGET =
            "com/genir/aitweaks/core/shipai/autofire/SelectTarget";
    private static final String SELECT_CONSTRUCTOR_DESCRIPTOR =
            "(Lcom/fs/starfarer/api/combat/WeaponAPI;"
                    + "Lcom/fs/starfarer/api/combat/CombatEntityAPI;"
                    + "Lcom/fs/starfarer/api/combat/ShipAPI;"
                    + "Lcom/genir/aitweaks/core/shipai/autofire/ballistics/BallisticParams;"
                    + "Lcom/genir/aitweaks/core/shipai/global/TargetTracker;"
                    + "Lkotlin/jvm/internal/DefaultConstructorMarker;)V";
    private static final String SELECT_METHOD = "target";
    private static final String SELECT_DESCRIPTOR =
            "()Lcom/fs/starfarer/api/combat/CombatEntityAPI;";
    private static final String WEAPON_API = "com/fs/starfarer/api/combat/WeaponAPI";
    private static final String WEAPON_DESCRIPTOR = "L" + WEAPON_API + ";";
    private static final String VECTOR = "org/lwjgl/util/vector/Vector2f";
    private static final String VECTOR_DESCRIPTOR = "L" + VECTOR + ";";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/AiTweaksWeaponLocationSnapshotRuntime";
    private static final String INTRINSICS = "kotlin/jvm/internal/Intrinsics";

    private AiTweaksWeaponLocationSnapshotPlan() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!enabled() || signature.majorVersion() != 61) return null;
        if (AUTOFIRE_CLASS.equals(signature.internalName())) {
            return transformAutofire(signature, originalBytes);
        }
        if (WEAPON_HANDLE_CLASS.equals(signature.internalName())) {
            return transformWeaponHandle(signature, originalBytes);
        }
        return null;
    }

    static List<Target> targets() {
        return List.of(
                new Target(
                        AUTOFIRE_CLASS,
                        AUTOFIRE_SHA256,
                        AUTOFIRE_METHOD,
                        AUTOFIRE_DESCRIPTOR),
                new Target(
                        WEAPON_HANDLE_CLASS,
                        WEAPON_HANDLE_SHA256,
                        LOCATION_METHOD,
                        LOCATION_DESCRIPTOR));
    }

    private static byte[] transformAutofire(ClassSignature signature, byte[] originalBytes) {
        if (!AUTOFIRE_SHA256.equals(signature.sha256())
                || !signature.hasMethod(AUTOFIRE_METHOD, AUTOFIRE_DESCRIPTOR)) return null;

        ClassNode owner = parse(originalBytes);
        MethodNode method = unique(owner, AUTOFIRE_METHOD, AUTOFIRE_DESCRIPTOR);
        if (method == null || field(owner, "weapon", WEAPON_DESCRIPTOR) == null) return null;

        TypeInsnNode allocation = null;
        MethodInsnNode constructor = null;
        MethodInsnNode selection = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW && SELECT_TARGET.equals(type.desc)) {
                if (allocation != null) return null;
                allocation = type;
            } else if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && SELECT_TARGET.equals(call.owner)
                    && "<init>".equals(call.name)
                    && SELECT_CONSTRUCTOR_DESCRIPTOR.equals(call.desc)) {
                if (constructor != null) return null;
                constructor = call;
            } else if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && SELECT_TARGET.equals(call.owner)
                    && SELECT_METHOD.equals(call.name)
                    && SELECT_DESCRIPTOR.equals(call.desc)) {
                if (selection != null) return null;
                selection = call;
            }
        }
        if (allocation == null || constructor == null || selection == null
                || method.instructions.indexOf(allocation) >= method.instructions.indexOf(constructor)
                || method.instructions.indexOf(constructor) >= method.instructions.indexOf(selection)
                || previousCode(selection) != constructor
                || !(nextCode(selection) instanceof FieldInsnNode targetAssignment)
                || targetAssignment.getOpcode() != Opcodes.PUTFIELD
                || !AUTOFIRE_CLASS.equals(targetAssignment.owner)
                || !"target".equals(targetAssignment.name)
                || !"Lcom/fs/starfarer/api/combat/CombatEntityAPI;".equals(targetAssignment.desc)) {
            return null;
        }

        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, 0));
        begin.add(new FieldInsnNode(
                Opcodes.GETFIELD, AUTOFIRE_CLASS, "weapon", WEAPON_DESCRIPTOR));
        begin.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "begin",
                "(Ljava/lang/Object;)V",
                false));
        method.instructions.insertBefore(allocation, begin);
        LabelNode protectedStart = new LabelNode();
        method.instructions.insertBefore(allocation, protectedStart);
        MethodInsnNode normalEnd = new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "end", "()V", false);
        method.instructions.insert(selection, normalEnd);
        LabelNode protectedEnd = new LabelNode();
        method.instructions.insert(normalEnd, protectedEnd);

        // Preserve the original exception while ensuring a caught failure cannot leak a cache
        // region into later WeaponHandle calls on the same game thread.
        int errorLocal = method.maxLocals;
        method.maxLocals++;
        LabelNode handler = new LabelNode();
        method.instructions.add(handler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, errorLocal));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "end", "()V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, errorLocal));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                protectedStart, protectedEnd, handler, null));

        byte[] transformed = write(owner);
        AiTweaksWeaponLocationSnapshotRuntime.autofireInstalled();
        return transformed;
    }

    private static byte[] transformWeaponHandle(ClassSignature signature, byte[] originalBytes) {
        if (!WEAPON_HANDLE_SHA256.equals(signature.sha256())
                || !signature.hasMethod(LOCATION_METHOD, LOCATION_DESCRIPTOR)) return null;

        ClassNode owner = parse(originalBytes);
        MethodNode method = unique(owner, LOCATION_METHOD, LOCATION_DESCRIPTOR);
        if (method == null || (method.access & Opcodes.ACC_STATIC) == 0
                || !exactOriginalLocationGetter(method)) return null;

        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;

        LabelNode miss = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "cachedLocation",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, miss));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, VECTOR));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(miss);
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                WEAPON_API,
                "getLocation",
                "()" + VECTOR_DESCRIPTOR,
                true));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new LdcInsnNode("getLocation(...)"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                INTRINSICS,
                "checkNotNullExpressionValue",
                "(Ljava/lang/Object;Ljava/lang/String;)V",
                false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "rememberLocation",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));

        byte[] transformed = write(owner);
        AiTweaksWeaponLocationSnapshotRuntime.weaponHandleInstalled();
        return transformed;
    }

    private static boolean exactOriginalLocationGetter(MethodNode method) {
        List<AbstractInsnNode> code = new java.util.ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) code.add(instruction);
        }
        if (code.size() != 6
                || !(code.get(0) instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                || load.var != 0
                || !(code.get(1) instanceof MethodInsnNode getter)
                || getter.getOpcode() != Opcodes.INVOKEINTERFACE
                || !WEAPON_API.equals(getter.owner)
                || !"getLocation".equals(getter.name)
                || !("()" + VECTOR_DESCRIPTOR).equals(getter.desc)
                || code.get(2).getOpcode() != Opcodes.DUP
                || !(code.get(3) instanceof LdcInsnNode message)
                || !"getLocation(...)".equals(message.cst)
                || !(code.get(4) instanceof MethodInsnNode check)
                || check.getOpcode() != Opcodes.INVOKESTATIC
                || !INTRINSICS.equals(check.owner)
                || !"checkNotNullExpressionValue".equals(check.name)
                || !"(Ljava/lang/Object;Ljava/lang/String;)V".equals(check.desc)
                || code.get(5).getOpcode() != Opcodes.ARETURN) {
            return false;
        }
        return true;
    }

    private static ClassNode parse(byte[] bytes) {
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
            if (!name.equals(method.name) || !descriptor.equals(method.desc)) continue;
            if (result != null) return null;
            result = method;
        }
        return result;
    }

    private static FieldNode field(ClassNode owner, String name, String descriptor) {
        return owner.fields.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst().orElse(null);
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction == null ? null : instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    record Target(String internalName, String sha256, String method, String descriptor) {
    }
}

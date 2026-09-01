package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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
import org.objectweb.asm.tree.VarInsnNode;

/** Removes redundant getter/interface work from exact base temporary-stat advancement. */
final class MutableStatTempAdvancePlan {
    static final String PLAN_ID = "mutable-stat-temp-advance-v1";
    static final String TARGET_CLASS =
            "com/fs/starfarer/api/combat/MutableStatWithTempMods";
    static final String ORIGINAL_SHA256 =
            "90690b6d4e6c4081990f3e545a9402c7120b659f0eb504218ec3c58da2c65a9e";
    static final String METHOD = "advance";
    static final String DESCRIPTOR = "(F)V";
    static final String ORIGINAL = "preflight$original$advance";

    private static final String MAP_FIELD = "tempMods";
    private static final String MAP_DESCRIPTOR = "Ljava/util/LinkedHashMap;";
    private static final String GET_MODS = "getMods";
    private static final String GET_MODS_DESCRIPTOR = "()Ljava/util/Map;";
    private static final String TEMP_MOD = TARGET_CLASS + "$TemporaryStatMod";
    private static final AtomicLong INSTALLED_TARGETS = new AtomicLong();

    private MutableStatTempAdvancePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(METHOD, DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode original = unique(owner, METHOD, DESCRIPTOR);
        if (original == null || unique(owner, ORIGINAL, DESCRIPTOR) != null
                || !reviewField(owner) || !reviewOriginal(original)) {
            return null;
        }

        int access = original.access;
        original.name = ORIGINAL;
        original.access = (original.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        owner.methods.add(optimized(access));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        installed();
        return writer.toByteArray();
    }

    static void installed() {
        INSTALLED_TARGETS.incrementAndGet();
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installedTargets", INSTALLED_TARGETS.get());
        values.put("strategy", "exact-base-direct-linked-hash-map-with-subclass-fallback");
        return values;
    }

    static void reset() {
        INSTALLED_TARGETS.set(0L);
    }

    private static MethodNode optimized(int access) {
        MethodNode method = new MethodNode(Opcodes.ASM9, access, METHOD, DESCRIPTOR, null, null);
        LabelNode fallback = new LabelNode();
        LabelNode loop = new LabelNode();
        LabelNode condition = new LabelNode();
        LabelNode done = new LabelNode();

        // A subclass may override getMods() while inheriting advance(). Preserve that behavior by
        // delegating its receiver to the byte-for-byte retained original method.
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        method.instructions.add(new LdcInsnNode(Type.getObjectType(TARGET_CLASS)));
        method.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPNE, fallback));

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, MAP_FIELD, MAP_DESCRIPTOR));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, done));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "isEmpty", "()Z", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNE, done));

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/LinkedHashMap",
                "values",
                "()Ljava/util/Collection;",
                false));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Collection",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, condition));

        method.instructions.add(loop);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "next",
                "()Ljava/lang/Object;",
                true));
        method.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, TEMP_MOD));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TEMP_MOD, "timeRemaining", "F"));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.FSUB));
        method.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, TEMP_MOD, "timeRemaining", "F"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TEMP_MOD, "timeRemaining", "F"));
        method.instructions.add(new InsnNode(Opcodes.FCONST_0));
        method.instructions.add(new InsnNode(Opcodes.FCMPG));
        method.instructions.add(new JumpInsnNode(Opcodes.IFGT, condition));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Iterator", "remove", "()V", true));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TEMP_MOD, "source", "Ljava/lang/String;"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                TARGET_CLASS,
                "unmodify",
                "(Ljava/lang/String;)V",
                false));

        method.instructions.add(condition);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "hasNext",
                "()Z",
                true));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNE, loop));
        method.instructions.add(done);
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        method.instructions.add(fallback);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, TARGET_CLASS, ORIGINAL, DESCRIPTOR, false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static boolean reviewField(ClassNode owner) {
        FieldNode match = null;
        for (FieldNode field : owner.fields) {
            if (MAP_FIELD.equals(field.name) && MAP_DESCRIPTOR.equals(field.desc)) {
                if (match != null) return false;
                match = field;
            }
        }
        return match != null
                && (match.access & Opcodes.ACC_PRIVATE) != 0
                && (match.access & Opcodes.ACC_STATIC) == 0;
    }

    private static boolean reviewOriginal(MethodNode method) {
        return calls(method, TARGET_CLASS, GET_MODS, GET_MODS_DESCRIPTOR) == 2
                && calls(method, "java/util/Map", "isEmpty", "()Z") == 1
                && calls(method, "java/util/Map", "values", "()Ljava/util/Collection;") == 1
                && calls(method, "java/util/Collection", "iterator", "()Ljava/util/Iterator;") == 1
                && calls(method, "java/util/Iterator", "next", "()Ljava/lang/Object;") == 1
                && calls(method, "java/util/Iterator", "remove", "()V") == 1
                && calls(method, "java/util/Iterator", "hasNext", "()Z") == 1
                && calls(method, TARGET_CLASS, "unmodify", "(Ljava/lang/String;)V") == 1
                && fieldReads(method, TARGET_CLASS, MAP_FIELD, MAP_DESCRIPTOR) == 1
                && fieldReads(method, TEMP_MOD, "timeRemaining", "F") == 2
                && fieldWrites(method, TEMP_MOD, "timeRemaining", "F") == 1
                && fieldReads(method, TEMP_MOD, "source", "Ljava/lang/String;") == 1;
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) count++;
        }
        return count;
    }

    private static int fieldReads(MethodNode method, String owner, String name, String descriptor) {
        return fields(method, Opcodes.GETFIELD, owner, name, descriptor);
    }

    private static int fieldWrites(MethodNode method, String owner, String name, String descriptor) {
        return fields(method, Opcodes.PUTFIELD, owner, name, descriptor);
    }

    private static int fields(
            MethodNode method, int opcode, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && instruction.getOpcode() == opcode
                    && owner.equals(field.owner) && name.equals(field.name)
                    && descriptor.equals(field.desc)) count++;
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
}

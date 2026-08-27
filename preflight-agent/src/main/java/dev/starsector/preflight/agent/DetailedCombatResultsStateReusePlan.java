package dev.starsector.preflight.agent;

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

/** Reuses exact Detailed Combat Results projectile and ship-state maps between combat frames. */
final class DetailedCombatResultsStateReusePlan {
    static final String PLAN_ID = DetailedCombatResultsStateReuseRuntime.PLAN_ID;
    static final String ENABLED_PROPERTY = DetailedCombatResultsStateReuseRuntime.ENABLED_PROPERTY;
    static final String TARGET_CLASS =
            "data/scripts/combatanalytics/damagedetection/FrameProcessorState";
    static final String ORIGINAL_SHA256 =
            "4df669b6bacfecffb5bd96da9def7e1cd6a8d975d18b2f375fe8eb57bcf0eebf";
    static final String SOURCE_FILE = "StarSectorDetailedCombatResults.jar";
    static final String SOURCE_SHA256 =
            "e8dedffb3a34ab1f8eb7d5479258999b42cf1064bffad825055ed81fbdb9c79c";
    static final String LOADER = "java/net/URLClassLoader";
    static final String METHOD = "updateCommonState";
    static final String DESCRIPTOR =
            "(FLcom/fs/starfarer/api/combat/CombatEngineAPI;)V";

    private static final String HASH_MAP = "java/util/HashMap";
    private static final String MAP_ENTRY = "java/util/Map$Entry";
    private static final String ITERATOR = "java/util/Iterator";
    private static final String LIST = "java/util/List";
    private static final String DOUBLE = "java/lang/Double";
    private static final String ENGINE = "com/fs/starfarer/api/combat/CombatEngineAPI";
    private static final String PROJECTILE =
            "com/fs/starfarer/api/combat/DamagingProjectileAPI";
    private static final String HISTORY = "historicalProjectilesToAge";
    private static final String ALIVE_LAST = "aliveShipsLastFrameById";
    private static final String ALIVE_THIS = "aliveShipsThisFrameById";
    private static final String KILLED = "killedShipsThisFrameById";
    private static final String CURRENT_AGE = "currentAge";
    private static final String MAP_DESCRIPTOR = "Ljava/util/HashMap;";
    private static final String HISTORY_HELPER = "$preflight$refreshProjectileHistory";
    private static final String HISTORY_HELPER_DESCRIPTOR =
            "(Lcom/fs/starfarer/api/combat/CombatEngineAPI;)V";
    private static final String SHIP_HELPER = "$preflight$rotateShipMaps";
    private static final String SHIP_HELPER_DESCRIPTOR = "()V";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/DetailedCombatResultsStateReuseRuntime";

    private DetailedCombatResultsStateReusePlan() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!enabled()
                || signature.majorVersion() != 60
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || !signature.hasMethod(METHOD, DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = unique(owner, METHOD, DESCRIPTOR);
        if (method == null
                || unique(owner, HISTORY_HELPER, HISTORY_HELPER_DESCRIPTOR) != null
                || unique(owner, SHIP_HELPER, SHIP_HELPER_DESCRIPTOR) != null
                || !reviewedShape(method)) {
            return null;
        }

        FieldInsnNode historyWrite = uniqueFieldWrite(method, HISTORY);
        TypeInsnNode historyAllocation = previousAllocation(historyWrite, HASH_MAP);
        FieldInsnNode aliveLastWrite = uniqueFieldWrite(method, ALIVE_LAST);
        FieldInsnNode aliveThisWrite = uniqueFieldWrite(method, ALIVE_THIS);
        AbstractInsnNode shipStart = previousCode(previousCode(previousCode(aliveLastWrite)));
        if (historyWrite == null || historyAllocation == null || aliveLastWrite == null
                || aliveThisWrite == null || shipStart == null
                || shipStart.getOpcode() != Opcodes.ALOAD) {
            return null;
        }

        InsnList historyCall = new InsnList();
        historyCall.add(new VarInsnNode(Opcodes.ALOAD, 0));
        historyCall.add(new VarInsnNode(Opcodes.ALOAD, 2));
        historyCall.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                TARGET_CLASS,
                HISTORY_HELPER,
                HISTORY_HELPER_DESCRIPTOR,
                false));
        method.instructions.insertBefore(historyAllocation, historyCall);
        removeInclusive(method.instructions, historyAllocation, historyWrite);

        InsnList shipCall = new InsnList();
        shipCall.add(new VarInsnNode(Opcodes.ALOAD, 0));
        shipCall.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                TARGET_CLASS,
                SHIP_HELPER,
                SHIP_HELPER_DESCRIPTOR,
                false));
        method.instructions.insertBefore(shipStart, shipCall);
        removeInclusive(method.instructions, shipStart, aliveThisWrite);

        owner.methods.add(historyHelper());
        owner.methods.add(shipHelper());
        DetailedCombatResultsStateReuseRuntime.installed();

        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static boolean reviewedShape(MethodNode method) {
        return method.access == Opcodes.ACC_PUBLIC
                && allocations(method, HASH_MAP) == 3
                && calls(method, HASH_MAP, "<init>", "(I)V") == 2
                && calls(method, HASH_MAP, "<init>", "(Ljava/util/Map;)V") == 1
                && calls(method, ENGINE, "getProjectiles", "()Ljava/util/List;") == 2
                && fieldWrites(method, HISTORY) == 1
                && fieldWrites(method, ALIVE_LAST) == 1
                && fieldWrites(method, ALIVE_THIS) == 1
                && fieldWrites(method, KILLED) == 1;
    }

    private static MethodNode historyHelper() {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                HISTORY_HELPER,
                HISTORY_HELPER_DESCRIPTOR,
                null,
                null);
        LabelNode loop = new LabelNode();
        LabelNode remove = new LabelNode();
        LabelNode retained = new LabelNode();
        LabelNode currentLoop = new LabelNode();
        LabelNode done = new LabelNode();

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, HISTORY, MAP_DESCRIPTOR));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, HASH_MAP, "size", "()I", false));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 8));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, HASH_MAP, "entrySet", "()Ljava/util/Set;", false));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(loop);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, retained));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, MAP_ENTRY));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, MAP_ENTRY, "getKey", "()Ljava/lang/Object;", true));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, PROJECTILE));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, PROJECTILE, "isExpired", "()Z", true));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNE, remove));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, MAP_ENTRY, "getValue", "()Ljava/lang/Object;", true));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, DOUBLE));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, DOUBLE, "doubleValue", "()D", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, CURRENT_AGE, "D"));
        method.instructions.add(new InsnNode(Opcodes.DCMPL));
        method.instructions.add(new JumpInsnNode(Opcodes.IFGT, loop));
        method.instructions.add(remove);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, ITERATOR, "remove", "()V", true));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, loop));

        method.instructions.add(retained);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, ENGINE, "getProjectiles", "()Ljava/util/List;", true));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, LIST, "iterator", "()Ljava/util/Iterator;", true));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 6));
        method.instructions.add(currentLoop);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 6));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, done));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 6));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, PROJECTILE));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 7));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, CURRENT_AGE, "D"));
        method.instructions.add(new InsnNode(Opcodes.DCONST_1));
        method.instructions.add(new InsnNode(Opcodes.DADD));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, DOUBLE, "valueOf", "(D)Ljava/lang/Double;", false));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                HASH_MAP,
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, currentLoop));

        method.instructions.add(done);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 8));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, HASH_MAP, "size", "()I", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, LIST, "size", "()I", true));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "historyFrame", "(III)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static MethodNode shipHelper() {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                SHIP_HELPER,
                SHIP_HELPER_DESCRIPTOR,
                null,
                null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, ALIVE_LAST, MAP_DESCRIPTOR));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, ALIVE_THIS, MAP_DESCRIPTOR));
        method.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, TARGET_CLASS, ALIVE_LAST, MAP_DESCRIPTOR));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, KILLED, MAP_DESCRIPTOR));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, HASH_MAP, "clear", "()V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, KILLED, MAP_DESCRIPTOR));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, ALIVE_LAST, MAP_DESCRIPTOR));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, HASH_MAP, "putAll", "(Ljava/util/Map;)V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, TARGET_CLASS, ALIVE_THIS, MAP_DESCRIPTOR));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, ALIVE_THIS, MAP_DESCRIPTOR));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, HASH_MAP, "clear", "()V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, ALIVE_LAST, MAP_DESCRIPTOR));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, HASH_MAP, "size", "()I", false));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "shipFrame", "(I)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
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

    private static FieldInsnNode uniqueFieldWrite(MethodNode method, String name) {
        FieldInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && TARGET_CLASS.equals(field.owner)
                    && name.equals(field.name)
                    && MAP_DESCRIPTOR.equals(field.desc)) {
                if (result != null) return null;
                result = field;
            }
        }
        return result;
    }

    private static TypeInsnNode previousAllocation(AbstractInsnNode end, String type) {
        for (AbstractInsnNode current = end; current != null; current = current.getPrevious()) {
            if (current instanceof TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW
                    && type.equals(allocation.desc)) return allocation;
        }
        return null;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static void removeInclusive(
            InsnList instructions, AbstractInsnNode start, AbstractInsnNode end) {
        AbstractInsnNode current = start;
        while (current != null) {
            AbstractInsnNode next = current.getNext();
            instructions.remove(current);
            if (current == end) return;
            current = next;
        }
        throw new IllegalStateException("Reviewed instruction range was not contiguous");
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

    private static int fieldWrites(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && TARGET_CLASS.equals(field.owner)
                    && name.equals(field.name)
                    && MAP_DESCRIPTOR.equals(field.desc)) count++;
        }
        return count;
    }
}

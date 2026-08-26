package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Omits Mnemonic Sensors' redundant first entity-list copy while retaining its match snapshot. */
final class MnemonicSensorsEntityFilterPlan {
    static final String PLAN_ID = "mnemonic-sensors-0.5.1-entity-filter-v1";
    static final String TARGET_CLASS =
            "com/dp/mnemonicutils/sensors/MnemonicSensorsEveryFrameScript";
    static final String ORIGINAL_SHA256 =
            "4b483a40555456cb6b8873dbf14735eaaae6607c947fbcf7d91ba470197dc9a6";
    static final String METHOD = "markKnownEntities";
    static final String DESCRIPTOR = "(Lcom/fs/starfarer/api/campaign/LocationAPI;)V";

    private static final String COLLECTIONS = "kotlin/collections/CollectionsKt";
    private static final String FILTER_NOT_NULL = "filterNotNull";
    private static final String FILTER_DESCRIPTOR = "(Ljava/lang/Iterable;)Ljava/util/List;";
    private static final String ENTITY = "com/fs/starfarer/api/campaign/SectorEntityToken";
    private static final String COLLECTION = "java/util/Collection";
    private static final String ADD_DESCRIPTOR = "(Ljava/lang/Object;)Z";
    private static final AtomicLong INSTALLED_TARGETS = new AtomicLong();

    private MnemonicSensorsEntityFilterPlan() {
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
        MethodNode method = unique(owner, METHOD, DESCRIPTOR);
        if (method == null || calls(owner, FILTER_NOT_NULL, FILTER_DESCRIPTOR) != 1) {
            return null;
        }
        MethodInsnNode filter = uniqueCall(method, COLLECTIONS, FILTER_NOT_NULL, FILTER_DESCRIPTOR);
        MethodInsnNode add = uniqueCall(method, COLLECTION, "add", ADD_DESCRIPTOR);
        if (filter == null || add == null) return null;

        TypeInsnNode predicateCast = firstEntityCastBetween(filter, add);
        AbstractInsnNode elementLoad = previousCode(predicateCast);
        AbstractInsnNode addedElementLoad = previousCode(add);
        AbstractInsnNode destinationLoad = previousCode(addedElementLoad);
        if (!(elementLoad instanceof VarInsnNode predicateElement)
                || predicateElement.getOpcode() != Opcodes.ALOAD
                || !(addedElementLoad instanceof VarInsnNode addedElement)
                || addedElement.getOpcode() != Opcodes.ALOAD
                || predicateElement.var != addedElement.var
                || !(destinationLoad instanceof VarInsnNode destination)
                || destination.getOpcode() != Opcodes.ALOAD) {
            return null;
        }

        JumpInsnNode predicateFalse = uniquePredicateFalse(predicateCast, destinationLoad);
        if (predicateFalse == null || !labelPrecedes(predicateFalse.label, predicateCast)) {
            return null;
        }

        // The source list is already an Iterable. Leaving it on the stack feeds the existing
        // second filter directly; the explicit guard gives that filter the same non-null input
        // sequence that Kotlin's discarded filterNotNull list supplied.
        method.instructions.remove(filter);
        InsnList nullGuard = new InsnList();
        nullGuard.add(new VarInsnNode(Opcodes.ALOAD, predicateElement.var));
        nullGuard.add(new JumpInsnNode(Opcodes.IFNULL, predicateFalse.label));
        method.instructions.insertBefore(elementLoad, nullGuard);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        INSTALLED_TARGETS.incrementAndGet();
        return writer.toByteArray();
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installedTargets", INSTALLED_TARGETS.get());
        values.put("strategy", "iterate-source-with-null-guard-then-retain-match-snapshot");
        return values;
    }

    static void reset() {
        INSTALLED_TARGETS.set(0L);
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

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String descriptor) {
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                if (result != null) return null;
                result = call;
            }
        }
        return result;
    }

    private static int calls(ClassNode owner, String name, String descriptor) {
        int count = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && COLLECTIONS.equals(call.owner)
                        && name.equals(call.name)
                        && descriptor.equals(call.desc)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static TypeInsnNode firstEntityCastBetween(
            AbstractInsnNode start, AbstractInsnNode end) {
        for (AbstractInsnNode instruction = start.getNext();
                instruction != null && instruction != end;
                instruction = instruction.getNext()) {
            if (instruction instanceof TypeInsnNode cast
                    && cast.getOpcode() == Opcodes.CHECKCAST
                    && ENTITY.equals(cast.desc)) {
                return cast;
            }
        }
        return null;
    }

    private static JumpInsnNode uniquePredicateFalse(
            AbstractInsnNode start, AbstractInsnNode end) {
        JumpInsnNode result = null;
        for (AbstractInsnNode instruction = start.getNext();
                instruction != null && instruction != end;
                instruction = instruction.getNext()) {
            if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ) {
                if (result != null) return null;
                result = jump;
            }
        }
        return result;
    }

    private static boolean labelPrecedes(LabelNode label, AbstractInsnNode instruction) {
        for (AbstractInsnNode current = label; current != null; current = current.getNext()) {
            if (current == instruction) return true;
        }
        return false;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }
}

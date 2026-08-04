package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Replaces MagicLib's per-frame linear scan of already-notified paintjob IDs. */
final class MagicLibPaintjobNotificationPlan {
    static final String TARGET_CLASS = "org/magiclib/paintjobs/MagicPaintjobManager";
    static final String ORIGINAL_SHA256 =
            "841f945d675920e0fad9ccf13c7fa3144b6437489b6ba11e121a3267e6b8993c";
    static final String ADVANCE_METHOD = "advance";
    static final String ADVANCE_DESCRIPTOR = "(F)V";

    private static final String FIELD = "completedPaintjobIdsThatUserHasBeenNotifiedFor";
    private static final String LIST_DESCRIPTOR = "Ljava/util/List;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/MagicLibPaintjobNotificationRuntime";

    private MagicLibPaintjobNotificationPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(ADVANCE_METHOD, ADVANCE_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        Review review = review(owner);
        if (review == null) {
            return null;
        }

        review.contains.setOpcode(Opcodes.INVOKESTATIC);
        review.contains.owner = RUNTIME;
        review.contains.name = "contains";
        review.contains.desc = "(Ljava/util/List;Ljava/lang/Object;)Z";
        review.contains.itf = false;
        for (MethodInsnNode clear : review.clears) {
            containing(owner, clear).instructions.insert(clear, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RUNTIME, "mutated", "()V", false));
        }
        for (MethodInsnNode add : review.adds) {
            containing(owner, add).instructions.insert(add, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RUNTIME, "mutated", "(Z)Z", false));
        }

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        MagicLibPaintjobNotificationRuntime.installed();
        return writer.toByteArray();
    }

    private static Review review(ClassNode owner) {
        FieldNode field = owner.fields.stream()
                .filter(candidate -> FIELD.equals(candidate.name)
                        && LIST_DESCRIPTOR.equals(candidate.desc)
                        && (candidate.access & Opcodes.ACC_STATIC) != 0
                        && (candidate.access & Opcodes.ACC_FINAL) != 0)
                .findFirst().orElse(null);
        if (field == null) {
            return null;
        }

        MethodInsnNode contains = null;
        java.util.List<MethodInsnNode> clears = new java.util.ArrayList<>();
        java.util.List<MethodInsnNode> adds = new java.util.ArrayList<>();
        int fieldReads = 0;
        int fieldWrites = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode access
                        && TARGET_CLASS.equals(access.owner)
                        && FIELD.equals(access.name)
                        && LIST_DESCRIPTOR.equals(access.desc)) {
                    if (access.getOpcode() == Opcodes.GETSTATIC) {
                        fieldReads++;
                    } else if (access.getOpcode() == Opcodes.PUTSTATIC) {
                        fieldWrites++;
                    } else {
                        return null;
                    }
                }
                if (!(instruction instanceof MethodInsnNode call)
                        || !"java/util/List".equals(call.owner)
                        || !referencesField(call)) {
                    continue;
                }
                if ("contains".equals(call.name) && "(Ljava/lang/Object;)Z".equals(call.desc)
                        && ADVANCE_METHOD.equals(method.name)
                        && ADVANCE_DESCRIPTOR.equals(method.desc)) {
                    if (contains != null) return null;
                    contains = call;
                } else if ("clear".equals(call.name) && "()V".equals(call.desc)) {
                    clears.add(call);
                } else if ("add".equals(call.name) && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                    adds.add(call);
                } else {
                    return null;
                }
            }
        }
        return contains != null && clears.size() == 1 && adds.size() == 2
                && fieldReads == 4 && fieldWrites == 1
                ? new Review(contains, clears, adds)
                : null;
    }

    /** The reviewed field load is at most two value-producing instructions before the call. */
    private static boolean referencesField(MethodInsnNode call) {
        AbstractInsnNode current = previousCode(call);
        for (int i = 0; i < 3 && current != null; i++, current = previousCode(current)) {
            if (current instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && TARGET_CLASS.equals(field.owner)
                    && FIELD.equals(field.name)
                    && LIST_DESCRIPTOR.equals(field.desc)) {
                return true;
            }
        }
        return false;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }

    private static MethodNode containing(ClassNode owner, AbstractInsnNode instruction) {
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode candidate : method.instructions) {
                if (candidate == instruction) return method;
            }
        }
        throw new IllegalStateException("reviewed instruction lost its owner");
    }

    private record Review(
            MethodInsnNode contains,
            java.util.List<MethodInsnNode> clears,
            java.util.List<MethodInsnNode> adds) {
    }
}

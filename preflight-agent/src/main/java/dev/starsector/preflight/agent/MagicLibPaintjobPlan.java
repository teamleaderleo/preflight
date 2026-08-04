package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Uses MagicLib's existing unlocked-id set instead of copying it into a list for every query. */
final class MagicLibPaintjobPlan {
    static final String TARGET_CLASS = "org/magiclib/paintjobs/MagicPaintjobManagerKt";
    static final String ORIGINAL_SHA256 =
            "419d8f9c5688c87273332900584b474fa2a9027156e025001375cdba65cc3b4e";
    static final String LOOKUP_METHOD = "isUnlocked";
    static final String SPEC = "org/magiclib/paintjobs/MagicPaintjobSpec";
    static final String LOOKUP_DESCRIPTOR = "(L" + SPEC + ";)Z";

    private static final String ORIGINAL = "preflight$original$isUnlocked";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/MagicLibPaintjobRuntime";

    private MagicLibPaintjobPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(LOOKUP_METHOD, LOOKUP_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (hasMethod(owner, ORIGINAL, LOOKUP_DESCRIPTOR)) {
            return null;
        }
        MethodNode lookup = unique(owner, LOOKUP_METHOD, LOOKUP_DESCRIPTOR);
        if (!reviewedBody(lookup)) {
            return null;
        }
        int access = lookup.access;
        lookup.name = ORIGINAL;
        owner.methods.add(wrapper(access));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        MagicLibPaintjobRuntime.installed();
        return writer.toByteArray();
    }

    private static MethodNode wrapper(int access) {
        MethodNode method = new MethodNode(
                Opcodes.ASM9, access, LOOKUP_METHOD, LOOKUP_DESCRIPTOR, null, null);
        LabelNode fallback = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "lookup", "(Ljava/lang/Object;)I", false));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.IFLT, fallback));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(fallback);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, TARGET_CLASS, ORIGINAL, LOOKUP_DESCRIPTOR, false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        return method;
    }

    /** Pins the allocation-plus-linear-scan body observed in MagicLib 1.5.6. */
    private static boolean reviewedBody(MethodNode method) {
        if (method == null || (method.access & Opcodes.ACC_STATIC) == 0) {
            return false;
        }
        int copiedListGetter = 0;
        int idGetter = 0;
        int listContains = 0;
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.IRETURN) {
                returns++;
            }
            if (instruction instanceof MethodInsnNode call) {
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && "org/magiclib/paintjobs/MagicPaintjobManager".equals(call.owner)
                        && "getUnlockedPaintjobIds".equals(call.name)
                        && "()Ljava/util/List;".equals(call.desc)) {
                    copiedListGetter++;
                } else if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && SPEC.equals(call.owner)
                        && "getId".equals(call.name)
                        && "()Ljava/lang/String;".equals(call.desc)) {
                    idGetter++;
                } else if (call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/List".equals(call.owner)
                        && "contains".equals(call.name)
                        && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                    listContains++;
                }
            }
        }
        return copiedListGetter == 1 && idGetter == 1 && listContains == 1 && returns == 1;
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) {
                    return null;
                }
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

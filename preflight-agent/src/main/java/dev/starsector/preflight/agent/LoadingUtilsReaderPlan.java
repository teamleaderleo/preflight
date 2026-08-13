package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Replaces the reviewed shared 1MiB-buffer/StringBuffer/regex text reader. */
final class LoadingUtilsReaderPlan {
    static final String PLAN_ID = "vanilla-loading-utils-utf8-reader-v1";
    static final String TARGET_CLASS = "com/fs/starfarer/loading/LoadingUtils";
    static final String METHOD = "super";
    static final String DESCRIPTOR = "(Ljava/io/InputStream;)Ljava/lang/String;";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/LoadingUtilsReaderRuntime";

    private LoadingUtilsReaderPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!apply(signature, owner)) {
            return null;
        }
        return write(owner);
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(METHOD, DESCRIPTOR)) {
            return false;
        }
        MethodNode target = null;
        for (MethodNode method : owner.methods) {
            if (METHOD.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                if (target != null) return false;
                target = method;
            }
        }
        if (target == null || (target.access & Opcodes.ACC_STATIC) == 0) return false;
        for (AbstractInsnNode instruction : target.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner) && "readUtf8".equals(call.name)) {
                return false;
            }
        }

        target.instructions.clear();
        target.tryCatchBlocks.clear();
        target.localVariables = null;
        target.visibleLocalVariableAnnotations = null;
        target.invisibleLocalVariableAnnotations = null;
        target.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        target.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "readUtf8",
                DESCRIPTOR, false));
        target.instructions.add(new InsnNode(Opcodes.ARETURN));

        return true;
    }

    static byte[] write(ClassNode owner) {
        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }
}

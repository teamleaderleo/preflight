package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Makes the resource resolver's one-shot source hint thread-local.
 *
 * <p>Vanilla stores this hint on the singleton resolver. Its synchronized setter and synchronized
 * resolver are individually safe, but the two calls are a transaction and the monitor is released
 * between them. A different loading thread can therefore consume the first thread's hint and search
 * only the wrong mod. The resulting error says an existing resource is missing.
 *
 * <p>The rewrite preserves the intended one-shot behavior exactly within a thread: the setter puts
 * the hint in a {@link ThreadLocal}, and the resolver takes and clears it at the same point where
 * vanilla reads and clears the shared field. An exact class and archive gate sits outside this plan;
 * the instruction-shape checks here are a second fail-closed boundary.
 */
final class SourceHintIsolationPlan {
    static final String TARGET_CLASS = "com/fs/util/C";
    static final String ORIGINAL_SHA256 =
            "ee81369a75dfa518ddbbf1bfb83c96845effc2cf9189179fc08a17863837d0fd";
    static final String SET_METHOD = "return";
    static final String SET_DESCRIPTOR = "(Ljava/lang/String;)V";
    static final String RESOLVE_METHOD = "Object";
    static final String RESOLVE_DESCRIPTOR = "(Ljava/lang/String;Z)Ljava/io/InputStream;";

    private static final String STRING_DESCRIPTOR = "Ljava/lang/String;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/SourceHintIsolationRuntime";

    private SourceHintIsolationPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(SET_METHOD, SET_DESCRIPTOR)
                || !signature.hasMethod(RESOLVE_METHOD, RESOLVE_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode setter = unique(owner, SET_METHOD, SET_DESCRIPTOR);
        MethodNode resolver = unique(owner, RESOLVE_METHOD, RESOLVE_DESCRIPTOR);
        if (setter == null || resolver == null || callsRuntime(setter) || callsRuntime(resolver)) {
            return null;
        }

        List<AbstractInsnNode> set = meaningful(setter);
        if (set.size() != 4
                || !(set.get(0) instanceof VarInsnNode setThis)
                || setThis.getOpcode() != Opcodes.ALOAD
                || setThis.var != 0
                || !(set.get(1) instanceof VarInsnNode setValue)
                || setValue.getOpcode() != Opcodes.ALOAD
                || setValue.var != 1
                || !(set.get(2) instanceof FieldInsnNode write)
                || write.getOpcode() != Opcodes.PUTFIELD
                || !TARGET_CLASS.equals(write.owner)
                || !STRING_DESCRIPTOR.equals(write.desc)
                || set.get(3).getOpcode() != Opcodes.RETURN) {
            return null;
        }

        List<AbstractInsnNode> resolve = meaningful(resolver);
        if (resolve.size() < 7
                || !(resolve.get(0) instanceof VarInsnNode resolveThis)
                || resolveThis.getOpcode() != Opcodes.ALOAD
                || resolveThis.var != 0
                || !(resolve.get(1) instanceof FieldInsnNode read)
                || read.getOpcode() != Opcodes.GETFIELD
                || !TARGET_CLASS.equals(read.owner)
                || !write.name.equals(read.name)
                || !STRING_DESCRIPTOR.equals(read.desc)
                || !(resolve.get(2) instanceof VarInsnNode storeHint)
                || storeHint.getOpcode() != Opcodes.ASTORE
                || storeHint.var != 3
                || !(resolve.get(3) instanceof VarInsnNode clearThis)
                || clearThis.getOpcode() != Opcodes.ALOAD
                || clearThis.var != 0
                || resolve.get(4).getOpcode() != Opcodes.ACONST_NULL
                || !(resolve.get(5) instanceof FieldInsnNode clear)
                || clear.getOpcode() != Opcodes.PUTFIELD
                || !TARGET_CLASS.equals(clear.owner)
                || !write.name.equals(clear.name)
                || !STRING_DESCRIPTOR.equals(clear.desc)) {
            return null;
        }

        // Setter: ALOAD 0, ALOAD 1, PUTFIELD -> ALOAD 1, INVOKESTATIC set.
        setter.instructions.remove(set.get(0));
        setter.instructions.set(write, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "set", SET_DESCRIPTOR, false));

        // Resolver: ALOAD 0, GETFIELD, ASTORE 3, ALOAD 0, ACONST_NULL, PUTFIELD
        //        -> INVOKESTATIC take, ASTORE 3.
        resolver.instructions.remove(resolve.get(0));
        resolver.instructions.set(read, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "take", "()Ljava/lang/String;", false));
        resolver.instructions.remove(resolve.get(3));
        resolver.instructions.remove(resolve.get(4));
        resolver.instructions.remove(resolve.get(5));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        SourceHintIsolationRuntime.installed();
        return writer.toByteArray();
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

    private static List<AbstractInsnNode> meaningful(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) {
                result.add(instruction);
            }
        }
        return result;
    }

    private static boolean callsRuntime(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) {
                return true;
            }
        }
        return false;
    }
}

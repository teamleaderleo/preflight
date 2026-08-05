package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Gives prepared-audio lookup the filename that the game used to obtain the encoded bytes. */
final class PreparedAudioPathPlan {
    static final String TARGET_CLASS = "sound/ooOO";
    static final String LOAD_METHOD = "o00000";
    static final String LOAD_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/io/InputStream;)Lsound/D;";

    private static final String DECODER = "sound/J";
    private static final String DECODE_METHOD = "o00000";
    private static final String DECODE_DESCRIPTOR = "(Ljava/io/InputStream;)Lsound/F;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/PreparedAudioRuntime";
    private static final String RUNTIME_DESCRIPTOR =
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/io/InputStream;"
                    + "Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;";

    private PreparedAudioPathPlan() {
    }

    /** Called only after the registry's exact original-class gate and resource-fallback rewrite. */
    static byte[] transform(byte[] verifiedBytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(verifiedBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!TARGET_CLASS.equals(owner.name) || (owner.version & 0xFFFF) < Opcodes.V1_7) {
            return null;
        }
        MethodNode loader = unique(owner, LOAD_METHOD, LOAD_DESCRIPTOR);
        if (loader == null || calls(loader, RUNTIME, "decodePath") != 0) {
            return null;
        }
        MethodInsnNode decode = null;
        for (AbstractInsnNode instruction : loader.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && DECODER.equals(call.owner)
                    && DECODE_METHOD.equals(call.name)
                    && DECODE_DESCRIPTOR.equals(call.desc)) {
                if (decode != null) return null;
                decode = call;
            }
        }
        if (decode == null) return null;
        AbstractInsnNode inputLoad = previousMeaningful(decode);
        if (!(inputLoad instanceof VarInsnNode input)
                || input.getOpcode() != Opcodes.ALOAD
                || input.var != 2) {
            return null;
        }

        // Existing stack: decoder. Insert the filename before the existing input-stream load,
        // then append the original decoder entry handle. A miss therefore reaches the ordinary
        // prepared-audio byte hash and ultimately the untouched vanilla decoder.
        loader.instructions.insertBefore(inputLoad, new VarInsnNode(Opcodes.ALOAD, 1));
        loader.instructions.insertBefore(decode, new LdcInsnNode(new Handle(
                Opcodes.H_INVOKEVIRTUAL, DECODER, DECODE_METHOD, DECODE_DESCRIPTOR, false)));
        MethodInsnNode replacement = new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "decodePath", RUNTIME_DESCRIPTOR, false);
        loader.instructions.set(decode, replacement);
        loader.instructions.insert(replacement, new TypeInsnNode(Opcodes.CHECKCAST, "sound/F"));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }
}

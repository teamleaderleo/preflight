package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Puts {@link PreparedAudioRuntime} in front of the game's Ogg Vorbis decode.
 *
 * <p>{@code sound.J.o00000(InputStream)} is the whole of the decode: the sound loader constructs a
 * fresh one per file, hands it the encoded bytes, and gets back a {@code sound.F} holding channel
 * count, a direct PCM buffer and a sample rate. Everything that touches OpenAL or shared state
 * happens in the caller, after this returns, so replacing it changes nothing about how the game
 * uploads or registers a sound -- only about how long it takes to have something to upload.
 *
 * <p>The target is the whole method rather than one call inside it, so the original is renamed
 * rather than deleted and a fresh method takes its name, handing the runtime a {@code MethodHandle}
 * to the renamed original. Vanilla stays exactly as it was compiled, one rename away, and the
 * runtime falls back to it on anything it cannot serve.
 *
 * <p>The rewrite declines rather than adapts if the class does not look like the reviewed one.
 */
final class PreparedAudioPlan {
    static final String TARGET_CLASS = "sound/J";
    /** {@code o00000}, the decode. Spelled by name so the target can require it before installing. */
    static final String DECODE_METHOD = "o00000";
    static final String DECODE_DESCRIPTOR = "(Ljava/io/InputStream;)Lsound/F;";
    static final String VANILLA_METHOD = "preflightVanillaDecodeOgg";

    private static final String RESULT_CLASS = "sound/F";
    private static final String RUNTIME = "dev/starsector/preflight/agent/PreparedAudioRuntime";
    private static final String RUNTIME_DESCRIPTOR = "(Ljava/lang/Object;Ljava/io/InputStream;"
            + "Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;";

    private PreparedAudioPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(DECODE_METHOD, DECODE_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        // A class file older than 51 cannot carry a MethodHandle constant, and raising its version
        // to make room would change how it is verified. Decline instead.
        if ((owner.version & 0xFFFF) < Opcodes.V1_7) {
            return null;
        }

        MethodNode vanilla = null;
        for (MethodNode method : owner.methods) {
            if (VANILLA_METHOD.equals(method.name)) {
                // Already rewritten.
                return null;
            }
            if (DECODE_METHOD.equals(method.name) && DECODE_DESCRIPTOR.equals(method.desc)) {
                if (vanilla != null) {
                    return null;
                }
                vanilla = method;
            }
        }
        if (vanilla == null || (vanilla.access & Opcodes.ACC_STATIC) != 0) {
            return null;
        }

        vanilla.name = VANILLA_METHOD;
        vanilla.access = (vanilla.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PUBLIC;

        MethodNode entry = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, DECODE_METHOD,
                DECODE_DESCRIPTOR, null, new String[] {"java/io/IOException"});
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        entry.instructions.add(new LdcInsnNode(new Handle(
                Opcodes.H_INVOKEVIRTUAL, TARGET_CLASS, VANILLA_METHOD, DECODE_DESCRIPTOR, false)));
        entry.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "decode", RUNTIME_DESCRIPTOR, false));
        entry.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, RESULT_CLASS));
        entry.instructions.add(new InsnNode(Opcodes.ARETURN));
        entry.maxStack = 3;
        entry.maxLocals = 2;
        owner.methods.add(entry);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }
}

package dev.starsector.preflight.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Keeps Linux's PCM buffer reachable until LWJGL's address-only native upload returns. */
final class LinuxAudioBufferFencePlan {
    static final String TARGET = "sound/Object";
    static final String SHA256 = "3032cfe9e6aa8b1e66fa0fe3c7c6794a0b728bd2dfdccdf30d944ce0c259688e";
    static final String METHOD = "o00000";
    static final String DESCRIPTOR = "(Ljava/lang/String;Ljava/io/InputStream;)Lsound/O0OO;";
    static final String UPLOAD = "(IILjava/nio/ByteBuffer;I)V";
    static final String HELPER = "preflight$uploadPcm";

    private LinuxAudioBufferFencePlan() { }

    static byte[] transform(ClassSignature signature, byte[] bytes) {
        if (!TARGET.equals(signature.internalName()) || !SHA256.equals(signature.sha256())
                || !SHA256.equals(dev.starsector.preflight.core.Hashes.sha256(bytes))) return null;
        ClassNode owner = new ClassNode();
        new ClassReader(bytes).accept(owner, 0);
        if (owner.methods.stream().anyMatch(m -> m.name.equals(HELPER))) return null;
        int count = 0;
        for (MethodNode method : owner.methods) {
            if (!method.name.equals(METHOD) || !method.desc.equals(DESCRIPTOR)) continue;
            for (AbstractInsnNode node : method.instructions) {
                if (node instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("org/lwjgl/openal/AL10")
                        && call.name.equals("alBufferData") && call.desc.equals(UPLOAD)) {
                    call.owner = TARGET;
                    call.name = HELPER;
                    count++;
                }
            }
        }
        if (count != 1) return null;
        MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                HELPER, UPLOAD, null, null);
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        helper.instructions.add(start);
        for (int i = 0; i < 4; i++) helper.instructions.add(new VarInsnNode(i == 2 ? Opcodes.ALOAD : Opcodes.ILOAD, i));
        helper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/lwjgl/openal/AL10", "alBufferData", UPLOAD, false));
        helper.instructions.add(end);
        fence(helper);
        helper.instructions.add(new InsnNode(Opcodes.RETURN));
        helper.instructions.add(handler);
        helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        fence(helper);
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        helper.instructions.add(new InsnNode(Opcodes.ATHROW));
        helper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        owner.methods.add(helper);
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static void fence(MethodNode helper) {
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        helper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/ref/Reference",
                "reachabilityFence", "(Ljava/lang/Object;)V", false));
    }
}

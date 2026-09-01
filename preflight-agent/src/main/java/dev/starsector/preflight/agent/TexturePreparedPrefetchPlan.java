package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Lets Starsector's exact image-prefetch worker build prepared carriers off the loading thread. */
final class TexturePreparedPrefetchPlan {
    static final String PLAN_ID = "texture-prepared-prefetch-v1";
    static final String WINDOWS_PROBE_PROPERTY =
            "preflight.texture.windowsPreparedPrefetchProbe";
    static final String TARGET_CLASS = TexturePrefetchBypassPlan.TARGET_CLASS;
    static final String DECODE_METHOD = "o00000";
    static final String DECODE_DESCRIPTOR =
            "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/TexturePreparedPixelRuntime";
    private static final String QUEUE_METHOD = "shouldQueuePreparedPrefetch";
    private static final String LOAD_METHOD = "prefetchLoad";

    private TexturePreparedPrefetchPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(DECODE_METHOD, DECODE_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, 0);
        MethodNode decode = uniqueMethod(owner, DECODE_METHOD, DECODE_DESCRIPTOR);
        MethodNode enqueue = preparedImageEnqueue(owner);
        if (decode == null || enqueue == null
                || containsRuntimeCall(decode) || containsRuntimeCall(enqueue)) {
            return null;
        }

        LabelNode enqueueOriginal = new LabelNode();
        InsnList queueGuard = new InsnList();
        queueGuard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        queueGuard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, QUEUE_METHOD, "(Ljava/lang/String;)Z", false));
        queueGuard.add(new JumpInsnNode(Opcodes.IFNE, enqueueOriginal));
        queueGuard.add(new InsnNode(Opcodes.RETURN));
        queueGuard.add(enqueueOriginal);
        queueGuard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        enqueue.instructions.insert(queueGuard);
        enqueue.maxStack = Math.max(enqueue.maxStack, 1);

        LabelNode decodeOriginal = new LabelNode();
        InsnList loadGuard = new InsnList();
        loadGuard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        loadGuard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, LOAD_METHOD, DECODE_DESCRIPTOR, false));
        loadGuard.add(new InsnNode(Opcodes.DUP));
        loadGuard.add(new JumpInsnNode(Opcodes.IFNULL, decodeOriginal));
        loadGuard.add(new InsnNode(Opcodes.ARETURN));
        loadGuard.add(decodeOriginal);
        loadGuard.add(new FrameNode(
                Opcodes.F_SAME1,
                0,
                null,
                1,
                new Object[] {"java/awt/image/BufferedImage"}));
        loadGuard.add(new InsnNode(Opcodes.POP));
        decode.instructions.insert(loadGuard);
        decode.maxStack = Math.max(decode.maxStack, 2);

        ClassWriter writer = new ClassWriter(0);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode preparedImageEnqueue(ClassNode owner) {
        String imageQueue = TexturePrefetchBypassPlan.imageQueueField(owner);
        return imageQueue == null ? null : TexturePrefetchBypassPlan.soleEnqueueOf(owner, imageQueue);
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (!name.equals(method.name)
                    || !descriptor.equals(method.desc)
                    || (method.access & Opcodes.ACC_STATIC) == 0
                    || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = method;
        }
        return found;
    }

    private static boolean containsRuntimeCall(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) {
                return true;
            }
        }
        return false;
    }
}

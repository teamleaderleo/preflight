package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Lets Starsector's exact image-prefetch worker build prepared carriers off the loading thread. */
final class TexturePreparedPrefetchPlan {
    static final String PLAN_ID = "texture-prepared-prefetch-v1";
    static final String WINDOWS_PROBE_PROPERTY =
            "preflight.texture.windowsPreparedPrefetchProbe";
    static final String WINDOWS_WORKERS_PROPERTY =
            "preflight.texture.windowsPreparedPrefetchWorkers";
    static final String TARGET_CLASS = TexturePrefetchBypassPlan.TARGET_CLASS;
    static final String DECODE_METHOD = "o00000";
    static final String DECODE_DESCRIPTOR =
            "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/TexturePreparedPixelRuntime";
    private static final String QUEUE_METHOD = "shouldQueuePreparedPrefetch";
    private static final String LOAD_METHOD = "prefetchLoad";
    private static final String POOL_RUNTIME =
            "dev/starsector/preflight/agent/TexturePreparedPrefetchPoolRuntime";
    private static final String POOL_START_DESCRIPTOR =
            "(Ljava/lang/Class;Ljava/util/List;Ljava/util/Map;Ljava/lang/Object;"
                    + "Ljava/util/List;Ljava/util/Map;Ljava/lang/Object;"
                    + "Ljava/lang/String;Ljava/lang/String;I)V";
    private static final String LIST_DESCRIPTOR = "Ljava/util/List;";
    private static final String MAP_DESCRIPTOR = "Ljava/util/Map;";
    private static final String IMAGE_DESCRIPTOR = "Ljava/awt/image/BufferedImage;";
    private static final String BYTE_ARRAY_DESCRIPTOR = "[B";
    static final String START_METHOD = "o00000";
    static final String STOP_METHOD = "Ò00000";

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

        int workers = Integer.getInteger(WINDOWS_WORKERS_PROPERTY, 1);
        if (workers > 1
                && !rewriteWorkerPool(owner, TexturePrefetchBypassPlan.imageQueueField(owner), workers)) {
            return null;
        }

        ClassWriter writer = new ClassWriter(0);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static boolean rewriteWorkerPool(ClassNode owner, String imageQueue, int workers) {
        if (workers > 8 || imageQueue == null) {
            return false;
        }
        MethodNode consumer = uniqueMethod(
                owner,
                TexturePrefetchBypassPlan.WINDOWS_CONSUMER_METHOD,
                TexturePrefetchBypassPlan.CONSUMER_DESCRIPTOR);
        MethodNode start = uniqueMethod(owner, START_METHOD, "()V");
        MethodNode stop = uniqueMethod(owner, STOP_METHOD, "()V");
        String imageResults = uniqueStaticRead(consumer, MAP_DESCRIPTOR);
        String imageMarker = uniqueStaticRead(consumer, IMAGE_DESCRIPTOR);
        String byteQueue = otherField(owner, LIST_DESCRIPTOR, imageQueue);
        String byteResults = otherField(owner, MAP_DESCRIPTOR, imageResults);
        String byteMarker = uniqueField(owner, BYTE_ARRAY_DESCRIPTOR);
        String byteDecoder = uniquePrivateStaticMethodName(owner, "(Ljava/lang/String;)[B");
        if (consumer == null || start == null || stop == null
                || imageResults == null || imageMarker == null
                || byteQueue == null || byteResults == null || byteMarker == null
                || byteDecoder == null) {
            return false;
        }

        clear(start);
        start.instructions.add(new LdcInsnNode(Type.getObjectType(owner.name)));
        addField(start.instructions, owner.name, imageQueue, LIST_DESCRIPTOR);
        addField(start.instructions, owner.name, imageResults, MAP_DESCRIPTOR);
        addField(start.instructions, owner.name, imageMarker, IMAGE_DESCRIPTOR);
        addField(start.instructions, owner.name, byteQueue, LIST_DESCRIPTOR);
        addField(start.instructions, owner.name, byteResults, MAP_DESCRIPTOR);
        addField(start.instructions, owner.name, byteMarker, BYTE_ARRAY_DESCRIPTOR);
        start.instructions.add(new LdcInsnNode(DECODE_METHOD));
        start.instructions.add(new LdcInsnNode(byteDecoder));
        start.instructions.add(new LdcInsnNode(workers));
        start.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, POOL_RUNTIME, "start", POOL_START_DESCRIPTOR, false));
        start.instructions.add(new InsnNode(Opcodes.RETURN));
        start.maxStack = 10;
        start.maxLocals = 0;

        clear(stop);
        stop.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, POOL_RUNTIME, "stop", "()V", false));
        addField(stop.instructions, owner.name, imageResults, MAP_DESCRIPTOR);
        stop.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Map", "clear", "()V", true));
        addField(stop.instructions, owner.name, byteResults, MAP_DESCRIPTOR);
        stop.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Map", "clear", "()V", true));
        stop.instructions.add(new InsnNode(Opcodes.RETURN));
        stop.maxStack = 1;
        stop.maxLocals = 0;
        return true;
    }

    private static void addField(InsnList instructions, String owner, String name, String descriptor) {
        instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, name, descriptor));
    }

    private static void clear(MethodNode method) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
    }

    private static String uniqueStaticRead(MethodNode method, String descriptor) {
        if (method == null) {
            return null;
        }
        String found = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETSTATIC
                    || !TARGET_CLASS.equals(field.owner)
                    || !descriptor.equals(field.desc)) {
                continue;
            }
            if (found != null && !found.equals(field.name)) {
                return null;
            }
            found = field.name;
        }
        return found;
    }

    private static String otherField(ClassNode owner, String descriptor, String excluded) {
        String found = null;
        for (FieldNode field : owner.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0
                    || !descriptor.equals(field.desc)
                    || field.name.equals(excluded)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = field.name;
        }
        return found;
    }

    private static String uniqueField(ClassNode owner, String descriptor) {
        return otherField(owner, descriptor, "");
    }

    private static String uniquePrivateStaticMethodName(ClassNode owner, String descriptor) {
        String found = null;
        for (MethodNode method : owner.methods) {
            if (!descriptor.equals(method.desc)
                    || (method.access & Opcodes.ACC_STATIC) == 0
                    || (method.access & Opcodes.ACC_PRIVATE) == 0
                    || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = method.name;
        }
        return found;
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

package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Keeps the game's own image prefetcher from queueing work the prepared cache can already serve.
 *
 * <p>Starsector enqueues every image resource onto one list and starts a single thread to decode
 * them. The consumer, called from {@code TextureLoader}, asks whether the path is queued and then
 * polls the result map, sleeping 10ms at a time until that one thread reaches it. On the reviewed
 * profile the loading thread spent 27 of the load's 96 seconds in that poll loop, while the decoded
 * pixels for those same images sat unread in Preflight's cache -- because the compatibility rewrite
 * splices its lookup in at the point where the prefetcher <em>misses</em>, which is on the far side
 * of the wait.
 *
 * <p>This rewrite drops the enqueue instead of racing it. A path the manifest can serve is never
 * queued, so the consumer finds nothing, returns immediately, and falls through to the cache lookup
 * that was already there. Nothing waits, the queue stays short enough that its O(n) membership scan
 * stops mattering, and the prefetch thread does not decode images no one will collect. Every path
 * the cache cannot serve is enqueued exactly as before, so the game's own path is untouched.
 */
final class TexturePrefetchBypassPlan {
    static final String PLAN_ID = "texture-prefetch-bypass-v1";
    static final String TARGET_CLASS = "com/fs/graphics/L";
    /** The prefetcher's consumer: it is what identifies which of the two queues holds images. */
    static final String CONSUMER_METHOD = "class";
    static final String WINDOWS_CONSUMER_METHOD = "Õ00000";
    static final String CONSUMER_DESCRIPTOR = "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;";
    static final String ENQUEUE_DESCRIPTOR = "(Ljava/lang/String;)V";
    private static final String LIST = "java/util/List";
    private static final String LIST_DESCRIPTOR = "Ljava/util/List;";
    private static final String RUNTIME = "dev/starsector/preflight/agent/TextureCompatibilityRuntime";
    private static final String RUNTIME_METHOD = "prefetchRedundant";
    private static final String RUNTIME_DESCRIPTOR = "(Ljava/lang/String;)Z";

    private TexturePrefetchBypassPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !hasConsumer(signature)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, 0);
        if (!TARGET_CLASS.equals(owner.name)) {
            return null;
        }

        // The class holds two structurally identical queues, one for images and one for raw bytes,
        // under obfuscated names that differ by a single diacritic. Reading the name would be a
        // transcription risk for no benefit; the image queue is instead defined as the one the
        // BufferedImage-returning consumer tests membership on.
        String imageQueue = imageQueueField(owner);
        if (imageQueue == null) {
            return null;
        }

        MethodNode enqueue = soleEnqueueOf(owner, imageQueue);
        if (enqueue == null || containsRuntimeCall(enqueue)) {
            return null;
        }

        LabelNode enqueueAsUsual = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, RUNTIME_METHOD, RUNTIME_DESCRIPTOR, false));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, enqueueAsUsual));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(enqueueAsUsual);
        guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        enqueue.instructions.insert(guard);
        enqueue.maxStack = Math.max(enqueue.maxStack, 1);

        ClassWriter writer = new ClassWriter(0);
        owner.accept(writer);
        return writer.toByteArray();
    }

    /** The static {@code List} field whose membership the decoded-image consumer tests first. */
    private static String imageQueueField(ClassNode owner) {
        for (MethodNode method : owner.methods) {
            if (!(CONSUMER_METHOD.equals(method.name)
                            || WINDOWS_CONSUMER_METHOD.equals(method.name))
                    || !CONSUMER_DESCRIPTOR.equals(method.desc)) {
                continue;
            }
            AbstractInsnNode first = firstOpcode(method.instructions.getFirst());
            AbstractInsnNode loaded = nextOpcode(first);
            AbstractInsnNode tested = nextOpcode(loaded);
            if (!(first instanceof FieldInsnNode field)
                    || first.getOpcode() != Opcodes.GETSTATIC
                    || !TARGET_CLASS.equals(field.owner)
                    || !LIST_DESCRIPTOR.equals(field.desc)
                    || !(loaded instanceof VarInsnNode argument)
                    || loaded.getOpcode() != Opcodes.ALOAD
                    || argument.var != 0
                    || !(tested instanceof MethodInsnNode call)
                    || tested.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !LIST.equals(call.owner)
                    || !"contains".equals(call.name)) {
                return null;
            }
            return field.name;
        }
        return null;
    }

    private static boolean hasConsumer(ClassSignature signature) {
        return signature.hasMethod(CONSUMER_METHOD, CONSUMER_DESCRIPTOR)
                || signature.hasMethod(WINDOWS_CONSUMER_METHOD, CONSUMER_DESCRIPTOR);
    }

    /**
     * The one {@code static void(String)} method whose entire body appends to the image queue.
     *
     * <p>Requiring the whole body to match, and requiring exactly one match, is what makes dropping
     * the call safe: a method that also did something else would lose that something else.
     */
    private static MethodNode soleEnqueueOf(ClassNode owner, String queueField) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (!ENQUEUE_DESCRIPTOR.equals(method.desc)
                    || (method.access & Opcodes.ACC_STATIC) == 0
                    || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || !appendsOnlyTo(method, queueField)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = method;
        }
        return found;
    }

    private static boolean appendsOnlyTo(MethodNode method, String queueField) {
        AbstractInsnNode queue = firstOpcode(method.instructions.getFirst());
        AbstractInsnNode argument = nextOpcode(queue);
        AbstractInsnNode append = nextOpcode(argument);
        AbstractInsnNode discard = nextOpcode(append);
        AbstractInsnNode end = nextOpcode(discard);
        return queue instanceof FieldInsnNode field
                && queue.getOpcode() == Opcodes.GETSTATIC
                && TARGET_CLASS.equals(field.owner)
                && queueField.equals(field.name)
                && LIST_DESCRIPTOR.equals(field.desc)
                && argument instanceof VarInsnNode loaded
                && argument.getOpcode() == Opcodes.ALOAD
                && loaded.var == 0
                && append instanceof MethodInsnNode call
                && append.getOpcode() == Opcodes.INVOKEINTERFACE
                && LIST.equals(call.owner)
                && "add".equals(call.name)
                && "(Ljava/lang/Object;)Z".equals(call.desc)
                && discard != null
                && discard.getOpcode() == Opcodes.POP
                && end != null
                && end.getOpcode() == Opcodes.RETURN
                && nextOpcode(end) == null;
    }

    private static boolean containsRuntimeCall(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner)
                    && RUNTIME_METHOD.equals(call.name)
                    && RUNTIME_DESCRIPTOR.equals(call.desc)) {
                return true;
            }
        }
        return false;
    }

    private static AbstractInsnNode firstOpcode(AbstractInsnNode instruction) {
        while (instruction instanceof LabelNode
                || instruction instanceof FrameNode
                || instruction instanceof LineNumberNode) {
            instruction = instruction.getNext();
        }
        return instruction;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        return instruction == null ? null : firstOpcode(instruction.getNext());
    }
}

package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class TexturePrefetchBypassPlanTest {
    /** The two queues differ only by which one the BufferedImage consumer tests. */
    private static final String IMAGE_QUEUE = "imageQueue";
    private static final String BYTE_QUEUE = "byteQueue";
    private static final String IMAGE_ENQUEUE = "enqueueImage";
    private static final String BYTE_ENQUEUE = "enqueueBytes";

    @Test
    void guardsTheImageEnqueueWithTheCacheProbe() throws Exception {
        byte[] original = syntheticPrefetcher();

        byte[] transformed =
                TexturePrefetchBypassPlan.transform(ClassSignature.parse(original), original);

        assertNotNull(transformed);
        MethodNode enqueue = method(parse(transformed), IMAGE_ENQUEUE);
        List<MethodInsnNode> calls = calls(enqueue);
        assertEquals(2, calls.size());
        assertEquals("dev/starsector/preflight/agent/TextureCompatibilityRuntime", calls.get(0).owner);
        assertEquals("prefetchRedundant", calls.get(0).name);
        // The original append survives, so a path the cache cannot serve is queued as before.
        assertEquals("add", calls.get(1).name);
    }

    @Test
    void leavesTheByteQueueAlone() throws Exception {
        // The raw-byte queue is a different resource kind the cache holds nothing for. Rewriting it
        // by name would be a one-diacritic mistake; the plan identifies its target by shape.
        byte[] original = syntheticPrefetcher();

        byte[] transformed =
                TexturePrefetchBypassPlan.transform(ClassSignature.parse(original), original);

        List<MethodInsnNode> calls = calls(method(parse(transformed), BYTE_ENQUEUE));
        assertEquals(1, calls.size());
        assertEquals("add", calls.get(0).name);
    }

    @Test
    void skipsTheAppendWhenTheCacheCanServeAndAppendsWhenItCannot() throws Exception {
        byte[] transformed = transformedPrefetcher();
        List<String> queued = new ArrayList<>();

        RecordingProbe.answer = true;
        invokeEnqueue(transformed, queued, "graphics/ships/cached.png");
        assertTrue(queued.isEmpty(), "a servable path must never reach the prefetch queue");

        RecordingProbe.answer = false;
        invokeEnqueue(transformed, queued, "graphics/ships/unknown.png");
        assertEquals(List.of("graphics/ships/unknown.png"), queued);
    }

    @Test
    void declinesAnEnqueueThatDoesAnythingElse() throws Exception {
        // Dropping a call that also had a side effect would lose that side effect.
        byte[] original = syntheticPrefetcher(true, false);

        assertNull(TexturePrefetchBypassPlan.transform(ClassSignature.parse(original), original));
    }

    @Test
    void declinesWhenTheConsumerDoesNotTestQueueMembershipFirst() throws Exception {
        byte[] original = syntheticPrefetcher(false, true);

        assertNull(TexturePrefetchBypassPlan.transform(ClassSignature.parse(original), original));
    }

    @Test
    void refusesToGuardTheSameMethodTwice() throws Exception {
        byte[] transformed = transformedPrefetcher();

        assertNull(TexturePrefetchBypassPlan.transform(ClassSignature.parse(transformed), transformed));
    }

    @Test
    void preparedPixelModeKeepsEveryPathOnTheGamesOwnQueue() {
        // Regression, 2026-08-01: the bypass shipped without this and crashed the load at 23.6s in
        // com.fs.graphics.oO0O, a greyscale-to-alpha mask converter, with ArrayIndexOutOfBounds.
        // Prepared-pixel mode answers with a 1x1 carrier that reports the texture's real dimensions,
        // which only the rewritten conversion can read. Taking paths off the game's queue hands that
        // carrier to consumers that walk the raster, so in this mode it must claim nothing.
        TextureCompatibilityRuntime.beginSession();
        try {
            TexturePreparedPixelRuntime.select(TextureAdapterMode.PREPARED_PIXELS);
            assertTrue(TexturePreparedPixelRuntime.servesUnreadableCarriers());
            assertFalse(TextureCompatibilityRuntime.prefetchRedundant("graphics/ships/anything.png"));
        } finally {
            TexturePreparedPixelRuntime.select(TextureAdapterMode.COMPATIBILITY);
        }
        assertFalse(TexturePreparedPixelRuntime.servesUnreadableCarriers());
    }

    @Test
    void anUnreadyCacheKeepsEveryPathOnTheGamesOwnQueue() {
        // Fail-open: with no manifest configured the predicate must never claim a path, or the
        // bypass would drop prefetches it has nothing to replace them with.
        TextureCompatibilityRuntime.beginSession();
        assertFalse(TextureCompatibilityRuntime.prefetchRedundant("graphics/ships/anything.png"));
        assertFalse(TextureCompatibilityRuntime.prefetchRedundant(null));
    }

    private static byte[] transformedPrefetcher() throws Exception {
        byte[] original = syntheticPrefetcher();
        byte[] transformed =
                TexturePrefetchBypassPlan.transform(ClassSignature.parse(original), original);
        assertNotNull(transformed);
        return transformed;
    }

    /** Loads the rewritten class with the runtime call redirected to a probe we control. */
    private static void invokeEnqueue(byte[] transformed, List<String> queued, String path)
            throws Exception {
        byte[] redirected = redirectRuntimeCallToProbe(transformed);
        RecordingProbe.queued = queued;
        Class<?> loaded = new ByteArrayLoader(redirected)
                .loadClass(TexturePrefetchBypassPlan.TARGET_CLASS.replace('/', '.'));
        loaded.getMethod(IMAGE_ENQUEUE, String.class).invoke(null, path);
        List<?> queue = (List<?>) loaded.getField(IMAGE_QUEUE).get(null);
        queued.clear();
        queue.forEach(entry -> queued.add((String) entry));
    }

    private static byte[] redirectRuntimeCallToProbe(byte[] transformed) {
        ClassNode node = parse(transformed);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && "prefetchRedundant".equals(call.name)) {
                    call.owner = RecordingProbe.class.getName().replace('.', '/');
                }
            }
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Stands in for the cache probe so the rewrite can be exercised without a manifest. */
    public static final class RecordingProbe {
        static boolean answer;
        static List<String> queued = new ArrayList<>();

        public static boolean prefetchRedundant(String logicalPath) {
            return answer;
        }
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private final byte[] bytes;

        private ByteArrayLoader(byte[] bytes) {
            super(TexturePrefetchBypassPlanTest.class.getClassLoader());
            this.bytes = bytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals(TexturePrefetchBypassPlan.TARGET_CLASS.replace('/', '.'))) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream()
                .filter(candidate -> name.equals(candidate.name)
                        && TexturePrefetchBypassPlan.ENQUEUE_DESCRIPTOR.equals(candidate.desc))
                .findFirst()
                .orElse(null);
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        assertNotNull(method);
        List<MethodInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call) {
                found.add(call);
            }
        }
        return found;
    }

    private static byte[] syntheticPrefetcher() {
        return syntheticPrefetcher(true, true);
    }

    private static byte[] syntheticPrefetcher(boolean consumerTestsQueue, boolean enqueueOnlyAppends) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String owner = TexturePrefetchBypassPlan.TARGET_CLASS;
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        for (String field : List.of(IMAGE_QUEUE, BYTE_QUEUE)) {
            writer.visitField(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "Ljava/util/List;", null, null)
                    .visitEnd();
        }

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        for (String field : List.of(IMAGE_QUEUE, BYTE_QUEUE)) {
            init.visitTypeInsn(Opcodes.NEW, "java/util/LinkedList");
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(
                    Opcodes.INVOKESPECIAL, "java/util/LinkedList", "<init>", "()V", false);
            init.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections",
                    "synchronizedList", "(Ljava/util/List;)Ljava/util/List;", false);
            init.visitFieldInsn(Opcodes.PUTSTATIC, owner, field, "Ljava/util/List;");
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // The consumer: what makes IMAGE_QUEUE the image queue is that this method tests it.
        MethodVisitor consumer = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                TexturePrefetchBypassPlan.CONSUMER_METHOD,
                TexturePrefetchBypassPlan.CONSUMER_DESCRIPTOR,
                null,
                null);
        consumer.visitCode();
        if (consumerTestsQueue) {
            consumer.visitFieldInsn(Opcodes.GETSTATIC, owner, IMAGE_QUEUE, "Ljava/util/List;");
            consumer.visitVarInsn(Opcodes.ALOAD, 0);
            consumer.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List",
                    "contains", "(Ljava/lang/Object;)Z", true);
            consumer.visitInsn(Opcodes.POP);
        }
        consumer.visitInsn(Opcodes.ACONST_NULL);
        consumer.visitInsn(Opcodes.ARETURN);
        consumer.visitMaxs(0, 0);
        consumer.visitEnd();

        appendMethod(writer, owner, IMAGE_ENQUEUE, IMAGE_QUEUE, enqueueOnlyAppends);
        appendMethod(writer, owner, BYTE_ENQUEUE, BYTE_QUEUE, true);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void appendMethod(
            ClassWriter writer, String owner, String name, String field, boolean onlyAppends) {
        MethodVisitor enqueue = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name,
                TexturePrefetchBypassPlan.ENQUEUE_DESCRIPTOR,
                null,
                null);
        enqueue.visitCode();
        enqueue.visitFieldInsn(Opcodes.GETSTATIC, owner, field, "Ljava/util/List;");
        enqueue.visitVarInsn(Opcodes.ALOAD, 0);
        enqueue.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List",
                "add", "(Ljava/lang/Object;)Z", true);
        enqueue.visitInsn(Opcodes.POP);
        if (!onlyAppends) {
            enqueue.visitFieldInsn(Opcodes.GETSTATIC, owner, BYTE_QUEUE, "Ljava/util/List;");
            enqueue.visitVarInsn(Opcodes.ALOAD, 0);
            enqueue.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List",
                    "add", "(Ljava/lang/Object;)Z", true);
            enqueue.visitInsn(Opcodes.POP);
        }
        enqueue.visitInsn(Opcodes.RETURN);
        enqueue.visitMaxs(0, 0);
        enqueue.visitEnd();
    }
}

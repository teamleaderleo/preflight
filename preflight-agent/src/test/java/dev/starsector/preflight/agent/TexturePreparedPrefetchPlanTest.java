package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class TexturePreparedPrefetchPlanTest {
    private static final String IMAGE_QUEUE = "imageQueue";
    private static final String ENQUEUE = "enqueueImage";

    @AfterEach
    void clearWorkerProperty() {
        System.clearProperty(TexturePreparedPrefetchPlan.WINDOWS_WORKERS_PROPERTY);
    }

    @Test
    void deduplicatesPreparedEnqueuesAndFeedsTheOriginalWorkerDecoder() throws Exception {
        byte[] original = syntheticPrefetcher();
        byte[] transformed = TexturePreparedPrefetchPlan.transform(
                ClassSignature.parse(original), original);

        assertNotNull(transformed);
        ClassNode parsed = parse(transformed);
        assertEquals(
                List.of("shouldQueuePreparedPrefetch", "add"),
                calls(method(parsed, ENQUEUE, TexturePrefetchBypassPlan.ENQUEUE_DESCRIPTOR))
                        .stream().map(call -> call.name).toList());
        assertEquals(
                List.of("prefetchLoad"),
                calls(method(parsed, TexturePreparedPrefetchPlan.DECODE_METHOD,
                        TexturePreparedPrefetchPlan.DECODE_DESCRIPTOR))
                        .stream()
                        .filter(call -> call.owner.contains("preflight"))
                        .map(call -> call.name)
                        .toList());

        Class<?> loaded = loadRedirected(transformed);
        RecordingProbe.queuePrepared = false;
        loaded.getMethod(ENQUEUE, String.class).invoke(null, "graphics/cached.png");
        assertEquals(List.of(), loaded.getField(IMAGE_QUEUE).get(null));

        RecordingProbe.queuePrepared = true;
        loaded.getMethod(ENQUEUE, String.class).invoke(null, "graphics/original.png");
        assertEquals(List.of("graphics/original.png"), loaded.getField(IMAGE_QUEUE).get(null));

        Method decode = loaded.getDeclaredMethod(
                TexturePreparedPrefetchPlan.DECODE_METHOD, String.class);
        decode.setAccessible(true);
        BufferedImage prepared = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        RecordingProbe.prepared = prepared;
        assertSame(prepared, decode.invoke(null, "graphics/cached.png"));

        RecordingProbe.prepared = null;
        BufferedImage fallback = (BufferedImage) decode.invoke(null, "graphics/original.png");
        assertEquals(1, fallback.getWidth());
        assertEquals(1, fallback.getHeight());
    }

    @Test
    void refusesToInstallTwice() throws Exception {
        byte[] original = syntheticPrefetcher();
        byte[] transformed = TexturePreparedPrefetchPlan.transform(
                ClassSignature.parse(original), original);
        assertNotNull(transformed);
        assertNull(TexturePreparedPrefetchPlan.transform(
                ClassSignature.parse(transformed), transformed));
    }

    @Test
    void rewritesTheExactStartAndStopMethodsForThreeRaceFreeWorkers() throws Exception {
        System.setProperty(TexturePreparedPrefetchPlan.WINDOWS_WORKERS_PROPERTY, "3");
        byte[] original = syntheticPrefetcher();

        byte[] transformed = TexturePreparedPrefetchPlan.transform(
                ClassSignature.parse(original), original);

        assertNotNull(transformed);
        ClassNode parsed = parse(transformed);
        assertEquals(
                List.of("start"),
                calls(method(parsed, TexturePreparedPrefetchPlan.START_METHOD, "()V"))
                        .stream()
                        .filter(call -> call.owner.contains("PrefetchPoolRuntime"))
                        .map(call -> call.name)
                        .toList());
        assertEquals(
                List.of("stop"),
                calls(method(parsed, TexturePreparedPrefetchPlan.STOP_METHOD, "()V"))
                        .stream()
                        .filter(call -> call.owner.contains("PrefetchPoolRuntime"))
                        .map(call -> call.name)
                        .toList());
    }

    private static Class<?> loadRedirected(byte[] transformed) throws Exception {
        ClassNode node = parse(transformed);
        String runtime = TexturePreparedPixelRuntime.class.getName().replace('.', '/');
        String probe = RecordingProbe.class.getName().replace('.', '/');
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call && runtime.equals(call.owner)) {
                    call.owner = probe;
                }
            }
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(TexturePreparedPrefetchPlanTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.equals(TexturePreparedPrefetchPlan.TARGET_CLASS.replace('/', '.'))) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        }.loadClass(TexturePreparedPrefetchPlan.TARGET_CLASS.replace('/', '.'));
    }

    public static final class RecordingProbe {
        static boolean queuePrepared;
        static BufferedImage prepared;

        public static boolean shouldQueuePreparedPrefetch(String ignored) {
            return queuePrepared;
        }

        public static BufferedImage prefetchLoad(String ignored) {
            return prepared;
        }
    }

    private static byte[] syntheticPrefetcher() {
        String owner = TexturePreparedPrefetchPlan.TARGET_CLASS;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                IMAGE_QUEUE,
                "Ljava/util/List;",
                null,
                null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "imageResults", "Ljava/util/Map;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "imageMarker", "Ljava/awt/image/BufferedImage;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "byteQueue", "Ljava/util/List;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "byteResults", "Ljava/util/Map;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "byteMarker", "[B", null, null).visitEnd();

        MethodVisitor init = writer.visitMethod(
                Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitTypeInsn(Opcodes.NEW, "java/util/LinkedList");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/LinkedList", "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, owner, IMAGE_QUEUE, "Ljava/util/List;");
        init.visitTypeInsn(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/concurrent/ConcurrentHashMap",
                "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, owner, "imageResults", "Ljava/util/Map;");
        init.visitTypeInsn(Opcodes.NEW, "java/awt/image/BufferedImage");
        init.visitInsn(Opcodes.DUP);
        init.visitInsn(Opcodes.ICONST_1);
        init.visitInsn(Opcodes.ICONST_1);
        init.visitInsn(Opcodes.ICONST_2);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/awt/image/BufferedImage",
                "<init>", "(III)V", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, owner, "imageMarker", "Ljava/awt/image/BufferedImage;");
        init.visitTypeInsn(Opcodes.NEW, "java/util/LinkedList");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/LinkedList", "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, owner, "byteQueue", "Ljava/util/List;");
        init.visitTypeInsn(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/concurrent/ConcurrentHashMap",
                "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, owner, "byteResults", "Ljava/util/Map;");
        init.visitInsn(Opcodes.ICONST_0);
        init.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
        init.visitFieldInsn(Opcodes.PUTSTATIC, owner, "byteMarker", "[B");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor consumer = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                TexturePrefetchBypassPlan.WINDOWS_CONSUMER_METHOD,
                TexturePrefetchBypassPlan.CONSUMER_DESCRIPTOR,
                null,
                null);
        consumer.visitCode();
        consumer.visitFieldInsn(Opcodes.GETSTATIC, owner, IMAGE_QUEUE, "Ljava/util/List;");
        consumer.visitVarInsn(Opcodes.ALOAD, 0);
        consumer.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List",
                "contains", "(Ljava/lang/Object;)Z", true);
        consumer.visitInsn(Opcodes.POP);
        consumer.visitFieldInsn(Opcodes.GETSTATIC, owner, "imageResults", "Ljava/util/Map;");
        consumer.visitVarInsn(Opcodes.ALOAD, 0);
        consumer.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map",
                "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        consumer.visitInsn(Opcodes.POP);
        consumer.visitFieldInsn(Opcodes.GETSTATIC, owner, "imageMarker", "Ljava/awt/image/BufferedImage;");
        consumer.visitInsn(Opcodes.POP);
        consumer.visitInsn(Opcodes.ACONST_NULL);
        consumer.visitInsn(Opcodes.ARETURN);
        consumer.visitMaxs(0, 0);
        consumer.visitEnd();

        MethodVisitor enqueue = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                ENQUEUE,
                TexturePrefetchBypassPlan.ENQUEUE_DESCRIPTOR,
                null,
                null);
        enqueue.visitCode();
        enqueue.visitFieldInsn(Opcodes.GETSTATIC, owner, IMAGE_QUEUE, "Ljava/util/List;");
        enqueue.visitVarInsn(Opcodes.ALOAD, 0);
        enqueue.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List",
                "add", "(Ljava/lang/Object;)Z", true);
        enqueue.visitInsn(Opcodes.POP);
        enqueue.visitInsn(Opcodes.RETURN);
        enqueue.visitMaxs(0, 0);
        enqueue.visitEnd();

        MethodVisitor decode = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                TexturePreparedPrefetchPlan.DECODE_METHOD,
                TexturePreparedPrefetchPlan.DECODE_DESCRIPTOR,
                null,
                new String[] {"java/io/IOException"});
        decode.visitCode();
        decode.visitTypeInsn(Opcodes.NEW, "java/awt/image/BufferedImage");
        decode.visitInsn(Opcodes.DUP);
        decode.visitInsn(Opcodes.ICONST_1);
        decode.visitInsn(Opcodes.ICONST_1);
        decode.visitInsn(Opcodes.ICONST_2);
        decode.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/awt/image/BufferedImage",
                "<init>", "(III)V", false);
        decode.visitInsn(Opcodes.ARETURN);
        decode.visitMaxs(0, 0);
        decode.visitEnd();

        MethodVisitor byteDecode = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "decodeBytes",
                "(Ljava/lang/String;)[B",
                null,
                new String[] {"java/io/IOException"});
        byteDecode.visitCode();
        byteDecode.visitInsn(Opcodes.ICONST_0);
        byteDecode.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
        byteDecode.visitInsn(Opcodes.ARETURN);
        byteDecode.visitMaxs(0, 0);
        byteDecode.visitEnd();

        MethodVisitor start = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                TexturePreparedPrefetchPlan.START_METHOD,
                "()V",
                null,
                null);
        start.visitCode();
        start.visitInsn(Opcodes.RETURN);
        start.visitMaxs(0, 0);
        start.visitEnd();

        MethodVisitor stop = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                TexturePreparedPrefetchPlan.STOP_METHOD,
                "()V",
                null,
                null);
        stop.visitCode();
        stop.visitInsn(Opcodes.RETURN);
        stop.visitMaxs(0, 0);
        stop.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst()
                .orElse(null);
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        assertNotNull(method);
        List<MethodInsnNode> found = new LinkedList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call) {
                found.add(call);
            }
        }
        return found;
    }
}

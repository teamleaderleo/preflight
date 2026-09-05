package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.starsector.preflight.core.PreparedTexture;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public class TexturePreparedResourceLoaderPlanTest {
    @BeforeEach
    void enableResourcePlan() {
        System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
    }

    @Test
    void declinesWhenPropertyIsOff() throws Exception {
        byte[] original = installed();
        ClassSignature signature = ClassSignature.parse(original);
        byte[] composed = compose(signature, original);
        System.clearProperty(TexturePreparedResourceRuntime.PROPERTY);
        assertNull(TexturePreparedResourceLoaderPlan.transform(signature, composed));
    }

    @AfterEach
    void resetRuntime() {
        TexturePreparedPixelRuntime.beginSession();
        TexturePaddingRuntime.beginSession();
        System.clearProperty(TexturePreparedResourceRuntime.PROPERTY);
        System.clearProperty(TexturePaddingRuntime.UNPADDED_PROPERTY);
        FakeGL.reset();
    }

    @Test
    void executableOriginalCompletionMatchesStockUploadAndRepositoryLifecycle() throws Exception {
        BufferedImage image = image();
        Executable stock = new Executable(false);
        stock.supply("p", image, false);
        Object stockHandle = stock.register("p", "p");
        List<String> expectedCalls = List.copyOf(FakeGL.calls);
        byte[] expectedPixels = FakeGL.pixels.clone();
        List<Object> expectedMetadata = metadata(stockHandle);

        Executable prepared = new Executable(true);
        prepared.supply("p", image, true);
        Object handle = prepared.register("p", "p");
        assertEquals(expectedCalls, FakeGL.calls);
        assertArrayEquals(expectedPixels, FakeGL.pixels);
        assertEquals(expectedMetadata, metadata(handle));
        assertTrue(prepared.results.isEmpty());
        assertEquals(1L, TexturePreparedResourceRuntime.telemetry().get("committed"));
        assertEquals(1L, TexturePreparedResourceRuntime.telemetry().get("coherent"));

        // A cached path returns the same real handler and performs no GL work.
        int calls = FakeGL.calls.size();
        assertSame(handle, prepared.register("alias", "p"));
        assertEquals(calls, FakeGL.calls.size());
        assertEquals("alias", handle.getClass().getMethod("oO0000").invoke(handle));
        // Global insert-only behavior keeps the first handle even for a different path.
        prepared.supply("q", image, false);
        assertSame(handle, prepared.register("p", "q"));
        assertTrue(prepared.cache().containsKey("q"));
        prepared.repo.getMethod("Ó00000", String.class).invoke(null, "p");
        assertFalse(prepared.cache().containsKey("p"));
        assertSame(handle, prepared.repository().get("alias"));
        calls = FakeGL.calls.size();
        prepared.repo.getMethod("Ò00000").invoke(null);
        assertTrue(prepared.cache().isEmpty());
        assertTrue(prepared.repository().isEmpty());
        assertEquals(calls, FakeGL.calls.size(), "removal/clear performs no GL deletion");
    }

    @Test
    void executableDirectAndCeilingFallbackLinkRealCompletionAndReleaseBuffers() throws Exception {
        Executable baseline = new Executable(false);
        baseline.supply("p", image(), false);
        Object original = baseline.register("p", "p");
        byte[] pixels = FakeGL.pixels.clone();
        List<String> calls = List.copyOf(FakeGL.calls);
        List<Object> metadata = metadata(original);
        PreparedTexture texture = texture(original, pixels);

        Executable direct = new Executable(true);
        // A direct completion must replay backing dimensions with optional gates off too.
        System.clearProperty(TexturePaddingRuntime.UNPADDED_PROPERTY);
        direct.supply("p", carrier(texture), true);
        Object handle = direct.register("p", "p");
        assertEquals(calls, FakeGL.calls);
        assertArrayEquals(pixels, FakeGL.pixels);
        assertEquals(metadata, metadata(handle));
        assertEquals(1L, TexturePreparedResourceRuntime.telemetry().get("direct"));
        assertEquals(0L, field(TexturePreparedPixelRuntime.class, "activeBytes").get(null));

        Executable ceiling = new Executable(true);
        BufferedImage coherent = carrier(texture);
        ceiling.supply("p", coherent, true);
        field(TexturePreparedPixelRuntime.class, "activeBytes").setLong(null,
                field(TexturePreparedPixelRuntime.class, "MAX_ACTIVE_DIRECT_BYTES").getLong(null));
        handle = ceiling.register("p", "p");
        assertEquals(calls, FakeGL.calls);
        assertArrayEquals(pixels, FakeGL.pixels);
        assertEquals(metadata, metadata(handle));
        assertEquals(1L, TexturePreparedResourceRuntime.telemetry().get("coherent"));
        assertTrue(((java.util.concurrent.atomic.AtomicBoolean)
                field(coherent.getClass(), "sharedHitCredited").get(coherent)).get());
    }

    @Test
    void executableUploadFailureReleasesPreparedBufferAndDoesNotPublishHandle() throws Exception {
        Executable baseline = new Executable(false);
        baseline.supply("p", image(), false);
        PreparedTexture texture = texture(baseline.register("p", "p"), FakeGL.pixels);
        Executable fixture = new Executable(true);
        fixture.supply("p", carrier(texture), true);
        FakeGL.failUpload = true;
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> fixture.register("p", "p"));
        assertTrue(error.getCause() instanceof IllegalStateException);
        assertEquals(0L, field(TexturePreparedPixelRuntime.class, "activeBytes").get(null));
        assertTrue(fixture.cache().isEmpty());
        assertTrue(fixture.repository().isEmpty());
        assertEquals(1L, TexturePreparedResourceRuntime.telemetry().get("failures"));
        assertEquals(0L, TexturePreparedResourceRuntime.telemetry().get("inFlight"));
    }

    @Test
    void executableReplacementRetainsHandlerAndOldPathAliasAndDeclinesSidecar() throws Exception {
        Executable fixture = new Executable(true);
        fixture.supply("p", image(), true);
        Object handle = fixture.register("p", "p");
        Object id = handle.getClass().getMethod("ö00000").invoke(handle);
        fixture.supply("q", image(), true);
        TexturePreparedResourceRuntime.enter("q", "q");
        try {
            fixture.loader.getClass().getMethod("o00000", handle.getClass(), String.class)
                    .invoke(fixture.loader, handle, "q");
        } finally {
            TexturePreparedResourceRuntime.exit(true);
        }
        assertSame(handle, fixture.cache().get("p"));
        assertSame(handle, fixture.cache().get("q"));
        assertEquals(id, handle.getClass().getMethod("ö00000").invoke(handle));
        assertEquals("q", handle.getClass().getMethod("Õ00000").invoke(handle));
        assertEquals(1L, TexturePreparedResourceRuntime.telemetry().get("originalConsumed"));
        assertEquals(1L, TexturePreparedResourceRuntime.telemetry().get("committed"));
        fixture.supply("q", image(), false);
        fixture.loader.getClass().getMethod("o00000", handle.getClass()).invoke(fixture.loader, handle);
        assertSame(handle, fixture.cache().get("q"));
        assertEquals(id, handle.getClass().getMethod("ö00000").invoke(handle));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {3, 4})
    void executableLargeNpotDeclineUsesOriginalConverterAndSubimageReload(int channels) throws Exception {
        System.clearProperty(TexturePaddingRuntime.UNPADDED_PROPERTY);
        BufferedImage image = new BufferedImage(1025, 3,
                channels == 4 ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        byte[] sourcePixels = new byte[1025 * 3 * channels];
        int offset = 0;
        for (int y = 2; y >= 0; y--) {
            for (int x = 0; x < 1025; x++) {
                int argb = ((channels == 4 ? (x + y) & 255 : 255) << 24) | 0x204060 | ((x + y) & 15);
                image.setRGB(x, y, argb);
                sourcePixels[offset++] = (byte) (argb >>> 16);
                sourcePixels[offset++] = (byte) (argb >>> 8);
                sourcePixels[offset++] = (byte) argb;
                if (channels == 4) sourcePixels[offset++] = (byte) (argb >>> 24);
            }
        }
        Executable stock = new Executable(false);
        stock.supply("p", image, false);
        Object original = stock.register("p", "p");
        List<String> expectedCalls = List.copyOf(FakeGL.calls);
        byte[] expectedPixels = FakeGL.pixels.clone();
        List<Object> expectedMetadata = metadata(original);
        PreparedTexture texture = new PreparedTexture("ab".repeat(32), PreparedTexture.Transformation.IDENTITY,
                1025, 3, 1025, 3, channels, 0, 0, 0, sourcePixels);
        Executable fixture = new Executable(true);
        fixture.supply("p", carrier(texture), true);
        Object handle = fixture.register("p", "p");
        assertEquals(expectedCalls, FakeGL.calls);
        assertArrayEquals(expectedPixels, FakeGL.pixels);
        // Deliberately wrong stored colors above prove the literal converter recomputed all three.
        assertEquals(expectedMetadata, metadata(handle));
        assertEquals(1L, TexturePreparedResourceRuntime.telemetry().get("coherent"));
        assertEquals(0L, TexturePreparedResourceRuntime.telemetry().get("direct"));
        assertEquals(0L, field(TexturePreparedPixelRuntime.class, "activeBytes").get(null));
        fixture.supply("p", image, false);
        fixture.loader.getClass().getMethod("o00000", handle.getClass()).invoke(fixture.loader, handle);
        assertTrue(FakeGL.calls.stream().anyMatch(c -> c.startsWith("subimage:")));
        assertSame(handle, fixture.cache().get("p"));
    }

    @Test
    void declinesUnreviewedOriginal() throws Exception {
        byte[] bytes = TexturePreparedPixelPlanTest.textureLoader(3, true, true);
        assertNull(TexturePreparedResourceLoaderPlan.transform(ClassSignature.parse(bytes), bytes));
    }

    @Test
    void ownedRgbUploadRestoresUnpackStateAfterSuccessAndFailure() throws Exception {
        byte[] pixels = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        for (boolean fail : new boolean[] {false, true}) {
            Executable fixture = new Executable(true);
            PreparedTexture texture = new PreparedTexture("ab".repeat(32), PreparedTexture.Transformation.IDENTITY,
                    2, 2, 2, 2, 3, 0, 0, 0, pixels);
            fixture.supply("p", carrier(texture), true);
            FakeGL.failUpload = fail;
            if (fail) {
                InvocationTargetException error = assertThrows(InvocationTargetException.class,
                        () -> fixture.register("p", "p"));
                assertTrue(error.getCause() instanceof IllegalStateException);
                assertTrue(fixture.cache().isEmpty());
            } else {
                Object handle = fixture.register("p", "p");
                assertSame(handle, fixture.cache().get("p"));
            }
            assertArrayEquals(pixels, FakeGL.pixels);
            assertEquals(1, FakeGL.uploadAlignment);
            assertEquals(4, FakeGL.unpackAlignment);
            assertEquals(1L, TexturePreparedPixelRuntime.telemetry().get("unpackAlignmentChanges"));
            assertEquals(1L, TexturePreparedPixelRuntime.telemetry().get("unpackAlignmentRestores"));
            assertEquals(0L, field(TexturePreparedPixelRuntime.class, "activeBytes").get(null));
        }
    }

    @Test
    void originalConverterBufferRetainsOriginalUnpackState() throws Exception {
        Executable fixture = new Executable(true);
        fixture.supply("p", new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), true);
        fixture.register("p", "p");
        assertEquals(4, FakeGL.uploadAlignment);
        assertEquals(4, FakeGL.unpackAlignment);
        assertEquals(0L, TexturePreparedPixelRuntime.telemetry().get("unpackAlignmentChanges"));
    }

    @Test
    void installedWindowsCompositionPreservesGlAndUsesTypedFallbacks() throws Exception {
        byte[] original = installed();
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(TexturePreparedResourceLoaderPlan.WINDOWS_SHA256, signature.sha256());
        byte[] composed = compose(signature, original);
        byte[] result = TexturePreparedResourceLoaderPlan.transform(signature, composed);
        assertNotNull(result);
        ClassNode before = read(composed);
        ClassNode after = read(result);
        MethodNode load = load(after);
        assertEquals(glCalls(load(before)), glCalls(load));
        assertEquals(load(before).tryCatchBlocks.size(), load.tryCatchBlocks.size());
        for (MethodNode method : after.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(after.name, method);
        }
        assertEquals(1, calls(load, TexturePreparedResourceLoaderPlan.RUNTIME, "take"));
        assertEquals(1, calls(load, TexturePreparedResourceLoaderPlan.COMPLETION, "prepare"));
        assertEquals(1, calls(load, TexturePreparedResourceLoaderPlan.COMPLETION, "creditOriginalFallback"));
        assertEquals(1, calls(load, TexturePreparedResourceLoaderPlan.COMPLETION, "image"));
        assertEquals(1, calls(load, TexturePreparedResourceLoaderPlan.COMPLETION, "converterImage"));
        assertEquals(1, calls(load, after.name, "preflight$original$convertPixels"));
        assertEquals(1, calls(load, after.name, "Ô00000"));
        assertEquals(1, calls(load, after.name, "o00000",
                TexturePreparedPixelPlan.CONVERT_DESCRIPTOR));
        // Every other method, including getter, wrapper, cleanup, and cache entry points, is intact.
        for (int i = 0; i < before.methods.size(); i++) {
            if (before.methods.get(i) != load(before)) {
                assertArrayEquals(methodBytes(before.methods.get(i)), methodBytes(after.methods.get(i)));
            }
        }
        for (AbstractInsnNode n : load.instructions) {
            if (n instanceof MethodInsnNode c
                    && (c.owner.equals(TexturePreparedResourceLoaderPlan.RUNTIME)
                    || c.owner.equals(TexturePreparedResourceLoaderPlan.COMPLETION))) {
                int index = load.instructions.indexOf(n);
                assertTrue(load.tryCatchBlocks.stream().anyMatch(t -> "java/lang/Throwable".equals(t.type)
                        && load.instructions.indexOf(t.start) <= index
                        && index < load.instructions.indexOf(t.end)));
            }
        }
        assertNull(TexturePreparedResourceLoaderPlan.transform(signature, result));
        assertNull(TexturePreparedResourceLoaderPlan.transform(ClassSignature.parse(composed), composed));
    }

    @Test
    void declinesMissingPrerequisitesAndAmbiguousDecode() throws Exception {
        byte[] original = installed();
        ClassSignature signature = ClassSignature.parse(original);
        assertNull(TexturePreparedResourceLoaderPlan.transform(signature, original));
        byte[] pixels = TexturePreparedPixelPlan.transform(signature, original);
        assertNotNull(pixels);
        assertNull(TexturePreparedResourceLoaderPlan.transform(signature, pixels));
        ClassNode owner = read(compose(signature, original));
        MethodNode load = load(owner);
        for (AbstractInsnNode n : load.instructions) {
            if (n instanceof MethodInsnNode c && c.name.equals("Ô00000")) {
                load.instructions.insertBefore(c, c.clone(new java.util.HashMap<>()));
                break;
            }
        }
        ClassWriter writer = new ClassWriter(0);
        owner.accept(writer);
        assertNull(TexturePreparedResourceLoaderPlan.transform(signature, writer.toByteArray()));
    }

    private static BufferedImage image() {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff204060);
        image.setRGB(1, 0, 0xff80a0c0);
        image.setRGB(0, 1, 0xff103050);
        image.setRGB(1, 1, 0xff7090b0);
        return image;
    }

    private static List<Object> metadata(Object handle) throws Exception {
        List<Object> values = new ArrayList<>();
        for (String method : List.of("ö00000", "Õ00000", "oO0000", "Object", "Ô00000",
                "o00000", "ÒO0000", "Ò00000", "ø00000", "public")) {
            values.add(handle.getClass().getMethod(method).invoke(handle));
        }
        return values;
    }

    private static PreparedTexture texture(Object handle, byte[] pixels) throws Exception {
        // Colors come from the real converter, so this tests metadata equivalence as well as bytes.
        int c0 = ((Color) handle.getClass().getMethod("Ò00000").invoke(handle)).getRGB();
        int c1 = ((Color) handle.getClass().getMethod("ø00000").invoke(handle)).getRGB();
        int c2 = ((Color) handle.getClass().getMethod("public").invoke(handle)).getRGB();
        return new PreparedTexture("ab".repeat(32), PreparedTexture.Transformation.IDENTITY,
                2, 2, 2, 2, 4, rgba(c0), rgba(c1), rgba(c2), pixels.clone());
    }

    private static int rgba(int argb) {
        return PreparedTexture.rgba((argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, argb >>> 24);
    }

    private static BufferedImage carrier(PreparedTexture texture) throws Exception {
        var layoutMethod = TexturePreparedPixelRuntime.class.getDeclaredMethod("uploadLayout", PreparedTexture.class);
        layoutMethod.setAccessible(true);
        Object layout = layoutMethod.invoke(null, texture);
        Class<?> carrier = Class.forName(TexturePreparedPixelRuntime.class.getName() + "$CarrierImage");
        var constructor = carrier.getDeclaredConstructor(String.class, PreparedTexture.class,
                layout.getClass(), boolean.class, boolean.class);
        constructor.setAccessible(true);
        return (BufferedImage) constructor.newInstance("p", texture, layout, false, false);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** Uses real game bodies and actual agent runtime. Reflection seeds admission only: these tests
     * do not claim that begin(), archive provenance, or the full startup state was exercised. */
    private static final class Executable {
        final Class<?> repo;
        final Object loader;
        final Map<Object, Object> results;
        final List<String> queue;

        @SuppressWarnings("unchecked")
        Executable(boolean resourcePlan) throws Exception {
            byte[] original = installed();
            TexturePreparedPixelRuntime.beginSession();
            FakeGL.reset();
            System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
            // Baseline is the unmodified installed loader, not the historically frame-widened
            // prepared+fold intermediate. Only the final prototype compose opts into frame repair.
            byte[] bytes = original;
            if (resourcePlan) bytes = TexturePreparedResourceLoaderPlan.transform(
                    ClassSignature.parse(original), compose(ClassSignature.parse(original), original));
            assertNotNull(bytes);
            final byte[] transformed = bytes;
            Path jarPath = Path.of(System.getProperty("preflight.starsector.common.jar"));
            ClassLoader child = new ClassLoader(getClass().getClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    try {
                        byte[] definition;
                        if (name.equals("org.apache.log4j.Logger")) definition = loggerStub();
                        else if (name.equals("org.lwjgl.BufferUtils")) definition = endpointStub(name, true);
                        else if (name.equals("org.lwjgl.opengl.GL11")) definition = endpointStub(name, false);
                        else if (name.equals("com.fs.graphics.TextureLoader")) definition = transformed;
                        else try (JarFile jar = new JarFile(jarPath.toFile())) {
                            var entry = jar.getJarEntry(name.replace('.', '/') + ".class");
                            if (entry == null) throw new ClassNotFoundException(name);
                            definition = jar.getInputStream(entry).readAllBytes();
                            if (name.equals("com.fs.graphics.L") && resourcePlan) {
                                ClassSignature signature = ClassSignature.parse(definition);
                                ClassNode node = read(definition);
                                assertTrue(TexturePreparedResourcePlan.applyPrefetch(signature, node));
                                definition = TexturePreparedResourcePlan.write(node);
                            }
                        }
                        return defineClass(name, definition, 0, definition.length);
                    } catch (Exception error) {
                        throw new ClassNotFoundException(name, error);
                    }
                }
            };
            repo = Class.forName("com.fs.graphics.oOoO", true, child);
            loader = repo.getMethod("String").invoke(null);
            Class<?> preloader = Class.forName("com.fs.graphics.L", true, child);
            results = (Map<Object, Object>) field(preloader, "void").get(null);
            queue = (List<String>) field(preloader, "Õ00000").get(null);
            field(TexturePreparedResourceRuntime.class, "stockResults").set(null, results);
            field(TexturePreparedResourceRuntime.class, "stockQueue").set(null, queue);
            field(TexturePreparedResourceRuntime.class, "stockSentinel").set(null, field(preloader, "String").get(null));
            field(TexturePreparedResourceRuntime.class, "mainThread").set(null, Thread.currentThread());
            field(TexturePreparedResourceRuntime.class, "workerThread").set(null, Thread.currentThread());
            field(TexturePreparedResourceRuntime.class, "active").setBoolean(null, true);
        }

        @SuppressWarnings("unchecked")
        void supply(String path, BufferedImage image, boolean sidecar) throws Exception {
            if (sidecar) {
                Map<String, TexturePreparedResourceRuntime.Obligation> obligations =
                        (Map<String, TexturePreparedResourceRuntime.Obligation>)
                                field(TexturePreparedResourceRuntime.class, "OBLIGATIONS").get(null);
                obligations.put(path, new TexturePreparedResourceRuntime.Obligation("TEXTURE", path, path, "fixture", 1));
                assertSame(image, TexturePreparedResourceRuntime.publish(path, image));
            }
            results.put(path, image);
        }

        Object register(String id, String path) throws Exception {
            TexturePreparedResourceRuntime.enter(path, id);
            boolean success = false;
            try {
                repo.getMethod("super", String.class, String.class).invoke(null, id, path);
                success = true;
                return repo.getMethod("Ò00000", String.class).invoke(null, id);
            } finally {
                TexturePreparedResourceRuntime.exit(success);
            }
        }

        Map<?, ?> cache() throws Exception { return (Map<?, ?>) loader.getClass().getMethod("o00000").invoke(loader); }
        Map<?, ?> repository() throws Exception { return (Map<?, ?>) repo.getMethod("super").invoke(null); }
    }

    private static byte[] endpointStub(String name, boolean buffers) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
        for (var method : FakeGL.class.getDeclaredMethods()) {
            boolean isBuffer = method.getName().startsWith("create");
            if (buffers != isBuffer || (!isBuffer && !method.getName().startsWith("gl"))) continue;
            String descriptor = Type.getMethodDescriptor(method);
            var visitor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    method.getName(), descriptor, null, null);
            visitor.visitCode();
            int local = 0;
            for (Type type : Type.getArgumentTypes(descriptor)) {
                visitor.visitVarInsn(type.getOpcode(Opcodes.ILOAD), local);
                local += type.getSize();
            }
            visitor.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(FakeGL.class), method.getName(), descriptor, false);
            visitor.visitInsn(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN));
            visitor.visitMaxs(0, 0);
            visitor.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] loggerStub() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String owner = "org/apache/log4j/Logger";
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        var get = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getLogger",
                "(Ljava/lang/Class;)L" + owner + ";", null, null);
        get.visitCode();
        get.visitTypeInsn(Opcodes.NEW, owner);
        get.visitInsn(Opcodes.DUP);
        get.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "()V", false);
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        for (String method : List.of("debug", "info", "warn", "error")) {
            for (String descriptor : List.of("(Ljava/lang/Object;)V", "(Ljava/lang/Object;Ljava/lang/Throwable;)V")) {
                var visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, method, descriptor, null, null);
                visitor.visitCode();
                visitor.visitInsn(Opcodes.RETURN);
                visitor.visitMaxs(0, 0);
                visitor.visitEnd();
            }
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** These endpoints record the original GL body's observable calls; no native context exists. */
    public static final class FakeGL {
        static final List<String> calls = new ArrayList<>();
        static byte[] pixels;
        static boolean failUpload;
        static int nextId;
        static int unpackAlignment, uploadAlignment;
        static void reset() { calls.clear(); pixels = null; failUpload = false; nextId = 1; unpackAlignment = 4; uploadAlignment = -1; }
        public static IntBuffer createIntBuffer(int size) { return ByteBuffer.allocateDirect(size * 4).asIntBuffer(); }
        public static ByteBuffer createByteBuffer(int size) { return ByteBuffer.allocateDirect(size); }
        public static void glGenTextures(IntBuffer target) { target.put(0, nextId++); calls.add("gen"); }
        public static void glBindTexture(int target, int id) { calls.add("bind:" + target + ":" + id); }
        public static void glTexParameteri(int target, int name, int value) { calls.add("parameter:" + target + ":" + name + ":" + value); }
        public static int glGetInteger(int name) { assertEquals(3317, name); return unpackAlignment; }
        public static void glPixelStorei(int name, int value) { assertEquals(3317, name); unpackAlignment = value; calls.add("unpack:" + value); }
        public static void glTexImage2D(int target, int level, int internal, int width, int height,
                int border, int format, int type, ByteBuffer bytes) {
            uploadAlignment = unpackAlignment;
            calls.add("image:" + target + ":" + level + ":" + internal + ":" + width + ":" + height
                    + ":" + border + ":" + format + ":" + type);
            pixels = new byte[bytes.remaining()];
            bytes.duplicate().get(pixels);
            if (failUpload) throw new IllegalStateException("fixture upload failure");
        }
        public static void glTexSubImage2D(int target, int level, int x, int y, int width, int height,
                int format, int type, ByteBuffer bytes) {
            calls.add("subimage:" + target + ":" + level + ":" + x + ":" + y + ":" + width + ":" + height
                    + ":" + format + ":" + type);
            pixels = new byte[bytes.remaining()];
            bytes.duplicate().get(pixels);
        }
    }

    private static byte[] compose(ClassSignature signature, byte[] original) throws Exception {
        byte[] pixels = TexturePreparedPixelPlan.transform(signature, original);
        assertNotNull(pixels);
        try {
            byte[] folded = TexturePaddingPlan.transform(ClassSignature.parse(pixels), pixels);
            assertNotNull(folded);
            return folded;
        } finally {
            TexturePaddingRuntime.beginSession();
        }
    }

    private static byte[] installed() throws Exception {
        String configured = System.getProperty("preflight.starsector.common.jar", "");
        Assumptions.assumeTrue(!configured.isBlank(), "supply exact Windows common JAR for installed structural checks");
        try (JarFile jar = new JarFile(Path.of(configured).toFile())) {
            return jar.getInputStream(jar.getJarEntry("com/fs/graphics/TextureLoader.class")).readAllBytes();
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode load(ClassNode owner) {
        return owner.methods.stream().filter(m -> m.name.equals("o00000")
                && m.desc.equals(TexturePreparedResourceLoaderPlan.LOAD_DESCRIPTOR)).findFirst().orElseThrow();
    }

    private static byte[] methodBytes(MethodNode method) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Comparison", null, "java/lang/Object", null);
        method.accept(new org.objectweb.asm.MethodVisitor(Opcodes.ASM9,
                writer.visitMethod(method.access, method.name, method.desc, method.signature,
                        method.exceptions.toArray(String[]::new))) {
            @Override
            public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
                // Final prototype composition repairs frames; compare every other method's code,
                // handlers, metadata, and maxima without equating old invalid stack-map entries.
            }
        });
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> glCalls(MethodNode method) {
        List<String> result = new ArrayList<>();
        for (AbstractInsnNode n : method.instructions) {
            if (n instanceof MethodInsnNode c && c.owner.startsWith("org/lwjgl/")) {
                result.add(c.getOpcode() + c.owner + c.name + c.desc);
            }
        }
        assertFalse(result.isEmpty());
        return result;
    }

    private static int calls(MethodNode method, String owner, String name) {
        return calls(method, owner, name, null);
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode n : method.instructions) {
            if (n instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name)
                    && (descriptor == null || descriptor.equals(c.desc))) count++;
        }
        return count;
    }
}

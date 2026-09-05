package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class TexturePreparedResourcePlanTest {
    private static final String PRELOADER = "com/fs/graphics/L";
    private static final String RESOURCE = "com/fs/starfarer/loading/ResourceLoaderState";
    private static final String WRAPPER = "preflight$commitPreparedResource";
    private static final String REGISTER = "(Ljava/lang/String;Ljava/lang/String;)V";

    @BeforeEach
    void enable() {
        System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
        System.clearProperty(TexturePreparedPrefetchPlan.WINDOWS_WORKERS_PROPERTY);
        System.clearProperty(TexturePreparedPrefetchPlan.WINDOWS_SPLIT_QUEUES_PROPERTY);
    }

    @AfterEach
    void reset() {
        System.clearProperty(TexturePreparedResourceRuntime.PROPERTY);
        System.clearProperty(TexturePreparedResourceRuntime.CLAIM_PROPERTY);
        System.clearProperty(TexturePreparedResourceRuntime.BARRIER_PROPERTY);
        System.clearProperty(TexturePreparedPrefetchPlan.WINDOWS_WORKERS_PROPERTY);
        System.clearProperty(TexturePreparedPrefetchPlan.WINDOWS_SPLIT_QUEUES_PROPERTY);
    }

    @Test
    void installedByteBarrierIsOnByteLoopFallthroughBeforeAnyImageWork() throws Exception {
        byte[] original = installed("common", TexturePreparedResourcePlan.WORKER);
        System.setProperty(TexturePreparedResourceRuntime.BARRIER_PROPERTY, "true");
        byte[] transformed = TexturePreparedResourcePlan.transformWorker(ClassSignature.parse(original), original);
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        MethodNode run = method(owner, "run", "()V");
        int boundaries = 0;
        for (AbstractInsnNode n : run.instructions) {
            if (n instanceof MethodInsnNode call && "bytePhaseComplete".equals(call.name)) {
                boundaries++;
                AbstractInsnNode jump = previousOpcode(n);
                assertEquals(Opcodes.IFEQ, jump.getOpcode());
                assertTrue(previousOpcode(jump) instanceof MethodInsnNode test
                        && "java/util/List".equals(test.owner) && "isEmpty".equals(test.name));
            }
        }
        assertEquals(1, boundaries);
        assertEquals(stockCalls(method(read(original), "run", "()V")), stockCalls(run));
        verifyDataflow(owner);
    }

    @Test
    void installedWorkerSignalsAfterResultPutWithoutChangingStockCallsOrControlFlow() throws Exception {
        byte[] original = installed("common", TexturePreparedResourcePlan.WORKER);
        ClassSignature signature = ClassSignature.parse(original);
        assertNull(TexturePreparedResourcePlan.transformWorker(signature, original));
        System.setProperty(TexturePreparedResourceRuntime.CLAIM_PROPERTY, "true");
        byte[] transformed = TexturePreparedResourcePlan.transformWorker(signature, original);
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        MethodNode run = method(owner, "run", "()V");
        MethodNode stock = method(read(original), "run", "()V");
        assertEquals(stockCalls(stock), stockCalls(run));
        assertEquals(1, calls(run, TexturePreparedResourcePlan.RUNTIME, "resultReady"));
        for (AbstractInsnNode n : run.instructions) {
            if (n instanceof MethodInsnNode call && "resultReady".equals(call.name)) {
                AbstractInsnNode image = previousOpcode(n);
                AbstractInsnNode path = previousOpcode(image);
                AbstractInsnNode pop = previousOpcode(path);
                assertEquals(Opcodes.ALOAD, image.getOpcode());
                assertEquals(Opcodes.ALOAD, path.getOpcode());
                assertEquals(Opcodes.POP, pop.getOpcode());
                assertTrue(previousOpcode(pop) instanceof MethodInsnNode put
                        && put.owner.equals("java/util/Map") && put.name.equals("put"));
            }
        }
        verifyDataflow(owner);
        assertNull(TexturePreparedResourcePlan.transformWorker(signature, transformed));
        assertNull(TexturePreparedResourcePlan.transformWorker(ClassSignature.parse(transformed), original));
        System.clearProperty(TexturePreparedResourceRuntime.PROPERTY);
        assertNull(TexturePreparedResourcePlan.transformWorker(signature, original));
    }

    @Test
    void unreviewedClassesAreDeclinedWithoutMutation() throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RESOURCE, null, "java/lang/Object", null);
        writer.visitEnd();
        byte[] original = writer.toByteArray();
        ClassNode owner = read(original);
        assertFalse(TexturePreparedResourcePlan.apply(ClassSignature.parse(original), owner));
        assertFalse(TexturePreparedResourcePlan.applyPrefetch(ClassSignature.parse(original), owner));
        assertArrayEquals(original, write(owner));
    }

    @Test
    void installedResourceWeaveBracketsOnlyTextureRegistrationAndAlwaysEnds() throws Exception {
        byte[] original = installed("core", RESOURCE);
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(FrameTimeStartupCompletionPlan.WINDOWS_ORIGINAL_SHA256, signature.sha256());
        ClassNode owner = read(original);
        MethodNode init = method(owner, "init", "(Ljava/util/Map;)V");
        int handlers = init.tryCatchBlocks.size();
        List<String> originalCalls = stockCalls(init);
        assertTrue(TexturePreparedResourcePlan.apply(signature, owner));
        assertEquals(1, calls(init, TexturePreparedResourcePlan.RUNTIME, "begin"));
        assertEquals(1, calls(init, owner.name, WRAPPER));
        assertEquals(handlers + 1, init.tryCatchBlocks.size());
        for (AbstractInsnNode n : init.instructions) {
            if (n.getOpcode() == Opcodes.RETURN) {
                assertTrue(previousOpcode(n) instanceof MethodInsnNode c
                        && c.owner.equals(TexturePreparedResourcePlan.RUNTIME) && c.name.equals("end"));
            }
            if (n instanceof MethodInsnNode c && c.owner.equals(PRELOADER) && c.name.equals("o00000")
                    && c.desc.equals("()V")) {
                assertTrue(previousOpcode(n) instanceof MethodInsnNode begin && begin.name.equals("begin"));
            }
        }
        List<String> afterCalls = stockCalls(init);
        List<String> expected = new ArrayList<>(originalCalls);
        assertTrue(expected.remove("com/fs/graphics/oOoO.super" + REGISTER));
        assertEquals(expected.stream().sorted().toList(), afterCalls.stream().sorted().toList());
        String registration = "com/fs/graphics/oOoO.super" + REGISTER;
        assertEquals(originalCalls.stream().filter(c -> !c.equals(registration)).toList(),
                afterCalls.stream().filter(c -> !c.equals(registration)).toList());
        MethodNode wrapper = method(owner, WRAPPER, REGISTER);
        assertEquals(1, calls(wrapper, TexturePreparedResourcePlan.RUNTIME, "enter"));
        assertEquals(2, calls(wrapper, TexturePreparedResourcePlan.RUNTIME, "exit"));
        assertEquals(1, calls(wrapper, "com/fs/graphics/oOoO", "super"));
        assertEquals(1, wrapper.tryCatchBlocks.size());
        assertNull(wrapper.tryCatchBlocks.get(0).type, "fatal errors must exit scope and rethrow");
        byte[] once = write(owner);
        assertFalse(TexturePreparedResourcePlan.apply(signature, owner));
        assertArrayEquals(once, write(owner));
        verifyDataflow(read(TexturePreparedResourcePlan.write(owner)));
    }

    @Test
    void resourceWeaveRequiresBothOriginalSeamsBeforeMutating() throws Exception {
        byte[] original = installed("core", RESOURCE);
        ClassNode owner = read(original);
        MethodNode init = method(owner, "init", "(Ljava/util/Map;)V");
        for (AbstractInsnNode n : init.instructions) {
            if (n instanceof MethodInsnNode c && c.owner.equals(PRELOADER)
                    && c.name.equals("o00000") && c.desc.equals("()V")) c.name = "drifted";
        }
        byte[] before = write(owner);
        assertFalse(TexturePreparedResourcePlan.apply(ClassSignature.parse(original), owner));
        assertArrayEquals(before, write(owner));
    }

    @Test
    void optOutAndIncompatibleWorkerConfigurationLeaveInstalledBytesAlone() throws Exception {
        byte[] resource = installed("core", RESOURCE);
        byte[] preloader = installed("common", PRELOADER);
        for (int mode = 0; mode < 3; mode++) {
            enable();
            if (mode == 0) System.clearProperty(TexturePreparedResourceRuntime.PROPERTY);
            if (mode == 1) System.setProperty(TexturePreparedPrefetchPlan.WINDOWS_WORKERS_PROPERTY, "2");
            if (mode == 2) System.setProperty(TexturePreparedPrefetchPlan.WINDOWS_SPLIT_QUEUES_PROPERTY, "true");
            ClassNode r = read(resource);
            ClassNode p = read(preloader);
            byte[] beforeR = write(r), beforeP = write(p);
            assertFalse(TexturePreparedResourcePlan.apply(ClassSignature.parse(resource), r));
            assertFalse(TexturePreparedResourcePlan.applyPrefetch(ClassSignature.parse(preloader), p));
            assertArrayEquals(beforeR, write(r));
            assertArrayEquals(beforeP, write(p));
        }
    }

    @Test
    void installedPrefetchWeavePreservesWorkerSchedulingAndVerifies() throws Exception {
        byte[] original = installed("common", PRELOADER);
        ClassNode before = read(original);
        ClassNode owner = read(original);
        assertTrue(TexturePreparedResourcePlan.applyPrefetch(ClassSignature.parse(original), owner));
        MethodNode decode = method(owner, "o00000", TexturePreparedPrefetchPlan.DECODE_DESCRIPTOR);
        MethodNode getter = method(owner, "Õ00000", TexturePreparedPrefetchPlan.DECODE_DESCRIPTOR);
        MethodNode stop = method(owner, "Ò00000", "()V");
        assertEquals(returns(decode), calls(decode, TexturePreparedResourcePlan.RUNTIME, "publish"));
        assertEquals(returns(getter), calls(getter, TexturePreparedResourcePlan.RUNTIME, "originalConsumed"));
        assertEquals(1, calls(stop, TexturePreparedResourcePlan.RUNTIME, "finishWorker"));
        assertEquals(0, calls(stop, TexturePreparedResourcePlan.RUNTIME, "end"));
        AbstractInsnNode first = stop.instructions.getFirst();
        while (first.getOpcode() < 0) first = first.getNext();
        assertTrue(first instanceof MethodInsnNode call
                && call.owner.equals(TexturePreparedResourcePlan.RUNTIME)
                && call.name.equals("finishWorker"), "drain must precede the stock interrupt/cleanup");
        MethodNode start = method(owner, "o00000", "()V");
        assertEquals(1, calls(start, TexturePreparedResourcePlan.RUNTIME, "worker"));
        for (AbstractInsnNode n : start.instructions) {
            if (n instanceof MethodInsnNode c && c.owner.equals("java/lang/Thread") && c.name.equals("start")) {
                assertTrue(previousOpcode(n) instanceof MethodInsnNode bind && bind.name.equals("worker"));
                assertEquals(Opcodes.DUP, previousOpcode(previousOpcode(n)).getOpcode());
            }
        }
        for (int i = 0; i < owner.methods.size(); i++) {
            assertEquals(stockCalls(before.methods.get(i)), stockCalls(owner.methods.get(i)));
        }
        verifyDataflow(read(write(owner)));
        // Also exercise the real caller's ClassWriter(0) path after its prepared-decode edits.
        byte[] composed = TexturePreparedPrefetchPlan.transform(ClassSignature.parse(original), original);
        assertNotNull(composed);
        verifyDataflow(read(composed));
    }

    @Test
    void prefetchWeaveRefusesASecondApplicationWithoutDuplicatingLifecycleHooks() throws Exception {
        byte[] original = installed("common", PRELOADER);
        ClassNode owner = read(original);
        ClassSignature signature = ClassSignature.parse(original);
        assertTrue(TexturePreparedResourcePlan.applyPrefetch(signature, owner));
        byte[] once = write(owner);
        assertFalse(TexturePreparedResourcePlan.applyPrefetch(signature, owner));
        assertArrayEquals(once, write(owner));
    }

    @Test
    void newJava17ExceptionHandlersHaveStackMapFramesWithTheResourceWriter() throws Exception {
        byte[] original = installed("core", RESOURCE);
        ClassNode owner = read(original);
        assertTrue(TexturePreparedResourcePlan.apply(ClassSignature.parse(original), owner));
        ClassNode emitted = read(TexturePreparedResourcePlan.write(owner));
        assertEquals(Opcodes.V17, emitted.version);
        for (MethodNode m : List.of(method(emitted, "init", "(Ljava/util/Map;)V"),
                method(emitted, WRAPPER, REGISTER))) {
            var handler = m.tryCatchBlocks.get(m.tryCatchBlocks.size() - 1).handler;
            boolean frame = false;
            for (AbstractInsnNode n = handler.getNext(); n != null && n.getOpcode() < 0; n = n.getNext()) {
                if (n instanceof FrameNode) frame = true;
            }
            assertTrue(frame, m.name + " new exception handler requires a StackMapTable frame");
        }
    }

    @Test
    void generatedWrapperPassesTheJvmVerifierWithTheProductionWriter() throws Exception {
        byte[] original = installed("core", RESOURCE);
        ClassNode owner = read(original);
        assertTrue(TexturePreparedResourcePlan.apply(ClassSignature.parse(original), owner));
        // Isolate the exact generated method from the installed game's dependency graph. Use the
        // production frame-computing writer so this checks the bytes the adapter really emits.
        ClassNode fixture = new ClassNode(Opcodes.ASM9);
        fixture.version = Opcodes.V17;
        fixture.access = Opcodes.ACC_PUBLIC;
        fixture.name = "PreparedResourceWrapperFixture";
        fixture.superName = "java/lang/Object";
        fixture.methods.add(method(owner, WRAPPER, REGISTER));
        byte[] emitted = TexturePreparedResourcePlan.write(fixture);
        Class<?> defined = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(null, emitted, 0, emitted.length); }
        }.define();
        assertDoesNotThrow(defined::getDeclaredMethods);
    }

    @Test
    void wholeInstalledResourceClassPassesJvmVerificationWithoutInitialization() throws Exception {
        byte[] original = installed("core", RESOURCE);
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(FrameTimeStartupCompletionPlan.WINDOWS_ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = TexturePreparedResourcePlan.transform(signature, original);
        assertNotNull(transformed);
        URL[] dependencies = installedVerificationClasspath();
        // Baseline first: distinguish missing dependency/fixture errors from an invalid weave.
        // The pinned original references fields such as StarfarerSettings."float.new". Strict
        // JVM verification rejects these obfuscator names before examining stack maps. Rename
        // illegal member names consistently in memory, preserving instructions and emitted frames.
        verifyWholeResourceClass(verificationMemberNames(original), dependencies);
        verifyWholeResourceClass(verificationMemberNames(transformed), dependencies);
    }

    private void verifyWholeResourceClass(byte[] definition, URL[] dependencies) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(dependencies, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                // The parent supplies agent/JUnit code. Game and graphics classes must come from
                // the installed JARs, not identically named lightweight test doubles in the parent.
                if (name.startsWith("com.fs.") || name.startsWith("org.lwjgl.")
                        || name.startsWith("org.newdawn.") || name.startsWith("org.apache.log4j.")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) loaded = findClass(name);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }

            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(RESOURCE.replace('/', '.'))) {
                    return defineClass(name, definition, 0, definition.length);
                }
                if (name.startsWith("com.fs.")) {
                    URL resource = findResource(name.replace('.', '/') + ".class");
                    if (resource == null) throw new ClassNotFoundException(name);
                    try (var input = resource.openStream()) {
                        byte[] bytes = verificationMemberNames(input.readAllBytes());
                        return defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException error) {
                        throw new ClassNotFoundException(name, error);
                    }
                }
                return super.findClass(name);
            }
        }) {
            Class<?> resource = Class.forName(RESOURCE.replace('/', '.'), false, loader);
            assertEquals(loader, resource.getClassLoader());
            // Reflection forces verification of every method's stack maps without invoking init,
            // constructing a game object, or triggering ResourceLoaderState's static initializer.
            var methods = assertDoesNotThrow(resource::getDeclaredMethods);
            assertTrue(java.util.Arrays.stream(methods).anyMatch(m -> m.getName().equals("init")));
        }
    }

    private static byte[] verificationMemberNames(byte[] bytes) {
        ClassNode owner = read(bytes);
        for (var field : owner.fields) field.name = verificationMemberName(field.name);
        for (MethodNode method : owner.methods) {
            method.name = verificationMemberName(method.name);
            for (AbstractInsnNode n : method.instructions) {
                if (n instanceof FieldInsnNode field) field.name = verificationMemberName(field.name);
                if (n instanceof MethodInsnNode call) call.name = verificationMemberName(call.name);
            }
        }
        // Deliberately COMPUTE_NOTHING: do not repair the production writer's frame merges.
        return write(owner);
    }

    private static String verificationMemberName(String name) {
        if (name.indexOf('.') < 0 && name.indexOf(';') < 0 && name.indexOf('[') < 0
                && name.indexOf('/') < 0) return name;
        return "preflight$verify$" + java.util.HexFormat.of().formatHex(
                name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static URL[] installedVerificationClasspath() throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String kind : List.of("core", "common")) {
            String configured = System.getProperty("preflight.starsector." + kind + ".jar", "");
            Assumptions.assumeTrue(!configured.isBlank(), "supply exact Windows " + kind + " JAR");
            urls.add(Path.of(configured).toUri().toURL());
        }
        String shared = System.getProperty("preflight.starsector.shared.java.dir", "");
        if (!shared.isBlank()) {
            // Only top-level shared Java libraries; never recurse into mods or load game assets.
            try (var files = Files.list(Path.of(shared))) {
                for (Path jar : files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .filter(p -> !p.getFileName().toString().equals("starfarer_obf.jar"))
                        .filter(p -> !p.getFileName().toString().equals("fs.common_obf.jar"))
                        .filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
                        .sorted().toList()) urls.add(jar.toUri().toURL());
            }
        }
        return urls.toArray(URL[]::new);
    }

    private static byte[] installed(String kind, String name) throws Exception {
        String configured = System.getProperty("preflight.starsector." + kind + ".jar", "");
        Assumptions.assumeTrue(!configured.isBlank(), "supply exact Windows " + kind + " JAR");
        try (JarFile jar = new JarFile(Path.of(configured).toFile())) {
            var entry = jar.getJarEntry(name + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) { return input.readAllBytes(); }
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static byte[] write(ClassNode owner) {
        ClassWriter writer = new ClassWriter(0);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static void verifyDataflow(ClassNode owner) throws Exception {
        for (MethodNode method : owner.methods) new Analyzer<>(new BasicVerifier()).analyze(owner.name, method);
    }

    private static MethodNode method(ClassNode owner, String name, String desc) {
        return owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
    }

    private static List<String> stockCalls(MethodNode method) {
        List<String> result = new ArrayList<>();
        for (AbstractInsnNode n : method.instructions) {
            if (n instanceof MethodInsnNode c && !c.owner.equals(TexturePreparedResourcePlan.RUNTIME)
                    && !c.name.equals(WRAPPER)) result.add(c.owner + "." + c.name + c.desc);
        }
        return result;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode n : method.instructions)
            if (n instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name)) count++;
        return count;
    }

    private static int returns(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode n : method.instructions) if (n.getOpcode() == Opcodes.ARETURN) count++;
        return count;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode node) {
        do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0);
        return node;
    }
}

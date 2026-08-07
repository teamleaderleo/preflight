package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class AudioStreamSourceErrorPlanTest {
    private static final String AL10 = "org/lwjgl/openal/AL10";
    private static final String RUNTIME =
            AudioStreamSourceErrorRuntime.class.getName().replace('.', '/');

    @BeforeEach
    @AfterEach
    void reset() {
        AudioStreamSourceErrorRuntime.beginSession();
        AudioMusicTransitionRuntime.beginSession();
        AdapterPlanControl.configure(Set.of());
    }

    @Test
    void staleErrorIsClearedAndActualGenerationErrorControlsVanillaBranch() throws Exception {
        byte[] transformed = AudioStreamSourceErrorPlan.transform(signature(), fixture(true));
        assertNotNull(transformed);
        MethodNode constructor = constructor(transformed);
        assertEquals(2, calls(constructor, AL10, "alGetError"));
        assertEquals(1, calls(constructor, RUNTIME, "beforeGeneration"));
        assertEquals(1, calls(constructor, RUNTIME, "afterGeneration"));
        assertEquals(1, calls(constructor,
                AudioMusicTransitionRuntime.class.getName().replace('.', '/'), "created"));

        var loader = new ByteArrayLoader(Map.of(
                "sound.oo0O", transformed,
                "org.lwjgl.openal.AL10", al10Fixture()));
        Class<?> al10 = loader.loadClass("org.lwjgl.openal.AL10");
        Class<?> player = loader.loadClass("sound.oo0O");
        var create = player.getConstructor(List.class, String.class);

        configure(al10, 40963, 0);
        Object menu = create.newInstance(List.of(), "menu");
        assertNotNull(menu);
        player.getMethod("class", int.class).invoke(menu, 2_000);
        player.getMethod("new", int.class).invoke(menu, 750);
        player.getMethod("ö00000").invoke(menu);
        assertEquals(1L, AudioStreamSourceErrorRuntime.telemetry().get("attempts"));
        assertEquals(1L, AudioStreamSourceErrorRuntime.telemetry().get("staleErrors"));
        assertEquals(0L, AudioStreamSourceErrorRuntime.telemetry().get("generationErrors"));
        assertEquals(1L, AudioStreamSourceErrorRuntime.telemetry().get("recoveredStaleErrors"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>)
                AudioMusicTransitionRuntime.telemetry().get("events");
        assertEquals(List.of("created", "fade-in", "fade-pause", "cleanup"),
                events.stream().map(event -> event.get("kind")).toList());
        assertEquals(2_000, events.get(1).get("durationMillis"));
        assertEquals(750, events.get(2).get("durationMillis"));
        assertEquals(0f, events.get(3).get("fadeScale"));
        assertEquals(7, events.get(3).get("source"));

        configure(al10, 0, 40965);
        InvocationTargetException thrown = assertThrows(
                InvocationTargetException.class,
                () -> create.newInstance(List.of(), "broken"));
        assertTrue(thrown.getCause() instanceof RuntimeException);
        assertEquals(2L, AudioStreamSourceErrorRuntime.telemetry().get("attempts"));
        assertEquals(1L, AudioStreamSourceErrorRuntime.telemetry().get("generationErrors"));
    }

    @Test
    void changedShapeWrongHashAndSecondRewriteFailClosed() {
        ClassSignature exact = signature();
        assertNull(AudioStreamSourceErrorPlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), fixture(true)));
        assertNull(AudioStreamSourceErrorPlan.transform(exact, fixture(false)));
        byte[] once = AudioStreamSourceErrorPlan.transform(exact, fixture(true));
        assertNotNull(once);
        assertNull(AudioStreamSourceErrorPlan.transform(exact, once));
    }

    @Test
    void transitionProbeCanBeFilteredWithoutLosingTheErrorOrderFix() {
        AdapterPlanControl.configure(Set.of(AudioMusicTransitionRuntime.PLAN_ID));

        byte[] transformed = AudioStreamSourceErrorPlan.transform(signature(), fixture(true));

        assertNotNull(transformed);
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        assertEquals(2, calls(constructor(transformed), AL10, "alGetError"));
        assertEquals(0, owner.methods.stream()
                .mapToInt(method -> calls(method,
                        AudioMusicTransitionRuntime.class.getName().replace('.', '/'), "created")
                        + calls(method,
                                AudioMusicTransitionRuntime.class.getName().replace('.', '/'), "fade")
                        + calls(method,
                                AudioMusicTransitionRuntime.class.getName().replace('.', '/'), "cleanup"))
                .sum());
        assertEquals(false, AudioMusicTransitionRuntime.telemetry().get("installed"));
    }

    @Test
    void targetPinsReviewedSoundArchiveAndPlanAvailability() {
        AdapterTarget target = AdapterTargetRegistry.audioStreamSourceErrorTarget();
        assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                "file:/game/Contents/Resources/Java/fs.sound_obf.jar",
                "/game/contents/resources/java/fs.sound_obf.jar",
                "STARSECTOR_CORE",
                target.sourceSha256(),
                "",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
        assertTrue(target.match(signature(), source).exact());
    }

    @Test
    void diagnosticPropertyOmitsOnlyStreamingAudioTarget() {
        String previous = System.getProperty(AudioStreamSourceErrorRuntime.DISABLED_PROPERTY);
        try {
            System.setProperty(AudioStreamSourceErrorRuntime.DISABLED_PROPERTY, "true");
            AdapterTargetRegistry registry = AdapterTargetRegistry.empty()
                    .withTextureTarget(TextureAdapterMode.PREPARED_PIXELS);
            assertTrue(registry.targets().stream()
                    .noneMatch(target -> AudioStreamSourceErrorRuntime.PLAN_ID.equals(target.planId())));
            assertTrue(registry.targets().stream()
                    .anyMatch(target -> AiTweaksEngagementRangeRuntime.PLAN_ID.equals(target.planId())));
        } finally {
            if (previous == null) {
                System.clearProperty(AudioStreamSourceErrorRuntime.DISABLED_PROPERTY);
            } else {
                System.setProperty(AudioStreamSourceErrorRuntime.DISABLED_PROPERTY, previous);
            }
        }
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                AudioStreamSourceErrorPlan.TARGET_CLASS,
                AudioStreamSourceErrorPlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(
                        new ClassSignature.Method(
                                "<init>", AudioStreamSourceErrorPlan.CONSTRUCTOR_DESCRIPTOR,
                                Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method("class", "(I)V", Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method("Ô00000", "(I)V", Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method("new", "(I)V", Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method("Ó00000", "(I)V", Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method("ö00000", "()V", Opcodes.ACC_PUBLIC)));
    }

    private static byte[] fixture(boolean reviewed) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sound/oo0O", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "Object", "Ljava/lang/String;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "o00000", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "Õ00000", "F", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "ô00000", "I", null, null).visitEnd();
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC, "<init>", AudioStreamSourceErrorPlan.CONSTRUCTOR_DESCRIPTOR,
                null, null);
        LabelNode ok = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(
                Opcodes.PUTFIELD, "sound/oo0O", "Object", "Ljava/lang/String;"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 7));
        method.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(
                Opcodes.PUTFIELD, "sound/oo0O", "o00000", "I"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, AL10, "alGetError", "()I", false));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, AL10,
                reviewed ? "alGenSources" : "changedGenSources",
                "(Ljava/nio/IntBuffer;)V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, ok));
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(ok);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.accept(writer);
        for (String name : new String[] {"class", "Ô00000", "new", "Ó00000"}) {
            MethodNode fade = new MethodNode(Opcodes.ACC_PUBLIC, name, "(I)V", null, null);
            fade.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fade.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
            fade.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(
                    Opcodes.PUTFIELD, "sound/oo0O", "ô00000", "I"));
            fade.instructions.add(new InsnNode(Opcodes.RETURN));
            fade.accept(writer);
        }
        MethodNode cleanup = new MethodNode(Opcodes.ACC_PUBLIC, "ö00000", "()V", null, null);
        cleanup.instructions.add(new InsnNode(Opcodes.RETURN));
        cleanup.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] al10Fixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, AL10, null, "java/lang/Object", null);
        for (String field : new String[] {"stale", "generation", "calls"}) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "I", null, null)
                    .visitEnd();
        }
        MethodNode error = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "alGetError", "()I", null, null);
        LabelNode actual = new LabelNode();
        error.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(
                Opcodes.GETSTATIC, AL10, "calls", "I"));
        error.instructions.add(new InsnNode(Opcodes.DUP));
        error.instructions.add(new InsnNode(Opcodes.ICONST_1));
        error.instructions.add(new InsnNode(Opcodes.IADD));
        error.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(
                Opcodes.PUTSTATIC, AL10, "calls", "I"));
        error.instructions.add(new JumpInsnNode(Opcodes.IFNE, actual));
        error.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(
                Opcodes.GETSTATIC, AL10, "stale", "I"));
        error.instructions.add(new InsnNode(Opcodes.IRETURN));
        error.instructions.add(actual);
        error.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(
                Opcodes.GETSTATIC, AL10, "generation", "I"));
        error.instructions.add(new InsnNode(Opcodes.IRETURN));
        error.accept(writer);
        MethodNode generate = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "alGenSources", "(Ljava/nio/IntBuffer;)V", null, null);
        generate.instructions.add(new InsnNode(Opcodes.RETURN));
        generate.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void configure(Class<?> al10, int stale, int generation) throws Exception {
        al10.getField("stale").setInt(null, stale);
        al10.getField("generation").setInt(null, generation);
        al10.getField("calls").setInt(null, 0);
    }

    private static MethodNode constructor(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream().filter(method -> "<init>".equals(method.name))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private ByteArrayLoader(Map<String, byte[]> classes) {
            super(AudioStreamSourceErrorPlanTest.class.getClassLoader());
            this.classes = Map.copyOf(classes);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && classes.containsKey(name)) {
                byte[] bytes = classes.get(name);
                loaded = defineClass(name, bytes, 0, bytes.length);
            }
            if (loaded == null) loaded = super.loadClass(name, false);
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}

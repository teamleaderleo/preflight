package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Opt-in exact installed-class transform check; it never starts or initializes the game. */
class CommodityEventModMemoInstalledAdapterIT {
    @BeforeEach
    @AfterEach
    void reset() {
        System.clearProperty(CommodityEventModMemoRuntime.ENABLED_PROPERTY);
        System.clearProperty(CommodityEventModMemoRuntime.DISABLED_PROPERTY);
        CommodityEventModMemoRuntime.beginSession();
    }

    @Test
    void installedCommodityClassHasTheReviewedPureRecomputationBoundary() throws Throwable {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(CommodityEventModMemoPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(CommodityEventModMemoPlan.ORIGINAL_SHA256, signature.sha256());
        System.setProperty(CommodityEventModMemoRuntime.ENABLED_PROPERTY, "true");
        byte[] transformed = CommodityEventModMemoPlan.transform(signature, original);
        assertNotNull(transformed);
        byte[] mutableOriginal;
        Path apiArchive = archive.resolveSibling("starfarer.api.jar");
        try (JarFile jar = new JarFile(apiArchive.toFile())) {
            var entry = jar.getJarEntry(MutableStatDirtyAccessorPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                mutableOriginal = input.readAllBytes();
            }
        }
        byte[] mutableTransformed = MutableStatDirtyAccessorPlan.transform(
                ClassSignature.parse(mutableOriginal), mutableOriginal);
        assertNotNull(mutableTransformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        assertTrue(owner.fields.stream().filter(
                field -> field.name.startsWith("preflight$eventMod")).allMatch(
                        field -> (field.access & Opcodes.ACC_TRANSIENT) != 0));
        var wrapper = owner.methods.stream().filter(method ->
                CommodityEventModMemoPlan.METHOD.equals(method.name)
                        && CommodityEventModMemoPlan.DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
        int originalCalls = 0;
        for (var instruction : wrapper.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && CommodityEventModMemoPlan.TARGET_CLASS.equals(call.owner)
                    && "preflight$original$reapplyEventMod".equals(call.name)) {
                originalCalls++;
            }
        }
        assertEquals(2, originalCalls);

        byte[] executable = withTestExercise(transformed);
        ListWithArchive classpath = gameClasspath(archive);
        try (InstalledLoader loader = new InstalledLoader(
                classpath.urls(), Map.of(
                        CommodityEventModMemoPlan.TARGET_CLASS.replace('/', '.'), executable,
                        MutableStatDirtyAccessorPlan.TARGET_CLASS.replace('/', '.'),
                        mutableTransformed,
                        "com.fs.starfarer.loading.return", commodityStub()))) {
            Class<?> commodityType = loader.loadAndResolve(
                    CommodityEventModMemoPlan.TARGET_CLASS.replace('/', '.'));
            Class<?> statType = loader.loadClass(
                    "com.fs.starfarer.api.combat.MutableStatWithTempMods");
            Class<?> commoditySpecType = loader.loadClass("com.fs.starfarer.loading.return");
            Object commodity = unsafe().allocateInstance(commodityType);
            var statConstructor = statType.getConstructor(float.class);
            Object available = statConstructor.newInstance(0f);
            var exercise = MethodHandles.publicLookup().findStatic(
                    commodityType,
                    "preflight$testExercise",
                    MethodType.methodType(void.class,
                            Object.class, Object.class, Object.class, Object.class, Object.class,
                            Object.class));
            exercise.invokeWithArguments(
                    commodity,
                    available,
                    statConstructor.newInstance(0f),
                    statConstructor.newInstance(0f),
                    statConstructor.newInstance(0f),
                    commoditySpecType.getConstructor(float.class).newInstance(1f));
            assertEquals(8L, CommodityEventModMemoRuntime.telemetry().get("delegated"));
            assertEquals(8L, CommodityEventModMemoRuntime.telemetry().get("hits"));
            assertEquals(0L,
                    CommodityEventModMemoRuntime.telemetry().get("fastValidationUnavailable"));
        }
    }

    @Test
    void missingDirtyAccessorDisablesMemoAndContinuesWithVanilla() throws Throwable {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(CommodityEventModMemoPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        System.setProperty(CommodityEventModMemoRuntime.ENABLED_PROPERTY, "true");
        byte[] transformed = CommodityEventModMemoPlan.transform(
                ClassSignature.parse(original), original);
        assertNotNull(transformed);
        byte[] executable = withTestExercise(transformed);
        ListWithArchive classpath = gameClasspath(archive);

        // Deliberately omit MutableStatDirtyAccessorPlan's transformed class. The first call authors
        // and records vanilla state. The second call sees the missing fast-path accessor, catches
        // LinkageError, permanently disables the memo, and delegates through vanilla again.
        try (InstalledLoader loader = new InstalledLoader(
                classpath.urls(), Map.of(
                        CommodityEventModMemoPlan.TARGET_CLASS.replace('/', '.'), executable,
                        "com.fs.starfarer.loading.return", commodityStub()))) {
            Class<?> commodityType = loader.loadAndResolve(
                    CommodityEventModMemoPlan.TARGET_CLASS.replace('/', '.'));
            Class<?> statType = loader.loadClass(
                    "com.fs.starfarer.api.combat.MutableStatWithTempMods");
            Class<?> commoditySpecType = loader.loadClass("com.fs.starfarer.loading.return");
            Object commodity = unsafe().allocateInstance(commodityType);
            var statConstructor = statType.getConstructor(float.class);
            Object available = statConstructor.newInstance(0f);
            var exercise = MethodHandles.publicLookup().findStatic(
                    commodityType,
                    "preflight$testExercise",
                    MethodType.methodType(void.class,
                            Object.class, Object.class, Object.class, Object.class, Object.class,
                            Object.class));
            exercise.invokeWithArguments(
                    commodity,
                    available,
                    statConstructor.newInstance(0f),
                    statConstructor.newInstance(0f),
                    statConstructor.newInstance(0f),
                    commoditySpecType.getConstructor(float.class).newInstance(1f));
            assertEquals(0L, CommodityEventModMemoRuntime.telemetry().get("hits"));
            assertEquals(2L, CommodityEventModMemoRuntime.telemetry().get("delegated"));
            assertEquals(1L,
                    CommodityEventModMemoRuntime.telemetry().get("fastValidationUnavailable"));
            assertEquals(false, CommodityEventModMemoRuntime.telemetry().get("enabled"));
        }
    }

    /** Adds an installed-test-only entry point without reflecting over invalid obfuscated fields. */
    private static byte[] withTestExercise(byte[] transformed) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "preflight$testExercise",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                null,
                null);
        initialize(method, 1, "available");
        initialize(method, 2, "tradeMod");
        initialize(method, 3, "tradeModPlus");
        initialize(method, 4, "tradeModMinus");
        invokeReapply(method);
        invokeReapply(method);
        // Any non-eMod change to available alters the fingerprint and must run vanilla once.
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,
                "com/fs/starfarer/api/combat/MutableStatWithTempMods"));
        method.instructions.add(new LdcInsnNode("installed-test"));
        method.instructions.add(new LdcInsnNode(5f));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/api/combat/MutableStatWithTempMods",
                "modifyFlat",
                "(Ljava/lang/String;F)V",
                false));
        invokeReapply(method);
        invokeReapply(method);
        // Exercise the non-zero branch too, including its econ-unit input and a changed quantity.
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new TypeInsnNode(
                Opcodes.CHECKCAST, CommodityEventModMemoPlan.TARGET_CLASS));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        method.instructions.add(new TypeInsnNode(
                Opcodes.CHECKCAST, "com/fs/starfarer/loading/return"));
        method.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                CommodityEventModMemoPlan.TARGET_CLASS,
                "commodity",
                "Lcom/fs/starfarer/loading/return;"));
        modifyFlat(method, 1, "installed-base", 5f);
        modifyFlat(method, 2, "installed-trade", 3f);
        invokeReapply(method);
        invokeReapply(method);
        modifyFlat(method, 2, "installed-trade", 4f);
        invokeReapply(method);
        invokeReapply(method);
        // Same-value relabels do not dirty MutableStat, but vanilla would restore this description.
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,
                "com/fs/starfarer/api/combat/MutableStatWithTempMods"));
        method.instructions.add(new LdcInsnNode("eMod"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/api/combat/MutableStat",
                "getFlatStatMod",
                "(Ljava/lang/String;)Lcom/fs/starfarer/api/combat/MutableStat$StatMod;",
                false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 6));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,
                "com/fs/starfarer/api/combat/MutableStatWithTempMods"));
        method.instructions.add(new LdcInsnNode("eMod"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 6));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/api/combat/MutableStat$StatMod",
                "getValue",
                "()F",
                false));
        method.instructions.add(new LdcInsnNode("external-description"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/api/combat/MutableStatWithTempMods",
                "modifyFlat",
                "(Ljava/lang/String;FLjava/lang/String;)V",
                false));
        invokeReapply(method);
        invokeReapply(method);
        // The public backing map permits direct same-key replacement without dirtying the stat.
        // A new, equal-valued StatMod must still invalidate the retained object identity.
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,
                "com/fs/starfarer/api/combat/MutableStat"));
        method.instructions.add(new LdcInsnNode("eMod"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/api/combat/MutableStat",
                "getFlatStatMod",
                "(Ljava/lang/String;)Lcom/fs/starfarer/api/combat/MutableStat$StatMod;",
                false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 6));
        method.instructions.add(new TypeInsnNode(
                Opcodes.NEW, "com/fs/starfarer/api/combat/MutableStat$StatMod"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new LdcInsnNode("eMod"));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "com/fs/starfarer/api/combat/MutableStat$StatModType",
                "FLAT",
                "Lcom/fs/starfarer/api/combat/MutableStat$StatModType;"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 6));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/fs/starfarer/api/combat/MutableStat$StatMod",
                "value",
                "F"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 6));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/fs/starfarer/api/combat/MutableStat$StatMod",
                "desc",
                "Ljava/lang/String;"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "com/fs/starfarer/api/combat/MutableStat$StatMod",
                "<init>",
                "(Ljava/lang/String;Lcom/fs/starfarer/api/combat/MutableStat$StatModType;FLjava/lang/String;)V",
                false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 7));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,
                "com/fs/starfarer/api/combat/MutableStat"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/api/combat/MutableStat",
                "getFlatMods",
                "()Ljava/util/HashMap;",
                false));
        method.instructions.add(new LdcInsnNode("eMod"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/HashMap",
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        method.instructions.add(new InsnNode(Opcodes.POP));
        invokeReapply(method);
        invokeReapply(method);
        // A direct write to MutableStat's public aggregate does not set its dirty bit. The exact
        // value snapshot must still reject the hit just as getModifiedValue would observe it.
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new TypeInsnNode(
                Opcodes.CHECKCAST, "com/fs/starfarer/api/combat/MutableStat"));
        method.instructions.add(new LdcInsnNode(9f));
        method.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                "com/fs/starfarer/api/combat/MutableStat",
                "modified",
                "F"));
        invokeReapply(method);
        invokeReapply(method);
        // Replacing a whole backing stat object also invalidates even when its value is identical.
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new TypeInsnNode(
                Opcodes.CHECKCAST, CommodityEventModMemoPlan.TARGET_CLASS));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new TypeInsnNode(
                Opcodes.CHECKCAST, "com/fs/starfarer/api/combat/MutableStatWithTempMods"));
        method.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                CommodityEventModMemoPlan.TARGET_CLASS,
                "tradeMod",
                "Lcom/fs/starfarer/api/combat/MutableStatWithTempMods;"));
        invokeReapply(method);
        invokeReapply(method);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        owner.methods.add(method);
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static void initialize(MethodNode method, int argument, String field) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new TypeInsnNode(
                Opcodes.CHECKCAST, CommodityEventModMemoPlan.TARGET_CLASS));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, argument));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,
                "com/fs/starfarer/api/combat/MutableStatWithTempMods"));
        method.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                CommodityEventModMemoPlan.TARGET_CLASS,
                field,
                "Lcom/fs/starfarer/api/combat/MutableStatWithTempMods;"));
    }

    private static void invokeReapply(MethodNode method) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new TypeInsnNode(
                Opcodes.CHECKCAST, CommodityEventModMemoPlan.TARGET_CLASS));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                CommodityEventModMemoPlan.TARGET_CLASS,
                CommodityEventModMemoPlan.METHOD,
                CommodityEventModMemoPlan.DESCRIPTOR,
                false));
    }

    private static void modifyFlat(MethodNode method, int argument, String source, float value) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, argument));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,
                "com/fs/starfarer/api/combat/MutableStatWithTempMods"));
        method.instructions.add(new LdcInsnNode(source));
        method.instructions.add(new LdcInsnNode(value));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/api/combat/MutableStatWithTempMods",
                "modifyFlat",
                "(Ljava/lang/String;F)V",
                false));
    }

    private static byte[] commodityStub() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "com/fs/starfarer/loading/return",
                null,
                "java/lang/Object",
                new String[] {"com/fs/starfarer/api/campaign/econ/CommoditySpecAPI"});
        writer.visitField(Opcodes.ACC_PRIVATE, "econUnit", "F", null, null).visitEnd();
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(F)V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        constructor.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, "com/fs/starfarer/loading/return", "econUnit", "F"));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        constructor.accept(writer);
        MethodNode getter = new MethodNode(
                Opcodes.ACC_PUBLIC, "getEconUnit", "()F", null, null);
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, "com/fs/starfarer/loading/return", "econUnit", "F"));
        getter.instructions.add(new InsnNode(Opcodes.FRETURN));
        getter.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static ListWithArchive gameClasspath(Path archive) throws Exception {
        try (var files = Files.list(archive.getParent())) {
            URL[] urls = files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .map(path -> {
                        try {
                            return path.toUri().toURL();
                        } catch (java.net.MalformedURLException error) {
                            throw new IllegalArgumentException(error);
                        }
                    })
                    .toArray(URL[]::new);
            return new ListWithArchive(urls);
        }
    }

    private record ListWithArchive(URL[] urls) {
    }

    private static final class InstalledLoader extends URLClassLoader {
        private final Map<String, byte[]> transformed;

        private InstalledLoader(URL[] classpath, Map<String, byte[]> transformed) {
            super(classpath, CommodityEventModMemoInstalledAdapterIT.class.getClassLoader());
            this.transformed = Map.copyOf(transformed);
        }

        private Class<?> loadAndResolve(String name) throws ClassNotFoundException {
            return loadClass(name, true);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = transformed.get(name);
            if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
            return super.findClass(name);
        }
    }
}

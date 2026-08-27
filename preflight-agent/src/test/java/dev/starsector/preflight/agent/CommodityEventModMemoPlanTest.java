package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

class CommodityEventModMemoPlanTest {
    private static final String RUNTIME =
            CommodityEventModMemoRuntime.class.getName().replace('.', '/');
    private static final String MUTABLE_TEMP =
            "com/fs/starfarer/api/combat/MutableStatWithTempMods";

    @BeforeEach
    @AfterEach
    void reset() {
        System.clearProperty(CommodityEventModMemoRuntime.ENABLED_PROPERTY);
        System.clearProperty(CommodityEventModMemoRuntime.DISABLED_PROPERTY);
        CommodityEventModMemoRuntime.beginSession();
    }

    @Test
    void exactMethodKeepsVanillaAndAddsTransientPerCommodityFingerprint() {
        byte[] transformed = CommodityEventModMemoPlan.transform(signature(), fixture());
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        assertNotNull(method(owner, "preflight$original$reapplyEventMod", "()V"));
        MethodNode wrapper = method(owner, CommodityEventModMemoPlan.METHOD, "()V");
        MethodNode slow = method(owner, "preflight$eventModValidateOrDelegate", "()V");
        assertEquals(1, calls(wrapper, RUNTIME, "enabled"));
        assertEquals(1, calls(wrapper, RUNTIME, "zeroQuantityHit"));
        assertEquals(1, calls(wrapper, RUNTIME, "zeroQuantityEmptyMapHit"));
        assertEquals(0, calls(wrapper, RUNTIME, "hit"));
        assertEquals(1, calls(wrapper, RUNTIME, "delegated"));
        assertEquals(2, calls(wrapper, CommodityEventModMemoPlan.TARGET_CLASS,
                "preflight$original$reapplyEventMod"));
        assertEquals(0, calls(wrapper, CommodityEventModMemoPlan.TARGET_CLASS,
                CommodityEventModMemoPlan.QUANTITY_METHOD));
        assertEquals(0, calls(wrapper, MUTABLE_TEMP, "getModifiedValue"));
        assertEquals(3, calls(wrapper, "com/fs/starfarer/api/combat/MutableStat",
                MutableStatDirtyAccessorPlan.ACCESSOR));
        assertEquals(1, calls(wrapper, "com/fs/starfarer/api/combat/MutableStat",
                MutableStatDirtyAccessorPlan.FLAT_MODS_ACCESSOR));
        assertEquals(1, calls(wrapper, "java/util/LinkedHashMap", "get"));
        assertEquals(1, calls(wrapper, "java/util/LinkedHashMap", "isEmpty"));
        assertEquals(0, calls(wrapper, "java/lang/Math", "max"));
        assertEquals(0, calls(wrapper, "java/lang/Math", "min"));
        assertEquals(1, calls(wrapper, RUNTIME, "fastValidationUnavailable"));
        assertEquals(1, wrapper.tryCatchBlocks.stream()
                .filter(block -> "java/lang/LinkageError".equals(block.type)).count());

        assertTrue((slow.access & Opcodes.ACC_PRIVATE) != 0);
        assertTrue((slow.access & Opcodes.ACC_SYNTHETIC) != 0);
        assertEquals(1, calls(slow, RUNTIME, "hit"));
        assertEquals(2, calls(slow, RUNTIME, "delegated"));
        assertEquals(2, calls(slow, CommodityEventModMemoPlan.TARGET_CLASS,
                "preflight$original$reapplyEventMod"));
        assertEquals(1, calls(slow, CommodityEventModMemoPlan.TARGET_CLASS,
                CommodityEventModMemoPlan.QUANTITY_METHOD));
        assertEquals(1, calls(slow, MUTABLE_TEMP, "getModifiedValue"));
        assertEquals(4, calls(slow, "com/fs/starfarer/api/combat/MutableStat",
                MutableStatDirtyAccessorPlan.ACCESSOR));
        assertEquals(1, calls(slow, "com/fs/starfarer/api/combat/MutableStat",
                MutableStatDirtyAccessorPlan.FLAT_MODS_ACCESSOR));
        assertEquals(1, calls(slow, "java/util/LinkedHashMap", "get"));
        assertEquals(1, calls(slow, RUNTIME, "fastValidationUnavailable"));
        assertEquals(1, slow.tryCatchBlocks.stream()
                .filter(block -> "java/lang/LinkageError".equals(block.type)).count());

        List<FieldNode> memoFields = owner.fields.stream()
                .filter(field -> field.name.startsWith("preflight$eventMod"))
                .toList();
        assertEquals(14, memoFields.size());
        assertTrue(memoFields.stream().allMatch(field -> (field.access & Opcodes.ACC_PRIVATE) != 0));
        assertTrue(memoFields.stream().allMatch(field -> (field.access & Opcodes.ACC_TRANSIENT) != 0));
    }

    @Test
    void wrongHashChangedBodyAndSecondRewriteFailClosed() {
        ClassSignature exact = signature();
        assertNull(CommodityEventModMemoPlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), fixture()));

        byte[] changed = fixtureWithoutSecondUnmodify();
        assertNull(CommodityEventModMemoPlan.transform(exact, changed));

        byte[] once = CommodityEventModMemoPlan.transform(exact, fixture());
        assertNotNull(once);
        assertNull(CommodityEventModMemoPlan.transform(exact, once));
    }

    @Test
    void runtimeGateAndCountersAreExplicitAndResettable() {
        assertFalse(CommodityEventModMemoRuntime.enabled());
        System.setProperty(CommodityEventModMemoRuntime.ENABLED_PROPERTY, "true");
        CommodityEventModMemoRuntime.installed();
        assertTrue(CommodityEventModMemoRuntime.enabled());
        CommodityEventModMemoRuntime.hit();
        CommodityEventModMemoRuntime.hit();
        CommodityEventModMemoRuntime.zeroQuantityHit();
        CommodityEventModMemoRuntime.zeroQuantityEmptyMapHit();
        CommodityEventModMemoRuntime.delegated();
        assertEquals(4L, CommodityEventModMemoRuntime.telemetry().get("hits"));
        assertEquals(2L, CommodityEventModMemoRuntime.telemetry().get("zeroQuantityHits"));
        assertEquals(1L,
                CommodityEventModMemoRuntime.telemetry().get("zeroQuantityEmptyMapHits"));
        assertEquals(1L, CommodityEventModMemoRuntime.telemetry().get("delegated"));
        assertEquals("split-exact-zero-empty-map-fast-path-with-complete-slow-fingerprint",
                CommodityEventModMemoRuntime.telemetry().get("validationStrategy"));
        CommodityEventModMemoRuntime.fastValidationUnavailable();
        assertFalse(CommodityEventModMemoRuntime.enabled());
        assertEquals(1L,
                CommodityEventModMemoRuntime.telemetry().get("fastValidationUnavailable"));
        System.setProperty(CommodityEventModMemoRuntime.DISABLED_PROPERTY, "true");
        CommodityEventModMemoRuntime.beginSession();
        CommodityEventModMemoRuntime.installed();
        assertFalse(CommodityEventModMemoRuntime.enabled());
    }

    @Test
    void ordinaryRunsDoNotWriteDeepProfilingCounters() {
        CommodityEventModMemoRuntime.beginSession(false);
        CommodityEventModMemoRuntime.hit();
        CommodityEventModMemoRuntime.zeroQuantityHit();
        CommodityEventModMemoRuntime.zeroQuantityEmptyMapHit();
        CommodityEventModMemoRuntime.delegated();

        assertEquals(false, CommodityEventModMemoRuntime.telemetry().get("telemetryEnabled"));
        assertEquals(0L, CommodityEventModMemoRuntime.telemetry().get("hits"));
        assertEquals(0L, CommodityEventModMemoRuntime.telemetry().get("zeroQuantityHits"));
        assertEquals(0L,
                CommodityEventModMemoRuntime.telemetry().get("zeroQuantityEmptyMapHits"));
        assertEquals(0L, CommodityEventModMemoRuntime.telemetry().get("delegated"));
    }

    @Test
    void ordinaryWrapperOmitsNoOpHotPathTelemetryCalls() {
        CommodityEventModMemoRuntime.beginSession(false);

        byte[] transformed = CommodityEventModMemoPlan.transform(signature(), fixture());

        assertNotNull(transformed);
        MethodNode wrapper = method(read(transformed), CommodityEventModMemoPlan.METHOD, "()V");
        assertEquals(1, calls(wrapper, RUNTIME, "enabled"));
        assertEquals(0, calls(wrapper, RUNTIME, "hit"));
        assertEquals(0, calls(wrapper, RUNTIME, "zeroQuantityHit"));
        assertEquals(0, calls(wrapper, RUNTIME, "zeroQuantityEmptyMapHit"));
        assertEquals(0, calls(wrapper, RUNTIME, "delegated"));
        assertEquals(1, calls(wrapper, RUNTIME, "fastValidationUnavailable"));
        assertEquals(2, calls(wrapper, CommodityEventModMemoPlan.TARGET_CLASS,
                "preflight$original$reapplyEventMod"));
        assertEquals(0, calls(wrapper, "java/lang/Math", "max"));
        assertEquals(0, calls(wrapper, "java/lang/Math", "min"));
    }

    @Test
    void targetBindsTheExactCoreArchiveAndAppLoader() {
        AdapterTarget target = AdapterTargetRegistry.commodityEventModMemoTarget();
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                "file:/game/Contents/Resources/Java/starfarer_obf.jar",
                "/game/contents/resources/java/starfarer_obf.jar",
                "STARSECTOR_CORE",
                target.sourceSha256(),
                "",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
        assertTrue(target.match(signature(), source).exact());

        AdapterTarget mutable = AdapterTargetRegistry.mutableStatDirtyAccessorTarget();
        AdapterSourceIdentity apiSource = new AdapterSourceIdentity(
                "file:/game/Contents/Resources/Java/starfarer.api.jar",
                "/game/contents/resources/java/starfarer.api.jar",
                "STARSECTOR_CORE",
                mutable.sourceSha256(),
                "",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
        assertTrue(mutable.match(new ClassSignature(
                MutableStatDirtyAccessorPlan.TARGET_CLASS,
                MutableStatDirtyAccessorPlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        MutableStatDirtyAccessorPlan.VALUE_METHOD,
                        MutableStatDirtyAccessorPlan.VALUE_DESCRIPTOR,
                        Opcodes.ACC_PUBLIC))), apiSource).exact());
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                CommodityEventModMemoPlan.TARGET_CLASS,
                CommodityEventModMemoPlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(
                        new ClassSignature.Method(CommodityEventModMemoPlan.METHOD, "()V",
                                Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method(CommodityEventModMemoPlan.QUANTITY_METHOD, "()F",
                                Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method(CommodityEventModMemoPlan.MOD_VALUE_METHOD, "(F)F",
                                Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method(CommodityEventModMemoPlan.AVAILABLE_METHOD,
                                CommodityEventModMemoPlan.AVAILABLE_DESCRIPTOR, Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method(CommodityEventModMemoPlan.COMMODITY_METHOD,
                                CommodityEventModMemoPlan.COMMODITY_DESCRIPTOR, Opcodes.ACC_PUBLIC)));
    }

    private static byte[] fixture() {
        return fixture(true);
    }

    private static byte[] fixtureWithoutSecondUnmodify() {
        return fixture(false);
    }

    private static byte[] fixture(boolean secondUnmodify) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CommodityEventModMemoPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        constructor(writer);
        valueMethod(writer, CommodityEventModMemoPlan.QUANTITY_METHOD, "()F");
        valueMethod(writer, CommodityEventModMemoPlan.MOD_VALUE_METHOD, "(F)F");
        objectMethod(writer, CommodityEventModMemoPlan.AVAILABLE_METHOD,
                CommodityEventModMemoPlan.AVAILABLE_DESCRIPTOR);
        objectMethod(writer, CommodityEventModMemoPlan.COMMODITY_METHOD,
                CommodityEventModMemoPlan.COMMODITY_DESCRIPTOR);

        MethodNode original = new MethodNode(Opcodes.ACC_PUBLIC,
                CommodityEventModMemoPlan.METHOD, "()V", null, null);
        original.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        original.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                CommodityEventModMemoPlan.TARGET_CLASS,
                CommodityEventModMemoPlan.QUANTITY_METHOD, "()F", false));
        original.instructions.add(new InsnNode(Opcodes.POP));
        original.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        original.instructions.add(new InsnNode(Opcodes.FCONST_1));
        original.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                CommodityEventModMemoPlan.TARGET_CLASS,
                CommodityEventModMemoPlan.MOD_VALUE_METHOD, "(F)F", false));
        original.instructions.add(new InsnNode(Opcodes.POP));
        unmodify(original);
        if (secondUnmodify) unmodify(original);
        original.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        original.instructions.add(new LdcInsnNode("eMod"));
        original.instructions.add(new InsnNode(Opcodes.FCONST_1));
        original.instructions.add(new LdcInsnNode("Recent trade/events"));
        original.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MUTABLE_TEMP,
                "modifyFlat", "(Ljava/lang/String;FLjava/lang/String;)V", false));
        original.instructions.add(new InsnNode(Opcodes.RETURN));
        original.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void unmodify(MethodNode method) {
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new LdcInsnNode("eMod"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MUTABLE_TEMP,
                "unmodifyFlat", "(Ljava/lang/String;)V", false));
    }

    private static void constructor(ClassWriter writer) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.accept(writer);
    }

    private static void valueMethod(ClassWriter writer, String name, String descriptor) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.instructions.add(new InsnNode(Opcodes.FCONST_0));
        method.instructions.add(new InsnNode(Opcodes.FRETURN));
        method.accept(writer);
    }

    private static void objectMethod(ClassWriter writer, String name, String descriptor) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.accept(writer);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
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
}

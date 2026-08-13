package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class IndustryDemandSupplyMemoPlanTest {
    private static final String RUNTIME =
            IndustryDemandSupplyMemoRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void reset() {
        IndustryDemandSupplyMemoRuntime.beginSession();
    }

    @Test
    void exactSettingsPairGetsOneRecordAndOneLookup() {
        byte[] original = settingsFixture();
        byte[] transformed = IndustryDemandSupplyMemoPlan.transform(
                signature(original, IndustryDemandSupplyMemoPlan.SETTINGS_SHA256), original);

        assertNotNull(transformed);
        assertEquals(1, runtimeCalls(transformed, "record"));
        assertEquals(1, runtimeCalls(transformed, "lookup"));
        assertTrue((boolean) IndustryDemandSupplyMemoRuntime.telemetry().get("settingsInstalled"));
        assertNull(IndustryDemandSupplyMemoPlan.transform(
                signature(original, "0".repeat(64)), original));
    }

    @Test
    void exactCodexScopeGetsEntryAndExit() {
        byte[] original = codexFixture();
        byte[] transformed = IndustryDemandSupplyMemoPlan.transform(
                signature(original, IndustryDemandSupplyMemoPlan.CODEX_SHA256), original);

        assertNotNull(transformed);
        assertEquals(1, runtimeCalls(transformed, "enterCodex"));
        assertEquals(2, runtimeCalls(transformed, "exitCodex"));
        assertEquals(1, tryCatchBlocks(transformed, IndustryDemandSupplyMemoPlan.LINK_METHOD));
        assertTrue((boolean) IndustryDemandSupplyMemoRuntime.telemetry().get("codexInstalled"));
    }

    @Test
    void runtimeIsScopedOneShotAndIdentityPreserving() {
        Object industry = new Object();
        assertNull(IndustryDemandSupplyMemoRuntime.lookup("industry"));

        IndustryDemandSupplyMemoRuntime.enterCodex();
        IndustryDemandSupplyMemoRuntime.record("industry", industry);
        assertSame(industry, IndustryDemandSupplyMemoRuntime.lookup("industry"));
        assertNull(IndustryDemandSupplyMemoRuntime.lookup("industry"));
        IndustryDemandSupplyMemoRuntime.record("leftover", industry);
        IndustryDemandSupplyMemoRuntime.exitCodex();
        assertNull(IndustryDemandSupplyMemoRuntime.lookup("leftover"));

        var telemetry = IndustryDemandSupplyMemoRuntime.telemetry();
        assertEquals(1L, telemetry.get("scopes"));
        assertEquals(2L, telemetry.get("records"));
        assertEquals(2L, telemetry.get("lookups"));
        assertEquals(1L, telemetry.get("hits"));
        assertEquals(1L, telemetry.get("misses"));
        assertFalse((boolean) telemetry.get("active"));
        assertEquals(0, telemetry.get("remaining"));
    }

    @Test
    void exactTargetsPinBothCoreArchives() {
        AdapterTarget settings = AdapterTargetRegistry.industryDemandSupplySettingsTarget();
        AdapterTarget codex = AdapterTargetRegistry.industryDemandSupplyCodexTarget();
        assertEquals("contents/resources/java/starfarer_obf.jar", settings.sourceSuffix());
        assertEquals("contents/resources/java/starfarer.api.jar", codex.sourceSuffix());
        assertTrue(AdapterTransformationRegistry.hasPlan(settings.planId()));
        assertTrue(AdapterTransformationRegistry.hasPlan(codex.planId()));
    }

    private static ClassSignature signature(byte[] bytes, String sha256) {
        try {
            ClassSignature parsed = ClassSignature.parse(bytes);
            return new ClassSignature(parsed.internalName(), sha256, parsed.majorVersion(),
                    parsed.access(), parsed.methods());
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }

    private static byte[] settingsFixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                IndustryDemandSupplyMemoPlan.SETTINGS_CLASS, null, "java/lang/Object", null);
        MethodVisitor spec = writer.visitMethod(Opcodes.ACC_PUBLIC, "getIndustrySpec",
                "(Ljava/lang/String;)Ljava/lang/Object;", null, null);
        spec.visitCode();
        spec.visitInsn(Opcodes.ACONST_NULL);
        spec.visitInsn(Opcodes.ARETURN);
        spec.visitMaxs(0, 0);
        spec.visitEnd();
        query(writer, "getIndustryDemand", 7, false);
        query(writer, "getIndustrySupply", 8, true);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void query(ClassWriter writer, String name, int resultLocal, boolean supply) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name,
                IndustryDemandSupplyMemoPlan.QUERY_DESCRIPTOR, null, null);
        method.visitCode();
        if (supply) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    IndustryDemandSupplyMemoPlan.SETTINGS_CLASS, "getIndustrySpec",
                    "(Ljava/lang/String;)Ljava/lang/Object;", false);
            method.visitVarInsn(Opcodes.ASTORE, 2);
        }
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitVarInsn(Opcodes.ASTORE, 5);
        method.visitVarInsn(Opcodes.ALOAD, 5);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/Industry", "apply", "()V", true);
        if (supply) {
            method.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(
                    Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitTypeInsn(Opcodes.NEW, "java/util/LinkedHashSet");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/util/LinkedHashSet", "<init>", "()V", false);
        method.visitVarInsn(Opcodes.ASTORE, resultLocal);
        method.visitVarInsn(Opcodes.ALOAD, resultLocal);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static byte[] codexFixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                IndustryDemandSupplyMemoPlan.CODEX_CLASS, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                IndustryDemandSupplyMemoPlan.LINK_METHOD, "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
        method.visitInsn(Opcodes.POP2);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int runtimeCalls(byte[] bytes, String name) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int count = 0;
        for (org.objectweb.asm.tree.MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && RUNTIME.equals(call.owner) && name.equals(call.name)) count++;
            }
        }
        return count;
    }

    private static int tryCatchBlocks(byte[] bytes, String methodName) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> methodName.equals(method.name))
                .mapToInt(method -> method.tryCatchBlocks.size())
                .sum();
    }
}

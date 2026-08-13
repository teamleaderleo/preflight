package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Opt-in structural verification against the exact installed Codex implementation pair. */
class CodexLazyFleetMemberInstalledAdapterIT {
    private static final String RUNTIME =
            CodexLazyFleetMemberRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void reset() {
        CodexLazyFleetMemberRuntime.beginSession();
    }

    @Test
    void installedCodexPairDefersPopulationAndMaterializesThroughEntryAccessor() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path obfuscated = Path.of(configured).toAbsolutePath().normalize();
        Path api = obfuscated.resolveSibling("starfarer.api.jar");
        Assumptions.assumeTrue(Files.isRegularFile(obfuscated));
        Assumptions.assumeTrue(Files.isRegularFile(api));

        byte[] codex = classBytes(api, CodexLazyFleetMemberPlan.CODEX_CLASS);
        ClassSignature codexSignature = ClassSignature.parse(codex);
        assertEquals(CodexLazyFleetMemberPlan.CODEX_SHA256, codexSignature.sha256());
        byte[] memoized = IndustryDemandSupplyMemoPlan.transform(codexSignature, codex);
        assertNotNull(memoized);
        byte[] transformedCodex = CodexLazyFleetMemberPlan.transform(codexSignature, memoized);
        assertNotNull(transformedCodex);
        assertEquals(2, runtimeCalls(transformedCodex, "defer"));
        assertEquals(1, methodCount(transformedCodex, "preflight$addLazyModuleEntry"));

        byte[] entry = classBytes(api, CodexLazyFleetMemberPlan.ENTRY_CLASS);
        ClassSignature entrySignature = ClassSignature.parse(entry);
        assertEquals(CodexLazyFleetMemberPlan.ENTRY_SHA256, entrySignature.sha256());
        byte[] transformedEntry = CodexLazyFleetMemberPlan.transform(entrySignature, entry);
        assertNotNull(transformedEntry);
        assertEquals(1, runtimeCalls(transformedEntry, "materialized"));

        // Both outputs must remain parseable with expanded frames after the control-flow edits.
        new ClassReader(transformedCodex).accept(
                new ClassNode(Opcodes.ASM9), ClassReader.EXPAND_FRAMES);
        new ClassReader(transformedEntry).accept(
                new ClassNode(Opcodes.ASM9), ClassReader.EXPAND_FRAMES);
        var telemetry = CodexLazyFleetMemberRuntime.telemetry();
        assertTrue((boolean) telemetry.get("codexInstalled"));
        assertTrue((boolean) telemetry.get("entryAccessorInstalled"));
        assertTrue((boolean) telemetry.get("active"));
    }

    @Test
    void entryTargetPinsTheReviewedApiArchive() {
        AdapterTarget target = AdapterTargetRegistry.codexLazyFleetMemberEntryTarget();
        assertEquals(CodexLazyFleetMemberPlan.ENTRY_CLASS, target.internalClassName());
        assertEquals(CodexLazyFleetMemberPlan.ENTRY_SHA256, target.sha256());
        assertEquals("contents/resources/java/starfarer.api.jar", target.sourceSuffix());
        assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
    }

    private static byte[] classBytes(Path archive, String className) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(className + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static int runtimeCalls(byte[] bytes, String name) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int count = 0;
        for (var method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && RUNTIME.equals(call.owner) && name.equals(call.name)) count++;
            }
        }
        return count;
    }

    private static int methodCount(byte[] bytes, String name) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return (int) owner.methods.stream().filter(method -> name.equals(method.name)).count();
    }
}

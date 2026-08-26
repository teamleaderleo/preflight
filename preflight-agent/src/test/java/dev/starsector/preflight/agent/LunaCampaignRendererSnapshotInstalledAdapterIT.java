package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

/** Opt-in transform check against the exact installed LunaLib campaign renderer pair. */
class LunaCampaignRendererSnapshotInstalledAdapterIT {
    @AfterEach
    void reset() {
        LunaCampaignRendererSnapshotRuntime.reset();
    }

    @Test
    void installedRendererEliminatesTheDeadCopyAndCachesOnlyPrivateEntitySnapshots()
            throws Exception {
        String configured = System.getProperty("preflight.lunalib.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.lunalib.jar=<LunaLib.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] scriptOriginal = classBytes(
                archive, LunaCampaignRendererSnapshotPlan.SCRIPT_CLASS);
        ClassSignature scriptSignature = ClassSignature.parse(scriptOriginal);
        assertEquals(LunaCampaignRendererSnapshotPlan.SCRIPT_SHA256,
                scriptSignature.sha256());
        byte[] scriptTransformed = LunaCampaignRendererSnapshotPlan.transform(
                scriptSignature, scriptOriginal);
        assertNotNull(scriptTransformed);
        ClassNode script = read(scriptTransformed);
        verify(script);
        assertEquals(0, calls(method(script, LunaCampaignRendererSnapshotPlan.ADVANCE,
                LunaCampaignRendererSnapshotPlan.ADVANCE_DESCRIPTOR),
                LunaCampaignRendererSnapshotPlan.SCRIPT_CLASS,
                LunaCampaignRendererSnapshotPlan.GET_RENDERERS));
        assertEquals(2, calls(method(script, LunaCampaignRendererSnapshotPlan.GET_RENDERERS,
                LunaCampaignRendererSnapshotPlan.GET_RENDERERS_DESCRIPTOR),
                "java/util/ArrayList", "addAll"),
                "the public fresh-mutable-list contract remains unchanged");
        assertNull(LunaCampaignRendererSnapshotPlan.transform(
                scriptSignature, scriptTransformed));

        byte[] entityOriginal = classBytes(
                archive, LunaCampaignRendererSnapshotPlan.ENTITY_CLASS);
        ClassSignature entitySignature = ClassSignature.parse(entityOriginal);
        assertEquals(LunaCampaignRendererSnapshotPlan.ENTITY_SHA256,
                entitySignature.sha256());
        ClassNode originalEntity = read(entityOriginal);
        byte[] entityTransformed = LunaCampaignRendererSnapshotPlan.transform(
                entitySignature, entityOriginal);
        assertNotNull(entityTransformed);
        ClassNode entity = read(entityTransformed);
        verify(entity);
        String runtime = LunaCampaignRendererSnapshotRuntime.class.getName().replace('.', '/');
        assertEquals(0, calls(entity,
                LunaCampaignRendererSnapshotPlan.SCRIPT_CLASS,
                LunaCampaignRendererSnapshotPlan.GET_RENDERERS));
        assertEquals(2, calls(entity, runtime, "snapshot"));
        assertEquals(2 + calls(originalEntity,
                LunaCampaignRendererSnapshotPlan.SCRIPT_CLASS,
                LunaCampaignRendererSnapshotPlan.GET_TRANSIENT), calls(entity,
                LunaCampaignRendererSnapshotPlan.SCRIPT_CLASS,
                LunaCampaignRendererSnapshotPlan.GET_TRANSIENT));
        assertEquals(2 + calls(originalEntity,
                LunaCampaignRendererSnapshotPlan.SCRIPT_CLASS,
                LunaCampaignRendererSnapshotPlan.GET_PERSISTENT), calls(entity,
                LunaCampaignRendererSnapshotPlan.SCRIPT_CLASS,
                LunaCampaignRendererSnapshotPlan.GET_PERSISTENT));
        assertNull(LunaCampaignRendererSnapshotPlan.transform(
                entitySignature, entityTransformed));

        assertTrue(AdapterTransformationRegistry.hasPlan(
                LunaCampaignRendererSnapshotRuntime.PLAN_ID));
        assertEquals(true,
                LunaCampaignRendererSnapshotRuntime.telemetry().get("installed"));
    }

    private static byte[] classBytes(Path archive, String internalName) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(internalName + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static void verify(ClassNode owner) throws Exception {
        for (MethodNode method : owner.methods) {
            new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);
        }
    }

    private static int calls(ClassNode owner, String callOwner, String name) {
        return owner.methods.stream().mapToInt(method -> calls(method, callOwner, name)).sum();
    }

    private static int calls(MethodNode method, String callOwner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && callOwner.equals(call.owner) && name.equals(call.name)) {
                result++;
            }
        }
        return result;
    }
}

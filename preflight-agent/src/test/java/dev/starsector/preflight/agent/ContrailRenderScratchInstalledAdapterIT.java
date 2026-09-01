package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

/** Opt-in transform check against the exact installed Starsector contrail renderer. */
class ContrailRenderScratchInstalledAdapterIT {
    private static final String VECTOR = "org/lwjgl/util/vector/Vector2f";

    @AfterEach
    void reset() {
        ContrailRenderScratchRuntime.beginSession();
    }

    @Test
    void installedRendererMovesEightLoopAllocationsToTransientPerEngineScratch()
            throws Exception {
        String configured = System.getProperty("preflight.starfarer.obf.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starfarer.obf.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] originalBytes = classBytes(archive, ContrailRenderScratchPlan.TARGET_CLASS);
        ClassSignature signature = ClassSignature.parse(originalBytes);
        assertEquals(ContrailRenderScratchPlan.ORIGINAL_SHA256, signature.sha256());
        ClassNode original = read(originalBytes);
        MethodNode originalRender = method(
                original,
                ContrailRenderScratchPlan.RENDER_METHOD,
                ContrailRenderScratchPlan.RENDER_DESCRIPTOR);
        assertEquals(ContrailRenderScratchPlan.SCRATCH_COUNT,
                allocations(originalRender, VECTOR));

        byte[] transformedBytes = ContrailRenderScratchPlan.transform(signature, originalBytes);
        assertNotNull(transformedBytes);
        ClassNode transformed = read(transformedBytes);
        verify(transformed);
        MethodNode render = method(
                transformed,
                ContrailRenderScratchPlan.RENDER_METHOD,
                ContrailRenderScratchPlan.RENDER_DESCRIPTOR);
        MethodNode ensure = method(
                transformed, ContrailRenderScratchPlan.ENSURE_METHOD, "()V");

        assertEquals(0, allocations(render, VECTOR));
        assertEquals(ContrailRenderScratchPlan.SCRATCH_COUNT, allocations(ensure, VECTOR));
        assertEquals(1, calls(render, ContrailRenderScratchPlan.TARGET_CLASS,
                ContrailRenderScratchPlan.ENSURE_METHOD));
        assertEquals(3, calls(render, VECTOR, "set"));
        assertEquals(calls(originalRender, VECTOR, "add"), calls(render, VECTOR, "add"));
        assertEquals(calls(originalRender, VECTOR, "sub"), calls(render, VECTOR, "sub"));
        assertEquals(calls(originalRender, "com/fs/util/super", "o00000"),
                calls(render, "com/fs/util/super", "o00000"));

        List<org.objectweb.asm.tree.FieldNode> scratchFields = transformed.fields.stream()
                .filter(field -> field.name.startsWith(ContrailRenderScratchPlan.FIELD_PREFIX))
                .toList();
        assertEquals(ContrailRenderScratchPlan.SCRATCH_COUNT, scratchFields.size());
        for (var field : scratchFields) {
            assertEquals("L" + VECTOR + ";", field.desc);
            assertTrue((field.access & Opcodes.ACC_PRIVATE) != 0);
            assertTrue((field.access & Opcodes.ACC_TRANSIENT) != 0,
                    "save serialization must not include render scratch");
            assertTrue((field.access & Opcodes.ACC_SYNTHETIC) != 0);
        }

        assertNull(ContrailRenderScratchPlan.transform(signature, transformedBytes));
        assertTrue(AdapterTransformationRegistry.hasPlan(
                ContrailRenderScratchRuntime.PLAN_ID));
        assertEquals(true, ContrailRenderScratchRuntime.telemetry().get("installed"));
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

    private static int allocations(MethodNode method, String type) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW
                    && type.equals(allocation.desc)) {
                result++;
            }
        }
        return result;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                result++;
            }
        }
        return result;
    }
}

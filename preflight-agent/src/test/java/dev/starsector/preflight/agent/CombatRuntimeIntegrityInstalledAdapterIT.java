package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact installed CombatEngine check; it never starts the game. */
class CombatRuntimeIntegrityInstalledAdapterIT {
    @BeforeEach
    void enableFrames() {
        FrameTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        CombatRuntimeIntegrityRuntime.beginSession();
        FrameTimeRuntime.reset();
    }

    @Test
    void installedCombatLoopCarriesIntegrityAndFrameObservations() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(CombatRuntimeIntegrityPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(CombatRuntimeIntegrityPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = CombatRuntimeIntegrityPlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(CombatRuntimeIntegrityPlan.transform(
                ClassSignature.parse(transformed), transformed));

        MethodNode method = method(read(transformed));
        assertEquals(1, calls(method,
                CombatRuntimeIntegrityRuntime.class.getName().replace('.', '/'), "observe"));
        assertEquals(1, calls(method,
                FrameTimeRuntime.class.getName().replace('.', '/'), "observeCombat"));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner) {
        return owner.methods.stream()
                .filter(candidate -> CombatRuntimeIntegrityPlan.ADVANCE_METHOD.equals(candidate.name)
                        && CombatRuntimeIntegrityPlan.ADVANCE_DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }
}

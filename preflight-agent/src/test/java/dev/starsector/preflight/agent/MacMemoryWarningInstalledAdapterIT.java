package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Opt-in exact installed-class transform check; it never starts or initializes the game. */
class MacMemoryWarningInstalledAdapterIT {
    @AfterEach
    void reset() {
        MacMemoryWarningRuntime.reset();
    }

    @Test
    void installedWarningUsesThePressureAwarePredicateAndRetainsItsMessage() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(MacMemoryWarningPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(MacMemoryWarningPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = MacMemoryWarningPlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(MacMemoryWarningPlan.transform(ClassSignature.parse(transformed), transformed));

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = owner.methods.stream()
                .filter(candidate -> MacMemoryWarningPlan.METHOD.equals(candidate.name)
                        && MacMemoryWarningPlan.DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
        String runtime = MacMemoryWarningRuntime.class.getName().replace('.', '/');
        assertEquals(1, calls(method, runtime, "shouldWarn"));
        assertEquals(1, constants(method, MacMemoryWarningPlan.WARNING));

        ClassSignature wrong = new ClassSignature(signature.internalName(), "0".repeat(64),
                signature.majorVersion(), signature.access(), signature.methods());
        assertNull(MacMemoryWarningPlan.transform(wrong, original));
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

    private static int constants(MethodNode method, Object expected) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode constant && expected.equals(constant.cst)) {
                result++;
            }
        }
        return result;
    }
}

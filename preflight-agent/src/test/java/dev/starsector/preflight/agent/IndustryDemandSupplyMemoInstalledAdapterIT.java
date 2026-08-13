package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Opt-in structural transforms of the exact installed Codex pair; never starts the game. */
class IndustryDemandSupplyMemoInstalledAdapterIT {
    @Test
    void installedSettingsAndCodexClassesAcceptTheReviewedRewrites() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path obfuscated = Path.of(configured).toAbsolutePath().normalize();
        Path api = obfuscated.resolveSibling("starfarer.api.jar");
        Assumptions.assumeTrue(Files.isRegularFile(obfuscated));
        Assumptions.assumeTrue(Files.isRegularFile(api));

        byte[] settings = classBytes(obfuscated, IndustryDemandSupplyMemoPlan.SETTINGS_CLASS);
        ClassSignature settingsSignature = ClassSignature.parse(settings);
        assertEquals(IndustryDemandSupplyMemoPlan.SETTINGS_SHA256, settingsSignature.sha256());
        byte[] transformedSettings = IndustryDemandSupplyMemoPlan.transform(
                settingsSignature, settings);
        assertNotNull(transformedSettings);
        assertEquals(1, runtimeCalls(transformedSettings, "record"));
        assertEquals(1, runtimeCalls(transformedSettings, "lookup"));

        byte[] codex = classBytes(api, IndustryDemandSupplyMemoPlan.CODEX_CLASS);
        ClassSignature codexSignature = ClassSignature.parse(codex);
        assertEquals(IndustryDemandSupplyMemoPlan.CODEX_SHA256, codexSignature.sha256());
        byte[] transformedCodex = IndustryDemandSupplyMemoPlan.transform(codexSignature, codex);
        assertNotNull(transformedCodex);
        assertEquals(1, runtimeCalls(transformedCodex, "enterCodex"));
        assertEquals(2, runtimeCalls(transformedCodex, "exitCodex"));
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
        String runtime = IndustryDemandSupplyMemoRuntime.class.getName().replace('.', '/');
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int count = 0;
        for (org.objectweb.asm.tree.MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && runtime.equals(call.owner) && name.equals(call.name)) count++;
            }
        }
        return count;
    }
}

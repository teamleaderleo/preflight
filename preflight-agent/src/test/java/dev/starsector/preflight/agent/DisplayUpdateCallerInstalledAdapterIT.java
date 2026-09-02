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
import org.objectweb.asm.tree.MethodNode;

/** Opt-in proof against the exact Windows starfarer_obf.jar; this never starts Starsector. */
class DisplayUpdateCallerInstalledAdapterIT {
    @Test
    void installedWindowsCallerRunsTheProbeOnlyAfterDisplayReturns() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<Windows starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));
        System.setProperty(DisplayThreadTextureProbeRuntime.ENABLED_PROPERTY, "on");
        try (JarFile jar = new JarFile(archive.toFile())) {
            byte[] original = bytes(jar, DisplayUpdateCallerPlan.TARGET_CLASS);
            ClassSignature signature = ClassSignature.parse(original);
            assertEquals(DisplayUpdateCallerPlan.ORIGINAL_SHA256, signature.sha256());
            byte[] transformed = DisplayUpdateCallerPlan.transform(signature, original);
            assertNotNull(transformed);
            MethodNode method = method(transformed);
            int update = callIndex(method, "org/lwjgl/opengl/Display", "update");
            int hook = callIndex(method,
                    FrameTimeRuntime.class.getName().replace('.', '/'), "postUpdate");
            assertEquals(update + 1, hook);
        } finally {
            System.clearProperty(DisplayThreadTextureProbeRuntime.ENABLED_PROPERTY);
        }
    }

    private static byte[] bytes(JarFile jar, String internalName) throws Exception {
        var entry = jar.getJarEntry(internalName + ".class");
        assertNotNull(entry);
        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static MethodNode method(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> DisplayUpdateCallerPlan.METHOD.equals(method.name)
                        && DisplayUpdateCallerPlan.DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) return index;
            index++;
        }
        return -1;
    }
}

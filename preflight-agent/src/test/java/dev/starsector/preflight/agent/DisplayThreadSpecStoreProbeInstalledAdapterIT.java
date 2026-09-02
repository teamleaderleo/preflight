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

/** Opt-in structural proof against the exact Windows ResourceLoaderState archive. */
class DisplayThreadSpecStoreProbeInstalledAdapterIT {
    @Test
    void exactWindowsSpecStoreCallIsBracketed() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<Windows starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));
        System.setProperty(DisplayThreadSpecStoreProbeRuntime.ENABLED_PROPERTY, "on");
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(DisplayThreadSpecStoreProbePlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            byte[] original;
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
            ClassSignature signature = ClassSignature.parse(original);
            assertEquals(DisplayThreadSpecStoreProbePlan.ORIGINAL_SHA256, signature.sha256());
            byte[] transformed = AdapterTransformationRegistry.transform(
                    AdapterTargetRegistry.windowsResourcePriorityTarget(), signature, original);
            assertNotNull(transformed);
            MethodNode init = init(transformed);
            String runtime = DisplayThreadSpecStoreProbeRuntime.class.getName().replace('.', '/');
            assertEquals(1, calls(init, runtime, "beforeSpecStore"));
            assertEquals(2, calls(init, runtime, "afterSpecStore"));
            assertEquals(1, init.tryCatchBlocks.stream()
                    .filter(block -> "java/lang/Throwable".equals(block.type)).count());
        } finally {
            System.clearProperty(DisplayThreadSpecStoreProbeRuntime.ENABLED_PROPERTY);
        }
    }

    private static MethodNode init(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> DisplayThreadSpecStoreProbePlan.INIT_METHOD.equals(method.name)
                        && DisplayThreadSpecStoreProbePlan.INIT_DESCRIPTOR.equals(method.desc))
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

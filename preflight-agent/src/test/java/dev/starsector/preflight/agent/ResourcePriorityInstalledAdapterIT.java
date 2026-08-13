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

/** Opt-in exact installed-class transform; it never starts the game. */
class ResourcePriorityInstalledAdapterIT {
    @Test
    void installedResourceLoaderHasExactlyOneReviewedPriorityMove() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(ResourcePriorityPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(ResourcePriorityPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = ResourcePriorityPlan.transform(signature, original);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode init = owner.methods.stream()
                .filter(method -> ResourcePriorityPlan.INIT_METHOD.equals(method.name)
                        && ResourcePriorityPlan.INIT_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
        String runtime = ResourcePriorityRuntime.class.getName().replace('.', '/');
        int calls = 0;
        for (AbstractInsnNode instruction : init.instructions) {
            if (instruction instanceof MethodInsnNode call && runtime.equals(call.owner)
                    && "removeAll".equals(call.name)) {
                calls++;
            }
        }
        assertEquals(1, calls);
    }
}

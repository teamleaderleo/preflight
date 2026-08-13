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

/** Opt-in check against the installed ResourceLoaderState; it never starts the game. */
class FrameTimeStartupCompletionInstalledAdapterIT {
    @BeforeEach
    void enable() {
        FrameTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        FrameTimeRuntime.reset();
    }

    @Test
    void installedResourceLoaderAcceptsExactlyOneCompletionMarker() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(FrameTimeStartupCompletionPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(FrameTimeStartupCompletionPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = FrameTimeStartupCompletionPlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(FrameTimeStartupCompletionPlan.transform(
                ClassSignature.parse(transformed), transformed));

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode init = owner.methods.stream()
                .filter(candidate -> FrameTimeStartupCompletionPlan.INIT_METHOD.equals(candidate.name)
                        && FrameTimeStartupCompletionPlan.INIT_DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
        String runtime = FrameTimeRuntime.class.getName().replace('.', '/');
        int calls = 0;
        for (AbstractInsnNode instruction : init.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && runtime.equals(call.owner)
                    && "markStartupComplete".equals(call.name)) {
                calls++;
            }
        }
        assertEquals(1, calls);
    }
}

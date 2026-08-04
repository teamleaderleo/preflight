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

/** Opt-in exact installed-LWJGL transform check; it never starts the game or an OpenGL context. */
class FrameTimeInstalledAdapterIT {
    @BeforeEach
    void enable() {
        FrameTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        FrameTimeRuntime.reset();
    }

    @Test
    void installedDisplayExposesOneFocusObservationAndOneBoundary() throws Exception {
        String configured = System.getProperty("preflight.starsector.lwjgl.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.lwjgl.jar=<lwjgl.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(FrameTimePlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(FrameTimePlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = FrameTimePlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(FrameTimePlan.transform(ClassSignature.parse(transformed), transformed));

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        String runtime = FrameTimeRuntime.class.getName().replace('.', '/');
        assertEquals(1, calls(method(owner, FrameTimePlan.UPDATE_METHOD,
                FrameTimePlan.UPDATE_DESCRIPTOR), runtime, "boundary"));
        assertEquals(1, calls(method(owner, FrameTimePlan.ACTIVE_METHOD,
                FrameTimePlan.ACTIVE_DESCRIPTOR), runtime, "observeActive"));

        ClassSignature wrong = new ClassSignature(signature.internalName(), "0".repeat(64),
                signature.majorVersion(), signature.access(), signature.methods());
        assertNull(FrameTimePlan.transform(wrong, original));
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name)
                        && descriptor.equals(candidate.desc))
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

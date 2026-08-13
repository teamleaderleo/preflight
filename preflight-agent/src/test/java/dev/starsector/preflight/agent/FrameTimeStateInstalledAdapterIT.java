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

/** Opt-in exact installed-core transform check; it never starts the game. */
class FrameTimeStateInstalledAdapterIT {
    private static final String RUNTIME = FrameTimeRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void enable() {
        FrameTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        FrameTimeRuntime.reset();
    }

    @Test
    void installedCampaignLoopAcceptsExactlyOneObserver() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        try (JarFile jar = new JarFile(archive.toFile())) {
            assertInstalled(jar, FrameTimeStatePlan.CAMPAIGN_CLASS,
                    FrameTimeStatePlan.CAMPAIGN_SHA256, "observeCampaign");
        }
    }

    private static void assertInstalled(JarFile jar, String className, String expectedHash,
            String observer) throws Exception {
        var entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry);
        byte[] original;
        try (var input = jar.getInputStream(entry)) {
            original = input.readAllBytes();
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(expectedHash, signature.sha256());
        byte[] transformed = FrameTimeStatePlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(FrameTimeStatePlan.transform(ClassSignature.parse(transformed), transformed));

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        assertEquals(1, calls(method(owner), RUNTIME, observer));
    }

    private static MethodNode method(ClassNode owner) {
        return owner.methods.stream()
                .filter(candidate -> FrameTimeStatePlan.ADVANCE_METHOD.equals(candidate.name)
                        && FrameTimeStatePlan.ADVANCE_DESCRIPTOR.equals(candidate.desc))
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

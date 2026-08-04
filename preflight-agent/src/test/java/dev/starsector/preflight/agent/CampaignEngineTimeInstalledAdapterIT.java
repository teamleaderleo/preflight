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

/** Exact installed campaign-engine transform check; it never starts the game. */
class CampaignEngineTimeInstalledAdapterIT {
    @BeforeEach
    void enable() {
        CampaignEngineTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        CampaignEngineTimeRuntime.reset();
    }

    @Test
    void installedCampaignEngineMatchesEveryReviewedCallSite() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(CampaignEngineTimePlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(CampaignEngineTimePlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = CampaignEngineTimePlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(CampaignEngineTimePlan.transform(ClassSignature.parse(transformed), transformed));

        MethodNode method = advance(transformed);
        String runtime = CampaignEngineTimeRuntime.class.getName().replace('.', '/');
        assertEquals(19, calls(method, runtime, "enter"));
        assertEquals(38, calls(method, runtime, "exit"));
        assertEquals(2, calls(method, runtime, "enterScript"));
        assertEquals(4, calls(method, runtime, "exitScript"));
    }

    private static MethodNode advance(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> CampaignEngineTimePlan.METHOD.equals(method.name)
                        && CampaignEngineTimePlan.DESCRIPTOR.equals(method.desc))
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

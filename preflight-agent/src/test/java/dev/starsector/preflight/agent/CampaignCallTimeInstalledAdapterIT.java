package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

/** Opt-in exact transform check against installed vanilla and Nexerelin archives; never starts the game. */
class CampaignCallTimeInstalledAdapterIT {
    @BeforeEach
    void enable() {
        CampaignCallTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        CampaignCallTimeRuntime.reset();
    }

    @Test
    void allInstalledCampaignTimingSeamsTransformExactlyOnce() throws Exception {
        String apiConfigured = System.getProperty("preflight.starsector.api.jar", "").trim();
        String nexConfigured = System.getProperty("preflight.nexerelin.jar", "").trim();
        Assumptions.assumeTrue(!apiConfigured.isEmpty() && !nexConfigured.isEmpty(),
                "set -Dpreflight.starsector.api.jar=<starfarer.api.jar> "
                        + "-Dpreflight.nexerelin.jar=<ExerelinCore.jar>");
        Path api = Path.of(apiConfigured).toAbsolutePath().normalize();
        Path nex = Path.of(nexConfigured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(api) && Files.isRegularFile(nex));

        String runtime = CampaignCallTimeRuntime.class.getName().replace('.', '/');
        for (CampaignCallTimePlan.Probe probe : CampaignCallTimePlan.probes()) {
            byte[] original = classBytes(probe.className().startsWith("exerelin/") ? nex : api,
                    probe.className());
            ClassSignature signature = ClassSignature.parse(original);
            assertEquals(probe.sha256(), signature.sha256());
            byte[] transformed = CampaignCallTimePlan.transform(signature, original);
            assertNotNull(transformed);
            assertNull(CampaignCallTimePlan.transform(ClassSignature.parse(transformed), transformed));

            ClassNode owner = new ClassNode(Opcodes.ASM9);
            new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
            MethodNode method = owner.methods.stream()
                    .filter(candidate -> probe.method().equals(candidate.name)
                            && probe.descriptor().equals(candidate.desc))
                    .findFirst().orElseThrow();
            assertEquals(1, calls(method, runtime, "enter"));
            assertEquals(returnCount(method) + 1, calls(method, runtime, "exit"));
        }
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

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }

    private static int returnCount(MethodNode method) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) result++;
        }
        return result;
    }
}

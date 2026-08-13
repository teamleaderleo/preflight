package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Opt-in transforms of the exact installed IndEvo classes; never starts the game. */
class IndEvoSyntheticMarketInstalledAdapterIT {
    @BeforeEach
    void reset() {
        IndEvoSyntheticMarketRuntime.beginSession();
    }

    @Test
    void installedIndustriesAcceptOnlyTheReviewedNullWorldGuards() throws Exception {
        String configured = System.getProperty("preflight.indevo.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(), "set -Dpreflight.indevo.jar=<IndEvo.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        List<Expected> expected = List.of(
                new Expected(IndEvoSyntheticMarketPlan.RELAY_CLASS,
                        IndEvoSyntheticMarketPlan.RELAY_SHA256, 2),
                new Expected(IndEvoSyntheticMarketPlan.ARTILLERY_CLASS,
                        IndEvoSyntheticMarketPlan.ARTILLERY_SHA256, 2),
                new Expected(IndEvoSyntheticMarketPlan.WONDER_CLASS,
                        IndEvoSyntheticMarketPlan.WONDER_SHA256, 1));
        for (Expected item : expected) {
            byte[] original = classBytes(archive, item.className());
            ClassSignature signature = ClassSignature.parse(original);
            assertEquals(item.sha256(), signature.sha256());
            byte[] transformed = IndEvoSyntheticMarketPlan.transform(signature, original);
            assertNotNull(transformed);
            assertEquals(item.runtimeCalls(), runtimeCalls(transformed));
        }
        assertEquals(3L,
                IndEvoSyntheticMarketRuntime.telemetry().get("installedClasses"));
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

    private static int runtimeCalls(byte[] bytes) {
        String runtime = IndEvoSyntheticMarketRuntime.class.getName().replace('.', '/');
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int count = 0;
        for (org.objectweb.asm.tree.MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && runtime.equals(call.owner)) count++;
            }
        }
        return count;
    }

    private record Expected(String className, String sha256, int runtimeCalls) {
    }
}

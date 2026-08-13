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

/** Opt-in exact installed SpecStore transform; it never starts the game. */
class SpecStoreQuoteNormalizationInstalledAdapterIT {
    @Test
    void installedSpecStoreHasExactlyTheReviewedQuoteCalls() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(SpecStoreQuoteNormalizationPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals("1947fee1403e93b27ae89b4995fcfde5f65b8ffe1ef3f564b4daaed3a5e69821",
                signature.sha256());
        byte[] transformed = SpecStoreQuoteNormalizationPlan.transform(signature, original);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        var method = owner.methods.stream()
                .filter(candidate -> SpecStoreQuoteNormalizationPlan.METHOD.equals(candidate.name)
                        && SpecStoreQuoteNormalizationPlan.DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
        String runtime = RulesRegexCacheRuntime.class.getName().replace('.', '/');
        int calls = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && runtime.equals(call.owner)
                    && "replaceAll".equals(call.name)) {
                calls++;
            }
        }
        assertEquals(2, calls);
    }
}

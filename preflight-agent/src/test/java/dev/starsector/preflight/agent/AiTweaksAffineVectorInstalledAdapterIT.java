package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

/** Opt-in structural transform of AI Tweaks' exact installed classes; never starts Starsector. */
class AiTweaksAffineVectorInstalledAdapterIT {
    @AfterEach
    void clearProperty() {
        System.clearProperty(AiTweaksAffineVectorPlan.ENABLED_PROPERTY);
    }

    @Test
    void installedTargetsReplaceOnlyTheThreeReviewedAffinePairs() throws Exception {
        String configured = System.getProperty("preflight.aitweaks.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.aitweaks.jar=<aitweaks-core.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));
        System.setProperty(AiTweaksAffineVectorPlan.ENABLED_PROPERTY, "true");

        try (JarFile jar = new JarFile(archive.toFile())) {
            for (var target : AiTweaksAffineVectorPlan.TARGETS) {
                var entry = jar.getJarEntry(target.internalName() + ".class");
                assertNotNull(entry);
                byte[] original;
                try (var input = jar.getInputStream(entry)) {
                    original = input.readAllBytes();
                }
                ClassSignature signature = ClassSignature.parse(original);
                assertEquals(target.sha256(), signature.sha256());
                byte[] transformed = AiTweaksAffineVectorPlan.transform(signature, original);
                assertNotNull(transformed, target.internalName());

                ClassNode before = parse(original);
                ClassNode after = parse(transformed);
                var beforeMethod = before.methods.stream()
                        .filter(method -> target.method().equals(method.name)
                                && target.descriptor().equals(method.desc))
                        .findFirst().orElseThrow();
                var afterMethod = after.methods.stream()
                        .filter(method -> target.method().equals(method.name)
                                && target.descriptor().equals(method.desc))
                        .findFirst().orElseThrow();
                assertEquals(1, calls(beforeMethod.instructions,
                        "com/genir/aitweaks/core/extensions/Vector2fKt", "times",
                        AiTweaksAffineVectorPlan.TIMES_DESCRIPTOR));
                assertEquals(1, calls(beforeMethod.instructions,
                        "com/genir/aitweaks/core/extensions/Vector2fKt", "plus",
                        AiTweaksAffineVectorPlan.PLUS_DESCRIPTOR));
                assertEquals(0, calls(afterMethod.instructions,
                        "com/genir/aitweaks/core/extensions/Vector2fKt", "times",
                        AiTweaksAffineVectorPlan.TIMES_DESCRIPTOR));
                assertEquals(0, calls(afterMethod.instructions,
                        "com/genir/aitweaks/core/extensions/Vector2fKt", "plus",
                        AiTweaksAffineVectorPlan.PLUS_DESCRIPTOR));
                assertEquals(1, calls(afterMethod.instructions,
                        target.internalName(),
                        AiTweaksAffineVectorPlan.AFFINE_METHOD,
                        AiTweaksAffineVectorPlan.AFFINE_DESCRIPTOR));
                for (var method : after.methods) {
                    new Analyzer<>(new BasicInterpreter()).analyze(after.name, method);
                }
            }
        }
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static int calls(
            Iterable<AbstractInsnNode> instructions,
            String owner,
            String name,
            String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) count++;
        }
        return count;
    }
}

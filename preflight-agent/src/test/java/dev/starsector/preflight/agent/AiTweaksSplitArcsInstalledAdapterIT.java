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

/** Opt-in structural transform of AI Tweaks' exact installed class; never starts Starsector. */
class AiTweaksSplitArcsInstalledAdapterIT {
    @AfterEach
    void clearProperty() {
        System.clearProperty(AiTweaksSplitArcsPlan.ENABLED_PROPERTY);
    }

    @Test
    void installedSplitArcsUsesBothExactCapacities() throws Exception {
        String configured = System.getProperty("preflight.aitweaks.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.aitweaks.jar=<aitweaks-core.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(AiTweaksSplitArcsPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(AiTweaksSplitArcsPlan.ORIGINAL_SHA256, signature.sha256());
        ClassNode before = new ClassNode(Opcodes.ASM9);
        new ClassReader(original).accept(before, ClassReader.EXPAND_FRAMES);
        var originalMethod = before.methods.stream()
                .filter(candidate -> AiTweaksSplitArcsPlan.METHOD.equals(candidate.name)
                        && AiTweaksSplitArcsPlan.DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
        System.setProperty(AiTweaksSplitArcsPlan.ENABLED_PROPERTY, "true");
        byte[] transformed = AiTweaksSplitArcsPlan.transform(signature, original);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        var method = owner.methods.stream()
                .filter(candidate -> AiTweaksSplitArcsPlan.METHOD.equals(candidate.name)
                        && AiTweaksSplitArcsPlan.DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
        assertEquals(2, constructors(originalMethod.instructions, "()V"));
        assertEquals(0, constructors(method.instructions, "()V"));
        assertEquals(constructors(originalMethod.instructions, "(I)V") + 2,
                constructors(method.instructions, "(I)V"));
        assertEquals(calls(originalMethod.instructions, "java/util/List", "size", "()I") + 2,
                calls(method.instructions, "java/util/List", "size", "()I"));
    }

    private static int constructors(Iterable<AbstractInsnNode> instructions, String descriptor) {
        return calls(instructions, "java/util/ArrayList", "<init>", descriptor);
    }

    private static int calls(
            Iterable<AbstractInsnNode> instructions,
            String owner,
            String name,
            String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) count++;
        }
        return count;
    }
}

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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Opt-in exact installed-class transform check; it never starts or initializes the game. */
class SourceHintIsolationInstalledAdapterIT {
    @AfterEach
    void reset() {
        SourceHintIsolationRuntime.reset();
    }

    @Test
    void installedResolverUsesThreadLocalOneShotHints() throws Exception {
        String configured = System.getProperty("preflight.starsector.common.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.common.jar=<fs.common_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(SourceHintIsolationPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(SourceHintIsolationPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = SourceHintIsolationPlan.transform(signature, original);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        String runtime = SourceHintIsolationRuntime.class.getName().replace('.', '/');
        long setters = owner.methods.stream()
                .filter(method -> SourceHintIsolationPlan.SET_METHOD.equals(method.name)
                        && SourceHintIsolationPlan.SET_DESCRIPTOR.equals(method.desc))
                .flatMap(method -> method.instructions.iterator().hasNext()
                        ? java.util.stream.StreamSupport.stream(
                                java.util.Spliterators.spliteratorUnknownSize(
                                        method.instructions.iterator(), 0), false)
                        : java.util.stream.Stream.empty())
                .filter(instruction -> instruction instanceof MethodInsnNode call
                        && runtime.equals(call.owner) && "set".equals(call.name))
                .count();
        long takes = owner.methods.stream()
                .filter(method -> SourceHintIsolationPlan.RESOLVE_METHOD.equals(method.name)
                        && SourceHintIsolationPlan.RESOLVE_DESCRIPTOR.equals(method.desc))
                .flatMap(method -> java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                method.instructions.iterator(), 0), false))
                .filter(instruction -> instruction instanceof MethodInsnNode call
                        && runtime.equals(call.owner) && "take".equals(call.name))
                .count();
        assertEquals(1, setters);
        assertEquals(1, takes);
    }
}

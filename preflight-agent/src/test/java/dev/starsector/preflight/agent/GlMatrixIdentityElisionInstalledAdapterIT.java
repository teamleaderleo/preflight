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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

/** Structural verification against the installed LWJGL archive; it never starts Starsector. */
final class GlMatrixIdentityElisionInstalledAdapterIT {
    private static final String RUNTIME =
            GlMatrixIdentityElisionRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void enable() {
        System.setProperty(GlMatrixIdentityElisionRuntime.ENABLE_PROPERTY, "true");
        GpuFrameTimeRuntime.beginSession(false);
        GlMatrixIdentityElisionRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        System.clearProperty(GlMatrixIdentityElisionRuntime.ENABLE_PROPERTY);
        GlMatrixIdentityElisionRuntime.reset();
        GpuFrameTimeRuntime.beginSession(false);
    }

    @Test
    void installedLwjglTargetAcceptsExactlyReviewedWrappers() throws Exception {
        String configured = System.getProperty("preflight.lwjgl.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(), "set -Dpreflight.lwjgl.jar=<lwjgl.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(GlMatrixIdentityElisionPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            byte[] original;
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
            ClassSignature signature = ClassSignature.parse(original);
            assertEquals(GlMatrixIdentityElisionPlan.TARGET_SHA256, signature.sha256());
            byte[] transformed = GlMatrixIdentityElisionPlan.transform(signature, original);
            assertNotNull(transformed);
            assertEquals(GlMatrixIdentityElisionPlan.EXPECTED_METHODS, runtimeCalls(transformed));
            assertNull(GlMatrixIdentityElisionPlan.transform(
                    ClassSignature.parse(transformed), transformed));

            ClassNode owner = new ClassNode(Opcodes.ASM9);
            new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
            for (var method : owner.methods) {
                new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);
            }
        }
        assertEquals(1, GlMatrixIdentityElisionRuntime.telemetry().get("installedTargetCount"));
        assertEquals(8, GlMatrixIdentityElisionRuntime.telemetry().get("installedMethodCount"));
    }

    private static int runtimeCalls(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int result = 0;
        for (var method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) {
                    result++;
                }
            }
        }
        return result;
    }
}

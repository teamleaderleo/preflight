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

/** Opt-in structural check against the installed LWJGL archive; it never starts Starsector. */
final class GlCommandCountInstalledAdapterIT {
    private static final String RUNTIME = GlCommandCountRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void enable() {
        System.setProperty(GlCommandCountRuntime.ENABLE_PROPERTY, "true");
        GpuFrameTimeRuntime.beginSession(false);
        GlCommandCountRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        System.clearProperty(GlCommandCountRuntime.ENABLE_PROPERTY);
        GpuFrameTimeRuntime.beginSession(false);
        GlCommandCountRuntime.reset();
    }

    @Test
    void installedLwjglTargetsAcceptExactlyTheReviewedCommandFamilies() throws Exception {
        String configured = System.getProperty("preflight.lwjgl.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.lwjgl.jar=<lwjgl.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        try (JarFile jar = new JarFile(archive.toFile())) {
            for (GlCommandCountPlan.Target target : GlCommandCountPlan.targets()) {
                var entry = jar.getJarEntry(target.internalName() + ".class");
                assertNotNull(entry, target.internalName());
                byte[] original;
                try (var input = jar.getInputStream(entry)) {
                    original = input.readAllBytes();
                }
                ClassSignature signature = ClassSignature.parse(original);
                assertEquals(target.sha256(), signature.sha256(), target.internalName());
                byte[] transformed = GlCommandCountPlan.transform(signature, original);
                assertNotNull(transformed, target.internalName());
                assertEquals(target.expectedMethods(), runtimeCalls(transformed), target.internalName());
                assertNull(GlCommandCountPlan.transform(
                        ClassSignature.parse(transformed), transformed), target.internalName());

                ClassNode owner = new ClassNode(Opcodes.ASM9);
                new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
                for (var method : owner.methods) {
                    new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);
                }
            }
        }
    }

    private static int runtimeCalls(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int result = 0;
        for (var method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && RUNTIME.equals(call.owner) && "record".equals(call.name)) result++;
            }
        }
        return result;
    }
}

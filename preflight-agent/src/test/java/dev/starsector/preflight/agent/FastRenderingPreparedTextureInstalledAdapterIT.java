package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

/** Opt-in structural proof against the installed Fast Rendering archive; it never starts the game. */
final class FastRenderingPreparedTextureInstalledAdapterIT {
    @Test
    void releasedPortArchivesMatchTransformAndCarryExactPixels() throws Exception {
        String configured = System.getProperty("preflight.fastRendering.portDirectory", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(), "set the reviewed port archive directory");
        for (String platform : new String[] {"linux", "mac"}) {
            Path archive = Path.of(configured, platform, "fr.jar").toAbsolutePath();
            byte[] original;
            try (JarFile jar = new JarFile(archive.toFile())) {
                try (var input = jar.getInputStream(jar.getJarEntry(FastRenderingPreparedTexturePlan.PORT_CLASS + ".class"))) {
                    original = input.readAllBytes();
                }
            }
            ClassSignature signature = ClassSignature.parse(original);
            AdapterTarget target = AdapterTargetRegistry.fastRenderingPortPreparedTextureTarget(platform.equals("mac"));
            var source = new AdapterSourceIdentity(archive.toUri().toString(), archive.toString(),
                    "FAST_RENDERING", dev.starsector.preflight.core.Hashes.sha256(archive), "",
                    "jdk/internal/loader/ClassLoaders$AppClassLoader", "app");
            org.junit.jupiter.api.Assertions.assertTrue(target.match(signature, source).exact());
            byte[] transformed = FastRenderingPreparedTexturePlan.transformPort(signature, original);
            assertNotNull(transformed);
            assertNull(FastRenderingPreparedTexturePlan.transformPort(ClassSignature.parse(transformed), transformed));
            ClassNode owner = new ClassNode(Opcodes.ASM9);
            new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
            for (var method : owner.methods) {
                new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);
            }
            // Parent excludes the synthetic test carrier: these fields come from the released JAR.
            try (var loader = new java.net.URLClassLoader(new java.net.URL[] {archive.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                var texture = new dev.starsector.preflight.core.PreparedTexture("1".repeat(64),
                        dev.starsector.preflight.core.PreparedTexture.Transformation.IDENTITY,
                        3, 1, 3, 1, 3, 0x112233ff, 0x445566ff, 0x778899ff,
                        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9});
                Object carrier = FastRenderingPreparedTextureRuntime.createCarrier(loader, texture, true);
                Class<?> type = carrier.getClass();
                assertEquals(loader, type.getClassLoader());
                assertEquals(3, type.getField("imageWidth").getInt(carrier));
                assertEquals(1, type.getField("imageHeight").getInt(carrier));
                assertEquals(3, type.getField("width").getInt(carrier));
                assertEquals(1, type.getField("height").getInt(carrier));
                assertEquals(false, type.getMethod("isDDS").invoke(carrier));
                assertEquals(texture.pixelsView(), type.getField("buffer").get(carrier));
                assertEquals(new java.awt.Color(0x11, 0x22, 0x33), type.getField("mean").get(carrier));
            }
        }
    }

    @Test
    void installedArchiveMatchesAndAcceptsTheReviewedPostDdsSeam() throws Exception {
        String configured = System.getProperty("preflight.fastRendering.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.fastRendering.jar=<fr.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));
        assertEquals(FastRenderingPreparedTexturePlan.SOURCE_SHA256,
                dev.starsector.preflight.core.Hashes.sha256(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(FastRenderingPreparedTexturePlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(FastRenderingPreparedTexturePlan.TARGET_SHA256, signature.sha256());

        byte[] transformed = FastRenderingPreparedTexturePlan.transform(signature, original);
        assertNotNull(transformed);
        assertEquals(1, runtimeCalls(transformed));
        assertNull(FastRenderingPreparedTexturePlan.transform(
                ClassSignature.parse(transformed), transformed));

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        for (var method : owner.methods) {
            new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);
        }
    }

    private static int runtimeCalls(byte[] bytes) {
        String runtime = FastRenderingPreparedTextureRuntime.class.getName().replace('.', '/');
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int count = 0;
        for (var method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && runtime.equals(call.owner) && "load".equals(call.name)) {
                    count++;
                }
            }
        }
        return count;
    }
}

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
import org.objectweb.asm.tree.MethodNode;

/** Opt-in structural proof against the exact Windows TextureLoader archive. */
class TextureSpecStoreOverlapInstalledAdapterIT {
    @Test
    void exactWindowsPreparedLoaderComposesOwnershipHooks() throws Exception {
        String configured = System.getProperty("preflight.starsector.common.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.common.jar=<Windows fs.common_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));
        System.setProperty(DisplayThreadSpecStoreProbeRuntime.CANDIDATE_PROPERTY, "true");
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(TextureSpecStoreOverlapPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            byte[] original;
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
            ClassSignature signature = ClassSignature.parse(original);
            assertEquals(TextureSpecStoreOverlapPlan.ORIGINAL_SHA256, signature.sha256());
            byte[] prepared = TexturePreparedPixelPlan.transform(signature, original);
            assertNotNull(prepared);
            byte[] transformed = TextureSpecStoreOverlapPlan.transform(signature, prepared);
            assertNotNull(transformed);

            ClassNode owner = new ClassNode(Opcodes.ASM9);
            new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
            String runtime = DisplayThreadSpecStoreProbeRuntime.class.getName().replace('.', '/');
            assertEquals(1, calls(method(owner, "<init>", "()V"), runtime,
                    "captureTextureLoader"));
            assertEquals(1, calls(method(owner, TextureSpecStoreOverlapPlan.LOAD_METHOD,
                    TextureSpecStoreOverlapPlan.LOAD_DESCRIPTOR), runtime,
                    "observeTextureRequest"));
        } finally {
            System.clearProperty(DisplayThreadSpecStoreProbeRuntime.CANDIDATE_PROPERTY);
        }
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
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

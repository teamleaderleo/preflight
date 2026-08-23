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

/** Opt-in check of the composed cache wrapper against the installed MagicLib class. */
class MagicLibPaintjobCacheInstalledAdapterIT {
    @AfterEach
    void reset() {
        MagicLibPaintjobCacheRuntime.beginSession();
    }

    @Test
    void exactInstalledCatalogComposesAndRetainsTheLoader() throws Exception {
        String configured = System.getProperty("preflight.magiclib.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.magiclib.jar=<MagicLib.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(MagicLibPaintjobCachePlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(MagicLibPaintjobCachePlan.ORIGINAL_SHA256, signature.sha256());

        byte[] notification = MagicLibPaintjobNotificationPlan.transform(signature, original);
        assertNotNull(notification);
        byte[] optionalJson = MagicLibPaintjobLoadPlan.transform(signature, notification);
        assertNotNull(optionalJson);
        byte[] transformed = MagicLibPaintjobCachePlan.transform(signature, optionalJson);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        assertNotNull(owner.methods.stream()
                .filter(method -> MagicLibPaintjobCacheRuntime.ORIGINAL_METHOD.equals(method.name))
                .findFirst().orElse(null));
        assertEquals(1, owner.methods.stream()
                .filter(method -> MagicLibPaintjobCachePlan.LOAD_METHOD.equals(method.name))
                .flatMap(method -> java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                method.instructions.iterator(), 0), false))
                .filter(instruction -> instruction instanceof MethodInsnNode call
                        && MagicLibPaintjobCacheRuntime.class.getName().replace('.', '/').equals(call.owner)
                        && "replay".equals(call.name))
                .count());
    }
}

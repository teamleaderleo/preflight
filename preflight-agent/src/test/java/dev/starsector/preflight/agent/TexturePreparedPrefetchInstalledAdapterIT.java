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

/** Opt-in structural gate for the exact installed Windows prepared-prefetch worker. */
class TexturePreparedPrefetchInstalledAdapterIT {
    @Test
    void exactWindowsWorkerAcceptsLearnedKaleidoscopeRetention() throws Exception {
        String configured = System.getProperty("preflight.starsector.common.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.common.jar=<Windows fs.common_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(TexturePreparedPrefetchPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals("9e339c5a0edadebdd81b088e0882f5a00b4696b9f5e862a9beec3ff03c439f3e",
                signature.sha256());
        System.setProperty(TexturePreparedPrefetchPlan.WINDOWS_KALEIDOSCOPE_PROPERTY, "true");
        System.setProperty(TexturePreparedPrefetchPlan.WINDOWS_RESOURCE_ORDER_PROPERTY, "true");
        byte[] transformed;
        try {
            transformed = TexturePreparedPrefetchPlan.transform(signature, original);
        } finally {
            System.clearProperty(TexturePreparedPrefetchPlan.WINDOWS_KALEIDOSCOPE_PROPERTY);
            System.clearProperty(TexturePreparedPrefetchPlan.WINDOWS_RESOURCE_ORDER_PROPERTY);
        }
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, 0);
        String runtime = TexturePreparedPixelRuntime.class.getName().replace('.', '/');
        int calls = 0;
        for (var method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && runtime.equals(call.owner)
                        && ("seedLearnedKaleidoscopePrefetches".equals(call.name)
                                || "retainLearnedKaleidoscopePrefetchResults".equals(call.name)
                                || "reorderPreparedPrefetches".equals(call.name))) {
                    calls++;
                }
            }
        }
        assertEquals(3, calls);
    }
}

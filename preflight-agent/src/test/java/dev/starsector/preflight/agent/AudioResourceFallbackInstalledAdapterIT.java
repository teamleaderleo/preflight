package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

/** Exact installed sound-store transform check; it never opens OpenAL or starts the game. */
class AudioResourceFallbackInstalledAdapterIT {
    @AfterEach
    void reset() {
        AudioResourceFallbackRuntime.reset();
    }

    @Test
    void installedStoreReplacesAllThreeRelativeLookups() throws Exception {
        String configured = System.getProperty("preflight.starsector.sound.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.sound.jar=<fs.sound_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(AudioResourceFallbackPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(AudioResourceFallbackPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = AudioResourceFallbackPlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(AudioResourceFallbackPlan.transform(ClassSignature.parse(transformed), transformed));

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        String runtime = AudioResourceFallbackRuntime.class.getName().replace('.', '/');
        assertEquals(3, calls(owner, runtime, "open"));
        assertEquals(0, calls(owner, "java/lang/Class", "getResourceAsStream"));
    }

    private static int calls(ClassNode owner, String callOwner, String name) {
        int result = 0;
        for (var method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && callOwner.equals(call.owner) && name.equals(call.name)) result++;
            }
        }
        return result;
    }
}

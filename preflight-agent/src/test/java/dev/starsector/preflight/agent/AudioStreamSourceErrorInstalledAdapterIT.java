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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Opt-in transform of the exact installed streaming-player class; never starts Starsector. */
class AudioStreamSourceErrorInstalledAdapterIT {
    @Test
    void installedConstructorChecksErrorOnBothSidesOfSourceGeneration() throws Exception {
        String configured = System.getProperty("preflight.starsector.sound.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.sound.jar=<fs.sound_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(AudioStreamSourceErrorPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(AudioStreamSourceErrorPlan.ORIGINAL_SHA256, signature.sha256());
        assertEquals(61, signature.majorVersion());
        org.junit.jupiter.api.Assertions.assertTrue(signature.hasMethod(
                AudioStreamSourceErrorPlan.CONSTRUCTOR,
                AudioStreamSourceErrorPlan.CONSTRUCTOR_DESCRIPTOR));
        byte[] transformed = AudioStreamSourceErrorPlan.transform(signature, original);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        var constructor = owner.methods.stream()
                .filter(method -> "<init>".equals(method.name)
                        && AudioStreamSourceErrorPlan.CONSTRUCTOR_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
        String runtime = AudioStreamSourceErrorRuntime.class.getName().replace('.', '/');
        String transitions = AudioMusicTransitionRuntime.class.getName().replace('.', '/');
        assertEquals(2, calls(constructor, "org/lwjgl/openal/AL10", "alGetError"));
        assertEquals(1, calls(constructor, "org/lwjgl/openal/AL10", "alGenSources"));
        assertEquals(1, calls(constructor, runtime, "beforeGeneration"));
        assertEquals(1, calls(constructor, runtime, "afterGeneration"));
        assertEquals(1, calls(constructor, transitions, "created"));
        assertEquals(4, calls(owner, transitions, "fade"));
        assertEquals(1, calls(owner, transitions, "cleanup"));
    }

    private static int calls(
            org.objectweb.asm.tree.MethodNode method, String owner, String name) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static int calls(ClassNode owner, String targetOwner, String name) {
        int count = 0;
        for (var method : owner.methods) count += calls(method, targetOwner, name);
        return count;
    }
}

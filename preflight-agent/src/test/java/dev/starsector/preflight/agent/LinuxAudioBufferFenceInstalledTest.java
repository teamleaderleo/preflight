package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

class LinuxAudioBufferFenceInstalledTest {
    @Test
    void exactNativeUploadRetainsPcmOnBothExits() throws Exception {
        String configured = System.getProperty("preflight.linux.audio.fixtures", "");
        Assumptions.assumeFalse(configured.isBlank(), "Supply installed Linux sound archive");
        byte[] original;
        try (JarFile jar = new JarFile(Path.of(configured).resolve("fs.sound_obf.jar").toFile())) {
            original = jar.getInputStream(jar.getJarEntry("sound/Object.class")).readAllBytes();
        }
        byte[] result = LinuxAudioBufferFencePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(result);
        assertNull(LinuxAudioBufferFencePlan.transform(ClassSignature.parse(result), result));
        byte[] changed = original.clone();
        changed[changed.length - 1] ^= 1;
        assertNull(LinuxAudioBufferFencePlan.transform(ClassSignature.parse(original), changed));
        ClassNode owner = new ClassNode();
        new ClassReader(result).accept(owner, 0);
        MethodNode helper = owner.methods.stream().filter(m -> m.name.equals(LinuxAudioBufferFencePlan.HELPER))
                .findFirst().orElseThrow();
        new Analyzer<>(new BasicVerifier()).analyze(owner.name, helper);
        assertEquals(1, helper.tryCatchBlocks.size());
        int fences = 0, nativeUploads = 0;
        for (AbstractInsnNode n : helper.instructions) {
            if (n instanceof MethodInsnNode call) {
                if (call.name.equals("reachabilityFence")) fences++;
                if (call.name.equals("alBufferData")) nativeUploads++;
            }
        }
        assertEquals(2, fences);
        assertEquals(1, nativeUploads);
    }
}

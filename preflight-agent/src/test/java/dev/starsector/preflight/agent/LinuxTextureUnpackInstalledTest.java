package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

class LinuxTextureUnpackInstalledTest {
    @Test
    void exactLinuxUploadsUseScopedAlignmentHelpers() throws Exception {
        verifyInstalled("preflight.linux.common.jar", AdapterTargetRegistry.linuxTexturePreparedPixelTarget());
    }

    @Test
    void exactMacUploadsUseScopedAlignmentHelpers() throws Exception {
        verifyInstalled("preflight.mac.common.jar", AdapterTargetRegistry.texturePreparedPixelTarget());
    }

    private void verifyInstalled(String property, AdapterTarget target) throws Exception {
        String configured = System.getProperty(property, "");
        Assumptions.assumeFalse(configured.isBlank(), "Supply installed common archive with " + property);
        byte[] original;
        try (JarFile jar = new JarFile(Path.of(configured).toFile())) {
            original = jar.getInputStream(jar.getJarEntry("com/fs/graphics/TextureLoader.class")).readAllBytes();
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(target.sha256(), signature.sha256());
        byte[] result = TexturePreparedPixelPlan.transform(signature, original);
        assertNotNull(result);
        ClassNode owner = new ClassNode();
        new ClassReader(result).accept(owner, 0);
        int helpers = 0, uploads = 0;
        for (MethodNode method : owner.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(owner.name, method);
            boolean helper = method.name.startsWith(TextureUnpackAlignmentPlan.HELPER_PREFIX);
            if (helper) {
                helpers++;
                assertFalse(method.tryCatchBlocks.isEmpty(), "exceptional exit must restore GL state");
                assertTrue(java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                        .anyMatch(n -> n instanceof MethodInsnNode call && call.name.equals("requiresTightRgbUnpack")));
            }
            for (AbstractInsnNode n : method.instructions) {
                if (n instanceof MethodInsnNode call && call.owner.equals("org/lwjgl/opengl/GL11")
                        && (call.name.equals("glTexImage2D") || call.name.equals("glTexSubImage2D"))) {
                    uploads++;
                    assertTrue(helper, "native upload must pass the scoped guard");
                }
            }
        }
        assertEquals(2, helpers);
        assertEquals(2, uploads);
    }
}

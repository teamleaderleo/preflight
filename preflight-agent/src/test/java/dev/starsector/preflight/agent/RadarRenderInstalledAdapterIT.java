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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Opt-in structural transform of the exact installed radar renderer; never starts the game. */
class RadarRenderInstalledAdapterIT {
    private static final String RUNTIME =
            RadarRenderRuntime.class.getName().replace('.', '/');

    @AfterEach
    void reset() {
        RadarRenderRuntime.beginSession();
    }

    @Test
    void installedRendererCarriesTheCachedTypeBranch() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(RadarRenderPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(RadarRenderPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = RadarRenderPlan.transform(signature, original);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        assertNotNull(owner.fields.stream()
                .filter(field -> "preflight$radarTypes".equals(field.name))
                .findFirst().orElse(null));
        MethodNode render = method(owner, RadarRenderPlan.RENDER_METHOD,
                RadarRenderPlan.RENDER_DESCRIPTOR);
        assertEquals(1, calls(render, RUNTIME, "enabled"));
        assertEquals(1, calls(render, RUNTIME, "hit"));
        assertEquals(1, reads(render, owner.name, "preflight$radarTypes"));
        assertEquals(7, calls(method(owner, "<clinit>", "()V"), "java/util/Set", "add"));
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static int reads(MethodNode method, String owner, String name) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && owner.equals(field.owner) && name.equals(field.name)) count++;
        }
        return count;
    }
}

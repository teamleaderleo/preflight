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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Opt-in structural transform of the installed GraphicsLib class; it does not start the game. */
class GraphicsLibHotSettingsInstalledAdapterIT {
    @Test
    void installedSettingsClassKeepsOriginalsAndEventInvalidation() throws Exception {
        String configured = System.getProperty("preflight.graphicslib.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.graphicslib.jar=<Graphics.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(GraphicsLibHotSettingsPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(GraphicsLibHotSettingsPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = GraphicsLibHotSettingsPlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(GraphicsLibHotSettingsPlan.transform(signature, transformed));

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        String runtime = GraphicsLibHotSettingsRuntime.class.getName().replace('.', '/');
        for (String name : new String[] {
                "fighterBrightnessScale", "weaponFlashHeight", "weaponLightHeight"}) {
            var wrapper = owner.methods.stream()
                    .filter(method -> name.equals(method.name) && "()F".equals(method.desc))
                    .findFirst().orElseThrow();
            assertEquals(0, calls(wrapper, "lunalib/lunaSettings/LunaSettings", "getFloat"));
            assertEquals(1, calls(wrapper, runtime, "hit"));
            assertEquals(1, calls(wrapper, runtime, "miss"));
            assertNotNull(owner.methods.stream()
                    .filter(method -> ("preflight$original$" + name).equals(method.name))
                    .findFirst().orElse(null));
        }
        assertEquals(1, calls(method(owner, "load"), runtime, "invalidated"));
        assertEquals(1, calls(method(owner, "applyChanges"), runtime, "invalidated"));
    }

    private static org.objectweb.asm.tree.MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(method -> name.equals(method.name))
                .findFirst().orElseThrow();
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
}

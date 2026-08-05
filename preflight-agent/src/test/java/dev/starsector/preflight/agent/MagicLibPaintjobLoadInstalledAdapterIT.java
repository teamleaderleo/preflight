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

/** Opt-in transform check against the exact installed MagicLib manager class. */
class MagicLibPaintjobLoadInstalledAdapterIT {
    @AfterEach
    void reset() {
        MagicLibPaintjobLoadRuntime.reset();
    }

    @Test
    void installedManagerKeepsVanillaLoadBehindTheKnownAbsentGuard() throws Exception {
        String configured = System.getProperty("preflight.magiclib.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.magiclib.jar=<MagicLib.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(MagicLibPaintjobLoadPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(MagicLibPaintjobLoadPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = MagicLibPaintjobLoadPlan.transform(signature, original);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        long guarded = calls(owner, MagicLibPaintjobLoadPlan.TARGET_CLASS,
                "preflight$optionalPaintjobJson");
        long vanilla = calls(owner, "com/fs/starfarer/api/SettingsAPI", "loadJSON");
        assertEquals(1, guarded);
        assertEquals(1, vanilla);
    }

    private static long calls(ClassNode owner, String callOwner, String name) {
        return owner.methods.stream()
                .flatMap(method -> java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                method.instructions.iterator(), 0), false))
                .filter(instruction -> instruction instanceof MethodInsnNode call
                        && callOwner.equals(call.owner) && name.equals(call.name))
                .count();
    }
}

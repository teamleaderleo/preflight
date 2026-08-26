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
class MagicLibPaintjobNotificationInstalledAdapterIT {
    @AfterEach
    void reset() {
        MagicLibPaintjobNotificationRuntime.reset();
        MagicLibPaintjobSnapshotRuntime.beginSession();
    }

    @Test
    void installedManagerUsesSnapshotContainsAndMutationInvalidation() throws Exception {
        String configured = System.getProperty("preflight.magiclib.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.magiclib.jar=<MagicLib.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(MagicLibPaintjobNotificationPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(MagicLibPaintjobNotificationPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = MagicLibPaintjobNotificationPlan.transform(signature, original);
        assertNotNull(transformed);
        transformed = MagicLibPaintjobSnapshotPlan.transform(signature, transformed);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        String runtime = MagicLibPaintjobNotificationRuntime.class.getName().replace('.', '/');
        long contains = calls(owner, runtime, "contains");
        long mutations = calls(owner, runtime, "mutated");
        assertEquals(1, contains);
        assertEquals(3, mutations);
        String snapshotRuntime = MagicLibPaintjobSnapshotRuntime.class.getName().replace('.', '/');
        assertEquals(1, calls(owner, snapshotRuntime, "snapshot"));
        assertEquals(11, calls(owner, snapshotRuntime, "mutated"));
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

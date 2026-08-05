package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Opt-in transform check against both exact installed version-checker forks. */
class VersionCheckResponseDedupInstalledAdapterIT {
    @Test
    void installedForksReplaceExactlyTheirTwoReviewedUrlReads() throws Exception {
        check(
                "preflight.lunalib.jar",
                VersionCheckResponseDedupPlan.LUNA_CLASS,
                VersionCheckResponseDedupPlan.LUNA_SHA256);
        check(
                "preflight.nexerelin.jar",
                VersionCheckResponseDedupPlan.NEX_CLASS,
                VersionCheckResponseDedupPlan.NEX_SHA256);
    }

    private static void check(String property, String className, String expectedHash) throws Exception {
        String configured = System.getProperty(property, "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(), "set -D" + property + "=<mod jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(className + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(expectedHash, signature.sha256());
        byte[] transformed = VersionCheckResponseDedupPlan.transform(signature, original);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        String runtime = VersionCheckResponseDedupRuntime.class.getName().replace('.', '/');
        assertEquals(0, calls(owner, "java/net/URL", "openStream"));
        assertEquals(2, calls(owner, runtime, "openStream"));
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

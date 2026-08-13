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
import org.objectweb.asm.tree.MethodNode;

/** Opt-in exact installed-class transform check; it never starts or initializes the game. */
class DeploymentIconCacheInstalledAdapterIT {
    private static final String RUNTIME =
            DeploymentIconCacheRuntime.class.getName().replace('.', '/');

    @AfterEach
    void reset() {
        DeploymentIconCacheRuntime.beginSession();
    }

    @Test
    void installedDeploymentGridHasTheReviewedMutationBoundary() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(DeploymentIconCachePlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(DeploymentIconCachePlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = DeploymentIconCachePlan.transform(signature, original);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        assertNotNull(method(owner, "preflight$original$getIconForMember",
                DeploymentIconCachePlan.LOOKUP_DESCRIPTOR));
        MethodNode wrapper = method(owner, DeploymentIconCachePlan.LOOKUP_METHOD,
                DeploymentIconCachePlan.LOOKUP_DESCRIPTOR);
        assertEquals(1, calls(wrapper, RUNTIME, "lookup"));
        assertEquals(1, calls(wrapper, RUNTIME, "record"));
        assertEquals(0, calls(wrapper, DeploymentIconCachePlan.TARGET_CLASS,
                DeploymentIconCachePlan.MEMBERS_METHOD));
        assertEquals(0, calls(wrapper, DeploymentIconCachePlan.TARGET_CLASS,
                DeploymentIconCachePlan.LIST_METHOD));
        assertEquals(1, calls(method(owner, DeploymentIconCachePlan.CLEAR_METHOD,
                DeploymentIconCachePlan.CLEAR_DESCRIPTOR), RUNTIME, "cleared"));
        assertEquals(1, calls(method(owner, DeploymentIconCachePlan.ADD_METHOD,
                DeploymentIconCachePlan.ADD_MEMBER_DESCRIPTOR), RUNTIME, "added"));
        assertEquals(1, calls(method(owner, DeploymentIconCachePlan.ADD_METHOD,
                DeploymentIconCachePlan.ADD_COMBAT_ENTRY_DESCRIPTOR), RUNTIME, "added"));
        assertEquals(1, calls(method(owner, DeploymentIconCachePlan.REMOVE_METHOD,
                DeploymentIconCachePlan.REMOVE_DESCRIPTOR), RUNTIME, "removed"));
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
                    && owner.equals(call.owner) && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }
}

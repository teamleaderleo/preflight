package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Opt-in proof against the exact Windows archive; this never starts Starsector. */
class ShipSystemHydrationBreakdownInstalledAdapterIT {
    @Test
    void installedWindowsShipSystemLoaderHasTheReviewedSingleLookupSeam() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<Windows starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        try (JarFile jar = new JarFile(archive.toFile())) {
            byte[] original = bytes(jar, SpecStorePhasePlan.TARGET_CLASS);
            ClassSignature signature = ClassSignature.parse(original);
            assertEquals(ShipSystemHydrationBreakdownPlan.WINDOWS_ORIGINAL_SHA256,
                    signature.sha256());
            ClassNode owner = read(original);
            assertEquals(1, hullLookups(owner));
            assertTrue(ShipSystemHydrationBreakdownPlan.apply(signature, owner));
            byte[] transformed = VariantJsonCachePlan.write(owner);
            assertNotNull(transformed);
            ClassNode installed = read(transformed);
            assertEquals(1, runtimeCalls(installed, "sampledHotCallStart"));
            assertEquals(1, runtimeCalls(installed, "sampledHotCallEnd"));
        }
    }

    private static byte[] bytes(JarFile jar, String internalName) throws Exception {
        var entry = jar.getJarEntry(internalName + ".class");
        assertNotNull(entry);
        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static int hullLookups(ClassNode owner) {
        return calls(owner, "com/fs/starfarer/loading/oO0O", "super");
    }

    private static int runtimeCalls(ClassNode owner, String methodName) {
        return calls(owner, StartupPhaseRuntime.class.getName().replace('.', '/'), methodName);
    }

    private static int calls(ClassNode owner, String callOwner, String methodName) {
        int calls = 0;
        for (MethodNode method : owner.methods) {
            if (!ShipSystemHydrationBreakdownPlan.METHOD.equals(method.name)
                    || !ShipSystemHydrationBreakdownPlan.DESCRIPTOR.equals(method.desc)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && callOwner.equals(call.owner) && methodName.equals(call.name)) calls++;
            }
        }
        return calls;
    }
}

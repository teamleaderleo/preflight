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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Opt-in proof against the exact Windows archive; this never starts Starsector. */
class FactionPriorityCacheInstalledAdapterIT {
    @Test
    void installedWindowsPriorityWalkAndCallbacksMatchTheReviewedContract() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<Windows starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        try (JarFile jar = new JarFile(archive.toFile())) {
            byte[] original = bytes(jar, FactionPriorityCachePlan.TARGET_CLASS);
            ClassSignature signature = ClassSignature.parse(original);
            assertEquals(AdapterTargetRegistry.windowsSpecStorePhaseTarget().sha256(),
                    signature.sha256());
            byte[] transformed = FactionPriorityCachePlan.transform(signature, original);
            assertNotNull(transformed);
            assertEquals(1, runtimeCalls(transformed, "replayOrBegin"));
            assertEquals(3, runtimeCalls(transformed, "record"));
            assertEquals(1, runtimeCalls(transformed, "completeCall"));

            // A partial reflective replay is allowed to fall back because these exact eight
            // callbacks only add to Sets; replaying an already-added ID is therefore idempotent.
            for (int suffix = 18; suffix <= 25; suffix++) {
                assertEquals(1, setAdds(bytes(jar,
                        "com/fs/starfarer/loading/SpecStore$" + suffix)));
            }
        }
    }

    private static byte[] bytes(JarFile jar, String internalName) throws Exception {
        var entry = jar.getJarEntry(internalName + ".class");
        assertNotNull(entry);
        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static int runtimeCalls(byte[] bytes, String methodName) {
        return calls(bytes, FactionPriorityCacheRuntime.class.getName().replace('.', '/'), methodName);
    }

    private static int setAdds(byte[] bytes) {
        return calls(bytes, "java/util/Set", "add");
    }

    private static int calls(byte[] bytes, String ownerName, String methodName) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int calls = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && ownerName.equals(call.owner) && methodName.equals(call.name)) calls++;
            }
        }
        return calls;
    }
}

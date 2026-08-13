package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Opt-in transform check against the exact installed Stellar Networks updater. */
class StelnetMarketUpdaterInstalledAdapterIT {
    @AfterEach
    void reset() {
        StelnetMarketUpdaterRuntime.reset();
    }

    @Test
    void installedUpdaterUsesOnePassAndRetainsTheRandomPickerFallback() throws Exception {
        String configured = System.getProperty("preflight.stelnet.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.stelnet.jar=<stelnet.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(StelnetMarketUpdaterPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(StelnetMarketUpdaterPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = StelnetMarketUpdaterPlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(StelnetMarketUpdaterPlan.transform(signature, transformed));

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        String runtime = StelnetMarketUpdaterRuntime.class.getName().replace('.', '/');
        MethodNode advance = method(owner, StelnetMarketUpdaterPlan.ADVANCE_METHOD);
        MethodNode picker = method(owner, StelnetMarketUpdaterPlan.PICK_METHOD);
        MethodNode fallback = method(owner, "preflight$original$pickRandomMarket");
        assertNotNull(advance);
        assertNotNull(picker);
        assertNotNull(fallback);
        assertEquals(1, calls(advance, runtime, "observePaused"));
        assertEquals(1, calls(advance, runtime, "hasPending"));
        assertEquals(1, calls(picker, runtime, "nextMarket"));
        assertEquals(1, calls(picker, StelnetMarketUpdaterPlan.TARGET_CLASS,
                "preflight$original$pickRandomMarket"));
        assertEquals(1, calls(fallback,
                "com/fs/starfarer/api/util/WeightedRandomPicker", "pick"));
        assertEquals(3, calls(owner, runtime, "invalidate"));

        ClassSignature wrong = new ClassSignature(signature.internalName(), "0".repeat(64),
                signature.majorVersion(), signature.access(), signature.methods());
        assertNull(StelnetMarketUpdaterPlan.transform(wrong, original));
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(candidate -> name.equals(candidate.name))
                .findFirst().orElse(null);
    }

    private static int calls(ClassNode owner, String callOwner, String name) {
        return owner.methods.stream().mapToInt(method -> calls(method, callOwner, name)).sum();
    }

    private static int calls(MethodNode method, String callOwner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && callOwner.equals(call.owner) && name.equals(call.name)) {
                result++;
            }
        }
        return result;
    }
}

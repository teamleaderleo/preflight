package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact installed CombatEngine check; it never starts the game. */
class CombatRuntimeIntegrityInstalledAdapterIT {
    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void enableFrames() {
        FrameTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        System.clearProperty("preflight.desktopSmoke");
        CombatRuntimeIntegrityRuntime.beginSession();
        FrameTimeRuntime.reset();
        InternalGameControlRuntime.reset();
        RuntimeSemanticState.reset();
    }

    @Test
    void installedCombatStateCarriesTheEarlierClosedInputBoundary() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(CombatRuntimeIntegrityPlan.COMBAT_STATE_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertTrue(CombatRuntimeIntegrityPlan.COMBAT_STATE_SHA256.equals(signature.sha256())
                        || CombatRuntimeIntegrityPlan.WINDOWS_COMBAT_STATE_SHA256.equals(
                                signature.sha256()),
                signature.sha256());
        if (CombatRuntimeIntegrityPlan.WINDOWS_COMBAT_STATE_SHA256.equals(signature.sha256())) {
            assertTrue(AdapterTargetRegistry.windowsCombatStateInputTarget()
                    .match(signature, windowsSource(archive)).exact());
        }
        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));

        byte[] transformed = CombatRuntimeIntegrityPlan.transform(signature, original);

        assertNotNull(transformed);
        assertNull(CombatRuntimeIntegrityPlan.transform(
                ClassSignature.parse(transformed), transformed));
        MethodNode traverse = read(transformed).methods.stream()
                .filter(candidate -> CombatRuntimeIntegrityPlan.TRAVERSE_METHOD.equals(candidate.name)
                        && CombatRuntimeIntegrityPlan.TRAVERSE_DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
        assertEquals(1, calls(traverse,
                InternalGameControlRuntime.class.getName().replace('.', '/'), "combatInput"));
    }

    @Test
    void installedCombatLoopCarriesIntegrityAndFrameObservations() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(CombatRuntimeIntegrityPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertTrue(CombatRuntimeIntegrityPlan.ORIGINAL_SHA256.equals(signature.sha256())
                        || CombatRuntimeIntegrityPlan.WINDOWS_ORIGINAL_SHA256.equals(
                                signature.sha256()),
                signature.sha256());
        if (CombatRuntimeIntegrityPlan.WINDOWS_ORIGINAL_SHA256.equals(signature.sha256())) {
            assertTrue(AdapterTargetRegistry.windowsCombatRuntimeIntegrityTarget()
                    .match(signature, windowsSource(archive)).exact());
        }
        String advanceDescriptor = CombatRuntimeIntegrityPlan.advanceDescriptor(signature.sha256());
        byte[] transformed = CombatRuntimeIntegrityPlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(CombatRuntimeIntegrityPlan.transform(
                ClassSignature.parse(transformed), transformed));

        MethodNode method = method(read(transformed), advanceDescriptor);
        assertEquals(1, calls(method,
                CombatRuntimeIntegrityRuntime.class.getName().replace('.', '/'), "observe"));
        assertEquals(1, calls(method,
                FrameTimeRuntime.class.getName().replace('.', '/'), "observeCombat"));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String descriptor) {
        return owner.methods.stream()
                .filter(candidate -> CombatRuntimeIntegrityPlan.ADVANCE_METHOD.equals(candidate.name)
                        && descriptor.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }

    private static AdapterSourceIdentity windowsSource(Path archive) {
        return new AdapterSourceIdentity(
                archive.toUri().toString(),
                "C:/Games/Starsector/starsector-core/starfarer_obf.jar",
                "STARSECTOR_CORE",
                "5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8",
                "",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }
}

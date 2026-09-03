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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact installed title transform checks; they never start the game. */
class MainMenuInteractiveInstalledAdapterIT {
    private static final String RUNTIME =
            RuntimeSemanticState.class.getName().replace('.', '/');
    private static final String CONTROL_RUNTIME =
            InternalGameControlRuntime.class.getName().replace('.', '/');

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        RuntimeSemanticState.reset();
        InternalGameControlRuntime.reset();
    }

    @Test
    void installedMacTitlePublishesUsabilityAtShowCompletion() throws Exception {
        byte[] original = installed(
                MainMenuInteractivePlan.TARGET_CLASS,
                MainMenuInteractivePlan.ORIGINAL_SHA256);
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));

        byte[] transformed = MainMenuInteractivePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(transformed);
        assertNull(MainMenuInteractivePlan.transform(ClassSignature.parse(transformed), transformed));
        assertEquals(1, calls(method(transformed,
                MainMenuInteractivePlan.SHOW_METHOD, MainMenuInteractivePlan.SHOW_DESCRIPTOR),
                RUNTIME, "mainMenuInteractive"));
        MethodNode advance = method(transformed,
                MainMenuInteractivePlan.ADVANCE_METHOD, MainMenuInteractivePlan.ADVANCE_DESCRIPTOR);
        assertEquals(0, calls(advance, RUNTIME, "mainMenuInteractive"));
        assertEquals(1, calls(advance, RUNTIME, "mainMenuOverlayRemoved"));
        assertEquals(1, calls(advance, CONTROL_RUNTIME, "titleAdvance"));
    }

    @Test
    void installedLinuxTitleRetainsItsReviewedShowCompletionSeam() throws Exception {
        byte[] original = installed(
                MainMenuInteractivePlan.LINUX_TARGET_CLASS,
                MainMenuInteractivePlan.LINUX_ORIGINAL_SHA256);
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));

        byte[] transformed = MainMenuInteractivePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(transformed);
        assertNull(MainMenuInteractivePlan.transform(ClassSignature.parse(transformed), transformed));
        MethodNode show = method(transformed,
                MainMenuInteractivePlan.SHOW_METHOD, MainMenuInteractivePlan.SHOW_DESCRIPTOR);
        assertEquals(1, calls(show, RUNTIME, "mainMenuInteractive"));
        assertEquals(0, calls(show, RUNTIME, "mainMenuOverlayRemoved"));
        assertEquals(0, calls(show, CONTROL_RUNTIME, "titleAdvance"));
    }

    @Test
    void installedWindowsTitlePublishesUsabilityBeforeItsOverlayRemovalHook() throws Exception {
        Path archive = configuredArchive();
        byte[] original = installed(
                archive,
                MainMenuInteractivePlan.WINDOWS_TARGET_CLASS,
                MainMenuInteractivePlan.WINDOWS_ORIGINAL_SHA256);
        ClassSignature signature = ClassSignature.parse(original);
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                archive.toUri().toString(),
                "C:/Games/Starsector/starsector-core/starfarer_obf.jar",
                "STARSECTOR_CORE",
                "5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8",
                "",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
        assertTrue(AdapterTargetRegistry.windowsMainMenuInteractiveTarget()
                .match(signature, source).exact());
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));

        byte[] transformed = MainMenuInteractivePlan.transform(signature, original);

        assertNotNull(transformed);
        assertNull(MainMenuInteractivePlan.transform(ClassSignature.parse(transformed), transformed));
        assertEquals(1, calls(method(transformed,
                MainMenuInteractivePlan.SHOW_METHOD, MainMenuInteractivePlan.SHOW_DESCRIPTOR),
                RUNTIME, "mainMenuInteractive"));
        MethodNode advance = method(transformed,
                MainMenuInteractivePlan.ADVANCE_METHOD, MainMenuInteractivePlan.ADVANCE_DESCRIPTOR);
        assertEquals(0, calls(advance, RUNTIME, "mainMenuInteractive"));
        assertEquals(1, calls(advance, RUNTIME, "mainMenuOverlayRemoved"));
        assertEquals(1, calls(advance, CONTROL_RUNTIME, "titleAdvance"));
    }

    private static byte[] installed(String targetClass, String expectedSha256) throws Exception {
        return installed(configuredArchive(), targetClass, expectedSha256);
    }

    private static byte[] installed(Path archive, String targetClass, String expectedSha256)
            throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(targetClass + ".class");
            Assumptions.assumeTrue(entry != null, "exact platform 0.98a-RC8 core fixture");
            try (var input = jar.getInputStream(entry)) {
                byte[] original = input.readAllBytes();
                assertEquals(expectedSha256, ClassSignature.parse(original).sha256());
                return original;
            }
        }
    }

    private static Path configuredArchive() {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));
        return archive;
    }

    private static MethodNode method(byte[] bytes, String name, String descriptor) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
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
}

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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Opt-in exact installed-core transform check; it never starts the game. */
class FrameTimeStateInstalledAdapterIT {
    private static final String RUNTIME = FrameTimeRuntime.class.getName().replace('.', '/');
    private static final String CONTROL_RUNTIME =
            InternalGameControlRuntime.class.getName().replace('.', '/');
    private static final String CAMPAIGN_ENGINE = "com/fs/starfarer/campaign/CampaignEngine";

    @BeforeEach
    void enable() {
        FrameTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        FrameTimeRuntime.reset();
    }

    @Test
    void installedCampaignLoopAcceptsExactlyOneObserver() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        try (JarFile jar = new JarFile(archive.toFile())) {
            assertInstalled(jar, FrameTimeStatePlan.CAMPAIGN_CLASS, "observeCampaign");
        }
    }

    @Test
    void installedWindowsCampaignAndLimiterCarryExactSourceBindings() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] campaign = classBytes(archive, FrameTimeStatePlan.CAMPAIGN_CLASS);
        ClassSignature campaignSignature = ClassSignature.parse(campaign);
        Assumptions.assumeTrue(
                FrameTimeStatePlan.WINDOWS_CAMPAIGN_SHA256.equals(campaignSignature.sha256()),
                "exact Windows 0.98a-RC8 core fixture");
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                archive.toUri().toString(),
                "C:/Games/Starsector/starsector-core/starfarer_obf.jar",
                "STARSECTOR_CORE",
                "5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8",
                "",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
        assertTrue(AdapterTargetRegistry.windowsCampaignFrameTimeStateTarget()
                .match(campaignSignature, source).exact());

        byte[] limiter = classBytes(archive, FrameLimiterTimePlan.TARGET_CLASS);
        ClassSignature limiterSignature = ClassSignature.parse(limiter);
        assertEquals(FrameLimiterTimePlan.WINDOWS_ORIGINAL_SHA256, limiterSignature.sha256());
        assertTrue(AdapterTargetRegistry.windowsFrameLimiterTimeTarget()
                .match(limiterSignature, source).exact());
        byte[] transformed = FrameLimiterTimePlan.transform(limiterSignature, limiter);
        assertNotNull(transformed);
        assertNull(FrameLimiterTimePlan.transform(ClassSignature.parse(transformed), transformed));
    }

    private static void assertInstalled(JarFile jar, String className, String observer)
            throws Exception {
        var entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry);
        byte[] original;
        try (var input = jar.getInputStream(entry)) {
            original = input.readAllBytes();
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertTrue(FrameTimeStatePlan.CAMPAIGN_SHA256.equals(signature.sha256())
                        || FrameTimeStatePlan.LINUX_CAMPAIGN_SHA256.equals(signature.sha256())
                        || FrameTimeStatePlan.WINDOWS_CAMPAIGN_SHA256.equals(signature.sha256()),
                signature.sha256());
        String advanceDescriptor = FrameTimeStatePlan.advanceDescriptor(signature.sha256());
        String processInputDescriptor = FrameTimeStatePlan.processInputDescriptor(signature.sha256());
        ClassNode originalOwner = new ClassNode(Opcodes.ASM9);
        new ClassReader(original).accept(originalOwner, ClassReader.EXPAND_FRAMES);
        int originalPauseCalls = calls(
                method(originalOwner, advanceDescriptor), CAMPAIGN_ENGINE, "isPaused", "()Z");
        int originalReturns = returns(processInput(originalOwner, processInputDescriptor));
        byte[] transformed = FrameTimeStatePlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(FrameTimeStatePlan.transform(ClassSignature.parse(transformed), transformed));

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        assertEquals(1, calls(method(owner, advanceDescriptor), RUNTIME, observer, "()V"));
        assertEquals(1, calls(method(owner, advanceDescriptor),
                RUNTIME, "observeCampaignPaused", "(Z)V"));
        assertEquals(originalPauseCalls + 1,
                calls(method(owner, advanceDescriptor), CAMPAIGN_ENGINE, "isPaused", "()Z"));
        assertEquals(1, calls(processInput(owner, processInputDescriptor),
                CONTROL_RUNTIME, "campaignInput",
                "(Ljava/lang/Object;Ljava/lang/Object;)V"));
        assertEquals(originalReturns, calls(processInput(owner, processInputDescriptor), CONTROL_RUNTIME,
                "campaignInputComplete", "(Ljava/lang/Object;)V"));
    }

    private static byte[] classBytes(Path archive, String className) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(className + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static MethodNode method(ClassNode owner, String descriptor) {
        return owner.methods.stream()
                .filter(candidate -> FrameTimeStatePlan.ADVANCE_METHOD.equals(candidate.name)
                        && descriptor.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static MethodNode processInput(ClassNode owner, String descriptor) {
        return owner.methods.stream()
                .filter(candidate -> FrameTimeStatePlan.PROCESS_INPUT_METHOD.equals(candidate.name)
                        && descriptor.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int returns(MethodNode method) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) result++;
        }
        return result;
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) result++;
        }
        return result;
    }
}

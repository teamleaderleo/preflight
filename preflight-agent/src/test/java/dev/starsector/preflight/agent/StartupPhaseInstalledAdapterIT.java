package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in structural gate for the exact installed 0.98a-RC8 startup coordinator. */
class StartupPhaseInstalledAdapterIT {
    @Test
    void installedCoreAcceptsTheDetailedStartupPhaseRewrite() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] bytes;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(StartupPhasePlan.TARGET_CLASS + ".class");
            assertNotNull(entry, StartupPhasePlan.TARGET_CLASS);
            try (var input = jar.getInputStream(entry)) {
                bytes = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(bytes);
        assertEquals(StartupPhasePlan.TARGET_CLASS, signature.internalName());
        Set<String> reviewed = Set.of(
                FrameTimeStartupCompletionPlan.ORIGINAL_SHA256,
                FrameTimeStartupCompletionPlan.LINUX_ORIGINAL_SHA256,
                FrameTimeStartupCompletionPlan.WINDOWS_ORIGINAL_SHA256);
        Assumptions.assumeTrue(reviewed.contains(signature.sha256()),
                "installed class is not a reviewed platform identity: " + signature.sha256());
        assertNotNull(StartupPhasePlan.transform(signature, bytes));
    }
}

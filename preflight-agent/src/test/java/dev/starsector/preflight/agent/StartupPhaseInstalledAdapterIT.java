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

    @Test
    void exactWindowsCoreAcceptsPreparedCarrierStagingRewrite() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] bytes;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(TexturePreparedStagingPlan.TARGET_CLASS + ".class");
            assertNotNull(entry, TexturePreparedStagingPlan.TARGET_CLASS);
            try (var input = jar.getInputStream(entry)) {
                bytes = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(bytes);
        Assumptions.assumeTrue(
                FrameTimeStartupCompletionPlan.WINDOWS_ORIGINAL_SHA256.equals(signature.sha256()),
                "installed class is not the reviewed Windows identity: " + signature.sha256());
        System.setProperty(TexturePreparedStagingRuntime.ENABLED_PROPERTY, "true");
        try {
            assertNotNull(TexturePreparedStagingPlan.transform(signature, bytes));
        } finally {
            System.clearProperty(TexturePreparedStagingRuntime.ENABLED_PROPERTY);
        }
    }

    @Test
    void exactWindowsCoreAcceptsDetailedSpecDecompositionRewrites() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<Windows starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] coordinator = exact(archive, StartupPhasePlan.TARGET_CLASS);
        Assumptions.assumeTrue(FrameTimeStartupCompletionPlan.WINDOWS_ORIGINAL_SHA256.equals(
                        ClassSignature.parse(coordinator).sha256()),
                "installed class is not the reviewed Windows identity");

        byte[] specStore = exact(archive, SpecStorePhasePlan.TARGET_CLASS);
        assertEquals(AdapterTargetRegistry.windowsSpecStorePhaseTarget().sha256(),
                ClassSignature.parse(specStore).sha256());
        assertNotNull(SpecStorePhasePlan.transform(ClassSignature.parse(specStore), specStore));

        byte[] weapon = exact(archive, WeaponLoaderPhasePlan.TARGET_CLASS);
        assertEquals(AdapterTargetRegistry.windowsWeaponLoaderPhaseTarget().sha256(),
                ClassSignature.parse(weapon).sha256());
        assertNotNull(WeaponLoaderPhasePlan.transform(ClassSignature.parse(weapon), weapon));

        byte[] hull = exact(archive, ShipHullLoaderPhasePlan.TARGET_CLASS);
        assertEquals(AdapterTargetRegistry.windowsShipHullLoaderPhaseTarget().sha256(),
                ClassSignature.parse(hull).sha256());
        assertNotNull(ShipHullLoaderPhasePlan.transform(ClassSignature.parse(hull), hull));

        byte[] rules = exact(archive, RulesLoaderPhasePlan.TARGET_CLASS);
        assertEquals(AdapterTargetRegistry.windowsRulesLoaderPhaseTarget().sha256(),
                ClassSignature.parse(rules).sha256());
        assertNotNull(RulesLoaderPhasePlan.transform(ClassSignature.parse(rules), rules));

        byte[] expression = exact(archive, RuleExpressionPhasePlan.WINDOWS_TARGET_CLASS);
        assertEquals(AdapterTargetRegistry.windowsRuleExpressionPhaseTarget().sha256(),
                ClassSignature.parse(expression).sha256());
        assertNotNull(RuleExpressionPhasePlan.transform(
                ClassSignature.parse(expression), expression));
    }

    private static byte[] exact(Path archive, String className) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(className + ".class");
            assertNotNull(entry, className);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }
}

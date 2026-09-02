package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in structural gate for the exact Linux 0.98a-RC8 startup-cache classes. */
class LinuxStartupCacheTargetsInstalledAdapterIT {
    @Test
    void installedLinuxCoreAcceptsEveryHighImpactStartupRewrite() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<Linux starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] loading = exact(archive, MergedReadCachePlan.TARGET_CLASS,
                "b1737290343c69e71dfa3d3a28ddd7757f3bdc5a230f877043312f510ba85e2e");
        assertNotNull(MergedReadCachePlan.transform(ClassSignature.parse(loading), loading));
        assertNotNull(LoadJsonMemoPlan.transform(ClassSignature.parse(loading), loading));

        byte[] specStore = exact(archive, SpecStorePhasePlan.TARGET_CLASS,
                "c24e0891883158c29767bd1d94cb41f4ce281418669d80b39472745626e23172");
        assertNotNull(SpecStorePhasePlan.transform(ClassSignature.parse(specStore), specStore));
        assertNotNull(VariantJsonCachePlan.transform(ClassSignature.parse(specStore), specStore));
        assertNotNull(SpecStoreQuoteNormalizationPlan.transform(
                ClassSignature.parse(specStore), specStore));

        byte[] weapon = exact(archive, WeaponLoaderPhasePlan.TARGET_CLASS,
                "d551ae2441d94c338cc4000bff809a5bd0f8d0783dfe2d9147831d289f91644e");
        assertEquals(WeaponLoaderPhasePlan.LINUX_LOAD_ALL_METHOD,
                WeaponLoaderPhasePlan.loadAllMethod(ClassSignature.parse(weapon)));
        assertNotNull(WeaponLoaderPhasePlan.transform(ClassSignature.parse(weapon), weapon));
        assertNotNull(WeaponJsonCachePlan.transform(ClassSignature.parse(weapon), weapon));
        assertNotNull(ProjectileJsonCachePlan.transform(ClassSignature.parse(weapon), weapon));

        byte[] hull = exact(archive, ShipHullLoaderPhasePlan.TARGET_CLASS,
                "1132ea9ddf52b2d6293f9ac8379fbb7dee3181ca5652a87bcf6f64a655fc5c00");
        assertEquals(ShipHullLoaderPhasePlan.LINUX_LOAD_ONE_METHOD,
                ShipHullLoaderPhasePlan.loadOneMethod(ClassSignature.parse(hull)));
        assertNotNull(ShipHullLoaderPhasePlan.transform(ClassSignature.parse(hull), hull));
        assertNotNull(HullJsonCachePlan.transform(ClassSignature.parse(hull), hull));

        byte[] rules = exact(archive, RulesLoaderPhasePlan.TARGET_CLASS,
                "7865fa80d98032c50346f800daecdd2d0dd6935a67e0ab58159410aa7c7c2842");
        assertNotNull(RulesLoaderPhasePlan.transform(ClassSignature.parse(rules), rules));
        assertNotNull(RulesCsvCachePlan.transform(ClassSignature.parse(rules), rules));
        assertNotNull(RulesDuplicateIndexPlan.transform(ClassSignature.parse(rules), rules));
        assertNotNull(RulesRegexCachePlan.transform(ClassSignature.parse(rules), rules));
        assertNotNull(RuleCommandClassCachePlan.transformLoader(
                ClassSignature.parse(rules), rules));

        byte[] expression = exact(archive, RuleExpressionPhasePlan.LINUX_TARGET_CLASS,
                "894b652ad366387a6fb15dd066fca922c70411b502496a079cec2fd065a57760");
        assertNotNull(RuleExpressionPhasePlan.transform(
                ClassSignature.parse(expression), expression));
        assertNotNull(RuleTokenCachePlan.transform(
                ClassSignature.parse(expression), expression));
        assertNotNull(RuleCommandClassCachePlan.transform(
                ClassSignature.parse(expression), expression));
    }

    private static byte[] exact(Path archive, String className, String expectedSha) throws Exception {
        byte[] bytes;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(className + ".class");
            assertNotNull(entry, className);
            try (var input = jar.getInputStream(entry)) {
                bytes = input.readAllBytes();
            }
        }
        assertEquals(expectedSha, ClassSignature.parse(bytes).sha256(), className);
        return bytes;
    }
}

package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class AshLibVariantLookupInstalledAdapterIT {
    @Test
    void transformsBothReviewedInstalledClasses() throws Exception {
        String configured = System.getProperty("preflight.ashlib.jar", "");
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.ashlib.jar=<ashlib.jar> for the installed-archive check");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        try (JarFile jar = new JarFile(archive.toFile())) {
            assertTransforms(jar, AshLibVariantLookupPlan.REPOSITORY_CLASS,
                    AshLibVariantLookupPlan.REPOSITORY_SHA256);
            assertTransforms(jar, AshLibVariantLookupPlan.LOOKUP_CLASS,
                    AshLibVariantLookupPlan.LOOKUP_SHA256);
            assertTransforms(jar, AshLibVariantLookupPlan.SHIP_JSON_CLASS,
                    AshLibVariantLookupPlan.SHIP_JSON_SHA256);
        }
    }

    private static void assertTransforms(JarFile jar, String className, String hash) throws Exception {
        var entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry);
        byte[] original;
        try (InputStream input = jar.getInputStream(entry)) {
            original = input.readAllBytes();
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(hash, signature.sha256());
        assertNotNull(AshLibVariantLookupPlan.transform(signature, original));
    }
}

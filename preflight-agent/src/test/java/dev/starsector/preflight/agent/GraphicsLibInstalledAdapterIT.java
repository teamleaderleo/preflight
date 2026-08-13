package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in exact-archive check; it transforms bytes but never loads or launches Starsector. */
class GraphicsLibInstalledAdapterIT {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        GraphicsLibCompactReplayPlan.beginSession();
    }

    @Test
    void exactStockArchiveTransformsWhileChangedClassAndArchiveFailClosed() throws Exception {
        String configured = System.getProperty("preflight.graphicslib.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.graphicslib.jar=<Graphics.jar> for the installed-archive check");
        Path installed = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(installed), "configured Graphics.jar does not exist");

        Path exact = temporaryDirectory.resolve("exact/mods/GraphicsLib/jars/Graphics.jar");
        Files.createDirectories(exact.getParent());
        Files.copy(installed, exact);
        byte[] original = classBytes(exact);
        assertEquals(GraphicsLibCompactReplayPlan.ORIGINAL_SHA256,
                ClassSignature.parse(original).sha256(), "configured jar is not reviewed stock 1.12.1");

        GraphicsLibCompactReplayPlan.configure(true, null);
        assertTrue(GraphicsLibCompactReplayPlan.ready(), GraphicsLibCompactReplayPlan.status());
        byte[] replacement = transform(exact, original, temporaryDirectory.resolve("exact-report.json"));
        assertNotNull(replacement, Files.readString(temporaryDirectory.resolve("exact-report.json")));
        assertEquals(GraphicsLibCompactReplayPlan.REPLACEMENT_SHA256,
                ClassSignature.parse(replacement).sha256());

        assertNull(transform(exact, replacement, temporaryDirectory.resolve("changed-class-report.json")));

        Path changedArchive = temporaryDirectory.resolve("changed/mods/GraphicsLib/jars/Graphics.jar");
        Files.createDirectories(changedArchive.getParent());
        Files.copy(installed, changedArchive);
        Files.write(changedArchive, new byte[] {0}, StandardOpenOption.APPEND);
        assertNull(transform(
                changedArchive, original, temporaryDirectory.resolve("changed-archive-report.json")));
    }

    private byte[] transform(Path archive, byte[] bytes, Path reportPath) throws Exception {
        AdapterTargetRegistry registry = AdapterTargetRegistry.empty()
                .withGraphicsLibCompactReplayTarget();
        AdapterReport report = new AdapterReport(
                AdapterMode.ENABLED, reportPath, null, List.of("com/fs/"));
        AdapterProbeTransformer transformer = new AdapterProbeTransformer(
                AdapterMode.ENABLED, registry, List.of("com/fs/"), report);
        CodeSource codeSource = new CodeSource(archive.toUri().toURL(), (Certificate[]) null);
        ProtectionDomain domain = new ProtectionDomain(codeSource, null);
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {archive.toUri().toURL()}, null)) {
            byte[] result = transformer.transform(
                    loader, GraphicsLibCompactReplayPlan.TARGET_CLASS, null, domain, bytes);
            report.write();
            return result;
        }
    }

    private static byte[] classBytes(Path archive) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(GraphicsLibCompactReplayPlan.TARGET_CLASS + ".class");
            assertNotNull(entry, "Graphics.jar has no TextureData.class");
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }
}

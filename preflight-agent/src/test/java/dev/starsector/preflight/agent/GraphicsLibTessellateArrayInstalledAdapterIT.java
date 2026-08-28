package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in stock-archive check for issue #1153's GraphicsLib tessellation array experiment. */
class GraphicsLibTessellateArrayInstalledAdapterIT {
    private static final String STOCK_GRAPHICS_JAR_SHA256 =
            "832064013fe853731941e547842884ba121fb8b20eff08d24137f7a2c916903a";

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        GraphicsLibTessellateArrayRuntime.resetForTest();
    }

    @Test
    void exactStockArchiveTransformsWhileChangedClassAndArchiveFailClosed() throws Exception {
        String configured = System.getProperty("preflight.graphicslib.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.graphicslib.jar=<Graphics.jar> for the installed-archive check");
        Path installed = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(installed), "configured Graphics.jar does not exist");
        assertEquals(STOCK_GRAPHICS_JAR_SHA256, sha256(installed),
                "configured jar is not the reviewed stock GraphicsLib 1.12.1 archive");

        Path exact = temporaryDirectory.resolve("exact/mods/GraphicsLib/jars/Graphics.jar");
        Files.createDirectories(exact.getParent());
        Files.copy(installed, exact);
        byte[] original = classBytes(exact);
        ClassSignature originalSignature = ClassSignature.parse(original);
        assertEquals(GraphicsLibTessellateArrayPlan.TARGET_CLASS, originalSignature.internalName());
        assertEquals(61, originalSignature.majorVersion());
        assertTrue(originalSignature.hasMethod(
                GraphicsLibTessellateArrayPlan.RENDER_METHOD,
                GraphicsLibTessellateArrayPlan.RENDER_DESCRIPTOR));

        Path targets = exactTargetFile(originalSignature.sha256());
        AdapterTargetRegistry registry = AdapterTargetRegistry.load(targets);
        GraphicsLibTessellateArrayRuntime.beginSessionForTest(true);

        byte[] transformed = transform(
                registry, exact, original, temporaryDirectory.resolve("exact-report.json"));
        assertNotNull(transformed, Files.readString(temporaryDirectory.resolve("exact-report.json")));
        ClassSignature transformedSignature = ClassSignature.parse(transformed);
        assertTrue(transformedSignature.hasMethod(
                "preflight$drawCachedTessellation",
                "(Lorg/dark/graphics/util/Tessellate$TessData;"
                        + "Lcom/fs/starfarer/api/combat/ShipAPI;)V"));

        assertNull(transform(
                registry,
                exact,
                transformed,
                temporaryDirectory.resolve("changed-class-report.json")));

        Path changedArchive = temporaryDirectory.resolve("changed/mods/GraphicsLib/jars/Graphics.jar");
        Files.createDirectories(changedArchive.getParent());
        Files.copy(installed, changedArchive);
        Files.write(changedArchive, new byte[] {0}, StandardOpenOption.APPEND);
        assertNull(transform(
                registry,
                changedArchive,
                original,
                temporaryDirectory.resolve("changed-archive-report.json")));
    }

    private Path exactTargetFile(String classSha256) throws Exception {
        Path file = temporaryDirectory.resolve("tessellate-targets.txt");
        Files.writeString(file, String.join(System.lineSeparator(),
                "target graphicslib-1.12.1-tessellate-array-live",
                "class " + GraphicsLibTessellateArrayPlan.TARGET_CLASS,
                "sha256 " + classSha256,
                "plan " + FrameTimeRuntime.PLAN_ID,
                "source-kind MOD",
                "source-suffix graphics.jar",
                "source-sha256 " + STOCK_GRAPHICS_JAR_SHA256,
                "loader-class java/net/URLClassLoader",
                "method " + GraphicsLibTessellateArrayPlan.RENDER_METHOD + " "
                        + GraphicsLibTessellateArrayPlan.RENDER_DESCRIPTOR,
                "end",
                ""));
        return file;
    }

    private byte[] transform(
            AdapterTargetRegistry registry, Path archive, byte[] bytes, Path reportPath) throws Exception {
        AdapterReport report = new AdapterReport(
                AdapterMode.ENABLED, reportPath, null, List.of("org/dark/"));
        AdapterProbeTransformer transformer = new AdapterProbeTransformer(
                AdapterMode.ENABLED, registry, List.of("org/dark/"), report);
        CodeSource codeSource = new CodeSource(archive.toUri().toURL(), (Certificate[]) null);
        ProtectionDomain domain = new ProtectionDomain(codeSource, null);
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {archive.toUri().toURL()}, null)) {
            byte[] result = transformer.transform(
                    loader,
                    GraphicsLibTessellateArrayPlan.TARGET_CLASS,
                    null,
                    domain,
                    bytes);
            report.write();
            return result;
        }
    }

    private static byte[] classBytes(Path archive) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(GraphicsLibTessellateArrayPlan.TARGET_CLASS + ".class");
            assertNotNull(entry, "Graphics.jar has no Tessellate.class");
            try (InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

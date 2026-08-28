package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
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

/** Opt-in installed-core check for issue #1153's final-frame-wait experiment. */
class HighResolutionFrameSyncInstalledAdapterIT {
    private static final String STOCK_CORE_JAR_SHA256 =
            "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149";

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        HighResolutionFrameSyncRuntime.resetForTest();
    }

    @Test
    void exactInstalledCoreTransformsWhileChangedClassAndArchiveFailClosed() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path installed = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(installed),
                "configured starfarer_obf.jar does not exist");
        assertEquals(STOCK_CORE_JAR_SHA256, sha256(installed),
                "configured jar is not the reviewed Starsector 0.98a-RC8 core archive");

        Path exact = temporaryDirectory.resolve("exact/contents/resources/java/starfarer_obf.jar");
        Files.createDirectories(exact.getParent());
        Files.copy(installed, exact);
        byte[] original = classBytes(exact);
        ClassSignature originalSignature = ClassSignature.parse(original);
        assertEquals(HighResolutionFrameSyncPlan.TARGET_CLASS, originalSignature.internalName());
        assertEquals(61, originalSignature.majorVersion());
        assertTrue(originalSignature.hasMethod(
                HighResolutionFrameSyncPlan.TRAVERSE_METHOD,
                HighResolutionFrameSyncPlan.TRAVERSE_DESCRIPTOR));

        AdapterTargetRegistry registry = AdapterTargetRegistry.load(
                exactTargetFile(originalSignature.sha256()));
        HighResolutionFrameSyncRuntime.beginSessionForTest(true, 2_000_000L);

        byte[] transformed = transform(
                registry, exact, original, temporaryDirectory.resolve("exact-report.json"));
        assertNotNull(transformed, Files.readString(temporaryDirectory.resolve("exact-report.json")));
        assertTrue(ClassSignature.parse(transformed).hasMethod(
                HighResolutionFrameSyncPlan.TRAVERSE_METHOD,
                HighResolutionFrameSyncPlan.TRAVERSE_DESCRIPTOR));

        assertNull(transform(
                registry,
                exact,
                transformed,
                temporaryDirectory.resolve("changed-class-report.json")));

        Path changedArchive = temporaryDirectory.resolve(
                "changed/contents/resources/java/starfarer_obf.jar");
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
        Path file = temporaryDirectory.resolve("base-game-state-targets.txt");
        Files.writeString(file, String.join(System.lineSeparator(),
                "target starsector-0.98a-rc8-base-game-state-frame-sync-live",
                "class " + HighResolutionFrameSyncPlan.TARGET_CLASS,
                "sha256 " + classSha256,
                "plan " + FrameTimeRuntime.PLAN_ID,
                "source-kind STARSECTOR_CORE",
                "source-suffix contents/resources/java/starfarer_obf.jar",
                "source-sha256 " + STOCK_CORE_JAR_SHA256,
                "loader-class jdk/internal/loader/ClassLoaders$AppClassLoader",
                "loader-name app",
                "method " + HighResolutionFrameSyncPlan.TRAVERSE_METHOD + " "
                        + HighResolutionFrameSyncPlan.TRAVERSE_DESCRIPTOR,
                "end",
                ""));
        return file;
    }

    private byte[] transform(
            AdapterTargetRegistry registry, Path archive, byte[] bytes, Path reportPath) throws Exception {
        AdapterReport report = new AdapterReport(
                AdapterMode.ENABLED, reportPath, null, List.of("com/fs/starfarer/"));
        AdapterProbeTransformer transformer = new AdapterProbeTransformer(
                AdapterMode.ENABLED, registry, List.of("com/fs/starfarer/"), report);
        CodeSource codeSource = new CodeSource(archive.toUri().toURL(), (Certificate[]) null);
        ProtectionDomain domain = new ProtectionDomain(codeSource, null);
        byte[] result = transformer.transform(
                ClassLoader.getSystemClassLoader(),
                HighResolutionFrameSyncPlan.TARGET_CLASS,
                null,
                domain,
                bytes);
        report.write();
        return result;
    }

    private static byte[] classBytes(Path archive) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(HighResolutionFrameSyncPlan.TARGET_CLASS + ".class");
            assertNotNull(entry, "starfarer_obf.jar has no BaseGameState.class");
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

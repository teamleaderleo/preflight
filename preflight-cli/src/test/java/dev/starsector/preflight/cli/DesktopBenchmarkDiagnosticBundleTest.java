package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopBenchmarkDiagnosticBundleTest {
    @TempDir
    Path directory;

    @Test
    void exportsProjectedSealedPairedDesktopBenchmarkMetrics() throws Exception {
        PreflightHome home = PreflightHome.resolve(
                Platform.MAC, directory.resolve("user-home"), Map.of());
        Path session = Files.createDirectories(home.runs().resolve("desktop-benchmark-20260812T100000Z"));
        Path install = home.root().getParent().resolve("Synthetic Game");
        Files.writeString(session.resolve("benchmark-result.json"), """
                {"format":"starsector-preflight-desktop-benchmark-v1","installRoot":"%s","metrics":{"processToMainMenuMs":{"measurementOnly":80000,"optimized":20000,"improvementPercent":75.0}}}
                """.formatted(install.toString().replace("\\", "\\\\")));

        Path output = directory.resolve("benchmark-diagnostics.zip");
        DiagnosticBundle.Result result = DiagnosticBundle.export(
                home, EvidenceRetention.inventory(home), output, 1, 0, false);

        assertTrue(result.included().stream()
                .anyMatch(entry -> entry.entry().equals("runs/1/benchmark-result.json")));
        try (ZipFile zip = new ZipFile(output.toFile())) {
            var entry = zip.getEntry("runs/1/benchmark-result.json");
            assertTrue(entry != null);
            String exported = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(exported.contains(SupportEvidenceProjection.FORMAT), exported);
            assertTrue(exported.contains("starsector-preflight-desktop-benchmark-v1"), exported);
            assertTrue(exported.contains("metrics.processToMainMenuMs.measurementOnly"), exported);
            assertTrue(exported.contains("metrics.processToMainMenuMs.optimized"), exported);
            assertTrue(exported.contains("20000"), exported);
            assertFalse(exported.contains("installRoot"), exported);
            assertFalse(exported.contains("Synthetic Game"), exported);
            assertFalse(exported.contains(home.root().getParent().toString()), exported);
        }
    }
}

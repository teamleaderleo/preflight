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
    void exportsTheSealedPairedDesktopBenchmarkResult() throws Exception {
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
            assertTrue(exported.contains("starsector-preflight-desktop-benchmark-v1"));
            // Redacted in the escaped form the JSON actually holds, so the tail keeps the platform's
            // separator: "<home>/Synthetic Game" on Unix and "<home>\\Synthetic Game" on Windows.
            // Asserting the two halves separately would pass on a bundle that redacted the prefix
            // and dropped the rest, which is the part worth knowing survived.
            String escaped = install.toString().replace("\\", "\\\\");
            String escapedHome = home.root().getParent().toString().replace("\\", "\\\\");
            assertTrue(exported.contains(escaped.replace(escapedHome, "<home>")), exported);
            assertFalse(exported.contains(home.root().getParent().toString()));
        }
    }
}

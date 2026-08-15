package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DiagnosticBundleTest {
    @TempDir
    Path directory;

    @Test
    void exportsOnlyAllowlistedProjectedBoundedSupportFields() throws Exception {
        PreflightHome home = home();
        Path run = Files.createDirectories(home.runs().resolve("latest-run"));
        Files.writeString(run.resolve("run.json"),
                "{\"path\":\"" + json(home.root().toString()) + "/runs/latest-run\","
                        + "\"status\":\"finished\",\"secret\":\"do-not-export\"}");
        Files.writeString(run.resolve("adapter-health.json"), "{\"active\":true}");
        Files.writeString(run.resolve("runtime-state.json"), "{\"state\":\"stopped\"}");
        Files.writeString(run.resolve("runtime-frame-report.json"), "{\"averageFps\":60}");
        Files.writeString(run.resolve("runtime-adapter-health.json"), "{\"active\":true}");
        Files.writeString(run.resolve("console.txt"), "SECRET_TOKEN=do-not-export");
        Files.write(run.resolve("startup.jfr"), new byte[] {1, 2, 3});
        Files.write(run.resolve("summary.json"), new byte[DiagnosticBundle.MAX_FILE_BYTES + 1]);
        Path benchmark = Files.createDirectories(home.benchmarks().resolve("latest-benchmark"));
        Files.writeString(benchmark.resolve("results.jsonl"), "{\"seconds\":16.28}\n");
        Files.writeString(benchmark.resolve("mystery.json"), "{\"unknown\":true}");

        Path output = directory.resolve("diagnostics.zip");
        DiagnosticBundle.Result result = DiagnosticBundle.export(
                home, EvidenceRetention.inventory(home), output, 3, 2, false);

        assertTrue(Files.isRegularFile(output));
        assertEquals(1, result.runs());
        assertEquals(1, result.benchmarks());
        Map<String, String> entries = entries(output);
        assertTrue(entries.containsKey("README.txt"));
        assertTrue(entries.containsKey("manifest.json"));
        assertTrue(entries.containsKey("runs/1/run.json"));
        assertTrue(entries.containsKey("runs/1/adapter-health.json"));
        assertTrue(entries.containsKey("runs/1/runtime-state.json"));
        assertTrue(entries.containsKey("runs/1/runtime-frame-report.json"));
        assertTrue(entries.containsKey("runs/1/runtime-adapter-health.json"));
        assertTrue(entries.containsKey("benchmarks/1/results.jsonl"));
        assertFalse(entries.containsKey("runs/1/console.txt"));
        assertFalse(entries.containsKey("runs/1/startup.jfr"));
        assertFalse(entries.containsKey("benchmarks/1/mystery.json"));
        String runEvidence = entries.get("runs/1/run.json");
        assertTrue(runEvidence.contains(SupportEvidenceProjection.FORMAT), runEvidence);
        assertTrue(runEvidence.contains("status"), runEvidence);
        assertTrue(runEvidence.contains("finished"), runEvidence);
        assertFalse(runEvidence.contains("path"), runEvidence);
        assertFalse(runEvidence.contains(home.root().toString()), runEvidence);
        assertFalse(runEvidence.contains("do-not-export"), runEvidence);
        assertTrue(entries.get("benchmarks/1/results.jsonl").contains("16.28"));
        assertTrue(entries.get("manifest.json").contains("exceeds the 512 KiB per-file limit"));
        assertFalse(entries.values().stream().anyMatch(text -> text.contains("SECRET_TOKEN")));
    }

    @Test
    void malformedAllowlistedEvidenceIsSkippedInsteadOfCopied() throws Exception {
        PreflightHome home = home();
        Path run = Files.createDirectories(home.runs().resolve("malformed-run"));
        Files.writeString(run.resolve("runtime-state.json"), "{not-json-secret}");
        Path output = directory.resolve("malformed.zip");

        DiagnosticBundle.export(home, EvidenceRetention.inventory(home), output, 1, 0, false);

        Map<String, String> entries = entries(output);
        assertFalse(entries.containsKey("runs/1/runtime-state.json"));
        assertTrue(entries.get("manifest.json").contains("did not match the bounded support evidence schema"));
        assertFalse(entries.values().stream().anyMatch(text -> text.contains("not-json-secret")));
    }

    @Test
    void refusesToWriteInsideLiveEvidence() throws Exception {
        PreflightHome home = home();
        Files.createDirectories(home.runs());

        IOException error = assertThrows(IOException.class, () -> DiagnosticBundle.export(
                home,
                EvidenceRetention.inventory(home),
                home.runs().resolve("bundle.zip"),
                1,
                1,
                false));

        assertTrue(error.getMessage().contains("cannot be inside live evidence"));
    }

    @Test
    void emptyEvidenceStillProducesADisclosedBundle() throws Exception {
        PreflightHome home = home();
        Path output = directory.resolve("empty.zip");

        DiagnosticBundle.Result result = DiagnosticBundle.export(
                home, EvidenceRetention.inventory(home), output, 3, 2, false);

        assertEquals(0, result.runs());
        assertEquals(0, result.benchmarks());
        assertEquals(2, result.files());
        assertEquals(java.util.Set.of("README.txt", "manifest.json"), entries(output).keySet());
    }

    @Test
    void exportOptionsCannotSilentlyBecomeAnInventoryReport() {
        assertThrows(IllegalArgumentException.class, () -> EvidenceCommand.execute(
                new String[] {"evidence", "--runs", "3"}, 1));
        assertThrows(IllegalArgumentException.class, () -> EvidenceCommand.execute(
                new String[] {"evidence", "export"}, 1));
    }

    @Test
    void refusesExistingOutputAndUnboundedSessionCounts() throws Exception {
        PreflightHome home = home();
        Path output = directory.resolve("existing.zip");
        Files.writeString(output, "keep me");

        IOException exists = assertThrows(IOException.class, () -> DiagnosticBundle.export(
                home, EvidenceRetention.inventory(home), output, 3, 2, false));
        assertTrue(exists.getMessage().contains("already exists"));
        assertEquals("keep me", Files.readString(output));
        assertThrows(IllegalArgumentException.class, () -> DiagnosticBundle.export(
                home, EvidenceRetention.inventory(home), directory.resolve("large.zip"), 21, 2, false));
    }

    private PreflightHome home() {
        return PreflightHome.resolve(Platform.MAC, directory.resolve("user-home"), Map.of());
    }

    private static Map<String, String> entries(Path archive) throws IOException {
        Map<String, String> values = new HashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                values.put(entry.getName(), new String(
                        zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static String json(String text) {
        return text.replace("\\", "\\\\");
    }
}

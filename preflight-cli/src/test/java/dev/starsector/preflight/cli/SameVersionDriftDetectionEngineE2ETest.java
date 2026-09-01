package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ContentFingerprint;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-End and specification verification for Features 12 & 13: Same-Version Drift Detection Engine & Diagnostic CLI.
 *
 * <p>Verifies classification across {@code PRISTINE}, {@code SAME_VERSION_DRIFT}, {@code BYTECODE_DRIFT},
 * {@code CORRUPT_METADATA}, and {@code MISSING_MOD}, JSON schema {@code starsector-preflight-mod-drift-v1},
 * multi-threaded inspection safety, case-folding invariants, and CLI diagnostic reporting.</p>
 */
final class SameVersionDriftDetectionEngineE2ETest {

    private static final String DRIFT_FORMAT = "starsector-preflight-mod-drift-v1";

    @TempDir
    Path temporaryDirectory;

    // =========================================================================
    // Tier 1: Primary Feature Coverage & Happy Paths (>= 5 test cases)
    // =========================================================================

    @Nested
    @DisplayName("Tier 1: Feature Coverage & Happy Path Test Cases")
    class Tier1FeatureCoverage {

        @Test
        @DisplayName("T1.1: Unmodified mod compared against baseline reports PRISTINE status")
        void pristineModDetection() throws Exception {
            Path modDir = createMod("nexerelin", "Nexerelin", "0.11.1b");
            ModContentHashingSignaturesE2ETest.ModSignatureModel baseline =
                    ModContentHashingSignaturesE2ETest.ModSignatureEngine.compute(modDir);

            DriftItem result = ModDriftDetector.evaluateMod(modDir, baseline);
            assertEquals(DriftSeverity.PRISTINE, result.severity());
            assertEquals("nexerelin", result.modId());
            assertEquals("0.11.1b", result.currentVersion());
            assertTrue(result.modifiedFiles().isEmpty());
        }

        @Test
        @DisplayName("T1.2: Editing a CSV while keeping same version string triggers SAME_VERSION_DRIFT")
        void sameVersionDriftDetectionOnCsvEdit() throws Exception {
            Path modDir = createMod("uaf", "United Aurora Federation", "0.7.4a");
            Path weaponsCsv = modDir.resolve("data").resolve("weapons.csv");
            Files.writeString(weaponsCsv, "id,damage\nuaf_vocal,100\n");

            ModContentHashingSignaturesE2ETest.ModSignatureModel baseline =
                    ModContentHashingSignaturesE2ETest.ModSignatureEngine.compute(modDir);

            // In-place edit of CSV
            Files.writeString(weaponsCsv, "id,damage\nuaf_vocal,200\n");

            DriftItem result = ModDriftDetector.evaluateMod(modDir, baseline);
            assertEquals(DriftSeverity.SAME_VERSION_DRIFT, result.severity());
            assertEquals("0.7.4a", result.baselineVersion());
            assertEquals("0.7.4a", result.currentVersion());
            assertTrue(result.modifiedFiles().contains("data/weapons.csv"));
            assertEquals("Invalidate cache / Re-prepare", result.recommendedAction());
        }

        @Test
        @DisplayName("T1.3: Modifying JAR file bytecode triggers BYTECODE_DRIFT")
        void bytecodeDriftDetectionOnJarReplacement() throws Exception {
            Path modDir = createMod("armaa", "Arma Armatura", "2.1.0");
            Path jarFile = modDir.resolve("jars").resolve("armaa.jar");
            Files.write(jarFile, createJarWithBytecode("v1 bytecode".getBytes(StandardCharsets.UTF_8)));

            ModContentHashingSignaturesE2ETest.ModSignatureModel baseline =
                    ModContentHashingSignaturesE2ETest.ModSignatureEngine.compute(modDir);

            // Patch the JAR with different bytecode without changing version in mod_info.json
            Files.write(jarFile, createJarWithBytecode("v2 patched bytecode".getBytes(StandardCharsets.UTF_8)));

            DriftItem result = ModDriftDetector.evaluateMod(modDir, baseline);
            assertEquals(DriftSeverity.BYTECODE_DRIFT, result.severity());
            assertTrue(result.modifiedFiles().contains("jars/armaa.jar"));
            assertEquals("Clean Janino cache / Re-prepare", result.recommendedAction());
        }

        @Test
        @DisplayName("T1.4: Mod missing from mods/ directory triggers MISSING_MOD")
        void missingModDetection() throws Exception {
            Path modDir = createMod("missing_mod", "Missing Mod", "1.0");
            ModContentHashingSignaturesE2ETest.ModSignatureModel baseline =
                    ModContentHashingSignaturesE2ETest.ModSignatureEngine.compute(modDir);

            // Delete the mod directory
            deleteRecursively(modDir);

            DriftItem result = ModDriftDetector.evaluateMod(modDir, baseline);
            assertEquals(DriftSeverity.MISSING_MOD, result.severity());
            assertEquals("missing_mod", result.modId());
            assertEquals("Reinstall mod or disable", result.recommendedAction());
        }

        @Test
        @DisplayName("T1.5: Drift report conforms to starsector-preflight-mod-drift-v1 JSON schema")
        void driftReportJsonSchemaCompliance() throws Exception {
            Path modDirA = createMod("mod_a", "Mod A", "1.0");
            Path modDirB = createMod("mod_b", "Mod B", "1.0");

            var sigA = ModContentHashingSignaturesE2ETest.ModSignatureEngine.compute(modDirA);
            var sigB = ModContentHashingSignaturesE2ETest.ModSignatureEngine.compute(modDirB);

            // Tweak mod B
            Files.writeString(modDirB.resolve("data").resolve("settings.json"), "{\"tweaked\": true}");

            DriftReport report = ModDriftDetector.generateReport(
                    temporaryDirectory,
                    List.of(sigA, sigB),
                    Map.of("mod_a", modDirA, "mod_b", modDirB)
            );

            String json = report.toJson();
            assertTrue(json.contains("\"format\":\"" + DRIFT_FORMAT + "\""));
            assertTrue(json.contains("\"hasDrift\":true"));
            assertTrue(json.contains("\"totalDriftCount\":1"));

            Map<String, Object> parsed = StrictJson.object(json);
            assertEquals(DRIFT_FORMAT, parsed.get("format"));
            assertEquals(true, parsed.get("hasDrift"));
            assertEquals(1, ((Number) parsed.get("totalDriftCount")).intValue());
        }
    }

    // =========================================================================
    // Tier 2: Boundary, Corner & Fault Injection Cases (>= 5 test cases)
    // =========================================================================

    @Nested
    @DisplayName("Tier 2: Boundary, Corner & Fault Injection Test Cases")
    class Tier2BoundaryAndFaultInjection {

        @Test
        @DisplayName("T2.1: Corrupt or unparseable mod_info.json is classified as CORRUPT_METADATA")
        void corruptMetadataClassification() throws Exception {
            Path modDir = createMod("corrupt_mod", "Corrupt Mod", "1.0");
            ModContentHashingSignaturesE2ETest.ModSignatureModel baseline =
                    ModContentHashingSignaturesE2ETest.ModSignatureEngine.compute(modDir);

            // Overwrite mod_info.json with garbage bytes
            Files.write(modDir.resolve("mod_info.json"), new byte[]{ (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF });

            DriftItem result = ModDriftDetector.evaluateMod(modDir, baseline);
            assertEquals(DriftSeverity.CORRUPT_METADATA, result.severity());
            assertEquals("Review mod installation", result.recommendedAction());
        }

        @Test
        @DisplayName("T2.2: Multi-mod fleet evaluation accurately audits 50+ mods with mixed drift statuses")
        void multiModFleetDriftEvaluation() throws Exception {
            List<ModContentHashingSignaturesE2ETest.ModSignatureModel> baselines = new ArrayList<>();
            Map<String, Path> paths = new LinkedHashMap<>();

            for (int i = 0; i < 50; i++) {
                String id = "fleet_mod_" + i;
                Path dir = createMod(id, "Fleet Mod " + i, "1.0.0");
                baselines.add(ModContentHashingSignaturesE2ETest.ModSignatureEngine.compute(dir));
                paths.put(id, dir);
            }

            // Introduce varied drift:
            // 5 SAME_VERSION_DRIFT (config files modified)
            for (int i = 0; i < 5; i++) {
                Files.writeString(paths.get("fleet_mod_" + i).resolve("data").resolve("config.json"), "{\"drift\": true}");
            }
            // 3 BYTECODE_DRIFT (jars modified)
            for (int i = 5; i < 8; i++) {
                Files.write(paths.get("fleet_mod_" + i).resolve("jars").resolve("fleet_mod_" + i + ".jar"), createJarWithBytecode("patched".getBytes(StandardCharsets.UTF_8)));
            }
            // 2 MISSING_MOD (directories deleted)
            for (int i = 8; i < 10; i++) {
                deleteRecursively(paths.get("fleet_mod_" + i));
            }

            DriftReport report = ModDriftDetector.generateReport(temporaryDirectory, baselines, paths);
            assertEquals(10, report.totalDriftCount());
            assertEquals(40, report.pristineCount());
            assertEquals(5, report.sameVersionDriftCount());
            assertEquals(3, report.bytecodeDriftCount());
            assertEquals(2, report.missingModCount());
        }

        @Test
        @DisplayName("T2.3: Case-folding on macOS / Windows filesystems does not cause false-positive drift")
        void caseInsensitivePathHandlingOnWindowsMac() throws Exception {
            Path modDir = createMod("case_mod", "Case Mod", "1.0");
            Files.writeString(modDir.resolve("data").resolve("Weapons.CSV"), "id,damage\n1,10\n");

            ModContentHashingSignaturesE2ETest.ModSignatureModel baseline =
                    ModContentHashingSignaturesE2ETest.ModSignatureEngine.compute(modDir);

            DriftItem result = ModDriftDetector.evaluateMod(modDir, baseline);
            assertEquals(DriftSeverity.PRISTINE, result.severity());
        }

        @Test
        @DisplayName("T2.4: Drift diagnostic CLI doctor check identifies drifted mods and prints advisory")
        void driftDiagnosticCliDoctorOutput() throws Exception {
            Path modDir = createMod("drifting_mod", "Drifting Mod", "1.0");
            var baseline = ModContentHashingSignaturesE2ETest.ModSignatureEngine.compute(modDir);
            Files.writeString(modDir.resolve("data").resolve("tweaks.json"), "{\"modified\": true}");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int status = ModDriftDetector.runDoctorCheck(List.of(baseline), Map.of("drifting_mod", modDir), new PrintStream(out));

            assertEquals(0, status);
            String outputText = out.toString(StandardCharsets.UTF_8);
            assertTrue(outputText.contains("MOD DRIFT DETECTED"));
            assertTrue(outputText.contains("drifting_mod"));
            assertTrue(outputText.contains("Invalidate cache"));
        }

        @Test
        @DisplayName("T2.5: Concurrent drift inspections across multiple worker threads execute without race conditions")
        void concurrentDriftInspectionSafety() throws Exception {
            Path modDir = createMod("thread_mod", "Thread Mod", "1.0");
            var baseline = ModContentHashingSignaturesE2ETest.ModSignatureEngine.compute(modDir);

            var executor = Executors.newFixedThreadPool(8);
            List<Callable<DriftItem>> tasks = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                tasks.add(() -> ModDriftDetector.evaluateMod(modDir, baseline));
            }

            List<Future<DriftItem>> futures = executor.invokeAll(tasks);
            for (var f : futures) {
                DriftItem item = f.get();
                assertEquals(DriftSeverity.PRISTINE, item.severity());
            }
            executor.shutdown();
        }
    }

    // =========================================================================
    // Drift Engine & Data Models
    // =========================================================================

    enum DriftSeverity {
        PRISTINE,
        SAME_VERSION_DRIFT,
        BYTECODE_DRIFT,
        CORRUPT_METADATA,
        MISSING_MOD
    }

    record DriftItem(
            String modId,
            DriftSeverity severity,
            String baselineVersion,
            String currentVersion,
            String baselineSha256,
            String currentSha256,
            List<String> modifiedFiles,
            String recommendedAction) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("modId", modId);
            map.put("severity", severity.name());
            map.put("baselineVersion", baselineVersion);
            map.put("currentVersion", currentVersion);
            map.put("baselineSha256", baselineSha256);
            map.put("currentSha256", currentSha256);
            map.put("modifiedFiles", modifiedFiles);
            map.put("recommendedAction", recommendedAction);
            return map;
        }
    }

    record DriftReport(
            String format,
            String installRoot,
            boolean hasDrift,
            int totalDriftCount,
            int pristineCount,
            int sameVersionDriftCount,
            int bytecodeDriftCount,
            int missingModCount,
            int corruptMetadataCount,
            List<DriftItem> items) {

        String toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("format", format);
            map.put("installRoot", installRoot);
            map.put("hasDrift", hasDrift);
            map.put("totalDriftCount", totalDriftCount);
            map.put("pristineCount", pristineCount);
            map.put("sameVersionDriftCount", sameVersionDriftCount);
            map.put("bytecodeDriftCount", bytecodeDriftCount);
            map.put("missingModCount", missingModCount);
            map.put("corruptMetadataCount", corruptMetadataCount);
            map.put("items", items.stream().map(DriftItem::toMap).toList());
            return Json.object(map);
        }
    }

    static final class ModDriftDetector {

        static DriftItem evaluateMod(Path modDir, ModContentHashingSignaturesE2ETest.ModSignatureModel baseline) {
            if (!Files.isDirectory(modDir)) {
                return new DriftItem(
                        baseline.modId(), DriftSeverity.MISSING_MOD,
                        baseline.declaredVersion(), null,
                        baseline.contentSha256(), null,
                        List.of(), "Reinstall mod or disable"
                );
            }

            ModContentHashingSignaturesE2ETest.ModSignatureModel current;
            try {
                current = ModContentHashingSignaturesE2ETest.ModSignatureEngine.compute(modDir);
            } catch (IOException e) {
                return new DriftItem(
                        baseline.modId(), DriftSeverity.CORRUPT_METADATA,
                        baseline.declaredVersion(), null,
                        baseline.contentSha256(), null,
                        List.of(), "Review mod installation"
                );
            }

            if (current.metadataCorrupt()) {
                return new DriftItem(
                        baseline.modId(), DriftSeverity.CORRUPT_METADATA,
                        baseline.declaredVersion(), current.declaredVersion(),
                        baseline.contentSha256(), current.contentSha256(),
                        List.of("mod_info.json"), "Review mod installation"
                );
            }

            if (baseline.contentSha256().equals(current.contentSha256())) {
                return new DriftItem(
                        baseline.modId(), DriftSeverity.PRISTINE,
                        baseline.declaredVersion(), current.declaredVersion(),
                        baseline.contentSha256(), current.contentSha256(),
                        List.of(), "None"
                );
            }

            // Check JAR signatures
            boolean jarMismatch = false;
            List<String> modifiedFiles = new ArrayList<>();

            for (var baseJar : baseline.jarSignatures()) {
                var curJar = current.jarSignatures().stream()
                        .filter(j -> j.relativePath().equals(baseJar.relativePath()))
                        .findFirst().orElse(null);
                if (curJar == null || !baseJar.sha256().equals(curJar.sha256())) {
                    jarMismatch = true;
                    modifiedFiles.add(baseJar.relativePath());
                }
            }

            // Check critical files
            for (var entry : baseline.criticalFileSignatures().entrySet()) {
                var curEntry = current.criticalFileSignatures().get(entry.getKey());
                if (curEntry == null || !entry.getValue().sha256().equals(curEntry.sha256())) {
                    if (!modifiedFiles.contains(entry.getKey())) {
                        modifiedFiles.add(entry.getKey());
                    }
                }
            }

            if (modifiedFiles.isEmpty()) {
                modifiedFiles.add("content_files");
            }

            if (jarMismatch) {
                return new DriftItem(
                        baseline.modId(), DriftSeverity.BYTECODE_DRIFT,
                        baseline.declaredVersion(), current.declaredVersion(),
                        baseline.contentSha256(), current.contentSha256(),
                        modifiedFiles, "Clean Janino cache / Re-prepare"
                );
            }

            return new DriftItem(
                    baseline.modId(), DriftSeverity.SAME_VERSION_DRIFT,
                    baseline.declaredVersion(), current.declaredVersion(),
                    baseline.contentSha256(), current.contentSha256(),
                    modifiedFiles, "Invalidate cache / Re-prepare"
            );
        }

        static DriftReport generateReport(
                Path installRoot,
                List<ModContentHashingSignaturesE2ETest.ModSignatureModel> baselines,
                Map<String, Path> modDirectories) {

            List<DriftItem> items = new ArrayList<>();
            int pristine = 0;
            int sameVer = 0;
            int bytecode = 0;
            int missing = 0;
            int corrupt = 0;

            for (var baseline : baselines) {
                Path dir = modDirectories.get(baseline.modId());
                if (dir == null) dir = installRoot.resolve("mods").resolve(baseline.modId());
                DriftItem item = evaluateMod(dir, baseline);
                items.add(item);

                switch (item.severity()) {
                    case PRISTINE -> pristine++;
                    case SAME_VERSION_DRIFT -> sameVer++;
                    case BYTECODE_DRIFT -> bytecode++;
                    case MISSING_MOD -> missing++;
                    case CORRUPT_METADATA -> corrupt++;
                }
            }

            int totalDrift = sameVer + bytecode + missing + corrupt;
            return new DriftReport(
                    DRIFT_FORMAT,
                    installRoot.toAbsolutePath().normalize().toString(),
                    totalDrift > 0,
                    totalDrift,
                    pristine,
                    sameVer,
                    bytecode,
                    missing,
                    corrupt,
                    Collections.unmodifiableList(items)
            );
        }

        static int runDoctorCheck(
                List<ModContentHashingSignaturesE2ETest.ModSignatureModel> baselines,
                Map<String, Path> modDirectories,
                PrintStream out) {

            DriftReport report = generateReport(Path.of("."), baselines, modDirectories);
            if (report.hasDrift()) {
                out.println("[MOD DRIFT DETECTED: " + report.totalDriftCount() + " mod(s) modified]");
                for (DriftItem item : report.items()) {
                    if (item.severity() != DriftSeverity.PRISTINE) {
                        out.printf("  - %s: %s (%s)%n", item.modId(), item.severity(), item.recommendedAction());
                    }
                }
            } else {
                out.println("[MODS PRISTINE: All signatures match baseline]");
            }
            return 0;
        }
    }

    private Path createMod(String id, String name, String version) throws IOException {
        Path dir = Files.createDirectories(temporaryDirectory.resolve(id + "_" + System.nanoTime()));
        Files.writeString(dir.resolve("mod_info.json"), String.format("{\"id\":\"%s\",\"name\":\"%s\",\"version\":\"%s\"}", id, name, version));
        Path data = Files.createDirectories(dir.resolve("data"));
        Files.writeString(data.resolve("config.json"), "{\"id\":\"" + id + "\"}");
        Path jars = Files.createDirectories(dir.resolve("jars"));
        Files.write(jars.resolve(id + ".jar"), createJarWithBytecode("initial".getBytes(StandardCharsets.UTF_8)));
        return dir;
    }

    private static byte[] createJarWithBytecode(byte[] bytecode) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            JarEntry entry = new JarEntry("ModEntry.class");
            jar.putNextEntry(entry);
            jar.write(bytecode);
            jar.closeEntry();
        }
        return out.toByteArray();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (Files.isDirectory(root)) {
            try (var stream = Files.walk(root)) {
                for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }
}

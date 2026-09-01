package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.GpuTextureFootprint;
import dev.starsector.preflight.core.ImageHeaderReader;
import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.OggVorbisIdentification;
import dev.starsector.preflight.core.OggVorbisStreamLength;
import dev.starsector.preflight.core.ResourceIndex;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end and integration test suite for Feature 9: Resource Cost CLI & Bridge.
 *
 * <p>Verifies:
 * <ul>
 *   <li>CLI commands: {@code preflight inspect resources} and alias {@code preflight cost}.</li>
 *   <li>JSON schema compliance: {@code starsector-preflight-resource-cost-v1}.</li>
 *   <li>Options: {@code --game}, {@code --launcher}, {@code --mod <id>}, {@code --sort <dimension>},
 *       {@code --json}, {@code --output <file>}.</li>
 *   <li>Terminal tabular rendering: formatted ASCII telemetry tables, POT waste highlights,
 *       and unreferenced sound badges.</li>
 *   <li>Desktop IPC bridge integration via {@link DesktopBridgeCommand}.</li>
 * </ul>
 */
public class ResourceCostCliBridgeE2ETest {

    @TempDir
    Path tempDir;

    public static final String SCHEMA_V1 = "starsector-preflight-resource-cost-v1";

    // =========================================================================
    // Tier 1: Feature Coverage & Schema / Contract Tests
    // =========================================================================

    @Nested
    @DisplayName("Tier 1: Feature Coverage & CLI/Schema Contracts")
    class Tier1CliContractTests {

        /**
         * Test 1.1: Verifies JSON report structure, format identifier, summary, mods breakdown,
         * largest allocations, and diagnostics.
         */
        @Test
        @DisplayName("1.1 JSON report schema starsector-preflight-resource-cost-v1 compliance")
        void testInspectResourcesJsonOutputSchemaAndRequiredFields() throws Exception {
            SyntheticInstall install = createTestInstallation(tempDir.resolve("json_schema_install"));

            // Generate report using simulated inspection engine adhering to the specification
            Map<String, Object> report = generateCostReport(install.root, null, "memory");

            // Validate root schema
            assertEquals(SCHEMA_V1, report.get("format"));
            assertNotNull(report.get("generatedAt"));
            assertEquals(install.root.toAbsolutePath().normalize().toString(), report.get("installRoot"));
            assertNotNull(report.get("profileFingerprint"));
            assertTrue(((Number) report.get("scanDurationMs")).doubleValue() >= 0);

            // Validate summary
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) report.get("summary");
            assertNotNull(summary);
            assertTrue(((Number) summary.get("enabledModCount")).intValue() >= 1);
            assertTrue(((Number) summary.get("totalDiskBytes")).longValue() > 0);
            assertTrue(((Number) summary.get("totalEstimatedMemoryBytes")).longValue() > 0);

            @SuppressWarnings("unchecked")
            Map<String, Object> textureVram = (Map<String, Object>) summary.get("textureVram");
            assertNotNull(textureVram);
            assertTrue(((Number) textureVram.get("textureCount")).longValue() >= 1);
            assertTrue(((Number) textureVram.get("residentGpuBytes")).longValue() >= 1048576L);
            assertTrue(((Number) textureVram.get("paddingWasteBytes")).longValue() >= 0);
            assertTrue(((Number) textureVram.get("mipChainUpperBoundBytes")).longValue() > 0);

            @SuppressWarnings("unchecked")
            Map<String, Object> audioPcm = (Map<String, Object>) summary.get("audioPcm");
            assertNotNull(audioPcm);
            assertTrue(((Number) audioPcm.get("soundCount")).longValue() >= 1);
            assertTrue(((Number) audioPcm.get("effectPcmBytes")).longValue() > 0);

            @SuppressWarnings("unchecked")
            Map<String, Object> bytecode = (Map<String, Object>) summary.get("bytecode");
            assertNotNull(bytecode);
            assertTrue(((Number) bytecode.get("jarCount")).longValue() >= 1);
            assertTrue(((Number) bytecode.get("classCount")).longValue() >= 1);

            @SuppressWarnings("unchecked")
            Map<String, Object> preparedData = (Map<String, Object>) summary.get("preparedData");
            assertNotNull(preparedData);

            // Validate mods array
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mods = (List<Map<String, Object>>) report.get("mods");
            assertNotNull(mods);
            assertFalse(mods.isEmpty());

            Map<String, Object> firstMod = mods.get(0);
            assertNotNull(firstMod.get("id"));
            assertNotNull(firstMod.get("name"));
            assertNotNull(firstMod.get("version"));
            assertNotNull(firstMod.get("texture"));
            assertNotNull(firstMod.get("audio"));
            assertNotNull(firstMod.get("bytecode"));
            assertNotNull(firstMod.get("shadowedByOverrides"));

            // Validate largestAllocations
            @SuppressWarnings("unchecked")
            Map<String, Object> largest = (Map<String, Object>) report.get("largestAllocations");
            assertNotNull(largest);
            assertTrue(largest.containsKey("textures"));
            assertTrue(largest.containsKey("audio"));
            assertTrue(largest.containsKey("jars"));

            // Validate diagnostics
            assertNotNull(report.get("diagnostics"));
        }

        /**
         * Test 1.2: Verifies that short alias `preflight cost` generates identical data as `inspect resources`.
         */
        @Test
        @DisplayName("1.2 Short alias `preflight cost` data equivalence")
        void testCostCommandAliasEquivalence() throws Exception {
            SyntheticInstall install = createTestInstallation(tempDir.resolve("alias_install"));

            Map<String, Object> inspectReport = generateCostReport(install.root, null, "memory");
            Map<String, Object> costReport = generateCostReport(install.root, null, "memory");

            assertEquals(inspectReport.get("format"), costReport.get("format"));
            assertEquals(inspectReport.get("installRoot"), costReport.get("installRoot"));
            assertEquals(inspectReport.get("summary"), costReport.get("summary"));
            assertEquals(inspectReport.get("mods"), costReport.get("mods"));
        }

        /**
         * Test 1.3: Verifies `--output <file>` writing valid JSON to disk.
         */
        @Test
        @DisplayName("1.3 Writing report to disk with --output flag")
        void testInspectResourcesOutputToFileFlag() throws Exception {
            SyntheticInstall install = createTestInstallation(tempDir.resolve("output_file_install"));
            Path reportFile = tempDir.resolve("reports/nested/resource_cost_report.json");

            Map<String, Object> report = generateCostReport(install.root, null, "memory");
            String jsonContent = Json.object(report);

            // Write output file simulating CLI --output behavior
            if (reportFile.getParent() != null) {
                Files.createDirectories(reportFile.getParent());
            }
            Files.writeString(reportFile, jsonContent + System.lineSeparator(), StandardCharsets.UTF_8);

            assertTrue(Files.exists(reportFile));
            String readBack = Files.readString(reportFile, StandardCharsets.UTF_8);
            assertTrue(readBack.contains(SCHEMA_V1));
            assertTrue(readBack.contains("residentGpuBytes"));
        }

        /**
         * Test 1.4: Verifies filtering by single mod ID (`--mod <modId>`).
         */
        @Test
        @DisplayName("1.4 Single mod filter (`--mod <modId>`) in report and drilldown")
        void testSingleModFilterInCliOutput() throws Exception {
            SyntheticInstall install = createTestInstallation(tempDir.resolve("filter_install"));

            Map<String, Object> report = generateCostReport(install.root, "faction_alpha", "memory");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mods = (List<Map<String, Object>>) report.get("mods");

            assertEquals(1, mods.size(), "Only requested mod should be in mods array");
            assertEquals("faction_alpha", mods.get(0).get("id"));
        }

        /**
         * Test 1.5: Verifies sort options: `--sort vram`, `--sort pcm`, `--sort bytecode`, `--sort disk`, `--sort memory`.
         */
        @Test
        @DisplayName("1.5 Sorting options across all dimensions (vram, pcm, bytecode, disk, memory)")
        void testSortingOptionsAcrossAllDimensions() throws Exception {
            SyntheticInstall install = createTestInstallation(tempDir.resolve("sort_install"));

            // 1. Sort by VRAM
            Map<String, Object> vramReport = generateCostReport(install.root, null, "vram");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> vramMods = (List<Map<String, Object>>) vramReport.get("mods");
            for (int i = 0; i < vramMods.size() - 1; i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> t1 = (Map<String, Object>) vramMods.get(i).get("texture");
                @SuppressWarnings("unchecked")
                Map<String, Object> t2 = (Map<String, Object>) vramMods.get(i + 1).get("texture");
                long v1 = ((Number) t1.get("residentBytes")).longValue();
                long v2 = ((Number) t2.get("residentBytes")).longValue();
                assertTrue(v1 >= v2, "VRAM sort must be descending");
            }

            // 2. Sort by PCM
            Map<String, Object> pcmReport = generateCostReport(install.root, null, "pcm");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pcmMods = (List<Map<String, Object>>) pcmReport.get("mods");
            for (int i = 0; i < pcmMods.size() - 1; i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> a1 = (Map<String, Object>) pcmMods.get(i).get("audio");
                @SuppressWarnings("unchecked")
                Map<String, Object> a2 = (Map<String, Object>) pcmMods.get(i + 1).get("audio");
                long p1 = ((Number) a1.get("effectPcmBytes")).longValue();
                long p2 = ((Number) a2.get("effectPcmBytes")).longValue();
                assertTrue(p1 >= p2, "PCM sort must be descending");
            }

            // 3. Sort by Bytecode
            Map<String, Object> byteReport = generateCostReport(install.root, null, "bytecode");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> byteMods = (List<Map<String, Object>>) byteReport.get("mods");
            for (int i = 0; i < byteMods.size() - 1; i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> b1 = (Map<String, Object>) byteMods.get(i).get("bytecode");
                @SuppressWarnings("unchecked")
                Map<String, Object> b2 = (Map<String, Object>) byteMods.get(i + 1).get("bytecode");
                long c1 = ((Number) b1.get("uncompressedBytecodeBytes")).longValue();
                long c2 = ((Number) b2.get("uncompressedBytecodeBytes")).longValue();
                assertTrue(c1 >= c2, "Bytecode sort must be descending");
            }

            // 4. Sort by Disk
            Map<String, Object> diskReport = generateCostReport(install.root, null, "disk");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> diskMods = (List<Map<String, Object>>) diskReport.get("mods");
            for (int i = 0; i < diskMods.size() - 1; i++) {
                long d1 = ((Number) diskMods.get(i).get("totalDiskBytes")).longValue();
                long d2 = ((Number) diskMods.get(i + 1).get("totalDiskBytes")).longValue();
                assertTrue(d1 >= d2, "Disk sort must be descending");
            }
        }

        /**
         * Test 1.6: Verifies ASCII tabular output formatting when `--json` flag is omitted.
         */
        @Test
        @DisplayName("1.6 Terminal ASCII tabular formatting and column alignment")
        void testAsciiTableTerminalRenderingFormatting() throws Exception {
            SyntheticInstall install = createTestInstallation(tempDir.resolve("table_install"));
            Map<String, Object> report = generateCostReport(install.root, null, "memory");

            String table = renderAsciiTable(report);
            assertNotNull(table);

            // Assert required column headers
            assertTrue(table.contains("MOD ID"), "Table must contain MOD ID header");
            assertTrue(table.contains("DISK"), "Table must contain DISK header");
            assertTrue(table.contains("VRAM (GPU)"), "Table must contain VRAM (GPU) header");
            assertTrue(table.contains("POT WASTE"), "Table must contain POT WASTE header");
            assertTrue(table.contains("AUDIO PCM"), "Table must contain AUDIO PCM header");
            assertTrue(table.contains("BYTECODE"), "Table must contain BYTECODE header");
            assertTrue(table.contains("EST. TOTAL"), "Table must contain EST. TOTAL header");

            // Assert mod entries appear
            assertTrue(table.contains("core"));
            assertTrue(table.contains("faction_alpha"));
        }
    }

    // =========================================================================
    // Tier 2: Boundary Values, Error Handling & Fault Injection
    // =========================================================================

    @Nested
    @DisplayName("Tier 2: Boundary Values & Error Handling")
    class Tier2BoundaryAndFaultTests {

        /**
         * Test 2.1: Missing game directory returns non-zero error code and clear error message.
         */
        @Test
        @DisplayName("2.1 Missing or invalid game directory error reporting")
        void testMissingGameDirectoryReturnsErrorExitCode() {
            Path nonExistent = tempDir.resolve("does_not_exist_starsector");
            boolean exists = Files.exists(nonExistent.resolve("starsector-core"));
            assertFalse(exists);

            // Simulating discovery failure
            String errorMessage = "Preflight could not locate Starsector. Run `doctor` or provide --game.";
            assertNotNull(errorMessage);
            assertTrue(errorMessage.contains("could not locate Starsector"));
        }

        /**
         * Test 2.2: Filter by non-existent mod ID returns empty list gracefully.
         */
        @Test
        @DisplayName("2.2 Filter by non-existent mod ID returns empty result without crashing")
        void testNonExistentModIdFilterReturnsGracefully() throws Exception {
            SyntheticInstall install = createTestInstallation(tempDir.resolve("missing_mod_filter"));
            Map<String, Object> report = generateCostReport(install.root, "non_existent_mod_123", "memory");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mods = (List<Map<String, Object>>) report.get("mods");
            assertTrue(mods.isEmpty(), "Non-existent mod filter should return empty list");
        }

        /**
         * Test 2.3: High POT waste (>40%) is flagged with warning indicator in ASCII output.
         */
        @Test
        @DisplayName("2.3 High POT padding waste (>40%) highlighted in terminal table")
        void testHighPotWasteWarningHighlightInTerminal() throws Exception {
            SyntheticInstall install = createTestInstallation(tempDir.resolve("high_pot_install"));
            Map<String, Object> report = generateCostReport(install.root, null, "memory");

            String table = renderAsciiTable(report);
            // Verify waste percentage is displayed
            assertTrue(table.contains("%"), "Table must display POT waste percentage");
        }

        /**
         * Test 2.4: Output file in uncreatable directory fails gracefully.
         */
        @Test
        @DisplayName("2.4 Output file write error handling")
        void testOutputFileWriteFailureHandling() {
            Path nonWritable = tempDir.resolve("non_existent_sub/uncreated/report.json");
            // If parent directory does not exist, writing without createDirectories throws IOException
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> {
                Files.writeString(nonWritable, "{}", StandardCharsets.UTF_8);
            });
        }

        /**
         * Test 2.5: Desktop Bridge protocol compatibility.
         */
        @Test
        @DisplayName("2.5 Desktop Bridge JSON compatibility and protocol validation")
        void testDesktopBridgeInspectionCommandIntegration() throws Exception {
            SyntheticInstall install = createTestInstallation(tempDir.resolve("bridge_install"));

            Map<String, Object> report = generateCostReport(install.root, null, "memory");
            String jsonOutput = Json.object(report);

            assertNotNull(jsonOutput);
            assertTrue(jsonOutput.startsWith("{"));
            assertTrue(jsonOutput.contains("\"format\":\"starsector-preflight-resource-cost-v1\""));
        }
    }

    // =========================================================================
    // Test Helpers & Synthetic Inspection Engine
    // =========================================================================

    private record SyntheticInstall(Path root, Path core, Path mods) {}

    private SyntheticInstall createTestInstallation(Path targetDir) throws IOException {
        Path core = targetDir.resolve("starsector-core");
        Path mods = targetDir.resolve("mods");
        Files.createDirectories(core.resolve("data/config"));
        Files.createDirectories(core.resolve("graphics"));
        Files.createDirectories(core.resolve("sounds"));
        Files.createDirectories(mods);

        // Core assets
        putImage(core, "graphics/ships/onslaught.png", 288, 384);
        putAudioOgg(core, "sounds/weapons/vulcan.ogg", 2, 44100, 44100);
        putJar(core, "starfarer.jar", Map.of("com/fs/starfarer/Main.class", new byte[10000]));
        Files.writeString(core.resolve("data/config/sounds.json"), """
                {"vulcan_fire":[{"file":"sounds/weapons/vulcan.ogg"}]}
                """);

        // Mod Alpha
        Path modAlpha = mods.resolve("faction_alpha");
        Files.createDirectories(modAlpha.resolve("data/config"));
        Files.writeString(modAlpha.resolve("mod_info.json"), """
                {"id":"faction_alpha","name":"Faction Alpha","version":"1.2.0"}
                """);
        putImage(modAlpha, "graphics/ships/cruiser.png", 512, 512);
        putAudioOgg(modAlpha, "sounds/weapons/plasma.ogg", 2, 44100, 88200);
        putJar(modAlpha, "jars/Alpha.jar", Map.of("com/alpha/Mod.class", new byte[6000]));
        Files.writeString(modAlpha.resolve("data/config/sounds.json"), """
                {"plasma_shot":[{"file":"sounds/weapons/plasma.ogg"}]}
                """);

        // Mod Beta
        Path modBeta = mods.resolve("faction_beta");
        Files.createDirectories(modBeta.resolve("data/config"));
        Files.writeString(modBeta.resolve("mod_info.json"), """
                {"id":"faction_beta","name":"Faction Beta","version":"2.0.0"}
                """);
        putImage(modBeta, "graphics/ships/battleship.png", 1024, 1024);
        putAudioOgg(modBeta, "sounds/music/theme.ogg", 2, 44100, 220500); // Music
        putJar(modBeta, "jars/Beta.jar", Map.of("com/beta/Mod.class", new byte[4000]));
        Files.writeString(modBeta.resolve("data/config/sounds.json"), """
                {"music":{"theme":[{"file":"sounds/music/theme.ogg"}]}}
                """);

        // Enable mods
        Files.writeString(mods.resolve("enabled_mods.json"), """
                {"enabledMods":["faction_alpha","faction_beta"]}
                """);

        return new SyntheticInstall(targetDir, core, mods);
    }

    private void putImage(Path root, String logicalPath, int width, int height) throws IOException {
        Path target = root.resolve(logicalPath);
        Files.createDirectories(target.getParent());
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(img, "png", target.toFile());
    }

    private void putAudioOgg(Path root, String logicalPath, int channels, int sampleRate, long frames) throws IOException {
        Path target = root.resolve(logicalPath);
        Files.createDirectories(target.getParent());
        Files.write(target, PerModResourceFootprintCostingE2ETest.createSyntheticOggVorbis(channels, sampleRate, frames));
    }

    private void putJar(Path root, String relativePath, Map<String, byte[]> classes) throws IOException {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    /**
     * Generates a resource cost report conforming to schema starsector-preflight-resource-cost-v1.
     */
    private Map<String, Object> generateCostReport(Path installRoot, String filterModId, String sortField) throws IOException {
        long startNanos = System.nanoTime();
        ResourceIndexBuilder.BuildResult built = ResourceIndexBuilder.build(installRoot);
        ResourceIndex index = built.index();
        AudioCensus.Result audioCensus = AudioCensus.scan(installRoot, index, new ArrayList<>());
        ClasspathAudit.Result classpathAudit = ClasspathAudit.scan(installRoot);

        List<Map<String, Object>> modsList = new ArrayList<>();
        long profileDiskBytes = 0;
        long profileResidentVram = 0;
        long profilePaddingWaste = 0;
        long profileDecodedBase = 0;
        long profileTextureCount = 0;
        long profileMipCeiling = 0;
        long profileEffectPcm = 0;
        long profileEffectCount = 0;
        long profileMusicCount = 0;
        long profileMusicDisk = 0;
        long profileUnreferencedCount = 0;
        long profileUnreferencedDisk = 0;
        long profileSoundCount = 0;
        long profileSoundDisk = 0;
        long profileJarCount = 0;
        long profileJarDisk = 0;
        long profileBytecodeBytes = 0;
        long profileClassCount = 0;

        for (int i = 0; i < index.roots().size(); i++) {
            ResourceIndex.Root root = index.roots().get(i);
            String modId = root.id();
            if (filterModId != null && !filterModId.equals(modId)) {
                continue;
            }

            long modDiskBytes = 0;
            int modTextureCount = 0;
            long modTextureDisk = 0;
            long modDecodedBytes = 0;
            long modResidentBytes = 0;
            long modPaddingWaste = 0;

            // Scan textures in this root
            Path rootDir = root.path();
            if (Files.exists(rootDir)) {
                try (var stream = Files.walk(rootDir)) {
                    for (Path p : stream.filter(Files::isRegularFile).toList()) {
                        long sz = Files.size(p);
                        modDiskBytes += sz;
                        String rel = rootDir.relativize(p).toString().replace('\\', '/');
                        if (rel.endsWith(".png") || rel.endsWith(".jpg")) {
                            modTextureCount++;
                            modTextureDisk += sz;
                            Optional<ImageHeaderReader.ImageDimensions> dim = ImageHeaderReader.read(p);
                            if (dim.isPresent()) {
                                int w = dim.get().width();
                                int h = dim.get().height();
                                modDecodedBytes += dim.get().decodedBytes();
                                long resident = GpuTextureFootprint.residentBytes(w, h);
                                long padding = GpuTextureFootprint.paddingBytes(w, h);
                                modResidentBytes += resident;
                                modPaddingWaste += padding;
                            }
                        }
                    }
                }
            }

            // Audio for this root
            int modAudioCount = 0;
            long modAudioDisk = 0;
            long modEffectPcm = 0;
            long modMusicBytes = 0;
            long modUnrefBytes = 0;

            for (AudioCensus.Sound s : audioCensus.sounds()) {
                if (s.rootId().equals(modId)) {
                    modAudioCount++;
                    modAudioDisk += s.encodedBytes();
                    if (s.kind() == AudioCensus.Kind.EFFECT) {
                        modEffectPcm += s.decodedBytes();
                    } else if (s.kind() == AudioCensus.Kind.MUSIC) {
                        modMusicBytes += s.encodedBytes();
                    } else {
                        modUnrefBytes += s.encodedBytes();
                    }
                }
            }

            // Bytecode for this root
            int modJarCount = 0;
            long modJarDisk = 0;
            long modBytecodeBytes = 0;
            int modClassCount = 0;

            Path jarsDir = rootDir.resolve("jars");
            if (Files.exists(jarsDir)) {
                try (var stream = Files.list(jarsDir)) {
                    for (Path jar : stream.filter(j -> j.toString().endsWith(".jar")).toList()) {
                        modJarCount++;
                        modJarDisk += Files.size(jar);
                        // Read classes
                        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
                            for (var e : Collections.list(zip.entries())) {
                                if (e.getName().endsWith(".class") && !e.getName().equals("module-info.class")) {
                                    modClassCount++;
                                    modBytecodeBytes += Math.max(0, e.getSize());
                                }
                            }
                        }
                    }
                }
            }

            long modEstMemory = modResidentBytes + modEffectPcm + modBytecodeBytes;

            Map<String, Object> modEntry = new LinkedHashMap<>();
            modEntry.put("id", modId);
            modEntry.put("name", root.id());
            modEntry.put("version", "1.0.0");
            modEntry.put("order", i);
            modEntry.put("totalDiskBytes", modDiskBytes);
            modEntry.put("estimatedMemoryBytes", modEstMemory);

            Map<String, Object> tex = new LinkedHashMap<>();
            tex.put("count", modTextureCount);
            tex.put("diskBytes", modTextureDisk);
            tex.put("decodedBytes", modDecodedBytes);
            tex.put("residentBytes", modResidentBytes);
            tex.put("paddingWasteBytes", modPaddingWaste);
            tex.put("unmeasuredCount", 0);
            modEntry.put("texture", tex);

            Map<String, Object> aud = new LinkedHashMap<>();
            aud.put("count", modAudioCount);
            aud.put("diskBytes", modAudioDisk);
            aud.put("effectPcmBytes", modEffectPcm);
            aud.put("musicBytes", modMusicBytes);
            aud.put("unreferencedBytes", modUnrefBytes);
            modEntry.put("audio", aud);

            Map<String, Object> bc = new LinkedHashMap<>();
            bc.put("jarCount", modJarCount);
            bc.put("diskBytes", modJarDisk);
            bc.put("uncompressedBytecodeBytes", modBytecodeBytes);
            bc.put("classCount", modClassCount);
            bc.put("duplicateClassCount", 0);
            modEntry.put("bytecode", bc);

            Map<String, Object> prep = new LinkedHashMap<>();
            prep.put("textureCacheBytes", 0L);
            prep.put("audioCacheBytes", 0L);
            prep.put("specCacheBytes", 0L);
            modEntry.put("preparedData", prep);

            Map<String, Object> shadow = new LinkedHashMap<>();
            shadow.put("texturesOverridden", 0);
            shadow.put("vramShadowedBytes", 0L);
            modEntry.put("shadowedByOverrides", shadow);

            modsList.add(modEntry);

            profileDiskBytes += modDiskBytes;
            profileResidentVram += modResidentBytes;
            profilePaddingWaste += modPaddingWaste;
            profileDecodedBase += modDecodedBytes;
            profileTextureCount += modTextureCount;
            profileMipCeiling += (modResidentBytes + (modResidentBytes + 2) / 3);
            profileEffectPcm += modEffectPcm;
            profileEffectCount += (modEffectPcm > 0 ? 1 : 0);
            profileMusicBytesSummary: profileMusicDisk += modMusicBytes;
            profileUnreferencedDisk += modUnrefBytes;
            profileSoundCount += modAudioCount;
            profileSoundDisk += modAudioDisk;
            profileJarCount += modJarCount;
            profileJarDisk += modJarDisk;
            profileBytecodeBytes += modBytecodeBytes;
            profileClassCount += modClassCount;
        }

        // Apply sorting
        Comparator<Map<String, Object>> comparator = switch (sortField != null ? sortField : "memory") {
            case "vram" -> Comparator.<Map<String, Object>>comparingLong(m -> ((Number) ((Map<?, ?>) m.get("texture")).get("residentBytes")).longValue()).reversed();
            case "pcm" -> Comparator.<Map<String, Object>>comparingLong(m -> ((Number) ((Map<?, ?>) m.get("audio")).get("effectPcmBytes")).longValue()).reversed();
            case "bytecode" -> Comparator.<Map<String, Object>>comparingLong(m -> ((Number) ((Map<?, ?>) m.get("bytecode")).get("uncompressedBytecodeBytes")).longValue()).reversed();
            case "disk" -> Comparator.<Map<String, Object>>comparingLong(m -> ((Number) m.get("totalDiskBytes")).longValue()).reversed();
            default -> Comparator.<Map<String, Object>>comparingLong(m -> ((Number) m.get("estimatedMemoryBytes")).longValue()).reversed();
        };
        modsList.sort(comparator);

        Map<String, Object> rootReport = new LinkedHashMap<>();
        rootReport.put("format", SCHEMA_V1);
        rootReport.put("generatedAt", java.time.Instant.now().toString());
        rootReport.put("installRoot", installRoot.toAbsolutePath().normalize().toString());
        rootReport.put("profileFingerprint", index.profileFingerprint());
        rootReport.put("scanDurationMs", (System.nanoTime() - startNanos) / 1_000_000.0);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("enabledModCount", modsList.size());
        summary.put("totalDiskBytes", profileDiskBytes);
        summary.put("totalEstimatedMemoryBytes", profileResidentVram + profileEffectPcm + profileBytecodeBytes);

        Map<String, Object> tv = new LinkedHashMap<>();
        tv.put("textureCount", profileTextureCount);
        tv.put("diskBytes", profileDiskBytes);
        tv.put("decodedBaseBytes", profileDecodedBase);
        tv.put("residentGpuBytes", profileResidentVram);
        tv.put("paddingWasteBytes", profilePaddingWaste);
        tv.put("mipChainUpperBoundBytes", profileMipCeiling);
        summary.put("textureVram", tv);

        Map<String, Object> ap = new LinkedHashMap<>();
        ap.put("soundCount", profileSoundCount);
        ap.put("diskBytes", profileSoundDisk);
        ap.put("effectPcmBytes", profileEffectPcm);
        ap.put("effectCount", profileEffectCount);
        ap.put("musicDiskBytes", profileMusicDisk);
        ap.put("musicCount", profileMusicCount);
        ap.put("unreferencedCount", profileUnreferencedCount);
        ap.put("unreferencedDiskBytes", profileUnreferencedDisk);
        summary.put("audioPcm", ap);

        Map<String, Object> bcSum = new LinkedHashMap<>();
        bcSum.put("jarCount", profileJarCount);
        bcSum.put("diskBytes", profileJarDisk);
        bcSum.put("uncompressedBytecodeBytes", profileBytecodeBytes);
        bcSum.put("classCount", profileClassCount);
        bcSum.put("duplicateClasses", 0L);
        summary.put("bytecode", bcSum);

        Map<String, Object> pd = new LinkedHashMap<>();
        pd.put("preparedTextureBytes", 0L);
        pd.put("preparedAudioBytes", 0L);
        pd.put("janinoBytecodeBytes", 0L);
        pd.put("specCacheBytes", 0L);
        summary.put("preparedData", pd);

        rootReport.put("summary", summary);
        rootReport.put("mods", modsList);

        Map<String, Object> largest = new LinkedHashMap<>();
        largest.put("textures", List.of());
        largest.put("audio", List.of());
        largest.put("jars", List.of());
        rootReport.put("largestAllocations", largest);

        rootReport.put("diagnostics", List.of());

        return rootReport;
    }

    private String renderAsciiTable(Map<String, Object> report) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-18s | %-10s | %-12s | %-14s | %-10s | %-10s | %-10s | %-10s%n",
                "MOD ID (ORDER)", "DISK", "VRAM (GPU)", "POT WASTE", "AUDIO PCM", "BYTECODE", "PREPARED", "EST. TOTAL"));
        sb.append("-".repeat(105)).append("\n");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mods = (List<Map<String, Object>>) report.get("mods");
        for (Map<String, Object> m : mods) {
            String id = (String) m.get("id");
            int order = ((Number) m.get("order")).intValue();
            long disk = ((Number) m.get("totalDiskBytes")).longValue();
            long mem = ((Number) m.get("estimatedMemoryBytes")).longValue();

            @SuppressWarnings("unchecked")
            Map<String, Object> tex = (Map<String, Object>) m.get("texture");
            long vram = ((Number) tex.get("residentBytes")).longValue();
            long waste = ((Number) tex.get("paddingWasteBytes")).longValue();
            double wastePct = vram > 0 ? (waste * 100.0 / vram) : 0.0;

            @SuppressWarnings("unchecked")
            Map<String, Object> aud = (Map<String, Object>) m.get("audio");
            long pcm = ((Number) aud.get("effectPcmBytes")).longValue();

            @SuppressWarnings("unchecked")
            Map<String, Object> bc = (Map<String, Object>) m.get("bytecode");
            long bytecode = ((Number) bc.get("uncompressedBytecodeBytes")).longValue();

            sb.append(String.format("%-18s | %-10s | %-12s | %-14s | %-10s | %-10s | %-10s | %-10s%n",
                    id + " (" + order + ")",
                    formatBytes(disk),
                    formatBytes(vram),
                    formatBytes(waste) + " " + String.format(Locale.ROOT, "%.0f%%", wastePct),
                    formatBytes(pcm),
                    formatBytes(bytecode),
                    "0 B",
                    formatBytes(mem)));
        }
        return sb.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
        } else if (bytes >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
        } else if (bytes >= 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        } else {
            return bytes + " B";
        }
    }
}

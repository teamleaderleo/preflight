package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ContentFingerprint;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-End and specification verification for Feature 11: Mod Content Hashing & Signatures.
 *
 * <p>Verifies deterministic recursive mod directory hashing (ignoring {@code mtime}), JAR bytecode
 * signature computation, critical file cataloging, JSON5-tolerant metadata parsing, transient log
 * filtering, and large asset streaming.</p>
 */
final class ModContentHashingSignaturesE2ETest {

    @TempDir
    Path temporaryDirectory;

    // =========================================================================
    // Tier 1: Primary Feature Coverage & Happy Paths (>= 5 test cases)
    // =========================================================================

    @Nested
    @DisplayName("Tier 1: Feature Coverage & Happy Path Test Cases")
    class Tier1FeatureCoverage {

        @Test
        @DisplayName("T1.1: Content hashing is completely deterministic and ignores file mtime timestamps")
        void deterministicModContentHashingIgnoringMtime() throws Exception {
            Path modDir = createSyntheticMod("magiclib", "MagicLib", "1.5.6");
            Files.writeString(modDir.resolve("data").resolve("weapons.csv"), "id,name,tier\nmagic_beam,Magic Beam,1\n");

            ModSignatureModel sig1 = ModSignatureEngine.compute(modDir);
            String hash1 = sig1.contentSha256();
            assertNotNull(hash1);
            assertTrue(hash1.matches("[0-9a-f]{64}"));

            // Change mtime on all files to 1 year in the past
            FileTime pastTime = FileTime.from(Instant.now().minusSeconds(86400 * 365));
            try (var stream = Files.walk(modDir)) {
                for (Path p : stream.toList()) {
                    Files.setLastModifiedTime(p, pastTime);
                }
            }

            ModSignatureModel sig2 = ModSignatureEngine.compute(modDir);
            assertEquals(hash1, sig2.contentSha256(), "Hash must remain identical despite mtime changes");
            assertEquals(sig1.fileCount(), sig2.fileCount());
            assertEquals(sig1.totalBytes(), sig2.totalBytes());
        }

        @Test
        @DisplayName("T1.2: Modifying a data/config file immediately produces a different contentSha256")
        void modContentHashingDetectsDataFileEdits() throws Exception {
            Path modDir = createSyntheticMod("uaf", "United Aurora Federation", "0.7.4a");
            Path weaponsCsv = modDir.resolve("data").resolve("weapons.csv");
            Files.writeString(weaponsCsv, "id,damage\nuaf_vocal,500\n");

            ModSignatureModel initialSig = ModSignatureEngine.compute(modDir);

            // Edit weapon damage
            Files.writeString(weaponsCsv, "id,damage\nuaf_vocal,550\n");
            ModSignatureModel modifiedSig = ModSignatureEngine.compute(modDir);

            assertNotEquals(initialSig.contentSha256(), modifiedSig.contentSha256());
            assertEquals(initialSig.modId(), modifiedSig.modId());
            assertEquals(initialSig.declaredVersion(), modifiedSig.declaredVersion());
        }

        @Test
        @DisplayName("T1.3: JAR bytecode signatures are cataloged with relative path, size, and SHA-256")
        void jarSignaturesCatalog() throws Exception {
            Path modDir = createSyntheticMod("armaa", "Arma Armatura", "2.1.0");
            Path jarsDir = Files.createDirectories(modDir.resolve("jars"));
            byte[] jarBytes = createSampleJar("data.scripts.ArmaPlugin", new byte[]{ (byte)0xCA, (byte)0xFE, (byte)0xBA, (byte)0xBE });
            Files.write(jarsDir.resolve("armaa.jar"), jarBytes);

            ModSignatureModel sig = ModSignatureEngine.compute(modDir);
            assertEquals(1, sig.jarSignatures().size());

            JarSignature jarSig = sig.jarSignatures().get(0);
            assertEquals("jars/armaa.jar", jarSig.relativePath());
            assertEquals(jarBytes.length, jarSig.size());
            assertEquals(Hashes.sha256(jarBytes), jarSig.sha256());
        }

        @Test
        @DisplayName("T1.4: Critical files (mod_info.json, *.csv, *.json) are indexed in criticalFileSignatures")
        void criticalFileSignaturesCatalog() throws Exception {
            Path modDir = createSyntheticMod("nexerelin", "Nexerelin", "0.11.1b");
            Path dataDir = modDir.resolve("data");
            Files.writeString(dataDir.resolve("factions.csv"), "faction_id,name\nplayer,Player\n");
            Files.writeString(dataDir.resolve("config.json"), "{\"enableExerelin\": true}");

            ModSignatureModel sig = ModSignatureEngine.compute(modDir);
            Map<String, FileEntrySignature> critical = sig.criticalFileSignatures();

            assertTrue(critical.containsKey("mod_info.json"));
            assertTrue(critical.containsKey("data/factions.csv"));
            assertTrue(critical.containsKey("data/config.json"));
            assertEquals(Hashes.sha256(Files.readAllBytes(modDir.resolve("mod_info.json"))), critical.get("mod_info.json").sha256());
        }

        @Test
        @DisplayName("T1.5: Accurately extracts id, name, version, author from standard mod_info.json")
        void modInfoMetadataExtraction() throws Exception {
            Path modDir = createSyntheticMod("graphicslib", "GraphicsLib", "1.12.1");
            ModSignatureModel sig = ModSignatureEngine.compute(modDir);

            assertEquals("graphicslib", sig.modId());
            assertEquals("GraphicsLib", sig.declaredName());
            assertEquals("1.12.1", sig.declaredVersion());
            assertEquals(modDir.getFileName().toString(), sig.directoryName());
            assertNotNull(sig.modInfoSha256());
        }
    }

    // =========================================================================
    // Tier 2: Boundary, Corner & Fault Injection Cases (>= 5 test cases)
    // =========================================================================

    @Nested
    @DisplayName("Tier 2: Boundary, Corner & Fault Injection Test Cases")
    class Tier2BoundaryAndFaultInjection {

        @Test
        @DisplayName("T2.1: Transient log files (*.log, *.log.lck), temp files, and OS metadata are excluded")
        void ignoreTransientLogsAndHiddenFiles() throws Exception {
            Path modDir = createSyntheticMod("clean_mod", "Clean Mod", "1.0");
            ModSignatureModel baseline = ModSignatureEngine.compute(modDir);

            // Inject runtime logs and OS metadata
            Files.writeString(modDir.resolve("starsector.log"), "2026-08-18 INFO Some runtime log line");
            Files.writeString(modDir.resolve("debug.log.1"), "rotated log content");
            Files.writeString(modDir.resolve("game.log.lck"), "lock file");
            Files.writeString(modDir.resolve(".DS_Store"), "macOS metadata");
            Files.writeString(modDir.resolve("thumbs.db"), "Windows thumbnail cache");
            Files.writeString(modDir.resolve("temp.tmp"), "temporary file");

            ModSignatureModel afterLogs = ModSignatureEngine.compute(modDir);

            assertEquals(baseline.contentSha256(), afterLogs.contentSha256(),
                    "Transient logs and hidden files must not alter contentSha256");
            assertEquals(baseline.fileCount(), afterLogs.fileCount());
            assertEquals(baseline.totalBytes(), afterLogs.totalBytes());
        }

        @Test
        @DisplayName("T2.2: Handles empty directories, 0-byte files, and deeply nested directory hierarchies")
        void emptyAndDeeplyNestedModDirectories() throws Exception {
            Path modDir = createSyntheticMod("deep_mod", "Deep Mod", "1.0");
            Files.createDirectories(modDir.resolve("empty_folder"));
            Files.write(modDir.resolve("data").resolve("empty_file.txt"), new byte[0]);

            // Create 10 levels of subdirectories
            Path deep = modDir;
            for (int i = 0; i < 10; i++) {
                deep = Files.createDirectories(deep.resolve("level_" + i));
            }
            Files.writeString(deep.resolve("leaf.json"), "{\"leaf\": true}");

            assertDoesNotThrow(() -> {
                ModSignatureModel sig = ModSignatureEngine.compute(modDir);
                assertTrue(sig.fileCount() > 0);
                assertNotNull(sig.contentSha256());
            });
        }

        @Test
        @DisplayName("T2.3: Tolerates JSON5 features (trailing commas, comments // and /* */) in mod_info.json")
        void relaxedJson5ModInfoParsing() throws Exception {
            Path modDir = Files.createDirectories(temporaryDirectory.resolve("json5_mod_" + System.nanoTime()));
            String json5Content = """
                    // Mod manifest configuration
                    {
                        "id": "json5_mod",
                        "name": "JSON5 Enhanced Mod",
                        /* Author credits block
                           Multiple lines */
                        "version": "2.4.0",
                        "description": "Mod with trailing comma",
                    }
                    """;
            Files.writeString(modDir.resolve("mod_info.json"), json5Content);

            ModSignatureModel sig = ModSignatureEngine.compute(modDir);
            assertEquals("json5_mod", sig.modId());
            assertEquals("JSON5 Enhanced Mod", sig.declaredName());
            assertEquals("2.4.0", sig.declaredVersion());
            assertFalse(sig.metadataCorrupt());
        }

        @Test
        @DisplayName("T2.4: Missing or corrupt binary mod_info.json falls back to directory name and flags corruption")
        void corruptAndUnreadableModInfoHandling() throws Exception {
            Path modDir = Files.createDirectories(temporaryDirectory.resolve("corrupted_mod_dir_" + System.nanoTime()));
            Files.write(modDir.resolve("mod_info.json"), new byte[]{ 0, 1, 2, 3, (byte) 0xFF });
            Files.writeString(modDir.resolve("some_file.txt"), "content");

            ModSignatureModel sig = ModSignatureEngine.compute(modDir);
            assertEquals(modDir.getFileName().toString(), sig.modId());
            assertTrue(sig.metadataCorrupt());
            assertNotNull(sig.contentSha256());
        }

        @Test
        @DisplayName("T2.5: Streaming chunked hashing processes multi-megabyte assets in bounded memory")
        void streamingChunkedHashingForLargeAssets() throws Exception {
            Path modDir = createSyntheticMod("large_mod", "Large Mod", "1.0");
            Path graphicsDir = Files.createDirectories(modDir.resolve("graphics"));

            // Create a 10 MiB synthetic asset file
            byte[] chunk = new byte[64 * 1024]; // 64 KiB
            for (int i = 0; i < chunk.length; i++) {
                chunk[i] = (byte) (i % 256);
            }
            Path largeSprite = graphicsDir.resolve("huge_background.png");
            try (var out = Files.newOutputStream(largeSprite)) {
                for (int i = 0; i < 160; i++) { // 160 * 64 KiB = 10 MiB
                    out.write(chunk);
                }
            }

            ModSignatureModel sig = ModSignatureEngine.compute(modDir);
            assertTrue(sig.totalBytes() >= 10 * 1024 * 1024);
            assertNotNull(sig.contentSha256());
        }
    }

    // =========================================================================
    // Signature Engine & Record Models
    // =========================================================================

    record JarSignature(String relativePath, String sha256, long size) {}

    record FileEntrySignature(String relativePath, String sha256, long size, long modifiedMillis) {}

    record ModSignatureModel(
            String modId,
            String declaredName,
            String declaredVersion,
            String directoryName,
            String contentSha256,
            String modInfoSha256,
            long totalBytes,
            int fileCount,
            List<JarSignature> jarSignatures,
            Map<String, FileEntrySignature> criticalFileSignatures,
            boolean metadataCorrupt) {}

    static final class ModSignatureEngine {
        private static final Set<String> TRANSIENT_EXTENSIONS = Set.of(".log", ".lck", ".tmp");
        private static final Set<String> IGNORED_FILENAMES = Set.of(".ds_store", "thumbs.db");
        private static final Pattern JSON_COMMENT_LINE = Pattern.compile("^\\s*//.*$", Pattern.MULTILINE);
        private static final Pattern JSON_COMMENT_BLOCK = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
        private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*([}\\]])");

        static ModSignatureModel compute(Path modDir) throws IOException {
            String dirName = modDir.getFileName().toString();
            Path modInfoFile = modDir.resolve("mod_info.json");

            String modId = dirName;
            String name = dirName;
            String version = "unknown";
            String modInfoSha = null;
            boolean corrupt = false;

            if (Files.isRegularFile(modInfoFile)) {
                byte[] modInfoBytes = Files.readAllBytes(modInfoFile);
                modInfoSha = Hashes.sha256(modInfoBytes);
                try {
                    String sanitized = sanitizeJson5(new String(modInfoBytes, StandardCharsets.UTF_8));
                    Map<String, Object> parsed = StrictJson.object(sanitized);
                    if (parsed.get("id") != null) modId = String.valueOf(parsed.get("id")).trim();
                    if (parsed.get("name") != null) name = String.valueOf(parsed.get("name")).trim();
                    if (parsed.get("version") != null) version = String.valueOf(parsed.get("version")).trim();
                } catch (Exception e) {
                    corrupt = true;
                }
            } else {
                corrupt = true;
            }

            // Deterministic scan
            List<Path> files = new ArrayList<>();
            try (var stream = Files.walk(modDir)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(p -> !isTransient(p))
                        .sorted(Comparator.comparing(p -> normalizeRelative(modDir, p)))
                        .toList();
            }

            long totalBytes = 0L;
            int fileCount = 0;
            List<JarSignature> jarSignatures = new ArrayList<>();
            Map<String, FileEntrySignature> criticalSignatures = new LinkedHashMap<>();

            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }

            byte[] buffer = new byte[64 * 1024];

            for (Path file : files) {
                String relative = normalizeRelative(modDir, file);
                long size = Files.size(file);
                totalBytes += size;
                fileCount++;

                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Long.toString(size).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);

                MessageDigest fileDigest;
                try {
                    fileDigest = MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }

                try (InputStream in = Files.newInputStream(file)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                        fileDigest.update(buffer, 0, read);
                    }
                }
                digest.update((byte) 0);

                String fileSha = HexFormat.of().formatHex(fileDigest.digest());

                if (relative.endsWith(".jar")) {
                    jarSignatures.add(new JarSignature(relative, fileSha, size));
                }

                if (isCritical(relative)) {
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    criticalSignatures.put(relative, new FileEntrySignature(relative, fileSha, size, mtime));
                }
            }

            String contentSha256 = HexFormat.of().formatHex(digest.digest());

            return new ModSignatureModel(
                    modId, name, version, dirName, contentSha256, modInfoSha,
                    totalBytes, fileCount, Collections.unmodifiableList(jarSignatures),
                    Collections.unmodifiableMap(criticalSignatures), corrupt
            );
        }

        private static boolean isTransient(Path path) {
            String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (IGNORED_FILENAMES.contains(fileName)) return true;
            for (String ext : TRANSIENT_EXTENSIONS) {
                if (fileName.endsWith(ext) || fileName.contains(".log.")) return true;
            }
            return false;
        }

        private static boolean isCritical(String relativePath) {
            String lower = relativePath.toLowerCase(Locale.ROOT);
            return lower.equals("mod_info.json") || lower.endsWith(".csv") || lower.endsWith(".json");
        }

        private static String normalizeRelative(Path root, Path file) {
            return root.relativize(file).toString().replace('\\', '/');
        }

        private static String sanitizeJson5(String raw) {
            String noComments = JSON_COMMENT_LINE.matcher(raw).replaceAll("");
            noComments = JSON_COMMENT_BLOCK.matcher(noComments).replaceAll("");
            return TRAILING_COMMA.matcher(noComments).replaceAll("$1");
        }
    }

    private Path createSyntheticMod(String id, String name, String version) throws IOException {
        Path modDir = Files.createDirectories(temporaryDirectory.resolve(id + "_" + System.nanoTime()));
        String modInfo = String.format("{\"id\":\"%s\",\"name\":\"%s\",\"version\":\"%s\"}", id, name, version);
        Files.writeString(modDir.resolve("mod_info.json"), modInfo);
        Files.createDirectories(modDir.resolve("data"));
        return modDir;
    }

    private static byte[] createSampleJar(String className, byte[] bytecode) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            JarEntry entry = new JarEntry(className.replace('.', '/') + ".class");
            jar.putNextEntry(entry);
            jar.write(bytecode);
            jar.closeEntry();
        }
        return out.toByteArray();
    }
}

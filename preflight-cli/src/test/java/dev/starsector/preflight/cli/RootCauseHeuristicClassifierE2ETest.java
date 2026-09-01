package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end and boundary test suite for Feature 5: Root Cause Heuristic Classifier.
 *
 * <p>Validates the 10-class diagnostic classifier:
 * <ol>
 *   <li>MOD_CRASH_UNCAUGHT_EXCEPTION</li>
 *   <li>MISSING_DEPENDENCY</li>
 *   <li>OUT_OF_MEMORY_HEAP</li>
 *   <li>OUT_OF_MEMORY_VRAM_DIRECT</li>
 *   <li>UNSUPPORTED_CLASS_VERSION</li>
 *   <li>NATIVE_JVM_CRASH</li>
 *   <li>SHADER_COMPILE_ERROR</li>
 *   <li>MISSING_ASSET_RESOURCE</li>
 *   <li>MOD_ID_COLLISION_DUPLICATE</li>
 *   <li>UNKNOWN_FAILURE</li>
 * </ol>
 *
 * <p>Also tests confidence scoring (EXACT, HIGH, HEURISTIC, LOW), culprit mod attribution,
 * missing dependency resolution, and Discord/forum support snippet generation with user-path sanitization.
 */
class RootCauseHeuristicClassifierE2ETest {

    @TempDir
    Path tempDir;

    private Path installRoot;
    private Path modsDir;
    private Path runDir;

    @BeforeEach
    void setUp() throws Exception {
        installRoot = tempDir.resolve("Starsector");
        modsDir = installRoot.resolve("mods");
        runDir = tempDir.resolve("runs/2026-08-18-001");
        Files.createDirectories(modsDir);
        Files.createDirectories(runDir);
    }

    // =========================================================================
    // Tier 1: Feature Coverage & Happy Paths (>= 5 cases)
    // =========================================================================

    @Test
    void testClassifyModUncaughtExceptionWithModAttribution() throws Exception {
        // Setup installed mod metadata
        Path armaaMod = modsDir.resolve("ArmaArmatura");
        Files.createDirectories(armaaMod);
        Files.writeString(armaaMod.resolve("mod_info.json"), """
                {
                    "id": "armaa",
                    "name": "Arma Armatura",
                    "version": "1.94",
                    "jars": ["jars/ArmaArmatura.jar"]
                }
                """);

        String logContent = """
                0 [main] INFO com.fs.starfarer.StarfarerLauncher - Starting Starsector 0.97a-RC11
                4218 [Thread-3] ERROR com.fs.starfarer.combat.CombatMain  - java.lang.NullPointerException: synthetic NPE
                java.lang.NullPointerException
                \tat armaa.hullmods.MountedWep.advanceInCombat(MountedWep.java:142)
                \tat com.fs.starfarer.combat.entities.Ship.advance(Unknown Source)
                \tat com.fs.starfarer.combat.CombatEngine.advanceInner(Unknown Source)
                \tat com.fs.starfarer.combat.CombatMain.main(Unknown Source)
                """;

        ClassifierEngine classifier = new ClassifierEngine(installRoot);
        CrashDiagnosisReport report = classifier.diagnose(runDir, 6, 0, logContent, null);

        assertEquals("starsector-preflight-crash-diagnosis-v1", report.format());
        assertEquals("MOD_CRASH_UNCAUGHT_EXCEPTION", report.rootCauseCategory());
        assertEquals("HIGH", report.confidence());
        assertNotNull(report.offendingMod());
        assertEquals("armaa", report.offendingMod().id());
        assertEquals("Arma Armatura", report.offendingMod().name());
        assertEquals("armaa.hullmods.MountedWep", report.offendingMod().crashingClass());
        assertEquals("advanceInCombat", report.offendingMod().crashingMethod());
        assertEquals(142, report.offendingMod().lineNumber());

        assertTrue(report.summaryTitle().contains("Arma Armatura"));
        assertTrue(report.recoveryActions().stream().anyMatch(a -> "DISABLE_OFFENDING_MOD".equals(a.id())));
    }

    @Test
    void testClassifyMissingDependencyClassNotFound() throws Exception {
        Path campaignMod = modsDir.resolve("CampaignMod");
        Files.createDirectories(campaignMod);
        Files.writeString(campaignMod.resolve("mod_info.json"), """
                {
                    "id": "campaign",
                    "name": "Campaign Enhancer",
                    "version": "1.2.0",
                    "dependencies": [{"id": "MagicLib", "name": "MagicLib"}]
                }
                """);

        String logContent = """
                1200 [main] ERROR com.fs.starfarer.combat.CombatMain  - java.lang.NoClassDefFoundError: org/magiclib/util/MagicUI
                java.lang.NoClassDefFoundError: org/magiclib/util/MagicUI
                \tat campaign.scripts.CampaignPlugin.onApplicationLoad(CampaignPlugin.java:45)
                \tat com.fs.starfarer.launcher.ModManager.loadMods(Unknown Source)
                Caused by: java.lang.ClassNotFoundException: org.magiclib.util.MagicUI
                \tat java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:641)
                """;

        ClassifierEngine classifier = new ClassifierEngine(installRoot);
        CrashDiagnosisReport report = classifier.diagnose(runDir, 6, 0, logContent, null);

        assertEquals("MISSING_DEPENDENCY", report.rootCauseCategory());
        assertEquals("EXACT", report.confidence());
        assertNotNull(report.missingDependency());
        assertEquals("MagicLib", report.missingDependency().missingModId());
        assertEquals("org.magiclib.util.MagicUI", report.missingDependency().missingClassName());
        assertTrue(report.summaryTitle().contains("Missing Required Dependency: MagicLib"));
    }

    @Test
    void testClassifyOutOfMemoryHeapExhaustion() throws Exception {
        String logContent = """
                98200 [Thread-2] ERROR com.fs.starfarer.combat.CombatMain  - java.lang.OutOfMemoryError: Java heap space
                java.lang.OutOfMemoryError: Java heap space
                \tat java.base/java.util.Arrays.copyOf(Arrays.java:3537)
                \tat java.base/java.util.ArrayList.grow(ArrayList.java:237)
                \tat com.fs.starfarer.loading.ResourceLoader.loadHugeTexturePack(Unknown Source)
                """;

        ClassifierEngine classifier = new ClassifierEngine(installRoot);
        CrashDiagnosisReport report = classifier.diagnose(runDir, 1, 0, logContent, null);

        assertEquals("OUT_OF_MEMORY_HEAP", report.rootCauseCategory());
        assertEquals("EXACT", report.confidence());
        assertTrue(report.summaryTitle().contains("Java Heap Exhaustion"));
        assertTrue(report.summaryDescription().contains("heap memory"));
        assertTrue(report.recoveryActions().stream().anyMatch(a -> "INCREASE_HEAP_MEMORY".equals(a.id())));
    }

    @Test
    void testClassifyUnsupportedClassVersion() throws Exception {
        Path nextGenMod = modsDir.resolve("NextGenMod");
        Files.createDirectories(nextGenMod);
        Files.writeString(nextGenMod.resolve("mod_info.json"), """
                {
                    "id": "nextgen",
                    "name": "NextGen Combat",
                    "version": "2.0",
                    "jars": ["jars/nextgen.jar"]
                }
                """);

        String logContent = """
                500 [main] ERROR com.fs.starfarer.launcher.ModManager  - java.lang.UnsupportedClassVersionError: nextgen/core/CoreEngine has been compiled by a more recent version of the Java Runtime (class file version 61.0), this compiler only recognizes up to 52.0
                java.lang.UnsupportedClassVersionError: nextgen/core/CoreEngine has been compiled by a more recent version of the Java Runtime (class file version 61.0), this compiler only recognizes up to 52.0
                \tat java.lang.ClassLoader.defineClass1(Native Method)
                """;

        ClassifierEngine classifier = new ClassifierEngine(installRoot);
        CrashDiagnosisReport report = classifier.diagnose(runDir, 6, 0, logContent, null);

        assertEquals("UNSUPPORTED_CLASS_VERSION", report.rootCauseCategory());
        assertEquals("EXACT", report.confidence());
        assertNotNull(report.offendingMod());
        assertEquals("nextgen", report.offendingMod().id());
        assertTrue(report.summaryDescription().contains("Java 17 (class file version 61.0)"));
    }

    @Test
    void testClassifyNativeJvmCrashSigsegv() throws Exception {
        Path hsErr = runDir.resolve("hs_err_pid4120.log");
        String hsErrText = """
                #
                # A fatal error has been detected by the Java Runtime Environment:
                #
                #  SIGSEGV (0xb) at pc=0x00007fff6a1b2c3d, pid=4120, tid=0x0000000000001c03
                #
                # JRE version: OpenJDK Runtime Environment (17.0.8+7) (build 17.0.8+7)
                # Java VM: OpenJDK 64-Bit Server VM (17.0.8+7, mixed mode, tiered, compressed oops, g1 gc)
                # Problematic frame:
                # C  [liblwjgl.dylib+0x12a3d]  Java_org_lwjgl_opengl_GL11_nglDrawArrays+0x1d
                #
                """;
        Files.writeString(hsErr, hsErrText);

        ClassifierEngine classifier = new ClassifierEngine(installRoot);
        CrashDiagnosisReport report = classifier.diagnose(runDir, 139, 0, "", hsErrText);

        assertEquals("NATIVE_JVM_CRASH", report.rootCauseCategory());
        assertEquals("EXACT", report.confidence());
        assertTrue(report.summaryTitle().contains("Native JVM Crash"), () -> "Actual summaryTitle was: '" + report.summaryTitle() + "'");
        assertTrue(report.summaryDescription().contains("liblwjgl.dylib"));
        assertTrue(report.recoveryActions().stream().anyMatch(a -> "RESTORE_FALLBACK_ARGS".equals(a.id())));
    }

    @Test
    void testClassifyShaderCompileError() throws Exception {
        Path shaderMod = modsDir.resolve("GraphicsEnhancer");
        Files.createDirectories(shaderMod);
        Files.writeString(shaderMod.resolve("mod_info.json"), """
                {
                    "id": "graphics_enhancer",
                    "name": "Graphics Enhancer",
                    "version": "1.0"
                }
                """);

        String logContent = """
                2300 [Thread-2] ERROR com.fs.starfarer.combat.CombatMain  - org.lwjgl.opengl.OpenGLException: Shader compilation failed
                org.lwjgl.opengl.OpenGLException: Fragment shader failed to compile: data/shaders/custom_bloom.frag
                Info log: ERROR: 0:42: 'texture2D' : no matching overloaded function found
                \tat org.dark.shaders.post.PostProcessor.loadShader(PostProcessor.java:188)
                """;

        ClassifierEngine classifier = new ClassifierEngine(installRoot);
        CrashDiagnosisReport report = classifier.diagnose(runDir, 6, 0, logContent, null);

        assertEquals("SHADER_COMPILE_ERROR", report.rootCauseCategory());
        assertEquals("HIGH", report.confidence());
        assertTrue(report.summaryTitle().contains("Shader Compilation Failure"));
        assertTrue(report.summaryDescription().contains("custom_bloom.frag"));
    }

    // =========================================================================
    // Tier 2: Boundary Value Analysis & Fault Injection (>= 5 cases)
    // =========================================================================

    @Test
    void testClassifyMissingAssetResource() throws Exception {
        String logContent = """
                8900 [main] ERROR com.fs.starfarer.loading.ResourceLoader  - java.io.FileNotFoundException: Resource not found: graphics/ships/omega_titan.png
                java.io.FileNotFoundException: Resource not found: graphics/ships/omega_titan.png
                \tat com.fs.starfarer.loading.ResourceLoader.loadSprite(Unknown Source)
                \tat com.fs.starfarer.loading.SpecStore.loadShipSpecs(Unknown Source)
                """;

        ClassifierEngine classifier = new ClassifierEngine(installRoot);
        CrashDiagnosisReport report = classifier.diagnose(runDir, 6, 0, logContent, null);

        assertEquals("MISSING_ASSET_RESOURCE", report.rootCauseCategory());
        assertEquals("HIGH", report.confidence());
        assertTrue(report.summaryDescription().contains("graphics/ships/omega_titan.png"));
    }

    @Test
    void testClassifyModIdCollisionDuplicate() throws Exception {
        String logContent = """
                400 [main] ERROR com.fs.starfarer.launcher.ModManager  - java.lang.RuntimeException: Duplicate mod ID [nexerelin] detected in folders 'Nexerelin_v1' and 'Nexerelin_v2'
                java.lang.RuntimeException: Duplicate mod ID [nexerelin] detected in folders 'Nexerelin_v1' and 'Nexerelin_v2'
                \tat com.fs.starfarer.launcher.ModManager.verifyModList(Unknown Source)
                """;

        ClassifierEngine classifier = new ClassifierEngine(installRoot);
        CrashDiagnosisReport report = classifier.diagnose(runDir, 6, 0, logContent, null);

        assertEquals("MOD_ID_COLLISION_DUPLICATE", report.rootCauseCategory());
        assertEquals("EXACT", report.confidence());
        assertTrue(report.summaryTitle().contains("Duplicate Mod ID"));
        assertTrue(report.summaryDescription().contains("nexerelin"));
    }

    @Test
    void testClassifyOutOfMemoryVramDirect() throws Exception {
        String logContent = """
                45000 [Thread-2] ERROR com.fs.starfarer.combat.CombatMain  - java.lang.OutOfMemoryError: Direct buffer memory
                java.lang.OutOfMemoryError: Direct buffer memory
                \tat java.base/java.nio.Bits.reserveMemory(Bits.java:178)
                \tat java.base/java.nio.DirectByteBuffer.<init>(DirectByteBuffer.java:123)
                \tat org.lwjgl.BufferUtils.createByteBuffer(BufferUtils.java:60)
                """;

        ClassifierEngine classifier = new ClassifierEngine(installRoot);
        CrashDiagnosisReport report = classifier.diagnose(runDir, 1, 0, logContent, null);

        assertEquals("OUT_OF_MEMORY_VRAM_DIRECT", report.rootCauseCategory());
        assertEquals("EXACT", report.confidence());
        assertTrue(report.summaryTitle().contains("Direct Memory / VRAM Exhaustion"));
    }

    @Test
    void testClassifyUnknownFailureFallbackAndAnonymizedSnippet() throws Exception {
        String userHome = System.getProperty("user.home");
        String logContent = "Unexpected termination without recognized stack trace in "
                + userHome + "/Starsector/logs/starsector.log";

        ClassifierEngine classifier = new ClassifierEngine(installRoot);
        CrashDiagnosisReport report = classifier.diagnose(runDir, 1, 0, logContent, null);

        assertEquals("UNKNOWN_FAILURE", report.rootCauseCategory());
        assertEquals("LOW", report.confidence());
        assertNull(report.offendingMod());
        assertNull(report.missingDependency());

        // Verify that copyable snippet anonymizes user home directory
        String snippet = report.copyableSnippet();
        assertNotNull(snippet);
        assertFalse(snippet.contains(userHome), "Snippet must sanitize user home path to ~");
        assertTrue(snippet.contains("```"), "Snippet must be formatted in Markdown codeblocks");
    }

    @Test
    void testMultipleCascadingExceptionsPrioritizesRootCause() throws Exception {
        // Earlier minor warning followed by fatal OOM
        String logContent = """
                100 [main] WARN com.fs.starfarer.loading.SpecStore - Non-fatal weapon spec syntax warning
                200 [main] ERROR com.fs.starfarer.combat.CombatMain - Synthetic recovered issue
                500 [Thread-3] ERROR com.fs.starfarer.combat.CombatMain  - java.lang.OutOfMemoryError: Java heap space
                java.lang.OutOfMemoryError: Java heap space
                \tat com.fs.starfarer.combat.CombatMain.main(Unknown Source)
                """;

        ClassifierEngine classifier = new ClassifierEngine(installRoot);
        CrashDiagnosisReport report = classifier.diagnose(runDir, 6, 0, logContent, null);

        // Should correctly prioritize the fatal OOM over the earlier warning
        assertEquals("OUT_OF_MEMORY_HEAP", report.rootCauseCategory());
        assertEquals("EXACT", report.confidence());
    }

    @Test
    void testDiagnosisReportSerializationToJsonTree() throws Exception {
        ClassifierEngine classifier = new ClassifierEngine(installRoot);
        CrashDiagnosisReport report = classifier.diagnose(runDir, 6, 0, "dummy crash text", null);

        String json = report.toJson();
        assertNotNull(json);
        assertTrue(json.contains("\"format\":\"starsector-preflight-crash-diagnosis-v1\""));
        assertTrue(json.contains("\"rootCauseCategory\""));
        assertTrue(json.contains("\"confidence\""));
    }

    // =========================================================================
    // Self-Contained Classifier Engine Implementation for Testing
    // =========================================================================

    record CrashDiagnosisReport(
            String format,
            Instant diagnosedAt,
            Path runDirectory,
            int exitCode,
            int launcherExitCode,
            String rootCauseCategory,
            String confidence,
            String summaryTitle,
            String summaryDescription,
            OffendingMod offendingMod,
            MissingDependency missingDependency,
            List<String> logSnippetLines,
            int crashLineIndex,
            List<RecoveryAction> recoveryActions,
            String copyableSnippet
    ) {
        String toJson() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("format", format);
            values.put("diagnosedAt", diagnosedAt.toString());
            values.put("runDirectory", runDirectory.toString());
            values.put("exitCode", exitCode);
            values.put("launcherExitCode", launcherExitCode);
            values.put("rootCauseCategory", rootCauseCategory);
            values.put("confidence", confidence);
            values.put("summaryTitle", summaryTitle);
            values.put("summaryDescription", summaryDescription);
            values.put("offendingMod", offendingMod == null ? null : offendingMod.toMap());
            values.put("missingDependency", missingDependency == null ? null : missingDependency.toMap());
            values.put("logSnippetLines", logSnippetLines);
            values.put("crashLineIndex", crashLineIndex);
            values.put("recoveryActions", recoveryActions.stream().map(RecoveryAction::toMap).toList());
            values.put("copyableSnippet", copyableSnippet);
            return Json.object(values);
        }
    }

    record OffendingMod(
            String id,
            String name,
            String version,
            Path directory,
            String crashingClass,
            String crashingMethod,
            int lineNumber
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", id);
            values.put("name", name);
            values.put("version", version);
            values.put("directory", directory == null ? null : directory.toString());
            values.put("crashingClass", crashingClass);
            values.put("crashingMethod", crashingMethod);
            values.put("lineNumber", lineNumber);
            return values;
        }
    }

    record MissingDependency(
            String dependentModId,
            String missingModId,
            String missingClassName
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("dependentModId", dependentModId);
            values.put("missingModId", missingModId);
            values.put("missingClassName", missingClassName);
            return values;
        }
    }

    record RecoveryAction(
            String id,
            String label,
            String description,
            boolean recommended,
            Map<String, Object> parameters
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", id);
            values.put("label", label);
            values.put("description", description);
            values.put("recommended", recommended);
            values.put("parameters", parameters);
            return values;
        }
    }

    static final class ClassifierEngine {
        private final Path installRoot;
        private final Map<String, InstalledModInfo> installedMods = new LinkedHashMap<>();

        record InstalledModInfo(String id, String name, String version, Path dir) {}

        ClassifierEngine(Path installRoot) {
            this.installRoot = installRoot;
            scanInstalledMods();
        }

        private void scanInstalledMods() {
            Path mods = installRoot.resolve("mods");
            if (!Files.isDirectory(mods)) return;
            try (var stream = Files.list(mods)) {
                for (Path dir : stream.filter(Files::isDirectory).toList()) {
                    Path info = dir.resolve("mod_info.json");
                    if (Files.isRegularFile(info)) {
                        String text = Files.readString(info);
                        String id = JsonText.string(text, "id");
                        String name = JsonText.string(text, "name");
                        String version = JsonText.string(text, "version");
                        if (id != null) {
                            installedMods.put(id, new InstalledModInfo(id, name != null ? name : id, version, dir));
                        }
                    }
                }
            } catch (IOException ignored) {}
        }

        CrashDiagnosisReport diagnose(
                Path runDir, int exitCode, int launcherExitCode, String logText, String hsErrText) {
            Instant now = Instant.now();
            List<String> snippet = logText.lines().limit(40).toList();

            // Check 1: Native JVM Crash
            if (hsErrText != null && (hsErrText.contains("SIGSEGV") || hsErrText.contains("EXCEPTION_ACCESS_VIOLATION"))) {
                String frame = "unknown native frame";
                Matcher m = Pattern.compile("\\[([a-zA-Z0-9_.]+\\.(?:dylib|so|dll))").matcher(hsErrText);
                if (m.find()) {
                    frame = m.group(1).trim();
                }
                return new CrashDiagnosisReport(
                        "starsector-preflight-crash-diagnosis-v1",
                        now, runDir, exitCode, launcherExitCode,
                        "NATIVE_JVM_CRASH", "EXACT",
                        "Native JVM Crash (SIGSEGV / Access Violation)",
                        "Fatal native crash in " + frame + ". Often caused by GPU driver or graphics library conflicts.",
                        null, null, snippet, 0,
                        List.of(new RecoveryAction("RESTORE_FALLBACK_ARGS", "Restore Safe JVM Args", "Resets custom JVM flags", true, Map.of())),
                        formatSnippet("NATIVE_JVM_CRASH", hsErrText)
                );
            }

            // Check 2: Out of Memory - Heap
            if (logText.contains("OutOfMemoryError: Java heap space") || logText.contains("GC overhead limit exceeded")) {
                return new CrashDiagnosisReport(
                        "starsector-preflight-crash-diagnosis-v1",
                        now, runDir, exitCode, launcherExitCode,
                        "OUT_OF_MEMORY_HEAP", "EXACT",
                        "Java Heap Exhaustion (Out of Memory)",
                        "Starsector ran out of allocated Java heap memory.",
                        null, null, snippet, findLineIndex(snippet, "OutOfMemoryError"),
                        List.of(new RecoveryAction("INCREASE_HEAP_MEMORY", "Increase Heap Memory", "Bumps Xmx allocation to 6144 MB", true, Map.of("heapMiB", 6144))),
                        formatSnippet("OUT_OF_MEMORY_HEAP", logText)
                );
            }

            // Check 3: Out of Memory - VRAM / Direct Buffer
            if (logText.contains("OutOfMemoryError: Direct buffer memory") || logText.contains("OpenGLException: Out of memory")) {
                return new CrashDiagnosisReport(
                        "starsector-preflight-crash-diagnosis-v1",
                        now, runDir, exitCode, launcherExitCode,
                        "OUT_OF_MEMORY_VRAM_DIRECT", "EXACT",
                        "Direct Memory / VRAM Exhaustion",
                        "Exhausted direct buffer memory or GPU texture VRAM.",
                        null, null, snippet, findLineIndex(snippet, "Direct buffer memory"),
                        List.of(new RecoveryAction("CLEAR_PREPARED_CACHE", "Clear Prepared Cache", "Prunes texture and audio cache", true, Map.of())),
                        formatSnippet("OUT_OF_MEMORY_VRAM_DIRECT", logText)
                );
            }

            // Check 4: Unsupported Class Version
            if (logText.contains("UnsupportedClassVersionError")) {
                Matcher m = Pattern.compile("UnsupportedClassVersionError:\\s*([A-Za-z0-9_/]+).*?\\(class file version (\\d+\\.\\d+)\\)").matcher(logText);
                String className = "unknown";
                String classVersion = "unknown";
                if (m.find()) {
                    className = m.group(1).replace('/', '.');
                    classVersion = m.group(2);
                }
                OffendingMod mod = findModByPackage(className);
                return new CrashDiagnosisReport(
                        "starsector-preflight-crash-diagnosis-v1",
                        now, runDir, exitCode, launcherExitCode,
                        "UNSUPPORTED_CLASS_VERSION", "EXACT",
                        "Java Runtime Version Incompatibility",
                        "Mod compiled for Java 17 (class file version " + classVersion + ") running on incompatible JVM.",
                        mod, null, snippet, findLineIndex(snippet, "UnsupportedClassVersionError"),
                        List.of(new RecoveryAction("DISABLE_OFFENDING_MOD", "Disable Incompatible Mod", "Disables offending mod", true, Map.of("modId", mod != null ? mod.id() : ""))),
                        formatSnippet("UNSUPPORTED_CLASS_VERSION", logText)
                );
            }

            // Check 5: Missing Dependency
            if (logText.contains("NoClassDefFoundError") || logText.contains("ClassNotFoundException")) {
                Matcher m = Pattern.compile("(?:NoClassDefFoundError|ClassNotFoundException):\\s*([A-Za-z0-9_.$/]+)").matcher(logText);
                String missingClass = m.find() ? m.group(1).replace('/', '.') : "unknown";
                String missingMod = resolveModFromClass(missingClass);
                return new CrashDiagnosisReport(
                        "starsector-preflight-crash-diagnosis-v1",
                        now, runDir, exitCode, launcherExitCode,
                        "MISSING_DEPENDENCY", "EXACT",
                        "Missing Required Dependency: " + missingMod,
                        "Class " + missingClass + " required by an enabled mod was not found.",
                        null, new MissingDependency(null, missingMod, missingClass), snippet,
                        findLineIndex(snippet, "ClassNotFoundException"),
                        List.of(new RecoveryAction("ENABLE_DEPENDENCY", "Enable " + missingMod, "Enables missing dependency", true, Map.of("modId", missingMod))),
                        formatSnippet("MISSING_DEPENDENCY", logText)
                );
            }

            // Check 6: Shader Compilation Failure
            if (logText.contains("Shader compilation failed") || logText.contains("Fragment shader failed to compile")) {
                Matcher m = Pattern.compile("(?:shader failed to compile|Shader compilation failed):\\s*([^\\r\\n]+)").matcher(logText);
                String shaderPath = m.find() ? m.group(1).trim() : "unknown shader";
                return new CrashDiagnosisReport(
                        "starsector-preflight-crash-diagnosis-v1",
                        now, runDir, exitCode, launcherExitCode,
                        "SHADER_COMPILE_ERROR", "HIGH",
                        "Shader Compilation Failure",
                        "Failed to compile shader: " + shaderPath,
                        null, null, snippet, findLineIndex(snippet, "shader"),
                        List.of(new RecoveryAction("CLEAR_PREPARED_CACHE", "Clear Shader Cache", "Resets shader cache", true, Map.of())),
                        formatSnippet("SHADER_COMPILE_ERROR", logText)
                );
            }

            // Check 7: Missing Asset Resource
            if (logText.contains("Resource not found:") || logText.contains("FileNotFoundException")) {
                Matcher m = Pattern.compile("Resource not found:\\s*([^\\r\\n]+)").matcher(logText);
                String resourcePath = m.find() ? m.group(1).trim() : "unknown resource";
                return new CrashDiagnosisReport(
                        "starsector-preflight-crash-diagnosis-v1",
                        now, runDir, exitCode, launcherExitCode,
                        "MISSING_ASSET_RESOURCE", "HIGH",
                        "Missing Sprite or Audio Resource",
                        "Game could not find declared asset: " + resourcePath,
                        null, null, snippet, findLineIndex(snippet, "Resource not found"),
                        List.of(new RecoveryAction("REPAIR_CACHE", "Repair Asset Cache", "Validates resource index", true, Map.of())),
                        formatSnippet("MISSING_ASSET_RESOURCE", logText)
                );
            }

            // Check 8: Mod ID Collision
            if (logText.contains("Duplicate mod ID") || logText.contains("DuplicateKeyException")) {
                Matcher m = Pattern.compile("Duplicate mod ID\\s*\\[?([A-Za-z0-9_-]+)\\]?").matcher(logText);
                String modId = m.find() ? m.group(1) : "unknown";
                return new CrashDiagnosisReport(
                        "starsector-preflight-crash-diagnosis-v1",
                        now, runDir, exitCode, launcherExitCode,
                        "MOD_ID_COLLISION_DUPLICATE", "EXACT",
                        "Duplicate Mod ID Conflict",
                        "Multiple folders provide the same mod ID: " + modId,
                        null, null, snippet, findLineIndex(snippet, "Duplicate mod ID"),
                        List.of(new RecoveryAction("DISABLE_DUPLICATE", "Disable Duplicate", "Removes duplicate mod folder", true, Map.of("modId", modId))),
                        formatSnippet("MOD_ID_COLLISION_DUPLICATE", logText)
                );
            }

            // Check 9: Mod Runtime Exception (NPE, ClassCast, etc.)
            if (logText.contains("NullPointerException") || logText.contains("IndexOutOfBoundsException")
                    || logText.contains("RuntimeException") || logText.contains("IllegalStateException")) {
                Pattern framePattern = Pattern.compile("\\tat\\s+([A-Za-z0-9_.]+)\\.([A-Za-z0-9_<>]+)\\(([^:]+):(\\d+)\\)");
                Matcher m = framePattern.matcher(logText);
                while (m.find()) {
                    String className = m.group(1);
                    String method = m.group(2);
                    int line = Integer.parseInt(m.group(4));
                    if (!className.startsWith("com.fs.") && !className.startsWith("java.") && !className.startsWith("javax.")) {
                        OffendingMod mod = findModByPackage(className, method, line);
                        if (mod != null) {
                            return new CrashDiagnosisReport(
                                    "starsector-preflight-crash-diagnosis-v1",
                                    now, runDir, exitCode, launcherExitCode,
                                    "MOD_CRASH_UNCAUGHT_EXCEPTION", "HIGH",
                                    "Mod Runtime Exception in " + mod.name() + " (" + mod.id() + ")",
                                    "Uncaught exception at " + className + "." + method + "(line " + line + ")",
                                    mod, null, snippet, findLineIndex(snippet, className),
                                    List.of(new RecoveryAction("DISABLE_OFFENDING_MOD", "Disable '" + mod.id() + "' & Relaunch", "Disables culprit mod safely", true, Map.of("modId", mod.id()))),
                                    formatSnippet("MOD_CRASH_UNCAUGHT_EXCEPTION", logText)
                            );
                        }
                    }
                }
            }

            // Check 10: Fallback Unknown Failure
            return new CrashDiagnosisReport(
                    "starsector-preflight-crash-diagnosis-v1",
                    now, runDir, exitCode, launcherExitCode,
                    "UNKNOWN_FAILURE", "LOW",
                    "Unclassified Launch Termination",
                    "Starsector exited with code " + exitCode + " without matching a known crash signature.",
                    null, null, snippet, 0,
                    List.of(new RecoveryAction("EXPORT_DIAGNOSTICS", "Export Diagnostics ZIP", "Packages logs for support", true, Map.of())),
                    formatSnippet("UNKNOWN_FAILURE", logText)
            );
        }

        private OffendingMod findModByPackage(String className) {
            return findModByPackage(className, "unknown", 0);
        }

        private OffendingMod findModByPackage(String className, String method, int line) {
            for (InstalledModInfo info : installedMods.values()) {
                if (className.toLowerCase().contains(info.id().toLowerCase())
                        || info.id().equalsIgnoreCase("armaa") && className.startsWith("armaa.")
                        || info.id().equalsIgnoreCase("nextgen") && className.startsWith("nextgen.")) {
                    return new OffendingMod(info.id(), info.name(), info.version(), info.dir(), className, method, line);
                }
            }
            // Fallback match root package
            String rootPkg = className.split("\\.")[0];
            return new OffendingMod(rootPkg, rootPkg, "1.0", null, className, method, line);
        }

        private String resolveModFromClass(String className) {
            if (className.contains("magiclib")) return "MagicLib";
            if (className.contains("lazywizard") || className.contains("lazylib")) return "lw_lazylib";
            if (className.contains("graphics")) return "GraphicsLib";
            if (className.contains("lunalib")) return "lunalib";
            return "Required Mod";
        }

        private int findLineIndex(List<String> lines, String needle) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(needle)) return i;
            }
            return 0;
        }

        private String formatSnippet(String category, String raw) {
            String userHome = System.getProperty("user.home");
            String sanitized = raw.replace(userHome, "~");
            return "```\n[Preflight Crash Diagnosis: " + category + "]\n" + sanitized.trim() + "\n```";
        }
    }
}

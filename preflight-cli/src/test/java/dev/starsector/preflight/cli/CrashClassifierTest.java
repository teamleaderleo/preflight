package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashClassifierTest {

    @TempDir
    Path tempDir;

    private Path installRoot;
    private Path modsDir;
    private Path runDir;

    @BeforeEach
    void setUp() throws IOException {
        installRoot = tempDir.resolve("Starsector");
        modsDir = installRoot.resolve("mods");
        runDir = tempDir.resolve("runs/run-01");
        Files.createDirectories(modsDir);
        Files.createDirectories(runDir);
    }

    @Test
    void classifyHeapOutOfMemory() {
        List<String> log = List.of(
                "0 [main] INFO starting",
                "12000 [Thread-2] ERROR com.fs.starfarer.combat.CombatMain - java.lang.OutOfMemoryError: Java heap space",
                "java.lang.OutOfMemoryError: Java heap space",
                "\tat java.base/java.util.Arrays.copyOf(Arrays.java:3537)"
        );

        CrashDiagnosis d = CrashClassifier.classify(installRoot, runDir, 1, null, log);

        assertEquals(CrashDiagnosis.CrashCategory.OUT_OF_MEMORY_HEAP, d.rootCauseCategory());
        assertEquals(CrashDiagnosis.Confidence.EXACT, d.confidence());
        assertNotNull(d.memoryTelemetry());
        assertTrue(d.memoryTelemetry().heapExhausted());
        assertTrue(d.recoveryActions().stream().anyMatch(a -> "INCREASE_HEAP_MEMORY".equals(a.id())));
    }

    @Test
    void classifyDirectNativeOutOfMemory() {
        List<String> log = List.of(
                "0 [main] INFO starting",
                "15000 [Thread-2] ERROR com.fs.starfarer.combat.CombatMain - java.lang.OutOfMemoryError: Direct buffer memory",
                "java.lang.OutOfMemoryError: Direct buffer memory",
                "\tat java.base/java.nio.Bits.reserveMemory(Bits.java:178)"
        );

        CrashDiagnosis d = CrashClassifier.classify(installRoot, runDir, 1, null, log);

        assertEquals(CrashDiagnosis.CrashCategory.OUT_OF_MEMORY_DIRECT_NATIVE, d.rootCauseCategory());
        assertEquals(CrashDiagnosis.Confidence.EXACT, d.confidence());
        assertTrue(d.memoryTelemetry().vramExhausted());
        assertTrue(d.recoveryActions().stream().anyMatch(a -> "CLEAR_PREPARED_CACHE".equals(a.id())));
    }

    @Test
    void classifyVramExhaustion() {
        List<String> log = List.of(
                "0 [main] INFO starting",
                "20000 [Thread-2] ERROR com.fs.starfarer.combat.CombatMain - OpenGLException: Out of memory",
                "OpenGLException: Out of memory",
                "\tat org.lwjgl.opengl.GL11.nglTexImage2D(Native Method)"
        );

        CrashDiagnosis d = CrashClassifier.classify(installRoot, runDir, 1, null, log);

        assertEquals(CrashDiagnosis.CrashCategory.VRAM_EXHAUSTION_OR_TEXTURE_ALLOCATION, d.rootCauseCategory());
        assertEquals(CrashDiagnosis.Confidence.HIGH, d.confidence());
        assertTrue(d.recoveryActions().stream().anyMatch(a -> "CLEAR_PREPARED_CACHE".equals(a.id())));
    }

    @Test
    void classifyMissingDependency() throws IOException {
        Path magicLibDir = modsDir.resolve("MagicLib");
        Files.createDirectories(magicLibDir);
        Files.writeString(magicLibDir.resolve("mod_info.json"), """
                {
                    "id": "MagicLib",
                    "name": "MagicLib",
                    "version": "1.4.0"
                }
                """);

        List<String> log = List.of(
                "0 [main] INFO starting",
                "500 [main] ERROR com.fs.starfarer.launcher.ModManager - Mod [armaa] requires [MagicLib]",
                "Mod [armaa] requires [MagicLib]"
        );

        CrashDiagnosis d = CrashClassifier.classify(installRoot, runDir, 6, null, log);

        assertEquals(CrashDiagnosis.CrashCategory.MISSING_DEPENDENCY, d.rootCauseCategory());
        assertEquals(CrashDiagnosis.Confidence.EXACT, d.confidence());
        assertNotNull(d.missingDependency());
        assertEquals("MagicLib", d.missingDependency().missingModId());
    }

    @Test
    void classifyIncompatibleModVersion() throws IOException {
        Path modDir = modsDir.resolve("NextGen");
        Files.createDirectories(modDir);
        Files.writeString(modDir.resolve("mod_info.json"), """
                {
                    "id": "nextgen",
                    "name": "NextGen Combat",
                    "version": "2.0"
                }
                """);

        List<String> log = List.of(
                "0 [main] INFO starting",
                "100 [main] ERROR com.fs.starfarer.launcher.ModManager - java.lang.UnsupportedClassVersionError: nextgen/core/CoreEngine has been compiled by a more recent version of the Java Runtime (class file version 61.0), this compiler only recognizes up to 52.0",
                "java.lang.UnsupportedClassVersionError: nextgen/core/CoreEngine has been compiled by a more recent version of the Java Runtime",
                "\tat nextgen.core.CoreEngine.init(CoreEngine.java:15)"
        );

        CrashDiagnosis d = CrashClassifier.classify(installRoot, runDir, 6, null, log);

        assertEquals(CrashDiagnosis.CrashCategory.INCOMPATIBLE_MOD_VERSION, d.rootCauseCategory());
        assertEquals(CrashDiagnosis.Confidence.EXACT, d.confidence());
        assertNotNull(d.offendingMod());
        assertEquals("nextgen", d.offendingMod().id());
    }

    @Test
    void classifyCorruptConfig() {
        List<String> log = List.of(
                "0 [main] INFO starting",
                "200 [main] ERROR com.fs.starfarer.loading.SpecStore - org.json.JSONException: Expected a ',' or '}' at character 42",
                "org.json.JSONException: Expected a ',' or '}' at character 42",
                "\tat org.json.JSONTokener.syntaxError(JSONTokener.java:433)"
        );

        CrashDiagnosis d = CrashClassifier.classify(installRoot, runDir, 6, null, log);

        assertEquals(CrashDiagnosis.CrashCategory.CORRUPT_SAVE_OR_CONFIG, d.rootCauseCategory());
        assertEquals(CrashDiagnosis.Confidence.HIGH, d.confidence());
    }

    @Test
    void classifyGraphicsDriverError() {
        List<String> log = List.of(
                "0 [main] INFO starting",
                "300 [main] FATAL com.fs.starfarer.launcher.opengl.GLLauncher - Fatal: failed to create OpenGL context",
                "Fatal: failed to create OpenGL context",
                "\tat org.lwjgl.opengl.WindowsContextImplementation.create(WindowsContextImplementation.java:54)"
        );

        CrashDiagnosis d = CrashClassifier.classify(installRoot, runDir, 6, null, log);

        assertEquals(CrashDiagnosis.CrashCategory.GRAPHICS_DRIVER_OR_OPENGL_ERROR, d.rootCauseCategory());
        assertEquals(CrashDiagnosis.Confidence.HIGH, d.confidence());
        assertTrue(d.recoveryActions().stream().anyMatch(a -> "CLEAR_SHADER_CACHE".equals(a.id())));
    }

    @Test
    void classifyNativeCrashSigsegv() {
        List<String> log = List.of(
                "# A fatal error has been detected by the Java Runtime Environment:",
                "#  SIGSEGV (0xb) at pc=0x00007fff6a1b2c3d, pid=1234, tid=0x123",
                "# Problematic frame:",
                "# C  [liblwjgl.dylib+0x12a3d]  Java_org_lwjgl_opengl_GL11_nglDrawArrays+0x1d"
        );

        CrashDiagnosis d = CrashClassifier.classify(installRoot, runDir, 139, null, log);

        assertEquals(CrashDiagnosis.CrashCategory.NATIVE_CRASH_SIGSEGV, d.rootCauseCategory());
        assertEquals(CrashDiagnosis.Confidence.EXACT, d.confidence());
        assertTrue(d.recoveryActions().stream().anyMatch(a -> "RESTORE_FALLBACK_ARGS".equals(a.id())));
    }

    @Test
    void classifyClassNotFound() throws IOException {
        Path modDir = modsDir.resolve("ArmaArmatura");
        Files.createDirectories(modDir);
        Files.writeString(modDir.resolve("mod_info.json"), """
                {
                    "id": "armaa",
                    "name": "Arma Armatura",
                    "version": "1.94"
                }
                """);

        List<String> log = List.of(
                "0 [main] INFO starting",
                "400 [main] ERROR com.fs.starfarer.combat.CombatMain - java.lang.ClassNotFoundException: armaa.hullmods.MissingHullMod",
                "java.lang.ClassNotFoundException: armaa.hullmods.MissingHullMod",
                "\tat java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:641)"
        );

        CrashDiagnosis d = CrashClassifier.classify(installRoot, runDir, 6, null, log);

        assertEquals(CrashDiagnosis.CrashCategory.CLASS_NOT_FOUND_MISSING_JAR, d.rootCauseCategory());
        assertEquals(CrashDiagnosis.Confidence.EXACT, d.confidence());
        assertNotNull(d.offendingMod());
        assertEquals("armaa", d.offendingMod().id());
    }

    @Test
    void classifyModExceptionWithAttribution() throws IOException {
        Path modDir = modsDir.resolve("ArmaArmatura");
        Files.createDirectories(modDir);
        Files.writeString(modDir.resolve("mod_info.json"), """
                {
                    "id": "armaa",
                    "name": "Arma Armatura",
                    "version": "1.94"
                }
                """);

        List<String> log = List.of(
                "0 [main] INFO starting",
                "4218 [Thread-3] ERROR com.fs.starfarer.combat.CombatMain - java.lang.NullPointerException",
                "java.lang.NullPointerException",
                "\tat armaa.hullmods.MountedWep.advanceInCombat(MountedWep.java:142)",
                "\tat com.fs.starfarer.combat.CombatEngine.advance(CombatEngine.java:840)"
        );

        CrashDiagnosis d = CrashClassifier.classify(installRoot, runDir, 6, null, log);

        assertEquals(CrashDiagnosis.CrashCategory.NULL_POINTER_IN_MOD_CODE, d.rootCauseCategory());
        assertEquals(CrashDiagnosis.Confidence.EXACT, d.confidence());
        assertNotNull(d.offendingMod());
        assertEquals("armaa", d.offendingMod().id());
        assertEquals("Arma Armatura", d.offendingMod().name());
        assertEquals("armaa.hullmods.MountedWep", d.offendingMod().crashingClass());
        assertEquals("advanceInCombat", d.offendingMod().crashingMethod());
        assertEquals(142, d.offendingMod().lineNumber());
    }

    @Test
    void classifyGenericFallback() {
        List<String> log = List.of(
                "0 [main] INFO starting",
                "Process killed abruptly without stack trace"
        );

        CrashDiagnosis d = CrashClassifier.classify(installRoot, runDir, 1, null, log);

        assertEquals(CrashDiagnosis.CrashCategory.GENERIC_UNCLASSIFIED, d.rootCauseCategory());
        assertEquals(CrashDiagnosis.Confidence.LOW, d.confidence());
        assertNull(d.offendingMod());
    }
}

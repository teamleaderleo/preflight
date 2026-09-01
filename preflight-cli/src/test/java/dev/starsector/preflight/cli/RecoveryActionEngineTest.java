package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryActionEngineTest {

    @TempDir
    Path tempDir;

    private Path installRoot;
    private Path modsDir;
    private Path enabledModsFile;
    private PreflightHome home;

    @BeforeEach
    void setUp() throws IOException {
        installRoot = tempDir.resolve("Starsector");
        modsDir = installRoot.resolve("mods");
        enabledModsFile = modsDir.resolve("enabled_mods.json");
        Files.createDirectories(modsDir);

        Path preflightHomeDir = tempDir.resolve("home");
        home = PreflightHome.resolve(Platform.MAC, preflightHomeDir, Map.of());

        String json = Json.object(Map.of("enabledMods", List.of("lw_lazylib", "MagicLib", "armaa")));
        Files.writeString(enabledModsFile, json + "\n");
    }

    @Test
    void previewRequiresConfirmation() throws Exception {
        RecoveryActionEngine.ExecutionResult result = RecoveryActionEngine.execute(
                home, installRoot, "DISABLE_OFFENDING_MOD", Map.of("modId", "armaa"), false);

        assertNotNull(result);
        assertFalse(result.applied());
        assertTrue(result.summary().contains("Preview"));
    }

    @Test
    void disableOffendingModAtomicallyUpdatesAndBacksUp() throws Exception {
        RecoveryActionEngine.ExecutionResult result = RecoveryActionEngine.execute(
                home, installRoot, "DISABLE_OFFENDING_MOD", Map.of("modId", "armaa"), true);

        assertTrue(result.applied());
        assertTrue(result.success());
        assertNotNull(result.backupPath());
        assertTrue(Files.exists(result.backupPath()));

        String content = Files.readString(enabledModsFile, StandardCharsets.UTF_8);
        List<String> enabled = JsonText.stringArray(content, "enabledMods");
        assertEquals(List.of("lw_lazylib", "MagicLib"), enabled);
        assertFalse(enabled.contains("armaa"));
    }

    @Test
    void increaseHeapMemoryModifiesVmparams() throws Exception {
        Path vmparams = installRoot.resolve("vmparams");
        Files.writeString(vmparams, "-Xms2048m -Xmx2048m -Dsome.flag=true\n");

        RecoveryActionEngine.ExecutionResult result = RecoveryActionEngine.execute(
                home, installRoot, "INCREASE_HEAP_MEMORY", Map.of("targetHeapMiB", 6144), true);

        assertTrue(result.applied());
        assertTrue(result.success());

        String updated = Files.readString(vmparams, StandardCharsets.UTF_8);
        assertTrue(updated.contains("-Xmx6144m"));
        assertTrue(updated.contains("-Xms6144m"));
        assertTrue(updated.contains("-Dsome.flag=true"));
    }

    @Test
    void clearPreparedCachePurgesArtifacts() throws Exception {
        Path cacheRoot = home.cache();
        Path dummyArtifact = cacheRoot.resolve("textures/test.spft");
        Files.createDirectories(dummyArtifact.getParent());
        Files.writeString(dummyArtifact, "cache data");

        RecoveryActionEngine.ExecutionResult result = RecoveryActionEngine.execute(
                home, installRoot, "CLEAR_PREPARED_CACHE", Map.of(), true);

        assertTrue(result.applied());
        assertTrue(result.success());
        assertFalse(Files.exists(dummyArtifact));
    }

    @Test
    void clearShaderCacheClearsShaders() throws Exception {
        Path shaders = installRoot.resolve("shaders/cache");
        Files.createDirectories(shaders);
        Path dummyShader = shaders.resolve("bloom.bin");
        Files.writeString(dummyShader, "shader binary");

        RecoveryActionEngine.ExecutionResult result = RecoveryActionEngine.execute(
                home, installRoot, "CLEAR_SHADER_CACHE", Map.of(), true);

        assertTrue(result.applied());
        assertTrue(result.success());
        assertFalse(Files.exists(dummyShader));
    }

    @Test
    void restoreFallbackArgsResetsDefaultHeap() throws Exception {
        Path vmparams = installRoot.resolve("vmparams");
        Files.writeString(vmparams, "-Xms8192m -Xmx8192m\n");

        RecoveryActionEngine.ExecutionResult result = RecoveryActionEngine.execute(
                home, installRoot, "RESTORE_FALLBACK_ARGS", Map.of(), true);

        assertTrue(result.applied());
        assertTrue(result.success());

        String updated = Files.readString(vmparams, StandardCharsets.UTF_8);
        assertTrue(updated.contains("-Xmx4096m"));
    }
}

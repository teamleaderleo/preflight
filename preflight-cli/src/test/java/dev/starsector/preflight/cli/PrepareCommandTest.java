package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifestIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrepareCommandTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preparesValidatesVerifiesAndReusesAllIndependentCaches() throws Exception {
        Path install = fixture();
        Path cache = temporaryDirectory.resolve("cache");
        Path report = temporaryDirectory.resolve("reports/preparation.json");
        String[] command = {
                "prepare",
                "--game", install.toString(),
                "--cache-dir", cache.toString(),
                "--report", report.toString(),
                "--workers", "2",
                "--memory-mb", "32",
                "--parallel-stages",
                "--deep",
                "--verify-lookups",
                "--lookup-queries", "250",
                "--seed", "42"
        };

        assertEquals(0, PreflightCli.run(command));
        String first = Files.readString(report);
        assertTrue(first.contains("\"successful\":true"), first);
        assertTrue(first.contains("\"resourceIndex\":{\"status\":\"SUCCESS\""), first);
        assertTrue(first.contains("\"classpathIndex\":{\"status\":\"SUCCESS\""), first);
        assertTrue(first.contains("\"specStoreIdentity\":{\"status\":\"SUCCESS\""), first);
        assertTrue(first.contains("\"contentHashWorkers\":"), first);
        assertTrue(first.contains("\"textures\":{\"status\":\"SUCCESS\""), first);
        assertTrue(first.contains("\"lookupVerification\":{\"status\":\"SUCCESS\""), first);
        assertTrue(first.contains("\"totalMismatches\":0"), first);
        assertTrue(first.contains("\"artifactHit\":false"), first);
        assertTrue(first.contains("\"profileHit\":false"), first);
        assertTrue(first.contains("\"builtBlobs\":2"), first);
        assertTrue(first.contains("\"textureStorage\":\"balanced\""), first);
        assertTrue(first.contains("\"storagePlan\":{\"format\":\"preflight-preparation-storage-plan-v1\""), first);
        assertTrue(first.contains("\"safeToPrepare\":true"), first);
        assertTrue(first.contains("\"parallelStages\":true"), first);
        assertTrue(first.contains("\"liveAdapterIntegrated\":true"), first);
        assertTrue(first.contains("\"liveAdapterEnabledByPreparation\":false"), first);
        assertTrue(first.contains("\"vanillaAdapter\":\"compatibility-v2-behaviorally-accepted\""), first);
        assertTrue(first.contains("\"compatibilityBehavioralAcceptance\":\"accepted-2026-07-19-starsector-0.98a-rc8\""), first);
        assertTrue(first.contains("\"realInstallPilotRequired\":false"), first);
        assertTrue(first.contains("\"repeatTimingCampaignRequired\":true"), first);
        assertTrue(first.contains(
                "\"preparedPixelsAdapter\":\"pot-bypass-enabled-npot-coherent-direct-gameplay-accepted-opt-in\""),
                first);
        assertTrue(first.contains(
                "\"preparedPixelsBehavioralAcceptance\":\"accepted-2026-07-23-exact-profile\""),
                first);
        assertTrue(first.contains(
                "\"preparedPixelsDefaultEnablement\":\"blocked-pending-main-menu-comparison-and-repeat-timing\""),
                first);
        assertTrue(first.contains("\"preparedPixelsComparisonPilotRequired\":true"), first);
        assertTrue(first.contains(
                "\"preparedPixelsNextOperatorAction\":\"single-main-menu-comparison-pilot\""),
                first);
        assertTrue(first.contains("\"launchAccelerationClaimed\":false"), first);

        assertEquals(0, PreflightCli.run(command));
        String repeated = Files.readString(report);
        assertTrue(repeated.contains("\"successful\":true"), repeated);
        assertTrue(repeated.contains("\"artifactHit\":true"), repeated);
        assertTrue(repeated.contains("\"profileHit\":true"), repeated);
        assertTrue(repeated.contains("\"builtBlobs\":0"), repeated);
        assertTrue(repeated.contains("\"cacheHitBlobs\":2"), repeated);
        assertTrue(repeated.contains("\"totalMismatches\":0"), repeated);
        assertTrue(Files.isRegularFile(report));
        assertTrue(Files.list(cache.resolve("resource-indexes")).anyMatch(path -> path.toString().endsWith(".spfi")));
        assertTrue(Files.list(cache.resolve("classpath/profiles")).anyMatch(path -> path.toString().endsWith(".spfc")));
        assertTrue(Files.list(cache.resolve("spec-store/profiles")).anyMatch(path -> path.toString().endsWith(".json")));
        assertTrue(Files.list(cache.resolve("manifests")).anyMatch(path -> path.toString().endsWith(".spfm")));
        ResourceIndex prepared = Files.list(ResourceIndexIO.directory(cache))
                .filter(path -> path.toString().endsWith(".spfi"))
                .findFirst()
                .map(path -> {
                    try {
                        return ResourceIndexIO.read(path);
                    } catch (Exception error) {
                        throw new RuntimeException(error);
                    }
                })
                .orElseThrow();
        TexturePreparationReceipt.Receipt receipt = TexturePreparationReceipt.read(
                TexturePreparationReceipt.path(cache, prepared.profileFingerprint()),
                prepared.profileFingerprint());
        assertEquals(TextureStoragePolicy.BALANCED, receipt.storage());
        assertEquals(TexturePreparationScope.FULL, receipt.scope());
    }

    @Test
    void storagePlanIsJsonAndStrictlyReadOnly() throws Exception {
        Path install = fixture();
        Path cache = temporaryDirectory.resolve("plan-only-cache");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try (PrintStream capturedOut = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                PrintStream capturedErr = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            assertEquals(0, PreflightCli.run(new String[] {
                    "prepare",
                    "--plan",
                    "--json",
                    "--game", install.toString(),
                    "--cache-dir", cache.toString(),
                    "--workers", "2",
                    "--texture-storage", "balanced"
            }));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        String json = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{\"format\":\"preflight-preparation-storage-plan-v1\""), json);
        assertTrue(json.contains("\"predictedAdditionalBytes\":"), json);
        assertFalse(json.contains("upperBoundAdditionalBytes"), json);
        assertTrue(json.contains("\"safeToPrepare\":true"), json);
        assertFalse(Files.exists(cache));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("prepare: storage-plan completed"));
    }

    @Test
    void printsBoundedStageProgressToStderrAndKeepsStdoutAsReportPath() throws Exception {
        Path install = fixture();
        Path report = temporaryDirectory.resolve("progress.json");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try (PrintStream capturedOut = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                PrintStream capturedErr = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            assertEquals(0, PreflightCli.run(new String[] {
                    "prepare",
                    "--game", install.toString(),
                    "--cache-dir", temporaryDirectory.resolve("progress-cache").toString(),
                    "--report", report.toString(),
                    "--no-resource-index",
                    "--no-classpath",
                    "--no-textures"
            }));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertEquals(report.toAbsolutePath().normalize() + System.lineSeparator(),
                stdout.toString(StandardCharsets.UTF_8));
        String progress = stderr.toString(StandardCharsets.UTF_8);
        for (String stage : new String[] {
                "census", "resource-index", "classpath-index", "spec-store-identity", "textures",
                "lookup-verification"}) {
            assertTrue(progress.contains("prepare: " + stage + " started"), progress);
            assertTrue(progress.contains("prepare: " + stage + " completed status="), progress);
            assertTrue(progress.contains(
                    PrepareCommand.PROGRESS_PREFIX
                            + "{\"format\":\"preflight-preparation-progress-v1\",\"phase\":\""
                            + stage + "\""), progress);
        }
        assertTrue(progress.length() < 8_000, progress);
    }

    @Test
    void reportsSkippedOptionalStagesWithoutEditingInstallation() throws Exception {
        Path install = fixture();
        Path report = temporaryDirectory.resolve("minimal.json");
        long before = Files.getLastModifiedTime(install.resolve("mods/enabled_mods.json")).toMillis();

        assertEquals(0, PreflightCli.run(new String[] {
                "prepare",
                "--game", install.toString(),
                "--cache-dir", temporaryDirectory.resolve("minimal-cache").toString(),
                "--report", report.toString(),
                "--serial-stages",
                "--no-resource-index",
                "--no-classpath",
                "--no-textures"
        }));

        String json = Files.readString(report);
        assertTrue(json.contains("\"resourceIndex\":{\"status\":\"SKIPPED\""), json);
        assertTrue(json.contains("\"classpathIndex\":{\"status\":\"SKIPPED\""), json);
        assertTrue(json.contains("\"specStoreIdentity\":{\"status\":\"SKIPPED\""), json);
        assertTrue(json.contains("\"textures\":{\"status\":\"SKIPPED\""), json);
        assertTrue(json.contains("\"parallelStages\":false"), json);
        assertEquals(before, Files.getLastModifiedTime(install.resolve("mods/enabled_mods.json")).toMillis());
    }

    @Test
    void minimalPreparationRecordsItsIntentForLaterHealthChecksAndLaunches() throws Exception {
        Path install = fixture();
        Path cache = temporaryDirectory.resolve("minimal-profile-cache");

        assertEquals(0, PreflightCli.run(new String[] {
                "prepare",
                "--game", install.toString(),
                "--cache-dir", cache.toString(),
                "--no-textures"
        }));

        ResourceIndex prepared = Files.list(ResourceIndexIO.directory(cache))
                .filter(path -> path.toString().endsWith(".spfi"))
                .findFirst()
                .map(path -> {
                    try {
                        return ResourceIndexIO.read(path);
                    } catch (Exception error) {
                        throw new RuntimeException(error);
                    }
                })
                .orElseThrow();
        Path marker = MinimalPreparationMarker.path(cache, prepared.profileFingerprint());
        assertTrue(Files.isRegularFile(marker));
        MinimalPreparationMarker.validate(marker, prepared.profileFingerprint());
        assertFalse(Files.exists(TexturePreparationReceipt.path(cache, prepared.profileFingerprint())));
        assertFalse(Files.exists(TextureManifestIO.directory(cache)
                .resolve(prepared.profileFingerprint() + ".spfm")));
    }

    private Path fixture() throws Exception {
        Path install = temporaryDirectory.resolve("Starsector");
        Path core = install.resolve("starsector-core");
        Path mods = install.resolve("mods");
        Path mod = mods.resolve("Example Mod");
        writeImage(core.resolve("graphics/core.png"), Color.GRAY);
        writeImage(core.resolve("graphics/shared.png"), Color.BLUE);
        writeImage(mod.resolve("graphics/shared.png"), Color.MAGENTA);
        jar(core.resolve("starfarer_obf.jar"), Map.of("game/Spec.class", new byte[] {9}));
        Files.createDirectories(mod);
        Files.writeString(mod.resolve("mod_info.json"), """
                {
                  "id":"example_mod",
                  "name":"Example Mod",
                  "jars":["jars/example.jar"]
                }
                """);
        jar(mod.resolve("jars/example.jar"), Map.of(
                "example/Plugin.class", new byte[] {1, 2, 3},
                "example/config.json", new byte[] {4, 5}));
        Files.createDirectories(mods);
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[\"example_mod\"]}");
        Path launcher = install.resolve("starsector.sh");
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, "#!/bin/sh\n");
        launcher.toFile().setExecutable(true);
        return install;
    }

    private static void writeImage(Path path, Color color) throws Exception {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }

    private static void jar(Path path, Map<String, byte[]> entries) throws Exception {
        Files.createDirectories(path.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }
}

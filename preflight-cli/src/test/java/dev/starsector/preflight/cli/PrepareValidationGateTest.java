package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrepareValidationGateTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resourceProviderChangeAfterPlanningIsRebuiltUnderThePreparationLease() throws Exception {
        Path install = fixture("planning-race");
        Path cache = temporaryDirectory.resolve("planning-race-cache");
        Path report = temporaryDirectory.resolve("planning-race-report.json");
        Path marker = temporaryDirectory.resolve("planning-race.marker");
        String beforeFingerprint = ResourceIndexBuilder.build(install).index().profileFingerprint();

        int exit = runWithPause(
                PreparationFaultInjection.PAUSE_MARKER_PROPERTY,
                marker,
                prepareCommand(install, cache, report),
                () -> writeImage(
                        install.resolve("mods/Example Mod/graphics/late-provider.png"),
                        Color.ORANGE));

        assertEquals(0, exit);
        String afterFingerprint = ResourceIndexBuilder.build(install).index().profileFingerprint();
        assertFalse(beforeFingerprint.equals(afterFingerprint));
        String json = Files.readString(report);
        assertTrue(json.contains("\"profileFingerprint\":\"" + afterFingerprint + "\""), json);
        assertTrue(json.contains(afterFingerprint + ".spfi"), json);
        assertFalse(json.contains(beforeFingerprint + ".spfi"), json);
    }

    @Test
    void failedResourceValidationPublishesNoNewIndexAndSkipsDependentStages() throws Exception {
        Path install = fixture("validation-failure");
        Path cache = temporaryDirectory.resolve("validation-failure-cache");
        Path report = temporaryDirectory.resolve("validation-failure-report.json");
        Path marker = temporaryDirectory.resolve("validation-failure.marker");
        Path provider = install.resolve("starsector-core/graphics/core.png");

        int exit = runWithPause(
                PreparationFaultInjection.RESOURCE_VALIDATION_PAUSE_MARKER_PROPERTY,
                marker,
                prepareCommand(install, cache, report),
                () -> Files.delete(provider));

        assertEquals(5, exit);
        String json = Files.readString(report);
        assertTrue(json.contains("\"successful\":false"), json);
        assertTrue(json.contains("\"resourceIndex\":{\"status\":\"FAILED\""), json);
        assertTrue(json.contains("\"specStoreIdentity\":{\"status\":\"SKIPPED\""), json);
        assertTrue(json.contains("\"textures\":{\"status\":\"SKIPPED\""), json);
        assertTrue(json.contains("\"lookupVerification\":{\"status\":\"SKIPPED\""), json);
        assertTrue(json.contains("\"resourceIndex\":null"), json);
        assertFalse(hasFileWithSuffix(
                dev.starsector.preflight.core.ResourceIndexIO.directory(cache), ".spfi"));
        assertFalse(hasFileWithSuffix(cache.resolve("spec-store/profiles"), ".json"));
        assertFalse(hasFileWithSuffix(cache.resolve("manifests"), ".spfm"));

        writeImage(provider, Color.GRAY);
        assertEquals(0, PreflightCli.run(prepareCommand(install, cache, report)));
        String retry = Files.readString(report);
        assertTrue(retry.contains("\"successful\":true"), retry);
        assertTrue(retry.contains("\"resourceIndex\":{\"status\":\"SUCCESS\""), retry);
        assertTrue(retry.contains("\"textures\":{\"status\":\"SUCCESS\""), retry);
    }

    @Test
    void failedValidationLeavesPreviouslyValidatedResourceArtifactUntouched() throws Exception {
        Path install = fixture("preserve-existing");
        Path cache = temporaryDirectory.resolve("preserve-existing-cache");
        Path report = temporaryDirectory.resolve("preserve-existing-report.json");
        String[] command = prepareCommand(install, cache, report);
        assertEquals(0, PreflightCli.run(command));

        Path index = Files.list(dev.starsector.preflight.core.ResourceIndexIO.directory(cache))
                .filter(path -> path.toString().endsWith(".spfi"))
                .findFirst()
                .orElseThrow();
        byte[] indexBefore = Files.readAllBytes(index);
        Path provider = install.resolve("starsector-core/graphics/core.png");
        byte[] providerBefore = Files.readAllBytes(provider);
        FileTime providerTime = Files.getLastModifiedTime(provider);
        Path marker = temporaryDirectory.resolve("preserve-existing.marker");

        int exit = runWithPause(
                PreparationFaultInjection.RESOURCE_VALIDATION_PAUSE_MARKER_PROPERTY,
                marker,
                command,
                () -> Files.delete(provider));

        assertEquals(5, exit);
        assertEquals(java.util.HexFormat.of().formatHex(indexBefore),
                java.util.HexFormat.of().formatHex(Files.readAllBytes(index)));

        Files.write(provider, providerBefore);
        Files.setLastModifiedTime(provider, providerTime);
        assertEquals(0, PreflightCli.run(command));
        String retry = Files.readString(report);
        assertTrue(retry.contains("\"artifactHit\":true"), retry);
        assertTrue(retry.contains("\"successful\":true"), retry);
    }

    private String[] prepareCommand(Path install, Path cache, Path report) {
        return new String[] {
                "prepare",
                "--game", install.toString(),
                "--cache-dir", cache.toString(),
                "--report", report.toString(),
                "--workers", "2",
                "--memory-mb", "32",
                "--serial-stages",
                "--verify-lookups",
                "--lookup-queries", "20"
        };
    }

    private int runWithPause(
            String markerProperty,
            Path marker,
            String[] command,
            CheckedRunnable mutation) throws Exception {
        String priorEnabled = System.getProperty(PreparationFaultInjection.ENABLED_PROPERTY);
        String priorMarker = System.getProperty(markerProperty);
        Path release = marker.resolveSibling(marker.getFileName() + ".release");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            System.setProperty(PreparationFaultInjection.ENABLED_PROPERTY, "true");
            System.setProperty(markerProperty, marker.toString());
            Future<Integer> result = executor.submit(() -> PreflightCli.run(command));
            waitFor(marker);
            mutation.run();
            Files.writeString(release, "release\n");
            return result.get(20, TimeUnit.SECONDS);
        } finally {
            if (Files.exists(marker) && !Files.exists(release)) {
                Files.writeString(release, "release\n");
            }
            executor.shutdownNow();
            restoreProperty(PreparationFaultInjection.ENABLED_PROPERTY, priorEnabled);
            restoreProperty(markerProperty, priorMarker);
            Files.deleteIfExists(release);
            Files.deleteIfExists(marker);
        }
    }

    private static void waitFor(Path marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(marker) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(marker), "Preparation never reached injected pause: " + marker);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static boolean hasFileWithSuffix(Path directory, String suffix) throws Exception {
        if (!Files.isDirectory(directory)) return false;
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> path.toString().endsWith(suffix));
        }
    }

    private Path fixture(String name) throws Exception {
        Path install = temporaryDirectory.resolve(name).resolve("Starsector");
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

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}

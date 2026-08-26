package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunCommandIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void fatalGameLogOverridesZeroLauncherExitAndIsReported() throws Exception {
        Path game = temporaryDirectory.resolve("Synthetic Starsector");
        Files.createDirectories(game.resolve("logs"));
        Path launcher = fakeLauncher(game, LauncherMode.FATAL_LOG_ZERO);
        Path trace = temporaryDirectory.resolve("fatal-trace");

        ProcessResult result = run(game, launcher, trace);

        assertTrue(result.completed(), result.output());
        assertEquals(StarsectorRunLogEvidence.FATAL_LIFECYCLE_EXIT, result.exitCode(), result.output());
        Map<String, Object> report = StrictJson.object(Files.readString(trace.resolve("run.json")));
        assertEquals((long) StarsectorRunLogEvidence.FATAL_LIFECYCLE_EXIT, report.get("exitCode"));
        assertEquals(0L, report.get("launcherExitCode"));
        assertEquals("FATAL_LOG_EVIDENCE", report.get("outcome"));
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) report.get("lifecycleEvidence");
        assertNotNull(evidence);
        assertEquals(Boolean.TRUE, evidence.get("fatalDetected"));
        assertEquals("OFF", report.get("adapterMode"));
        assertEquals(null, report.get("textureCacheDirectory"));
        assertTrue(result.output().contains("is not a clean game exit"), result.output());
    }

    @Test
    void fatalChildConsoleOverridesZeroLauncherExitAndRecordsExactEvidencePath() throws Exception {
        Path game = temporaryDirectory.resolve("Console Fatal Starsector");
        Files.createDirectories(game.resolve("logs"));
        Path launcher = fakeLauncher(game, LauncherMode.FATAL_CONSOLE_ZERO);
        Path trace = temporaryDirectory.resolve("console-fatal-trace");

        ProcessResult result = run(game, launcher, trace);

        assertTrue(result.completed(), result.output());
        assertEquals(StarsectorRunLogEvidence.FATAL_LIFECYCLE_EXIT, result.exitCode(), result.output());
        assertTrue(result.output().contains("FATAL com.fs.starfarer.launcher.opengl.GLLauncher"), result.output());
        Map<String, Object> report = StrictJson.object(Files.readString(trace.resolve("run.json")));
        assertEquals((long) StarsectorRunLogEvidence.FATAL_LIFECYCLE_EXIT, report.get("exitCode"));
        assertEquals(0L, report.get("launcherExitCode"));
        assertEquals("FATAL_LOG_EVIDENCE", report.get("outcome"));
        assertTrue(report.get("launcherConsole").toString().endsWith("console.txt"));
        @SuppressWarnings("unchecked")
        Map<String, Object> capture = (Map<String, Object>) report.get("launcherConsoleCapture");
        assertEquals(Boolean.TRUE, capture.get("combinedStdoutStderr"));
        assertEquals(Boolean.FALSE, capture.get("truncated"));
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) report.get("lifecycleEvidence");
        assertEquals(Boolean.TRUE, evidence.get("consoleAvailable"));
        assertEquals(Boolean.TRUE, evidence.get("fatalDetected"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) evidence.get("matches");
        assertEquals("launcher-fatal", matches.get(0).get("category"));
        assertEquals("console.txt", matches.get(0).get("consoleFile"));
    }

    @Test
    void cleanZeroLauncherExitRemainsSuccessfulAndRecordsWrapperIdentity() throws Exception {
        Path game = temporaryDirectory.resolve("Clean Synthetic Starsector");
        Files.createDirectories(game.resolve("logs"));
        Path launcher = fakeLauncher(game, LauncherMode.CLEAN_ZERO);
        Path trace = temporaryDirectory.resolve("clean-trace");

        ProcessResult result = run(game, launcher, trace);

        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        Map<String, Object> report = StrictJson.object(Files.readString(trace.resolve("run.json")));
        assertEquals(0L, report.get("exitCode"));
        assertEquals(0L, report.get("launcherExitCode"));
        assertEquals("COMPLETED", report.get("outcome"));
        assertTrue(Files.isRegularFile(trace.resolve("console.txt")));
        Path packaged = Path.of("target", "preflight.jar").toRealPath();
        assertEquals(RunIdentity.SCOPE, report.get("runtimeIdentityScope"));
        assertEquals(packaged.toString(), report.get("preflightJar"));
        assertEquals(Hashes.sha256(packaged), report.get("preflightJarSha256"));
        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) report.get("wrapperRuntime");
        assertNotNull(runtime);
        assertEquals(System.getProperty("java.version"), runtime.get("javaVersion"));
        assertEquals(System.getProperty("java.vendor"), runtime.get("javaVendor"));
        assertEquals(System.getProperty("os.arch"), runtime.get("osArch"));
    }

    @Test
    void quietLoggingWritesAndInjectsTheExactPerRunConfiguration() throws Exception {
        Path game = temporaryDirectory.resolve("Quiet Log Synthetic Starsector");
        Files.createDirectories(game.resolve("logs"));
        Path launcher = fakeLauncher(game, LauncherMode.CLEAN_ZERO);
        Path trace = temporaryDirectory.resolve("quiet-log-trace");

        ProcessResult result = run(game, launcher, trace, List.of(
                "--no-adapter", "--quiet-logs"));

        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        Path configuration = trace.resolve(QuietLogConfiguration.FILE_NAME).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(configuration));
        String injected = Files.readString(game.resolve("java-tool-options.txt"));
        assertTrue(injected.contains(QuietLogConfiguration.javaOption(configuration)), injected);
        assertTrue(injected.contains(",quietLogs=on"), injected);

        Map<String, Object> report = StrictJson.object(Files.readString(trace.resolve("run.json")));
        assertEquals(Boolean.TRUE, report.get("fileOnlyLogs"));
        assertEquals(Boolean.TRUE, report.get("quietLogs"));
        assertEquals(configuration.toString(), report.get("quietLogConfiguration"));
    }

    @Test
    void fileOnlyLoggingKeepsTheAppenderUnbuffered() throws Exception {
        Path game = temporaryDirectory.resolve("File Only Synthetic Starsector");
        Files.createDirectories(game.resolve("logs"));
        Path launcher = fakeLauncher(game, LauncherMode.CLEAN_ZERO);
        Path trace = temporaryDirectory.resolve("file-only-log-trace");

        ProcessResult result = run(game, launcher, trace, List.of(
                "--no-adapter", "--file-only-logs"));

        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        Path configuration = trace.resolve(QuietLogConfiguration.FILE_ONLY_NAME)
                .toAbsolutePath().normalize();
        String content = Files.readString(configuration);
        assertFalse(content.contains("BufferedIO"));
        assertFalse(content.contains("ConsoleAppender"));
        Map<String, Object> report = StrictJson.object(Files.readString(trace.resolve("run.json")));
        assertEquals(Boolean.TRUE, report.get("fileOnlyLogs"));
        assertEquals(Boolean.FALSE, report.get("quietLogs"));
        assertEquals(configuration.toString(), report.get("quietLogConfiguration"));
    }

    @Test
    void nonzeroLauncherExitRemainsAuthoritativeWithoutFatalEvidence() throws Exception {
        Path game = temporaryDirectory.resolve("Nonzero Synthetic Starsector");
        Files.createDirectories(game.resolve("logs"));
        Path launcher = fakeLauncher(game, LauncherMode.CLEAN_NONZERO);
        Path trace = temporaryDirectory.resolve("nonzero-trace");

        ProcessResult result = run(game, launcher, trace);

        assertTrue(result.completed(), result.output());
        assertEquals(23, result.exitCode(), result.output());
        Map<String, Object> report = StrictJson.object(Files.readString(trace.resolve("run.json")));
        assertEquals(23L, report.get("exitCode"));
        assertEquals(23L, report.get("launcherExitCode"));
        assertEquals("LAUNCHER_EXIT_NONZERO", report.get("outcome"));
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) report.get("lifecycleEvidence");
        assertEquals(Boolean.FALSE, evidence.get("fatalDetected"));
    }

    @Test
    void automaticTextureLaunchPassesExactCurrentArtifactsToTheRealLauncher() throws Exception {
        Path game = temporaryDirectory.resolve("Auto Texture Starsector");
        Path source = game.resolve("starsector-core/graphics/test.png");
        Path mods = game.resolve("mods");
        Files.createDirectories(source.getParent());
        Files.createDirectories(mods);
        Files.writeString(source, "texture");
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[]}");
        Path launcher = fakeLauncher(game, LauncherMode.CLEAN_ZERO);

        var current = ResourceIndexBuilder.build(game).index();
        Path cache = temporaryDirectory.resolve("auto-texture-cache");
        Path index = cache.resolve("resource-indexes/" + current.profileFingerprint() + ".spfi");
        Path manifest = cache.resolve("manifests/" + current.profileFingerprint() + ".spfm");
        dev.starsector.preflight.core.ResourceIndexIO.write(index, current);
        dev.starsector.preflight.core.TextureManifestIO.write(
                manifest,
                new dev.starsector.preflight.core.TextureManifest(current.profileFingerprint(), Map.of()));
        Path trace = temporaryDirectory.resolve("auto-texture-trace");

        ProcessResult result = run(game, launcher, trace, List.of(
                "--adapter", "--texture-auto", "--texture-cache-dir", cache.toString()));

        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        Map<String, Object> report = StrictJson.object(Files.readString(trace.resolve("run.json")));
        assertEquals(Boolean.TRUE, report.get("textureAuto"));
        assertEquals(current.profileFingerprint(), report.get("textureProfileFingerprint"));
        assertEquals(cache.toRealPath().toString(), report.get("textureCacheDirectory"));
        assertEquals(manifest.toRealPath().toString(), report.get("textureManifest"));
        assertEquals(index.toRealPath().toString(), report.get("textureIndex"));
        assertEquals("ENABLED", report.get("adapterMode"));
        String injected = Files.readString(game.resolve("java-tool-options.txt"));
        assertTrue(injected.contains("textureCache64=" + encoded(cache.toRealPath())), injected);
        assertTrue(injected.contains("textureManifest64=" + encoded(manifest.toRealPath())), injected);
        assertTrue(injected.contains("textureIndex64=" + encoded(index.toRealPath())), injected);
        assertTrue(injected.contains("textureMode=compatibility"), injected);
        assertFalse(injected.contains("prepared-pixels"), injected);
    }

    @Test
    void automaticLaunchUsesMinimalPreparationWithoutInjectingTextureArtifacts() throws Exception {
        Path game = temporaryDirectory.resolve("Minimal Prepared Starsector");
        Path source = game.resolve("starsector-core/graphics/test.png");
        Path mods = game.resolve("mods");
        Files.createDirectories(source.getParent());
        Files.createDirectories(mods);
        Files.writeString(source, "texture");
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[]}");
        Path launcher = fakeLauncher(game, LauncherMode.CLEAN_ZERO);

        var current = ResourceIndexBuilder.build(game).index();
        Path cache = temporaryDirectory.resolve("minimal-prepared-cache");
        Path index = cache.resolve("resource-indexes/" + current.profileFingerprint() + ".spfi");
        dev.starsector.preflight.core.ResourceIndexIO.write(index, current);
        MinimalPreparationMarker.write(cache, current.profileFingerprint());
        Path trace = temporaryDirectory.resolve("minimal-prepared-trace");

        ProcessResult result = run(game, launcher, trace, List.of(
                "--adapter", "--texture-auto", "--texture-cache-dir", cache.toString()));

        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        Map<String, Object> report = StrictJson.object(Files.readString(trace.resolve("run.json")));
        assertEquals(Boolean.FALSE, report.get("preparedTextures"));
        assertEquals(cache.toRealPath().toString(), report.get("textureCacheDirectory"));
        assertEquals(null, report.get("textureManifest"));
        assertEquals(null, report.get("textureIndex"));
        assertEquals(current.profileFingerprint(), report.get("textureProfileFingerprint"));
        String injected = Files.readString(game.resolve("java-tool-options.txt"));
        assertTrue(injected.contains("textureCache64="), injected);
        assertTrue(injected.contains("textureProfile=" + current.profileFingerprint()), injected);
        assertFalse(injected.contains("textureManifest64="), injected);
        assertFalse(injected.contains("textureIndex64="), injected);
        assertTrue(injected.contains("textureMode=compatibility"), injected);
    }

    @Test
    void singleChunkRecordingPolicyReachesTheLauncherAndRunReceipt() throws Exception {
        Path game = temporaryDirectory.resolve("Single Chunk Starsector");
        Files.createDirectories(game.resolve("logs"));
        Path launcher = fakeLauncher(game, LauncherMode.CLEAN_ZERO);
        Path trace = temporaryDirectory.resolve("single-chunk-trace");

        ProcessResult result = run(game, launcher, trace, List.of(
                "--no-adapter", "--profile", "--single-chunk-recording"));

        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        Map<String, Object> report = StrictJson.object(Files.readString(trace.resolve("run.json")));
        assertEquals("SAMPLE", report.get("recordingMode"));
        assertEquals(true, report.get("singleChunkRecording"));
        assertEquals(256L * 1024L * 1024L, report.get("recordingMaxChunkBytes"));
        assertEquals(false, report.get("recordingPeriodicFlush"));

        String injected = Files.readString(game.resolve("java-tool-options.txt"));
        assertTrue(injected.contains(",record=sample"), injected);
        assertTrue(injected.contains(",flush=0"), injected);
        assertTrue(injected.contains(
                "-XX:FlightRecorderOptions=memorysize=256m,maxchunksize=256m"), injected);
    }

    @Test
    void campaignEntityIndexGateReachesTheLauncherAndRunReceipt() throws Exception {
        Path game = temporaryDirectory.resolve("Entity Index Starsector");
        Files.createDirectories(game.resolve("logs"));
        Path launcher = fakeLauncher(game, LauncherMode.CLEAN_ZERO);
        Path trace = temporaryDirectory.resolve("entity-index-trace");

        ProcessResult result = run(game, launcher, trace, List.of(
                "--adapter", "--campaign-entity-index", "--no-record"));

        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        Map<String, Object> report = StrictJson.object(Files.readString(trace.resolve("run.json")));
        assertEquals("ENABLED", report.get("adapterMode"));
        assertEquals(true, report.get("campaignEntityIndex"));
        assertEquals("OFF", report.get("recordingMode"));
        assertEquals(false, report.get("recordingPeriodicFlush"));

        String injected = Files.readString(game.resolve("java-tool-options.txt"));
        assertTrue(injected.contains("adapter=enabled"), injected);
        assertTrue(injected.contains(" -Dpreflight.campaign.entityIndex=true"), injected);
        assertTrue(injected.contains(",record=off"), injected);
    }

    @Test
    void campaignTimingEnablesFrameAndAttributionReportsInTheGameJvm() throws Exception {
        Path game = temporaryDirectory.resolve("Campaign Timing Starsector");
        Files.createDirectories(game.resolve("logs"));
        Path launcher = fakeLauncher(game, LauncherMode.CLEAN_ZERO);
        Path trace = temporaryDirectory.resolve("campaign-timing-trace");

        ProcessResult result = run(game, launcher, trace, List.of(
                "--adapter", "--campaign-times", "--no-record"));

        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        Map<String, Object> report = StrictJson.object(Files.readString(trace.resolve("run.json")));
        assertEquals(true, report.get("campaignTimes"));
        assertEquals(true, report.get("frameTimes"));

        String injected = Files.readString(game.resolve("java-tool-options.txt"));
        assertTrue(injected.contains(" -Dpreflight.frameTimes=true"), injected);
        assertTrue(injected.contains(" -Dpreflight.campaignTimes=true"), injected);
    }

    @Test
    void fastRenderingOwnsJaninoWithoutDisablingTheRestOfTheAdapter() throws Exception {
        Path game = temporaryDirectory.resolve("Fast Rendering Synthetic Starsector");
        Files.createDirectories(game.resolve("logs"));
        Path vanillaName = fakeLauncher(game, LauncherMode.CLEAN_ZERO);
        boolean windows = System.getProperty("os.name")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
        Path launcher = game.resolve(windows ? "fr.bat" : "fr.sh");
        Files.move(vanillaName, launcher);
        Path trace = temporaryDirectory.resolve("fast-rendering-owner-trace");

        ProcessResult result = run(game, launcher, trace, List.of(
                "--adapter", "--janino-bytecode-cache", "--no-record"));

        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        assertFalse(result.output().contains("left Janino compilation to Fast Rendering"),
                result.output());
        String injected = Files.readString(game.resolve("java-tool-options.txt"));
        assertTrue(injected.contains("adapter=enabled"), injected);
        assertFalse(injected.contains("janinoBytecodeCache"), injected);
        Map<String, Object> report = StrictJson.object(Files.readString(trace.resolve("run.json")));
        assertEquals("FAST_RENDERING", report.get("runtimeOwner"));
        assertEquals(Boolean.TRUE, report.get("janinoBytecodeCache"),
                "the receipt preserves that the user requested it even though ownership suppressed it");
    }

    @Test
    void ordinaryRunDoesNotDumpItsInternalLaunchPlan() throws Exception {
        Path game = temporaryDirectory.resolve("Quiet Starsector");
        Files.createDirectories(game.resolve("logs"));
        Path launcher = fakeLauncher(game, LauncherMode.CLEAN_ZERO);
        Path trace = temporaryDirectory.resolve("quiet-trace");

        ProcessResult result = run(game, launcher, trace, List.of("--no-adapter", "--no-record"));

        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        assertFalse(result.output().contains("Preflight selected:"), result.output());
        assertFalse(result.output().contains("recording was not created"), result.output());
    }

    @Test
    void dryRunShowsTheInternalLaunchPlanWithoutStartingTheGame() throws Exception {
        Path game = temporaryDirectory.resolve("Dry Run Starsector");
        Files.createDirectories(game.resolve("logs"));
        Path launcher = fakeLauncher(game, LauncherMode.CLEAN_ZERO);
        Path trace = temporaryDirectory.resolve("dry-run-trace");

        ProcessResult result = run(game, launcher, trace, List.of("--no-adapter", "--dry-run"));

        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Preflight selected:"), result.output());
        assertTrue(result.output().contains("optimization preset:"), result.output());
        assertFalse(Files.exists(game.resolve("java-tool-options.txt")));
    }

    private static Path fakeLauncher(Path game, LauncherMode mode) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        String clean = "100 [Thread-3] INFO com.fs.starfarer.combat.CombatMain  - synthetic clean exit";
        String fatalLog = "328814 [Thread-3] ERROR com.fs.starfarer.combat.CombatMain  - "
                + "java.lang.NullPointerException: synthetic fatal";
        String frame = "at com.fs.starfarer.combat.CombatMain.main(Unknown Source)";
        String fatalConsole = "FATAL com.fs.starfarer.launcher.opengl.GLLauncher - "
                + "java.lang.IllegalArgumentException: synthetic fatal console";
        Path launcher = game.resolve(windows ? "starsector.bat" : "starsector.sh");
        if (windows) {
            StringBuilder script = new StringBuilder("@echo off\r\n")
                    .append("> \"%~dp0java-tool-options.txt\" echo %JAVA_TOOL_OPTIONS%\r\n");
            if (mode == LauncherMode.FATAL_LOG_ZERO) {
                script.append("> \"%~dp0logs\\starsector.log\" echo ").append(fatalLog).append("\r\n")
                        .append(">> \"%~dp0logs\\starsector.log\" echo     ").append(frame).append("\r\n");
            } else {
                script.append("> \"%~dp0logs\\starsector.log\" echo ").append(clean).append("\r\n");
            }
            if (mode == LauncherMode.FATAL_CONSOLE_ZERO) {
                script.append("1>&2 echo ").append(fatalConsole).append("\r\n");
            }
            script.append("exit /b ").append(mode == LauncherMode.CLEAN_NONZERO ? 23 : 0).append("\r\n");
            Files.writeString(launcher, script);
        } else {
            StringBuilder script = new StringBuilder("#!/bin/sh\n")
                    .append("printf '%s\\n' \"$JAVA_TOOL_OPTIONS\" > java-tool-options.txt\n");
            if (mode == LauncherMode.FATAL_LOG_ZERO) {
                script.append("printf '%s\\n' '").append(fatalLog).append("' > logs/starsector.log\n")
                        .append("printf '%s\\n' '    ").append(frame).append("' >> logs/starsector.log\n");
            } else {
                script.append("printf '%s\\n' '").append(clean).append("' > logs/starsector.log\n");
            }
            if (mode == LauncherMode.FATAL_CONSOLE_ZERO) {
                script.append("printf '%s\\n' '").append(fatalConsole).append("' >&2\n");
            }
            script.append("exit ").append(mode == LauncherMode.CLEAN_NONZERO ? 23 : 0).append("\n");
            Files.writeString(launcher, script);
        }
        return launcher;
    }

    private static ProcessResult run(Path game, Path launcher, Path trace) throws Exception {
        return run(game, launcher, trace, List.of("--no-adapter"));
    }

    private static ProcessResult run(Path game, Path launcher, Path trace, List<String> adapterArguments)
            throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Path jar = Path.of("target", "preflight.jar").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(jar), "missing packaged CLI: " + jar);
        String reactorClasses = Path.of("target", "classes").toAbsolutePath().normalize().toString();
        String testClasspath = reactorClasses + System.getProperty("path.separator")
                + System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(
                java.toString(),
                "-D" + SelfJar.CLASSES_OVERRIDE_PROPERTY + "=" + jar,
                "-cp", testClasspath,
                PreflightCli.class.getName(),
                "run",
                "--game", game.toString(),
                "--launcher", launcher.toString(),
                "--trace-dir", trace.toString(),
                "--no-scan",
                "--no-summary"));
        command.addAll(adapterArguments);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(completed, process.exitValue(), output);
    }

    private static String encoded(Path path) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
    }

    private enum LauncherMode {
        CLEAN_ZERO,
        FATAL_LOG_ZERO,
        FATAL_CONSOLE_ZERO,
        CLEAN_NONZERO
    }

    private record ProcessResult(boolean completed, int exitCode, String output) {
    }
}

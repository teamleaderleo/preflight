package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** Owns one packaged Preflight launch and its PID-addressed desktop smoke driver. */
final class DesktopSmokeLaunch {
    static final String FORMAT = "starsector-preflight-smoke-launch-v1";
    static final String CANCELLATION_FILE = "cancel.requested";
    private static final Duration RUNTIME_IDENTITY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POSTPROCESS_TIMEOUT = Duration.ofSeconds(60);

    private DesktopSmokeLaunch() {
    }

    static Map<String, Object> launch(
            DesktopSmokeScenario scenario,
            Path runDirectory,
            Path game,
            Path launcher,
            DesktopSmokeDriver driver,
            Clock clock) throws Exception {
        requireSupportedLaunch(scenario);
        requirePreparedStorage(
                scenario,
                PreflightHome.resolve(
                        Platform.current(),
                        Path.of(System.getProperty("user.home")),
                        System.getenv()),
                game);
        Path run = freshRunDirectory(runDirectory);
        Path runtimeProcess = run.resolve("runtime-process.json");
        Path launcherOutput = run.resolve("smoke-launcher.txt");
        Path launchResult = run.resolve("smoke-launch-result.json");
        Path cancellationFile = run.resolve(CANCELLATION_FILE);
        List<String> diagnostics = new ArrayList<>();

        // Interactive scenarios prove their desktop driver before a game exists. Startup-only
        // scenarios read Preflight's runtime marker and never touch desktop input permissions.
        if (!scenario.usesOnlyRuntimeControl()) {
            DesktopSmokeDriver.Descriptor descriptor = driver.descriptor();
            diagnostics.addAll(
                    descriptor.diagnostics() == null ? List.of() : descriptor.diagnostics());
        }
        rejectTrackedRuntime(run);

        List<String> command = command(
                javaExecutable(), SelfJar.locate(), scenario, run, game, launcher);
        ProcessBuilder launcherBuilder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(launcherOutput.toFile());
        // The native bridge belongs only to this controller. Mods run in the game child and must
        // never inherit the per-launch capability that authorizes desktop operations.
        MacDesktopSmokeDriver.removeBridgeCredentials(launcherBuilder.environment());
        Process process = launcherBuilder.start();
        AtomicBoolean cancellationObserved = new AtomicBoolean(false);
        AtomicBoolean launchFinished = new AtomicBoolean(false);
        Thread cancellationMonitor = cancellationMonitor(
                process, runtimeProcess, cancellationFile, cancellationObserved, launchFinished);
        Map<String, Object> evidence = null;
        String status = "failed";
        try {
            awaitRuntimeIdentity(process, runtimeProcess, launcherOutput);
            if (cancellationRequested(cancellationFile)) {
                cancellationObserved.set(true);
                throw new java.util.concurrent.CancellationException(
                        "Safe cancellation was requested before desktop control began");
            }
            evidence = DesktopSmokeRunner.run(
                    scenario, runtimeProcess, run, driver, clock);
            status = evidence.get("status").toString();
        } catch (Exception failure) {
            diagnostics.add("Unattended smoke launch failed: " + bounded(failure.getMessage()));
        } finally {
            boolean runtimeStoppedByController = stopExactRuntime(runtimeProcess, diagnostics);
            awaitLauncher(process, diagnostics);
            launchFinished.set(true);
            joinCancellationMonitor(cancellationMonitor, diagnostics);
            boolean cancelled = cancellationObserved.get() || cancellationRequested(cancellationFile);
            boolean cleanupComplete = !process.isAlive() && exactRuntimeCleanupComplete(runtimeProcess);
            if (cancelled && cleanupComplete) {
                status = "cancelled";
                diagnostics.add("Safe cancellation was requested; exact-process cleanup completed");
            } else if (cancelled) {
                status = "failed";
                diagnostics.add("Safe cancellation was requested, but exact-process cleanup failed");
            } else if (process.isAlive()) {
                status = "failed";
                diagnostics.add("Preflight launch process is still alive after bounded cleanup");
            } else if (process.exitValue() != 0
                    && !acceptsControllerStopExit(scenario, status, runtimeStoppedByController)) {
                status = "failed";
                diagnostics.add("Preflight launch exited with code " + process.exitValue()
                        + tailDetail(launcherOutput));
            }
            Map<String, Object> result = result(
                    status, run, runtimeProcess, launcherOutput, command,
                    process.isAlive() ? null : process.exitValue(), evidence, diagnostics);
            atomicWrite(launchResult, Json.object(result) + System.lineSeparator());
        }
        return result(
                status, run, runtimeProcess, launcherOutput, command,
                process.isAlive() ? null : process.exitValue(), evidence, diagnostics);
    }

    private static Thread cancellationMonitor(
            Process launcher,
            Path runtimeProcess,
            Path cancellationFile,
            AtomicBoolean cancellationObserved,
            AtomicBoolean launchFinished) {
        Thread monitor = new Thread(() -> {
            while (!launchFinished.get()) {
                if (cancellationRequested(cancellationFile)) {
                    cancellationObserved.set(true);
                    stopExactRuntime(runtimeProcess, new ArrayList<>());
                    if (!launcher.isAlive()) return;
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "preflight-smoke-cancellation");
        monitor.setDaemon(true);
        monitor.start();
        return monitor;
    }

    private static void joinCancellationMonitor(Thread monitor, List<String> diagnostics) {
        try {
            monitor.join(1_000L);
            if (monitor.isAlive()) {
                monitor.interrupt();
                monitor.join(1_000L);
            }
            if (monitor.isAlive()) {
                diagnostics.add("Cancellation monitor didn't stop after launch cleanup");
            }
        } catch (InterruptedException interrupted) {
            monitor.interrupt();
            Thread.currentThread().interrupt();
            diagnostics.add("Interrupted while stopping the cancellation monitor");
        }
    }

    static boolean cancellationRequested(Path cancellationFile) {
        return Files.isRegularFile(cancellationFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(cancellationFile);
    }

    private static boolean exactRuntimeCleanupComplete(Path runtimeProcess) {
        if (!Files.isRegularFile(runtimeProcess, LinkOption.NOFOLLOW_LINKS)) return true;
        try {
            return !Boolean.TRUE.equals(RuntimeProcessIdentity.read(runtimeProcess)
                    .inspect().get("attachable"));
        } catch (IOException | IllegalArgumentException unavailable) {
            return false;
        }
    }

    static List<String> command(
            Path java,
            Path selfJar,
            DesktopSmokeScenario scenario,
            Path runDirectory,
            Path game,
            Path launcher) {
        requireSupportedLaunch(scenario);
        List<String> command = new ArrayList<>();
        command.add(java.toAbsolutePath().normalize().toString());
        command.add("-Duser.home=" + Path.of(System.getProperty("user.home"))
                .toAbsolutePath().normalize());
        command.add("-jar");
        command.add(selfJar.toAbsolutePath().normalize().toString());
        command.add("run");
        if ("fast".equals(scenario.launchPreset())) {
            command.add("--fast");
        } else if ("measurement-only".equals(scenario.launchPreset())) {
            command.add("--optimization-preset");
            command.add("off");
        } else {
            throw new IllegalArgumentException(
                    "Unsupported unattended launch preset: " + scenario.launchPreset());
        }
        command.add("--direct");
        command.add("--desktop-smoke");
        if (scenario.sampleRecording()) {
            command.add("--profile");
            command.add("--single-chunk-recording");
        }
        if (scenario.campaignTimes()) {
            command.add("--campaign-times");
        }
        if (scenario.smoothFramePacing()) {
            command.add("--smooth-frame-pacing");
        }
        command.add("--trace-dir");
        command.add(runDirectory.toAbsolutePath().normalize().toString());
        if (game != null) {
            command.add("--game");
            command.add(game.toAbsolutePath().normalize().toString());
        }
        if (launcher != null) {
            command.add("--launcher");
            command.add(launcher.toAbsolutePath().normalize().toString());
        }
        return List.copyOf(command);
    }

    static void requirePreparedStorage(
            DesktopSmokeScenario scenario,
            PreflightHome home,
            Path game) throws Exception {
        if (!"minimal".equals(scenario.textureStorage())) return;
        if (game == null) {
            throw new IllegalArgumentException(
                    "A Minimal startup benchmark needs an explicit Starsector installation");
        }
        String profile = ResourceIndexBuilder.build(game).index().profileFingerprint();
        CacheHealth.Report health = CacheHealth.inspect(home, profile);
        if (!"ready".equals(health.status()) || !Boolean.FALSE.equals(health.preparedTextures())) {
            throw new IllegalStateException(
                    "Minimal startup benchmark requires this exact profile to be prepared with Minimal disk first");
        }
    }

    private static void requireSupportedLaunch(DesktopSmokeScenario scenario) {
        if (scenario.launchProfile() != null) {
            throw new IllegalArgumentException(
                    "Named smoke profiles aren't wired to the launch engine yet");
        }
    }

    static Path freshRunDirectory(Path requested) throws IOException {
        Path absolute = requested.toAbsolutePath().normalize();
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Smoke run destination isn't a directory: " + absolute);
            }
            try (Stream<Path> entries = Files.list(absolute)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException("Smoke run destination isn't empty: " + absolute);
                }
            }
        } else {
            Files.createDirectories(absolute);
        }
        if (Files.isSymbolicLink(absolute)) {
            throw new IOException("Smoke run destination must not be a symbolic link");
        }
        return absolute.toRealPath();
    }

    private static void rejectTrackedRuntime(Path newRun) throws IOException {
        Path home = Path.of(System.getProperty("user.home"))
                .resolve(".starsector-preflight")
                .resolve("runs")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> runs = Files.list(home)) {
            for (Path candidate : runs.toList()) {
                if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                        || candidate.toAbsolutePath().normalize().equals(newRun)) {
                    continue;
                }
                Path identity = candidate.resolve("runtime-process.json");
                if (!Files.isRegularFile(identity, LinkOption.NOFOLLOW_LINKS)) continue;
                boolean attachable;
                try {
                    attachable = Boolean.TRUE.equals(RuntimeProcessIdentity.read(identity)
                            .inspect().get("attachable"));
                } catch (IOException | IllegalArgumentException ignored) {
                    // An invalid old record is not authority to stop or block a current launch.
                    continue;
                }
                if (attachable) {
                    throw new IOException(
                            "Another tracked Starsector runtime is still active: " + candidate);
                }
            }
        }
    }

    private static void awaitRuntimeIdentity(
            Process launcher, Path runtimeProcess, Path launcherOutput) throws Exception {
        long deadline = System.nanoTime() + RUNTIME_IDENTITY_TIMEOUT.toNanos();
        String lastProblem = "runtime-process.json hasn't appeared";
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(runtimeProcess, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    if (Boolean.TRUE.equals(
                            RuntimeProcessIdentity.read(runtimeProcess).inspect().get("attachable"))) {
                        return;
                    }
                    lastProblem = "runtime-process.json isn't attachable yet";
                } catch (IOException | IllegalArgumentException unavailable) {
                    lastProblem = unavailable.getMessage();
                }
            }
            if (!launcher.isAlive()) {
                throw new IOException("Preflight launch exited before publishing a runtime identity"
                        + tailDetail(launcherOutput));
            }
            Thread.sleep(50L);
        }
        throw new IOException("Timed out waiting for the launched game runtime: " + lastProblem);
    }

    private static boolean stopExactRuntime(Path runtimeProcess, List<String> diagnostics) {
        if (!Files.isRegularFile(runtimeProcess, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            RuntimeProcessIdentity identity = RuntimeProcessIdentity.read(runtimeProcess);
            Map<String, Object> inspected = identity.inspect();
            if (!Boolean.TRUE.equals(inspected.get("attachable"))) return false;
            long pid = ((Number) inspected.get("pid")).longValue();
            java.time.Instant startedAt = (java.time.Instant) inspected.get("startedAt");
            DesktopSmokeDriver.ProcessTarget target =
                    new DesktopSmokeDriver.ProcessTarget(pid, startedAt);
            ProcessHandle process = ProcessHandle.of(pid).orElse(null);
            if (process == null || !sameLifetime(process, target)) return false;
            process.destroy();
            waitForExit(target, Duration.ofSeconds(2));
            if (sameLifetime(process, target)) {
                process.destroyForcibly();
                waitForExit(target, Duration.ofSeconds(2));
            }
            if (sameLifetime(process, target)) {
                diagnostics.add("Exact runtime cleanup couldn't stop PID " + pid);
                return false;
            }
            return true;
        } catch (Exception problem) {
            diagnostics.add("Exact runtime cleanup was unavailable: " + bounded(problem.getMessage()));
            return false;
        }
    }

    static boolean acceptsControllerStopExit(
            DesktopSmokeScenario scenario, String status, boolean runtimeStoppedByController) {
        return scenario.usesOnlyRuntimeControl()
                && "passed".equals(status)
                && runtimeStoppedByController;
    }

    private static void awaitLauncher(Process launcher, List<String> diagnostics) {
        try {
            if (launcher.waitFor(POSTPROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) return;
            launcher.destroy();
            if (!launcher.waitFor(2, TimeUnit.SECONDS)) launcher.destroyForcibly();
            diagnostics.add("Preflight launch postprocessing exceeded "
                    + POSTPROCESS_TIMEOUT.toSeconds() + " seconds");
        } catch (InterruptedException interrupted) {
            launcher.destroyForcibly();
            Thread.currentThread().interrupt();
            diagnostics.add("Interrupted while waiting for Preflight launch postprocessing");
        }
    }

    private static boolean sameLifetime(
            ProcessHandle process, DesktopSmokeDriver.ProcessTarget target) {
        return process.isAlive()
                && process.pid() == target.pid()
                && process.info().startInstant().map(target.startedAt()::equals).orElse(false);
    }

    private static void waitForExit(
            DesktopSmokeDriver.ProcessTarget target, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            ProcessHandle process = ProcessHandle.of(target.pid()).orElse(null);
            if (process == null || !sameLifetime(process, target)) return;
            Thread.sleep(25L);
        }
    }

    private static Map<String, Object> result(
            String status,
            Path run,
            Path runtimeProcess,
            Path launcherOutput,
            List<String> command,
            Integer launcherExitCode,
            Map<String, Object> evidence,
            List<String> diagnostics) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", FORMAT);
        result.put("status", status);
        result.put("runDirectory", run);
        result.put("runtimeProcess", runtimeProcess);
        result.put("launcherOutput", launcherOutput);
        result.put("command", command);
        result.put("launcherExitCode", launcherExitCode);
        result.put("evidence", evidence);
        result.put("diagnostics", diagnostics.stream().map(DesktopSmokeLaunch::bounded).toList());
        return result;
    }

    private static String tailDetail(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return "";
            long size = Files.size(file);
            int count = (int) Math.min(size, 4_096L);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(count);
            try (java.nio.channels.SeekableByteChannel input =
                    Files.newByteChannel(file, StandardOpenOption.READ)) {
                input.position(size - count);
                while (buffer.hasRemaining() && input.read(buffer) >= 0) {
                    // Read only the bounded tail.
                }
            }
            return "; launcher tail: "
                    + bounded(new String(buffer.array(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String bounded(String value) {
        String text = value == null ? "" : value.strip();
        return text.length() <= 2_000 ? text : text.substring(0, 2_000);
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"))
                .resolve("bin")
                .resolve(Platform.current() == Platform.WINDOWS ? "java.exe" : "java");
    }

    static void atomicWrite(Path destination, String value) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName()
                + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }
}

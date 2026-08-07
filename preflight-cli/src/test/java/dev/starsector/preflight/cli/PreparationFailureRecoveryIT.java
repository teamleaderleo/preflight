package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparationFailureRecoveryIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void killedPackagedPreparationIsRecoveredByTheNextPackagedRun() throws Exception {
        Path install = fixture("interrupted-game");
        Map<String, String> installationBefore = snapshot(install);
        Path home = temporaryDirectory.resolve("interrupted-home");
        Path marker = temporaryDirectory.resolve("lease-acquired.marker");
        Path firstOutput = temporaryDirectory.resolve("interrupted-output.txt");

        Process interrupted = start(home, install, List.of(
                "-D" + PreparationFaultInjection.ENABLED_PROPERTY + "=true",
                "-D" + PreparationFaultInjection.PAUSE_MARKER_PROPERTY + "=" + marker), firstOutput);
        awaitFile(marker, Duration.ofSeconds(10));
        assertTrue(interrupted.isAlive(), Files.readString(firstOutput));
        assertEquals(interrupted.pid(), Long.parseLong(Files.readString(marker)));

        Path staleTemporary = home.resolve(PreflightHome.DIRECTORY_NAME)
                .resolve("cache/blobs/interrupted.tmp-" + interrupted.pid() + "-1");
        Files.createDirectories(staleTemporary.getParent());
        Files.writeString(staleTemporary, "partial publication");

        interrupted.destroyForcibly();
        assertTrue(interrupted.waitFor(10, TimeUnit.SECONDS), "interrupted process did not exit");
        Path owner = home.resolve(PreflightHome.DIRECTORY_NAME).resolve("state/operation.json");
        assertTrue(Files.isRegularFile(owner));

        Path recoveredOutput = temporaryDirectory.resolve("recovered-output.txt");
        ProcessResult recovered = run(home, install, List.of(), recoveredOutput);

        assertEquals(0, recovered.exitCode(), recovered.output());
        assertTrue(recovered.output().contains(
                "recovered ownership left by interrupted preparing process " + interrupted.pid()),
                recovered.output());
        assertTrue(recovered.output().contains("removed 1 incomplete temporary files"), recovered.output());
        assertFalse(Files.exists(staleTemporary));
        assertFalse(Files.exists(owner));
        assertTrue(Files.isRegularFile(report(home)));
        assertEquals(installationBefore, snapshot(install));
    }

    @Test
    void injectedDiskFullPreservesThePublishedReportAndInstallation() throws Exception {
        Path install = fixture("disk-full-game");
        Map<String, String> installationBefore = snapshot(install);
        Path home = temporaryDirectory.resolve("disk-full-home");
        Path baselineOutput = temporaryDirectory.resolve("baseline-output.txt");
        ProcessResult baseline = run(home, install, List.of(), baselineOutput);
        assertEquals(0, baseline.exitCode(), baseline.output());
        byte[] published = Files.readAllBytes(report(home));

        Path failedOutput = temporaryDirectory.resolve("disk-full-output.txt");
        ProcessResult failed = run(home, install, List.of(
                "-D" + PreparationFaultInjection.ENABLED_PROPERTY + "=true",
                "-D" + PreparationFaultInjection.ENOSPC_TARGET_PROPERTY + "=" + report(home)), failedOutput);

        assertEquals(1, failed.exitCode(), failed.output());
        assertTrue(failed.output().contains("No space left on device"), failed.output());
        assertArrayEquals(published, Files.readAllBytes(report(home)));
        assertTrue(temporarySiblings(report(home)).isEmpty());
        assertFalse(Files.exists(home.resolve(PreflightHome.DIRECTORY_NAME).resolve("state/operation.json")));
        assertEquals(installationBefore, snapshot(install));
    }

    private Process start(
            Path home,
            Path install,
            List<String> jvmArguments,
            Path output) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-Duser.home=" + home.toAbsolutePath().normalize());
        command.addAll(jvmArguments);
        command.add("-jar");
        command.add(packagedJar().toString());
        command.addAll(preparationArguments(home, install));
        Files.createDirectories(output.getParent());
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
    }

    private ProcessResult run(
            Path home,
            Path install,
            List<String> jvmArguments,
            Path output) throws Exception {
        Process process = start(home, install, jvmArguments, output);
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        if (!completed) process.waitFor(10, TimeUnit.SECONDS);
        String text = Files.exists(output) ? Files.readString(output) : "";
        return new ProcessResult(completed ? process.exitValue() : -1, text);
    }

    private static List<String> preparationArguments(Path home, Path install) {
        return List.of(
                "prepare",
                "--game", install.toString(),
                "--cache-dir", home.resolve(PreflightHome.DIRECTORY_NAME).resolve("cache").toString(),
                "--report", report(home).toString(),
                "--serial-stages",
                "--no-resource-index",
                "--no-classpath",
                "--no-textures");
    }

    private Path fixture(String name) throws Exception {
        Path install = temporaryDirectory.resolve(name);
        Path core = install.resolve("starsector-core");
        Path mods = install.resolve("mods");
        Files.createDirectories(mods);
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[]}");
        jar(core.resolve("starfarer_obf.jar"), Map.of("game/Spec.class", new byte[] {9}));
        Path launcher = install.resolve(Platform.current() == Platform.WINDOWS
                ? "starsector.exe" : "starsector.sh");
        Files.writeString(launcher, "synthetic launcher");
        launcher.toFile().setExecutable(true);
        return install;
    }

    private static void jar(Path path, Map<String, byte[]> entries) throws Exception {
        Files.createDirectories(path.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                values.put(root.relativize(path).toString(), Hashes.sha256(path));
            }
        }
        return Map.copyOf(values);
    }

    private static List<Path> temporarySiblings(Path target) throws Exception {
        String prefix = target.getFileName() + ".tmp-";
        try (var paths = Files.list(target.getParent())) {
            return paths.filter(path -> path.getFileName().toString().startsWith(prefix)).toList();
        }
    }

    private static void awaitFile(Path file, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (!Files.isRegularFile(file)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Timed out waiting for " + file);
            }
            Thread.sleep(20);
        }
    }

    private static Path report(Path home) {
        return home.resolve(PreflightHome.DIRECTORY_NAME).resolve("cache/reports/preparation-latest.json")
                .toAbsolutePath().normalize();
    }

    private static Path packagedJar() {
        return Path.of("target", "preflight.jar").toAbsolutePath().normalize();
    }

    private static Path javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toAbsolutePath().normalize();
    }

    private record ProcessResult(int exitCode, String output) {
    }
}

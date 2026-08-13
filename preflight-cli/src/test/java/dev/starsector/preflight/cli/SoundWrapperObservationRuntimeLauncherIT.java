package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import dev.starsector.preflight.core.Hashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoundWrapperObservationRuntimeLauncherIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void selectedJavaLaunchesObservationChildDirectlyAndPreservesItsProcessExit() throws Exception {
        assumeFalse(isWindows(), "POSIX executable shims are covered on Linux and macOS");

        Path testClasses = Path.of("target", "test-classes").toAbsolutePath().normalize();
        Path application = Path.of("target", "preflight.jar").toAbsolutePath().normalize();
        Path game = temporaryDirectory.resolve("Starsector.app");
        Path archives = game.resolve("Contents/Resources/Java");
        Files.createDirectories(archives);
        Path sound = archives.resolve("fs.sound_obf.jar");
        Path jogg = archives.resolve("jogg-0.0.7.jar");
        Path jorbis = archives.resolve("jorbis-0.0.15.jar");
        writeJar(sound, testClasses, List.of("sound"));
        writeJar(jogg, testClasses, List.of("com/jcraft/jogg"));
        writeJar(jorbis, testClasses, List.of("com/jcraft/jorbis"));

        Path brokenLog = temporaryDirectory.resolve("broken-java.log");
        Path broken = game.resolve("Contents/Home/bin/java");
        writeExecutable(broken, "#!/bin/sh\nprintf '%s\\n' broken >> " + quote(brokenLog) + "\nexit 41\n");

        Path invocationLog = temporaryDirectory.resolve("selected-java.log");
        Path invalidJavaHome = temporaryDirectory.resolve("reported-java-home");
        Path invalidSecondHop = invalidJavaHome.resolve("bin/java");
        Path secondHopMarker = temporaryDirectory.resolve("invalid-second-hop-used");
        writeExecutable(
                invalidSecondHop,
                "#!/bin/sh\nprintf '%s\\n' used > " + quote(secondHopMarker) + "\nexit 97\n");

        Path selected = game.resolve("Contents/Resources/Java/jre/bin/java");
        Path hostJava = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().normalize();
        Files.createSymbolicLink(
                invalidJavaHome.resolve("conf"),
                Path.of(System.getProperty("java.home"), "conf").toAbsolutePath().normalize());
        Files.createSymbolicLink(
                invalidJavaHome.resolve("lib"),
                Path.of(System.getProperty("java.home"), "lib").toAbsolutePath().normalize());
        writeExecutable(selected, selectedJavaShim(hostJava, invalidJavaHome, invocationLog, 23));

        Path report = temporaryDirectory.resolve("sound-wrapper-observation.json");
        int exit = SoundWrapperObservationRuntimeLauncher.execute(
                new SoundWrapperObservationRuntimeLauncher.Options(game, jogg, jorbis, null, report),
                application,
                Hashes.sha256(jogg),
                Hashes.sha256(jorbis),
                "ci");

        assertEquals(23, exit, "the outer launcher must preserve the selected Java process exit");
        assertTrue(Files.isRegularFile(report));
        assertFalse(Files.exists(secondHopMarker), "reported java.home must never select another executable");
        assertTrue(Files.readString(brokenLog).contains("broken"), "the higher-ranked failed candidate was probed");

        List<String> invocations = Files.readAllLines(invocationLog);
        assertEquals(2, invocations.stream().filter("CALL"::equals).count(), invocations.toString());
        assertEquals(1, invocations.stream().filter("-version"::equals).count(), invocations.toString());
        assertEquals(1, invocations.stream().filter("-cp"::equals).count(), invocations.toString());
        assertEquals(2, invocations.stream().filter("-noverify"::equals).count(), invocations.toString());
        assertEquals(2, invocations.stream().filter("-XX:+UnlockDiagnosticVMOptions"::equals).count(), invocations.toString());
        assertEquals(2, invocations.stream().filter("-XX:-BytecodeVerificationLocal"::equals).count(), invocations.toString());
        assertEquals(2, invocations.stream().filter("-XX:-BytecodeVerificationRemote"::equals).count(), invocations.toString());
        assertFalse(invocations.contains("-jar"), invocations.toString());
        assertFalse(invocations.contains("audio"), invocations.toString());
        assertTrue(invocations.contains(SoundWrapperObservationChild.class.getName()), invocations.toString());
        int classpathIndex = invocations.indexOf("-cp") + 1;
        int childCallIndex = invocations.lastIndexOf("CALL");
        assertEquals("-noverify", invocations.get(childCallIndex + 1));
        assertTrue(childCallIndex < classpathIndex, invocations.toString());
        String firstClasspathEntry = invocations.get(classpathIndex).split(
                java.util.regex.Pattern.quote(System.getProperty("path.separator")), -1)[0];
        assertEquals(application.toString(), firstClasspathEntry);

        String json = Files.readString(report);
        assertTrue(json.contains("\"observationComplete\":true"), json);
        assertTrue(json.contains("\"childJavaExecutable\":\"" + selected.toAbsolutePath().normalize() + "\""), json);
        assertTrue(json.contains("\"childJavaSelectionSource\":\"bundled-auto\""), json);
        assertTrue(json.contains("\"childJavaExecutableSha256\":\"" + Hashes.sha256(selected) + "\""), json);
        assertTrue(json.contains("\"childLaunchProfile\":\"starsector-bytecode-verification-disabled-v1\""), json);
        assertTrue(json.contains("\"childBytecodeVerificationDisabled\":true"), json);
        assertTrue(json.contains("\"preparedAudioReadsEnabled\":false"), json);
        assertTrue(json.contains("\"preparedAudioWritesEnabled\":false"), json);
        assertTrue(json.contains("\"cacheReadsEnabled\":false"), json);
        assertTrue(json.contains("\"cacheWritesEnabled\":false"), json);
        assertTrue(json.contains("\"liveTransformEnabled\":false"), json);
        assertTrue(json.contains("\"equivalenceEstablished\":false"), json);
        assertTrue(json.contains("\"requiresHumanReview\":true"), json);
    }

    private static String selectedJavaShim(
            Path hostJava,
            Path invalidJavaHome,
            Path invocationLog,
            int childExit) {
        return "#!/bin/sh\n"
                + "printf '%s\\n' CALL >> " + quote(invocationLog) + "\n"
                + "version=false\n"
                + "for argument in \"$@\"; do printf '%s\\n' \"$argument\" >> " + quote(invocationLog) + "; done\n"
                + "for argument in \"$@\"; do if [ \"$argument\" = '-version' ]; then version=true; fi; done\n"
                + "if [ \"$version\" = true ]; then\n"
                + "  printf '%s\\n' 'synthetic Starsector Java 17'\n"
                + "  exit 0\n"
                + "fi\n"
                + "" + quote(hostJava) + " -Djava.home=" + quote(invalidJavaHome) + " \"$@\"\n"
                + "actual=$?\n"
                + "if [ \"$actual\" -ne 0 ]; then exit \"$actual\"; fi\n"
                + "exit " + childExit + "\n";
    }

    private static void writeExecutable(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        assertTrue(path.toFile().setExecutable(true), "could not make test shim executable: " + path);
    }

    private static String quote(Path path) {
        return "'" + path.toAbsolutePath().normalize().toString().replace("'", "'\"'\"'") + "'";
    }

    private static void writeJar(Path destination, Path classes, List<String> packageRoots) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(destination))) {
            for (String packageRoot : packageRoots) {
                Path root = classes.resolve(packageRoot);
                try (var files = Files.walk(root)) {
                    for (Path file : files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".class"))
                            .sorted(Comparator.comparing(Path::toString))
                            .toList()) {
                        String name = classes.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                        JarEntry entry = new JarEntry(name);
                        entry.setTime(0);
                        output.putNextEntry(entry);
                        Files.copy(file, output);
                        output.closeEntry();
                    }
                }
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}

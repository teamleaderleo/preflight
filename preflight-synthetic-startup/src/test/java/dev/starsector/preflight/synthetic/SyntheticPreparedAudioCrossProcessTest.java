package dev.starsector.preflight.synthetic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class SyntheticPreparedAudioCrossProcessTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(value = 120)
    void coldWarmAndCorruptRunsPreservePoliciesAndPcmAcrossJvms() throws Exception {
        Path profile = temporaryDirectory.resolve("profile");
        Path cache = temporaryDirectory.resolve("cache");
        createProfile(profile);

        String cold = launch(profile, cache, temporaryDirectory.resolve("cold.json"));
        assertCommonPolicyCounts(cold);
        assertEquals(2, longField(cold, "eligibleDecoderCalls"));
        assertEquals(0, longField(cold, "cacheHits"));
        assertEquals(2, longField(cold, "cacheMisses"));
        assertEquals(0, longField(cold, "cacheCorruptFallbacks"));
        assertEquals(0, longField(cold, "cacheReadErrors"));
        assertEquals(0, longField(cold, "cacheWriteErrors"));
        assertEquals(0, longField(cold, "preparedBytesServed"));
        assertEquals(28, longField(cold, "preparedBytesWritten"));

        String warm = launch(profile, cache, temporaryDirectory.resolve("warm.json"));
        assertCommonPolicyCounts(warm);
        assertEquals(0, longField(warm, "eligibleDecoderCalls"));
        assertEquals(2, longField(warm, "cacheHits"));
        assertEquals(0, longField(warm, "cacheMisses"));
        assertEquals(0, longField(warm, "cacheCorruptFallbacks"));
        assertEquals(28, longField(warm, "preparedBytesServed"));
        assertEquals(0, longField(warm, "preparedBytesWritten"));

        List<Path> blobs;
        try (var stream = Files.walk(cache)) {
            blobs = stream
                    .filter(path -> path.getFileName().toString().endsWith(".spau"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        assertEquals(2, blobs.size());
        Files.write(blobs.get(0), new byte[] {1, 2, 3});

        String repaired = launch(profile, cache, temporaryDirectory.resolve("repaired.json"));
        assertCommonPolicyCounts(repaired);
        assertEquals(1, longField(repaired, "eligibleDecoderCalls"));
        assertEquals(1, longField(repaired, "cacheHits"));
        assertEquals(0, longField(repaired, "cacheMisses"));
        assertEquals(1, longField(repaired, "cacheCorruptFallbacks"));
        assertEquals(0, longField(repaired, "cacheReadErrors"));
        assertEquals(0, longField(repaired, "cacheWriteErrors"));
        assertEquals(28,
                longField(repaired, "preparedBytesServed")
                        + longField(repaired, "preparedBytesWritten"));

        assertEquals(stringField(cold, "preparedOutputsSha256"), stringField(warm, "preparedOutputsSha256"));
        assertEquals(stringField(cold, "preparedOutputsSha256"), stringField(repaired, "preparedOutputsSha256"));
        assertEquals(stringField(cold, "profileFingerprintSha256"), stringField(warm, "profileFingerprintSha256"));
        assertEquals(stringField(cold, "manifestSha256"), stringField(warm, "manifestSha256"));
        assertEquals(stringField(cold, "manifestSha256"), stringField(repaired, "manifestSha256"));

        long currentPid = ProcessHandle.current().pid();
        long coldPid = longField(cold, "processId");
        long warmPid = longField(warm, "processId");
        long repairedPid = longField(repaired, "processId");
        assertNotEquals(currentPid, coldPid);
        assertNotEquals(currentPid, warmPid);
        assertNotEquals(currentPid, repairedPid);
        assertNotEquals(coldPid, warmPid);
        assertNotEquals(warmPid, repairedPid);
    }

    private static void assertCommonPolicyCounts(String json) {
        assertEquals(2, longField(json, "eligibleResources"));
        assertEquals(2, longField(json, "ineligibleLookups"));
        assertEquals(1, longField(json, "streamedSelections"));
        assertEquals(1, longField(json, "unsupportedSelections"));
        assertEquals(4, longField(json, "manifestEntries"));
    }

    private static void createProfile(Path profile) throws Exception {
        Files.createDirectories(profile.resolve("effects"));
        Files.createDirectories(profile.resolve("music"));
        Files.createDirectories(profile.resolve("unsupported"));
        Files.write(profile.resolve("effects/laser.bin"), new byte[] {0, 64, (byte) 128, (byte) 255});
        Files.write(profile.resolve("effects/impact.bin"), new byte[] {12, 34, 56});
        Files.write(profile.resolve("music/theme.bin"), new byte[] {9, 8, 7, 6, 5});
        Files.write(profile.resolve("unsupported/format.bin"), new byte[] {1, 3, 5, 7});
    }

    private static String launch(Path profile, Path cache, Path report) throws Exception {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classPath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(classPath);
        command.add(SyntheticPreparedAudioWorker.class.getName());
        command.add(profile.toString());
        command.add(cache.toString());
        command.add(report.toString());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(Duration.ofSeconds(40).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            fail("Synthetic prepared-audio worker exceeded 40 seconds");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(Files.isRegularFile(report), "Worker did not write " + report + ": " + output);
        return Files.readString(report, StandardCharsets.UTF_8);
    }

    private static long longField(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\":(-?[0-9]+)").matcher(json);
        assertTrue(matcher.find(), () -> "Missing numeric field " + name + " in " + json);
        return Long.parseLong(matcher.group(1));
    }

    private static String stringField(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\":\\\"([^\\\"]*)\\\"")
                .matcher(json);
        assertTrue(matcher.find(), () -> "Missing string field " + name + " in " + json);
        return matcher.group(1);
    }
}

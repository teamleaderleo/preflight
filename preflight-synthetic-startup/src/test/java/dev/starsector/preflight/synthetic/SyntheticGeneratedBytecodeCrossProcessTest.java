package dev.starsector.preflight.synthetic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.starsector.preflight.core.GeneratedBytecodeCache;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class SyntheticGeneratedBytecodeCrossProcessTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(value = 120)
    void coldWarmAndCorruptRunsReuseCompleteMapsAcrossJvms() throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        String cold = launch(cache, temporaryDirectory.resolve("cold.json"));
        assertEquals("ORIGINAL_STORED", stringField(cold, "source"));
        assertEquals("MISS", stringField(cold, "lookupStatus"));
        assertEquals(1, longField(cold, "generationCalls"));
        assertEquals(3, longField(cold, "classCount"));
        assertEquals("true", rawField(cold, "cacheUsable"));

        String warm = launch(cache, temporaryDirectory.resolve("warm.json"));
        assertEquals("CACHE_HIT", stringField(warm, "source"));
        assertEquals("HIT", stringField(warm, "lookupStatus"));
        assertEquals(0, longField(warm, "generationCalls"));
        assertEquals(3, longField(warm, "classCount"));

        Path bundle = GeneratedBytecodeCache.bundlePath(
                cache,
                SyntheticGeneratedBytecodeWorker.context().keySha256(),
                SyntheticGeneratedBytecodeWorker.REQUESTED_CLASS);
        Files.write(bundle, new byte[] {1, 2, 3});
        String repaired = launch(cache, temporaryDirectory.resolve("repaired.json"));
        assertEquals("ORIGINAL_STORED", stringField(repaired, "source"));
        assertEquals("CORRUPT", stringField(repaired, "lookupStatus"));
        assertEquals(1, longField(repaired, "generationCalls"));
        assertEquals(3, longField(repaired, "classCount"));

        assertEquals(stringField(cold, "completeMapSha256"), stringField(warm, "completeMapSha256"));
        assertEquals(stringField(cold, "completeMapSha256"), stringField(repaired, "completeMapSha256"));
        assertEquals(stringField(cold, "contextKeySha256"), stringField(warm, "contextKeySha256"));
        assertEquals(stringField(cold, "contextKeySha256"), stringField(repaired, "contextKeySha256"));

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

    private static String launch(Path cache, Path report) throws Exception {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classPath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-Djava.awt.headless=true");
        command.add("-cp");
        command.add(classPath);
        command.add(SyntheticGeneratedBytecodeWorker.class.getName());
        command.add(cache.toString());
        command.add(report.toString());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(Duration.ofSeconds(40).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            fail("Synthetic bytecode worker exceeded 40 seconds");
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

    private static String rawField(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\":([^,}]+)").matcher(json);
        assertTrue(matcher.find(), () -> "Missing field " + name + " in " + json);
        return matcher.group(1);
    }
}

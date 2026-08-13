package dev.starsector.preflight.synthetic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.starsector.preflight.core.AppCdsCapabilityDetector;
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

class SyntheticAppCdsCrossProcessTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(120)
    void childJvmProvesArchiveCreationConsumptionAndFailureGating() throws Exception {
        Path report = temporaryDirectory.resolve("report.json");
        String json = launch(temporaryDirectory.resolve("work"), report);

        assertEquals("SUPPORTED", stringField(json, "status"));
        assertEquals("true", rawField(json, "supported"));
        assertTrue(longField(json, "javaExecutableBytes") > 0);
        assertEquals(64, stringField(json, "javaExecutableSha256").length());
        assertEquals(0, longField(json, "generationExitCode"));
        assertEquals(0, longField(json, "consumptionExitCode"));
        assertEquals("false", rawField(json, "outputTruncated"));
        assertTrue(longField(json, "proofArchiveBytes") > 0);
        assertEquals(64, stringField(json, "proofArchiveSha256").length());
        assertEquals(2, longField(json, "creationArgumentCount"));
        assertEquals(2, longField(json, "consumptionArgumentCount"));
        assertTrue(json.contains(AppCdsCapabilityDetector.XSHARE_ON));
        assertTrue(json.contains(AppCdsCapabilityDetector.ARCHIVE_AT_EXIT_PREFIX));
        assertTrue(json.contains(AppCdsCapabilityDetector.SHARED_ARCHIVE_PREFIX));

        assertEquals(0, longField(json, "copiedJavaCreationArgumentCount"));
        assertEquals(0, longField(json, "copiedJavaConsumptionArgumentCount"));
        assertEquals("ERROR", stringField(json, "missingExecutableStatus"));
        assertEquals(0, longField(json, "missingCreationArgumentCount"));
        assertEquals(0, longField(json, "missingConsumptionArgumentCount"));
        assertNotEquals(ProcessHandle.current().pid(), longField(json, "processId"));
    }

    private static String launch(Path workRoot, Path report) throws Exception {
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
        command.add(SyntheticAppCdsWorker.class.getName());
        command.add(workRoot.toString());
        command.add(report.toString());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(Duration.ofSeconds(70).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            fail("Synthetic AppCDS worker exceeded 70 seconds");
        }
        byte[] outputBytes = process.getInputStream().readNBytes(64 * 1024 + 1);
        assertTrue(outputBytes.length <= 64 * 1024, "Worker output exceeded its byte limit");
        String output = new String(outputBytes, StandardCharsets.UTF_8);
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

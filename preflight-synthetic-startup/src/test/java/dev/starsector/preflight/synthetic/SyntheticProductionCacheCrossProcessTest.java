package dev.starsector.preflight.synthetic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class SyntheticProductionCacheCrossProcessTest {
    private static final int MAX_WORKER_OUTPUT_BYTES = 64 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(600)
    void coldWarmAndCorruptPassesReuseProductionSpauAndSpjbFormats() throws Exception {
        SyntheticExtendedProfile.Scale scale = Boolean.getBoolean("preflight.synthetic.medium")
                ? SyntheticExtendedProfile.Scale.MEDIUM
                : SyntheticExtendedProfile.Scale.TINY;
        Path profile = temporaryDirectory.resolve("profile");
        Path cache = temporaryDirectory.resolve("cache");
        SyntheticExtendedProfile.Manifest manifest = SyntheticExtendedProfile.generate(
                profile,
                45_678,
                scale);

        String cold = launch(profile, cache, temporaryDirectory.resolve("cold.json"));
        String warm = launch(profile, cache, temporaryDirectory.resolve("warm.json"));

        assertNotEquals(longField(cold, "processId"), longField(warm, "processId"));
        assertEquals(scale.name(), stringField(cold, "scale"));
        assertEquals(manifest.fingerprintSha256(), stringField(cold, "profileFingerprintSha256"));
        assertEquals(stringField(cold, "profileFingerprintSha256"), stringField(warm, "profileFingerprintSha256"));
        assertEquals(stringField(cold, "providerDigestSha256"), stringField(warm, "providerDigestSha256"));

        assertEquals("MISS", stringField(cold, "indexLookupStatus"));
        assertEquals(1, longField(cold, "indexWrites"));
        assertEquals(scale.jars(), longField(cold, "indexJarScans"));
        assertEquals(scale.physicalFiles() - scale.jars(), longField(cold, "indexLooseVisits"));
        assertEquals(scale.physicalFiles(), longField(cold, "indexPhysicalFilesVisited"));
        assertEquals(scale.jars() * 2L, longField(cold, "indexJarEntriesVisited"));

        assertEquals("HIT", stringField(warm, "indexLookupStatus"));
        assertEquals(0, longField(warm, "indexWrites"));
        assertEquals(0, longField(warm, "indexJarScans"));
        assertEquals(0, longField(warm, "indexLooseVisits"));
        assertEquals(0, longField(warm, "indexPhysicalFilesVisited"));
        assertEquals(0, longField(warm, "indexJarEntriesVisited"));

        assertEquals(manifest.effectFiles(), longField(cold, "audioEffectFiles"));
        assertEquals(manifest.streamedFiles(), longField(cold, "audioStreamedFiles"));
        assertEquals(0, longField(cold, "audioHits"));
        assertEquals(manifest.effectFiles(), longField(cold, "audioMisses"));
        assertEquals(manifest.streamedFiles(), longField(cold, "audioIneligible"));
        assertEquals(manifest.effectFiles(), longField(cold, "audioDecoderCalls"));
        assertEquals(manifest.effectFiles(), longField(cold, "audioWrites"));
        assertEquals(0, longField(cold, "audioCorruptFallbacks"));
        assertEquals(0, longField(cold, "audioReadErrors"));
        assertEquals(0, longField(cold, "audioWriteErrors"));

        assertEquals(manifest.effectFiles(), longField(warm, "audioHits"));
        assertEquals(0, longField(warm, "audioMisses"));
        assertEquals(manifest.streamedFiles(), longField(warm, "audioIneligible"));
        assertEquals(0, longField(warm, "audioDecoderCalls"));
        assertEquals(0, longField(warm, "audioWrites"));
        assertEquals(0, longField(warm, "audioCorruptFallbacks"));
        assertEquals(0, longField(warm, "audioReadErrors"));
        assertEquals(0, longField(warm, "audioWriteErrors"));

        assertEquals("MISS", stringField(cold, "bytecodeLookupStatus"));
        assertEquals("ORIGINAL_GENERATED", stringField(cold, "bytecodeSourceDisposition"));
        assertEquals(1, longField(cold, "bytecodeCompilerCalls"));
        assertEquals("true", rawField(cold, "bytecodeWriteAttempted"));
        assertEquals("true", rawField(cold, "bytecodeWriteSucceeded"));
        assertEquals(scale.javaSources() * 3L, longField(cold, "bytecodeClassCount"));
        assertEquals((scale.javaSources() - 1L) * 14L + 7L, longField(cold, "bytecodeRequestedValue"));

        assertEquals("HIT", stringField(warm, "bytecodeLookupStatus"));
        assertEquals("CACHE_REUSED", stringField(warm, "bytecodeSourceDisposition"));
        assertEquals(0, longField(warm, "bytecodeCompilerCalls"));
        assertEquals("false", rawField(warm, "bytecodeWriteAttempted"));
        assertEquals("false", rawField(warm, "bytecodeWriteSucceeded"));
        assertEquals(longField(cold, "bytecodeClassCount"), longField(warm, "bytecodeClassCount"));
        assertEquals(longField(cold, "bytecodeRequestedValue"), longField(warm, "bytecodeRequestedValue"));

        assertExactOutputs(cold, warm);
        assertEquals(64, stringField(cold, "bytecodeContextKeySha256").length());
        assertEquals(64, stringField(cold, "combinedOutputSha256").length());

        Path spau = firstCacheFile(cache.resolve("prepared-audio"), ".spau");
        Path spjb = firstCacheFile(cache.resolve("generated-bytecode"), ".spjb");
        Files.write(spau, new byte[] {1, 2, 3});
        Files.write(spjb, new byte[] {4, 5, 6});

        String repaired = launch(profile, cache, temporaryDirectory.resolve("repaired.json"));
        assertNotEquals(longField(warm, "processId"), longField(repaired, "processId"));
        assertEquals("HIT", stringField(repaired, "indexLookupStatus"));
        assertEquals(manifest.effectFiles() - 1L, longField(repaired, "audioHits"));
        assertEquals(1, longField(repaired, "audioCorruptFallbacks"));
        assertEquals(1, longField(repaired, "audioDecoderCalls"));
        assertEquals(1, longField(repaired, "audioWrites"));
        assertEquals("CORRUPT", stringField(repaired, "bytecodeLookupStatus"));
        assertEquals("ORIGINAL_GENERATED", stringField(repaired, "bytecodeSourceDisposition"));
        assertEquals(1, longField(repaired, "bytecodeCompilerCalls"));
        assertEquals("true", rawField(repaired, "bytecodeWriteAttempted"));
        assertEquals("true", rawField(repaired, "bytecodeWriteSucceeded"));
        assertExactOutputs(cold, repaired);
    }

    private static void assertExactOutputs(String expected, String actual) {
        for (String field : List.of(
                "audioOutputSha256",
                "bytecodeContextKeySha256",
                "bytecodeOutputSha256",
                "combinedOutputSha256")) {
            assertEquals(stringField(expected, field), stringField(actual, field), field);
        }
    }

    private static Path firstCacheFile(Path root, String suffix) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .findFirst()
                    .orElseThrow(() -> new IOException("Missing cache file ending in " + suffix));
        }
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
        command.add("-Djava.awt.headless=true");
        command.add("-cp");
        command.add(classPath);
        command.add(SyntheticProductionCacheWorker.class.getName());
        command.add(profile.toString());
        command.add(cache.toString());
        command.add(report.toString());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        OutputCapture capture = new OutputCapture(process.getInputStream());
        Thread outputThread = new Thread(capture, "synthetic-production-cache-output");
        outputThread.setDaemon(true);
        outputThread.start();

        boolean completed = process.waitFor(Duration.ofSeconds(180).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        outputThread.join(Duration.ofSeconds(5).toMillis());
        if (outputThread.isAlive()) {
            process.getInputStream().close();
            outputThread.join(Duration.ofSeconds(5).toMillis());
        }
        if (!completed) fail("Production-cache worker exceeded 180 seconds");
        if (capture.failure() != null) throw capture.failure();
        assertTrue(!capture.truncated(), "Worker output exceeded 64 KiB");
        assertEquals(0, process.exitValue(), capture.output());
        assertTrue(Files.isRegularFile(report), capture.output());
        return Files.readString(report, StandardCharsets.UTF_8);
    }

    private static long longField(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\":(-?[0-9]+)")
                .matcher(json);
        assertTrue(matcher.find(), () -> "Missing numeric field " + name + " in " + json);
        return Long.parseLong(matcher.group(1));
    }

    private static String stringField(String json, String name) {
        Matcher matcher = Pattern.compile(
                "\\\"" + Pattern.quote(name) + "\\\":\\\"([^\\\"]*)\\\"")
                .matcher(json);
        assertTrue(matcher.find(), () -> "Missing string field " + name + " in " + json);
        return matcher.group(1);
    }

    private static String rawField(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\":([^,}]+)")
                .matcher(json);
        assertTrue(matcher.find(), () -> "Missing field " + name + " in " + json);
        return matcher.group(1);
    }

    private static final class OutputCapture implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final AtomicBoolean truncated = new AtomicBoolean();
        private final AtomicReference<IOException> failure = new AtomicReference<>();

        private OutputCapture(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            try (input) {
                byte[] buffer = new byte[4_096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    int remaining = MAX_WORKER_OUTPUT_BYTES - bytes.size();
                    if (remaining > 0) bytes.write(buffer, 0, Math.min(remaining, read));
                    if (read > remaining) truncated.set(true);
                }
            } catch (IOException error) {
                failure.compareAndSet(null, error);
            }
        }

        private boolean truncated() {
            return truncated.get();
        }

        private IOException failure() {
            return failure.get();
        }

        private String output() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}

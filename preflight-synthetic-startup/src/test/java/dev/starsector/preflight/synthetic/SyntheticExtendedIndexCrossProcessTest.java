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

class SyntheticExtendedIndexCrossProcessTest {
    private static final int MAX_WORKER_OUTPUT_BYTES = 64 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(300)
    void coldWarmAndCorruptPassesUseSeparateJvmsAndPreserveExactProviders() throws Exception {
        SyntheticExtendedProfile.Scale scale = Boolean.getBoolean("preflight.synthetic.medium")
                ? SyntheticExtendedProfile.Scale.MEDIUM
                : SyntheticExtendedProfile.Scale.TINY;
        Path profile = temporaryDirectory.resolve("profile");
        Path cache = temporaryDirectory.resolve("cache");
        SyntheticExtendedProfile.Manifest manifest = SyntheticExtendedProfile.generate(
                profile,
                12_345,
                scale);

        String cold = launch(profile, cache, temporaryDirectory.resolve("cold.json"));
        String warm = launch(profile, cache, temporaryDirectory.resolve("warm.json"));

        assertNotEquals(longField(cold, "processId"), longField(warm, "processId"));
        assertEquals(scale.name(), stringField(cold, "scale"));
        assertEquals(scale.name(), stringField(warm, "scale"));
        assertEquals(manifest.physicalFiles(), longField(cold, "profileFilesValidated"));
        assertEquals(manifest.physicalFiles(), longField(warm, "profileFilesValidated"));
        assertEquals(manifest.fingerprintSha256(), stringField(cold, "profileFingerprintSha256"));
        assertEquals(manifest.fingerprintSha256(), stringField(warm, "profileFingerprintSha256"));

        assertEquals("MISS", stringField(cold, "indexLookupStatus"));
        assertEquals(0, longField(cold, "indexHits"));
        assertEquals(1, longField(cold, "indexMisses"));
        assertEquals(1, longField(cold, "indexWrites"));
        assertEquals(scale.jars(), longField(cold, "jarScans"));
        assertEquals(scale.physicalFiles() - scale.jars(), longField(cold, "looseVisits"));
        assertEquals(scale.physicalFiles(), longField(cold, "physicalFilesVisited"));
        assertEquals(scale.jars() * 2L, longField(cold, "jarEntriesVisited"));
        assertTrue(longField(cold, "bytesHashed") > 0);

        assertEquals("HIT", stringField(warm, "indexLookupStatus"));
        assertEquals(1, longField(warm, "indexHits"));
        assertEquals(0, longField(warm, "indexMisses"));
        assertEquals(0, longField(warm, "indexWrites"));
        for (String field : List.of(
                "jarScans",
                "looseVisits",
                "physicalFilesVisited",
                "jarEntriesVisited",
                "bytesHashed")) {
            assertEquals(0, longField(warm, field), field);
        }

        assertEquals(expectedProviderCount(scale), longField(cold, "providerCount"));
        assertEquals(expectedCollidedPaths(scale), longField(cold, "collidedPaths"));
        assertEquals(expectedCollisionEvents(scale), longField(cold, "collisionEvents"));
        assertEquals(longField(cold, "providerCount"), longField(warm, "providerCount"));
        assertEquals(longField(cold, "providerBytesRead"), longField(warm, "providerBytesRead"));
        assertEquals(
                stringField(cold, "providerDigestSha256"),
                stringField(warm, "providerDigestSha256"));
        assertEquals(
                stringField(cold, "providerOutputSha256"),
                stringField(warm, "providerOutputSha256"));
        assertZeroErrors(cold);
        assertZeroErrors(warm);

        Path indexPath = SyntheticExtendedResourceIndex.cachePath(
                cache,
                manifest.fingerprintSha256());
        Files.write(indexPath, new byte[] {1, 2, 3});
        String repaired = launch(profile, cache, temporaryDirectory.resolve("repaired.json"));

        assertNotEquals(longField(warm, "processId"), longField(repaired, "processId"));
        assertEquals("CORRUPT", stringField(repaired, "indexLookupStatus"));
        assertEquals(1, longField(repaired, "indexCorruptFallbacks"));
        assertEquals(1, longField(repaired, "indexWrites"));
        assertEquals(scale.jars(), longField(repaired, "jarScans"));
        assertEquals(scale.physicalFiles() - scale.jars(), longField(repaired, "looseVisits"));
        assertEquals(
                stringField(cold, "providerDigestSha256"),
                stringField(repaired, "providerDigestSha256"));
        assertEquals(
                stringField(cold, "providerOutputSha256"),
                stringField(repaired, "providerOutputSha256"));
        assertZeroErrors(repaired);
    }

    private static long expectedProviderCount(SyntheticExtendedProfile.Scale scale) {
        return uniqueCount(scale.resources())
                + uniqueCount(scale.images())
                + scale.audio()
                + scale.javaSources()
                + scale.jars()
                + Math.max(1, scale.jars() / 2);
    }

    private static long expectedCollidedPaths(SyntheticExtendedProfile.Scale scale) {
        return (scale.resources() - uniqueCount(scale.resources()))
                + (scale.images() - uniqueCount(scale.images()))
                + Math.max(1, scale.jars() / 2);
    }

    private static long expectedCollisionEvents(SyntheticExtendedProfile.Scale scale) {
        int shared = Math.max(1, scale.jars() / 2);
        return (scale.resources() - uniqueCount(scale.resources()))
                + (scale.images() - uniqueCount(scale.images()))
                + (scale.jars() * 2L - shared);
    }

    private static int uniqueCount(int count) {
        return Math.max(1, count - Math.max(1, count / 4));
    }

    private static void assertZeroErrors(String json) {
        assertEquals(0, longField(json, "indexReadErrors"));
        assertEquals(0, longField(json, "indexWriteErrors"));
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
        command.add(SyntheticExtendedIndexWorker.class.getName());
        command.add(profile.toString());
        command.add(cache.toString());
        command.add(report.toString());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        OutputCapture capture = new OutputCapture(process.getInputStream());
        Thread outputThread = new Thread(capture, "synthetic-extended-index-output");
        outputThread.setDaemon(true);
        outputThread.start();

        boolean completed = process.waitFor(Duration.ofSeconds(120).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        outputThread.join(Duration.ofSeconds(5).toMillis());
        if (outputThread.isAlive()) {
            process.getInputStream().close();
            outputThread.join(Duration.ofSeconds(5).toMillis());
        }
        if (!completed) fail("Extended provider-index worker exceeded 120 seconds");
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

package dev.starsector.preflight.synthetic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.IOException;
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
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class SyntheticStartupCrossProcessTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(value = 90)
    void coldWarmAndCorruptRunsUseSeparateJvmsAndPreserveOutputs() throws Exception {
        Path profile = temporaryDirectory.resolve("profile");
        Path cache = temporaryDirectory.resolve("cache");
        createProfile(profile);

        String cold = launch(profile, cache, temporaryDirectory.resolve("cold.json"));
        assertEquals(3, longField(cold, "modCount"));
        assertEquals(6, longField(cold, "discoveredFiles"));
        assertEquals(4, longField(cold, "winningResources"));
        assertEquals(2, longField(cold, "resourceCollisions"));
        assertEquals(3, longField(cold, "imageResources"));
        assertEquals(3, longField(cold, "imageDecoderCalls"));
        assertEquals(0, longField(cold, "imageCacheHits"));
        assertEquals(3, longField(cold, "imageCacheMisses"));
        assertEquals(0, longField(cold, "imageCacheCorruptFallbacks"));
        assertEquals(0, longField(cold, "imageCacheReadErrors"));
        assertEquals(0, longField(cold, "imageCacheWriteErrors"));
        assertEquals(0, longField(cold, "preparedBytesServed"));
        assertEquals(48, longField(cold, "preparedBytesWritten"));

        String warm = launch(profile, cache, temporaryDirectory.resolve("warm.json"));
        assertEquals(0, longField(warm, "imageDecoderCalls"));
        assertEquals(3, longField(warm, "imageCacheHits"));
        assertEquals(0, longField(warm, "imageCacheMisses"));
        assertEquals(0, longField(warm, "imageCacheCorruptFallbacks"));
        assertEquals(48, longField(warm, "preparedBytesServed"));
        assertEquals(0, longField(warm, "preparedBytesWritten"));

        List<Path> preparedFiles;
        try (var stream = Files.walk(cache)) {
            preparedFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".spxi"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        assertEquals(3, preparedFiles.size());
        Files.write(preparedFiles.get(0), new byte[] {1, 2, 3});

        String corrupt = launch(profile, cache, temporaryDirectory.resolve("corrupt.json"));
        assertEquals(1, longField(corrupt, "imageDecoderCalls"));
        assertEquals(2, longField(corrupt, "imageCacheHits"));
        assertEquals(0, longField(corrupt, "imageCacheMisses"));
        assertEquals(1, longField(corrupt, "imageCacheCorruptFallbacks"));
        assertEquals(0, longField(corrupt, "imageCacheReadErrors"));
        assertEquals(0, longField(corrupt, "imageCacheWriteErrors"));
        assertEquals(32, longField(corrupt, "preparedBytesServed"));
        assertEquals(16, longField(corrupt, "preparedBytesWritten"));

        assertEquals(stringField(cold, "winningProvidersSha256"), stringField(warm, "winningProvidersSha256"));
        assertEquals(stringField(cold, "winningProvidersSha256"), stringField(corrupt, "winningProvidersSha256"));
        assertEquals(
                stringField(cold, "preparedImageOutputsSha256"),
                stringField(warm, "preparedImageOutputsSha256"));
        assertEquals(
                stringField(cold, "preparedImageOutputsSha256"),
                stringField(corrupt, "preparedImageOutputsSha256"));

        long currentPid = ProcessHandle.current().pid();
        long coldPid = longField(cold, "processId");
        long warmPid = longField(warm, "processId");
        long corruptPid = longField(corrupt, "processId");
        assertNotEquals(currentPid, coldPid);
        assertNotEquals(currentPid, warmPid);
        assertNotEquals(currentPid, corruptPid);
        assertNotEquals(coldPid, warmPid);
        assertNotEquals(warmPid, corruptPid);
    }

    private static void createProfile(Path profile) throws IOException {
        Files.createDirectories(profile.resolve("mods/mod-a/graphics"));
        Files.createDirectories(profile.resolve("mods/mod-a/data"));
        Files.createDirectories(profile.resolve("mods/mod-b/graphics"));
        Files.createDirectories(profile.resolve("mods/mod-b/data"));
        Files.createDirectories(profile.resolve("mods/mod-c/graphics"));
        Files.writeString(
                profile.resolve("mod-order.txt"),
                "mod-a\nmod-b\nmod-c\n",
                StandardCharsets.UTF_8);

        writePng(profile.resolve("mods/mod-a/graphics/shared.png"), 0xffff0000);
        writePng(profile.resolve("mods/mod-a/graphics/only-a.png"), 0xff00ff00);
        Files.writeString(
                profile.resolve("mods/mod-a/data/config.json"),
                "{\"owner\":\"a\"}\n",
                StandardCharsets.UTF_8);

        writePng(profile.resolve("mods/mod-b/graphics/shared.png"), 0xff0000ff);
        Files.writeString(
                profile.resolve("mods/mod-b/data/config.json"),
                "{\"owner\":\"b\"}\n",
                StandardCharsets.UTF_8);

        writePng(profile.resolve("mods/mod-c/graphics/only-c.png"), 0x80ffffff);
    }

    private static void writePng(Path target, int argb) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, argb ^ (x << 8) ^ y);
            }
        }
        assertTrue(ImageIO.write(image, "png", target.toFile()), "PNG writer unavailable");
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
        command.add(SyntheticStartupWorker.class.getName());
        command.add(profile.toString());
        command.add(cache.toString());
        command.add(report.toString());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            fail("Synthetic startup worker exceeded 30 seconds");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(Files.isRegularFile(report), "Worker did not write " + report + ": " + output);
        String json = Files.readString(report, StandardCharsets.UTF_8);
        assertFalse(json.isBlank());
        return json;
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

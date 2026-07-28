package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the probe against real Flight Recorder output rather than a stub.
 *
 * <p>The thing worth testing here is not the arithmetic, it is the join: a {@code jdk.FileRead}
 * carries an absolute filesystem path and the census carries logical paths, and if those fail to
 * match, every file looks unopened and the probe reports a confident {@code LAZY} that would cancel a
 * milestone. So these tests record genuine file reads and require the probe to find them.</p>
 */
class AudioDecodeProbeTest {
    @TempDir
    Path temporaryDirectory;

    /**
     * Same {@code @Name} as the agent's own marker, which is all JFR matches on. Declaring it here
     * keeps the test from reaching into another module for a package-private class.
     */
    @Name("preflight.AgentStarted")
    static final class AgentStarted extends Event {
        @Label("Exhaustive File Reads")
        boolean exhaustiveFileReads;
    }

    @Test
    void reportsEagerWhenEveryDeclaredEffectIsOpened() throws Exception {
        Path core = profile();
        Path first = sound(core, "sounds/one.ogg");
        Path second = sound(core, "sounds/two.ogg");
        declare("""
                {"one":[{"file":"sounds/one.ogg"}],"two":[{"file":"sounds/two.ogg"}]}
                """);

        Path recording = record(true, first, second);
        AudioDecodeProbe.Result result = AudioDecodeProbe.run(recording, temporaryDirectory);

        assertEquals(AudioDecodeProbe.Verdict.EAGER, result.verdict(), result.detail());
        assertEquals(2, opened(result, "effect"));
        assertEquals(0, neverOpened(result, "effect"));
    }

    @Test
    void reportsLazyWhenTheDeclaredEffectsAreNeverOpened() throws Exception {
        Path core = profile();
        Path unrelated = core.resolve("data/config/sounds.json");
        sound(core, "sounds/one.ogg");
        sound(core, "sounds/two.ogg");
        declare("""
                {"one":[{"file":"sounds/one.ogg"}],"two":[{"file":"sounds/two.ogg"}]}
                """);

        Path recording = record(true, unrelated);
        AudioDecodeProbe.Result result = AudioDecodeProbe.run(recording, temporaryDirectory);

        assertEquals(AudioDecodeProbe.Verdict.LAZY, result.verdict(), result.detail());
        assertEquals(2, neverOpened(result, "effect"));
    }

    /**
     * The guard that keeps a false negative out of the record. Without the marker there is no way to
     * know whether an absent read means the file was not opened or the event was filtered, and the
     * two would be reported identically.
     */
    @Test
    void refusesARecordingThatDidNotCaptureEveryFileRead() throws Exception {
        Path core = profile();
        Path first = sound(core, "sounds/one.ogg");
        declare("""
                {"one":[{"file":"sounds/one.ogg"}]}
                """);

        Path recording = record(false, first);
        AudioDecodeProbe.Result result = AudioDecodeProbe.run(recording, temporaryDirectory);

        assertEquals(AudioDecodeProbe.Verdict.UNUSABLE, result.verdict());
        assertTrue(result.detail().contains("--trace-all-file-reads"), result.detail());
    }

    /**
     * Attribution is what separates "the game loaded these" from "something walked the directory",
     * so the reads have to arrive carrying a resolved stack. The frame named here is whatever called
     * in — under JUnit that is the harness, in a real run it is the game — so the assertion is that a
     * caller was resolved at all, not which one.
     */
    @Test
    void namesTheCodeThatOpenedTheFiles() throws Exception {
        Path core = profile();
        Path first = sound(core, "sounds/one.ogg");
        declare("""
                {"one":[{"file":"sounds/one.ogg"}]}
                """);

        AudioDecodeProbe.Result result =
                AudioDecodeProbe.run(record(true, first), temporaryDirectory);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readers = (List<Map<String, Object>>) result.report().get("topReaders");
        assertTrue(readers.stream().anyMatch(reader -> {
            String frame = String.valueOf(reader.get("frame"));
            return !frame.startsWith("<") && ((Number) reader.get("reads")).longValue() > 0;
        }), "expected a resolved calling frame, got " + readers);
    }

    // --- fixture -------------------------------------------------------------------------------

    /**
     * Records genuine {@code jdk.FileRead} events for the given files. The marker event is committed
     * only when {@code exhaustive} is set, mirroring what the agent does.
     */
    private Path record(boolean exhaustive, Path... files) throws Exception {
        Path destination = temporaryDirectory.resolve("probe-" + System.nanoTime() + ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.FileRead")
                    .withThreshold(exhaustive ? Duration.ZERO : Duration.ofMillis(1))
                    .withStackTrace();
            recording.start();

            AgentStarted started = new AgentStarted();
            started.exhaustiveFileReads = exhaustive;
            started.commit();

            byte[] buffer = new byte[64];
            for (Path file : files) {
                try (InputStream in = Files.newInputStream(file)) {
                    while (in.read(buffer) > 0) {
                        // Drain it, the way something decoding the file would.
                    }
                }
            }
            recording.stop();
            recording.dump(destination);
        }
        return destination;
    }

    private static int opened(AudioDecodeProbe.Result result, String kind) {
        return field(result, kind, "opened");
    }

    private static int neverOpened(AudioDecodeProbe.Result result, String kind) {
        return field(result, kind, "neverOpened");
    }

    @SuppressWarnings("unchecked")
    private static int field(AudioDecodeProbe.Result result, String kind, String name) {
        List<Map<String, Object>> kinds = (List<Map<String, Object>>) result.report().get("byKind");
        return kinds.stream()
                .filter(entry -> kind.equals(entry.get("kind")))
                .findFirst()
                .map(entry -> ((Number) entry.get(name)).intValue())
                .orElseThrow(() -> new AssertionError("no " + kind + " row in " + kinds));
    }

    private Path profile() throws IOException {
        Path core = temporaryDirectory.resolve("starsector-core");
        Files.createDirectories(core.resolve("data/config"));
        Files.createDirectories(core.resolve("sounds"));
        Files.createDirectories(temporaryDirectory.resolve("mods"));
        Files.writeString(temporaryDirectory.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[]}");
        return core;
    }

    /** Content does not need to decode; the census classifies from sounds.json, not from bytes. */
    private Path sound(Path core, String logicalPath) throws IOException {
        Path file = core.resolve(logicalPath);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[4096]);
        return file;
    }

    private void declare(String soundsJson) throws IOException {
        Files.writeString(
                temporaryDirectory.resolve("starsector-core/data/config/sounds.json"),
                soundsJson,
                StandardCharsets.UTF_8);
    }
}

package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
    void reportsEagerWhenEveryDeclaredEffectIsOpenedInABurst() throws Exception {
        Path core = profile();
        List<Path> declared = declareEffects(core, 40);

        Path recording = record(true, declared);
        AudioDecodeProbe.Result result = AudioDecodeProbe.run(recording, temporaryDirectory);

        assertEquals(AudioDecodeProbe.Verdict.EAGER, result.verdict(), result.detail());
        assertEquals(40, opened(result, "effect"));
        assertEquals(0, neverOpened(result, "effect"));
    }

    /**
     * The case the first real run produced and the first version of the verdict got wrong: 1,278 of
     * 2,050 effects, all inside 1.5 seconds of a six-minute session. A fraction between the eager and
     * lazy thresholds is not ambiguity when the opens are a burst — it means the session never reached
     * a later loading phase. The detail has to say that rather than claim full coverage.
     */
    @Test
    void reportsEagerWhenOnlySomeAreOpenedButAllAtOnce() throws Exception {
        Path core = profile();
        List<Path> declared = declareEffects(core, 60);

        Path recording = record(true, declared.subList(0, 30));
        AudioDecodeProbe.Result result = AudioDecodeProbe.run(recording, temporaryDirectory);

        assertEquals(AudioDecodeProbe.Verdict.EAGER, result.verdict(), result.detail());
        assertEquals(30, opened(result, "effect"));
        assertTrue(result.detail().contains("only part of what the profile declares"), result.detail());
    }

    /**
     * A player who quits moments after the menu appears has not changed how the game loaded, so a
     * session barely longer than the load itself must still read as a burst. Verdict logic that only
     * compared the window against the session length got this wrong, and CI caught it on a runner
     * where a test's reads were a large share of its short recording.
     */
    @Test
    void aShortSessionDoesNotTurnABulkLoadIntoSomethingElse() throws Exception {
        Path core = profile();
        List<Path> declared = declareEffects(core, 40);

        Path recording = record(true, declared, false);
        AudioDecodeProbe.Result result = AudioDecodeProbe.run(recording, temporaryDirectory);

        assertEquals(AudioDecodeProbe.Verdict.EAGER, result.verdict(), result.detail());
    }

    /** One open has a zero-length window, which must not read as a perfect bulk load. */
    @Test
    void doesNotCallASingleOpenABurst() throws Exception {
        Path core = profile();
        List<Path> declared = declareEffects(core, 40);

        Path recording = record(true, declared.subList(0, 1));
        AudioDecodeProbe.Result result = AudioDecodeProbe.run(recording, temporaryDirectory);

        assertNotEquals(AudioDecodeProbe.Verdict.EAGER, result.verdict(), result.detail());
    }

    @Test
    void reportsLazyWhenTheDeclaredEffectsAreNeverOpened() throws Exception {
        Path core = profile();
        declareEffects(core, 40);

        Path recording = record(true, List.of(core.resolve("data/config/sounds.json")));
        AudioDecodeProbe.Result result = AudioDecodeProbe.run(recording, temporaryDirectory);

        assertEquals(AudioDecodeProbe.Verdict.LAZY, result.verdict(), result.detail());
        assertEquals(40, neverOpened(result, "effect"));
    }

    /**
     * The opened and unopened sets have to partition what the census declared, and each has to carry
     * its own PCM total — sizing the opened side is the whole reason the probe exists.
     */
    @Test
    void partitionsTheDeclaredSetAndSizesEachSide() throws Exception {
        Path core = profile();
        List<Path> declared = declareEffects(core, 40);

        AudioDecodeProbe.Result result =
                AudioDecodeProbe.run(record(true, declared.subList(0, 30)), temporaryDirectory);

        assertEquals(30, opened(result, "effect"));
        assertEquals(10, neverOpened(result, "effect"));
        Map<String, Object> effects = kindRow(result, "effect");
        assertTrue(effects.containsKey("openedDecodedBytes"), effects.toString());
        assertTrue(effects.containsKey("neverOpenedDecodedBytes"), effects.toString());
        @SuppressWarnings("unchecked")
        List<String> sample = (List<String>) effects.get("neverOpenedSample");
        assertEquals(10, sample.size(), "every unopened file should appear in a sample this small");
    }

    /**
     * A mod can open its own losing copy directly. That read must not be credited to the provider
     * ordinary resource lookup actually selects, or a profile with enough such reads can be reported
     * eager even though none of the resolved effects were opened.
     */
    @Test
    void doesNotCreditAReadOfAShadowedProviderToTheWinningEffect() throws Exception {
        Path core = profile();
        Path shadowed = sound(core, "sounds/one.ogg");
        declare("""
                {"one":[{"file":"sounds/one.ogg"}]}
                """);
        Path mod = temporaryDirectory.resolve("mods/override");
        Files.createDirectories(mod);
        Files.writeString(mod.resolve("mod_info.json"), "{\"id\":\"override\"}");
        sound(mod, "sounds/one.ogg");
        Files.writeString(
                temporaryDirectory.resolve("mods/enabled_mods.json"),
                "{\"enabledMods\":[\"override\"]}");

        AudioDecodeProbe.Result result =
                AudioDecodeProbe.run(record(true, List.of(shadowed)), temporaryDirectory);

        assertEquals(AudioDecodeProbe.Verdict.LAZY, result.verdict(), result.detail());
        assertEquals(0, opened(result, "effect"));
        assertEquals(1, neverOpened(result, "effect"));
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

        Path recording = record(false, List.of(first));
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
                AudioDecodeProbe.run(record(true, List.of(first)), temporaryDirectory);

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
    private Path record(boolean exhaustive, List<Path> files) throws Exception {
        return record(exhaustive, files, true);
    }

    private Path record(boolean exhaustive, List<Path> files, boolean pauseAfterwards) throws Exception {
        Path destination = temporaryDirectory.resolve("probe-" + System.nanoTime() + ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.FileRead")
                    .withThreshold(exhaustive ? Duration.ZERO : Duration.ofMillis(1))
                    .withStackTrace();
            recording.start();

            AgentStarted started = new AgentStarted();
            started.exhaustiveFileReads = exhaustive;
            started.commit();

            for (Path file : files) {
                drain(file);
            }

            if (pauseAfterwards) {
                // Stands in for the minutes a real session spends after loading, so the window is a
                // small share of the recording as well as short in absolute terms.
                Thread.sleep(300);
                drain(temporaryDirectory.resolve("mods/enabled_mods.json"));
            }

            recording.stop();
            recording.dump(destination);
        }
        return destination;
    }

    private static void drain(Path file) throws IOException {
        byte[] buffer = new byte[64];
        try (InputStream in = Files.newInputStream(file)) {
            while (in.read(buffer) > 0) {
                // Read it all, the way something decoding the file would.
            }
        }
    }

    /** Declares {@code count} effects in sounds.json and returns their files in declaration order. */
    private List<Path> declareEffects(Path core, int count) throws IOException {
        List<Path> files = new ArrayList<>();
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < count; i++) {
            String logicalPath = "sounds/effect_" + i + ".ogg";
            files.add(sound(core, logicalPath));
            json.append(i == 0 ? "" : ",")
                    .append("\"effect_").append(i).append("\":[{\"file\":\"").append(logicalPath)
                    .append("\"}]");
        }
        declare(json.append("}").toString());
        return files;
    }

    private static int opened(AudioDecodeProbe.Result result, String kind) {
        return field(result, kind, "opened");
    }

    private static int neverOpened(AudioDecodeProbe.Result result, String kind) {
        return field(result, kind, "neverOpened");
    }

    private static int field(AudioDecodeProbe.Result result, String kind, String name) {
        return ((Number) kindRow(result, kind).get(name)).intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> kindRow(AudioDecodeProbe.Result result, String kind) {
        List<Map<String, Object>> kinds = (List<Map<String, Object>>) result.report().get("byKind");
        return kinds.stream()
                .filter(entry -> kind.equals(entry.get("kind")))
                .findFirst()
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

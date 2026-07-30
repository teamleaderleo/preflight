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
import java.util.concurrent.TimeUnit;
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

        @Label("Working Directory")
        String workingDirectory;
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
     * Partial coverage opened all at once. A fraction between the eager and lazy thresholds is not
     * ambiguity when the opens are a burst — the session reached one loading phase and not a later
     * one — and the detail has to say so rather than claim full coverage.
     *
     * <p>This is kept synthetic on purpose. The real run that motivated it looked like this case and
     * was not: its missing 38% was
     * {@link AudioDecodeProbeTest#matchesResourcesTheGameOpenedByRelativePath a path-resolution bug in
     * the probe}, not a phase the player never reached.
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

    /**
     * Audio the census cannot account for has to be counted and shown, not dropped.
     *
     * <p>The first version discarded it silently, and a real run was summarised as having opened no
     * music while the recording held 1,806 reads of {@code sounds/music/music.bin} — vanilla music is
     * one container, not files the census has entries for. A third of that run's audio reads matched
     * nothing, and the report said so nowhere.</p>
     */
    @Test
    void countsAudioReadsTheCensusCannotAccountFor() throws Exception {
        Path core = profile();
        List<Path> declared = declareEffects(core, 40);
        Path container = temporaryDirectory.resolve("outside/sounds/music/music.bin");
        Files.createDirectories(container.getParent());
        Files.write(container, new byte[4096]);

        List<Path> read = new ArrayList<>(declared);
        read.add(container);
        AudioDecodeProbe.Result result =
                AudioDecodeProbe.run(record(true, read), temporaryDirectory);

        long unmatched =
                ((Number) result.report().get("unmatchedAudioFileReadEvents")).longValue();
        assertTrue(unmatched > 0, "expected the container read to be counted, report " + result.report());
        @SuppressWarnings("unchecked")
        List<String> sample = (List<String>) result.report().get("unmatchedAudioSample");
        assertTrue(sample.stream().anyMatch(path -> path.endsWith("music.bin")),
                "expected music.bin named in " + sample);
    }

    /**
     * The join the probe got wrong for two releases: Starsector opens its own resources by relative
     * path, and a relative path means nothing without the directory it is relative to.
     *
     * <p>Flight Recorder stores what the JVM passed to the OS, so a core sound arrives as
     * {@code sounds/sfx_impacts/shield_hit_heavy_01.ogg}. Resolving that against Preflight's own
     * working directory silently missed every core resource in the reviewed profile — 7,309 audio
     * reads, a third of the recording's audio, and the whole of issue #232.</p>
     *
     * <p>The recording has to come from a separate process for this to test anything. {@code
     * toRealPath} resolves a relative path against the <em>reading</em> process's working directory,
     * so a recording made in this JVM would match with or without the fix — which is exactly why the
     * bug survived: it only appears when the recorder and the analyser disagree about where "here"
     * is, and in production they always do. So a child JVM is started in the core directory and reads
     * the sound the way the game does.</p>
     */
    @Test
    void matchesResourcesTheGameOpenedByRelativePath() throws Exception {
        Path core = profile();
        sound(core, "sounds/one.ogg");
        declare("""
                {"one":[{"file":"sounds/one.ogg"}]}
                """);

        Path recording = recordInAnotherWorkingDirectory(core, "sounds/one.ogg");
        AudioDecodeProbe.Result result = AudioDecodeProbe.run(recording, temporaryDirectory);

        assertEquals(1, opened(result, "effect"), result.detail());
        assertEquals(0, neverOpened(result, "effect"));
        assertTrue(((Number) result.report().get("relativeFileReadEvents")).longValue() > 0,
                "the relative read should be counted, report " + result.report());
    }

    /**
     * A recording that states where the game ran is believed over any reconstruction from the install
     * layout. The layout only ever supported a good guess; the recorded value is the fact.
     */
    @Test
    void believesTheWorkingDirectoryTheRecordingStates() throws Exception {
        Path core = profile();
        Path elsewhere = Files.createDirectories(temporaryDirectory.resolve("somewhere-else"));
        declareEffects(core, 40);

        AudioDecodeProbe.Result result = AudioDecodeProbe.run(
                record(true, List.of(core.resolve("data/config/sounds.json")), true,
                        elsewhere.toString()),
                temporaryDirectory);

        assertEquals(elsewhere.toString(), result.report().get("gameWorkingDirectory"));
        assertEquals("recording", result.report().get("gameWorkingDirectorySource"));
    }

    /**
     * Recordings predate the field, so the core resource root stays as the fallback — that is where
     * the launcher {@code cd}s before starting the JVM. Pinned separately so that changing the
     * fallback cannot pass unnoticed just because current recordings state their own directory.
     */
    @Test
    void fallsBackToTheCoreResourceRootWhenTheRecordingSaysNothing() throws Exception {
        Path core = profile();
        declareEffects(core, 40);

        AudioDecodeProbe.Result result = AudioDecodeProbe.run(
                record(true, List.of(core.resolve("data/config/sounds.json"))), temporaryDirectory);

        assertEquals(core.toRealPath().toString(), result.report().get("gameWorkingDirectory"));
        assertEquals("core-root", result.report().get("gameWorkingDirectorySource"));
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
        return record(exhaustive, files, true, null);
    }

    private Path record(boolean exhaustive, List<Path> files, boolean pauseAfterwards) throws Exception {
        return record(exhaustive, files, pauseAfterwards, null);
    }

    /**
     * @param workingDirectory what the marker event states the recorded process ran in, or
     *     {@code null} to leave it unset the way a recording from an older agent has it
     */
    private Path record(
            boolean exhaustive, List<Path> files, boolean pauseAfterwards, String workingDirectory)
            throws Exception {
        Path destination = temporaryDirectory.resolve("probe-" + System.nanoTime() + ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.FileRead")
                    .withThreshold(exhaustive ? Duration.ZERO : Duration.ofMillis(1))
                    .withStackTrace();
            recording.start();

            AgentStarted started = new AgentStarted();
            started.exhaustiveFileReads = exhaustive;
            started.workingDirectory = workingDirectory;
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

    /**
     * Records reads of {@code relativePaths} from a JVM whose working directory is {@code directory},
     * so the recorded paths are relative to somewhere this process is not. Source-launched rather
     * than given a classpath, because the child needs nothing but the JDK.
     */
    private Path recordInAnotherWorkingDirectory(Path directory, String... relativePaths)
            throws Exception {
        Path source = temporaryDirectory.resolve("RelativeReader.java");
        Files.writeString(source, """
                import java.io.InputStream;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.time.Duration;
                import jdk.jfr.Event;
                import jdk.jfr.Name;
                import jdk.jfr.Recording;

                public class RelativeReader {
                    @Name("preflight.AgentStarted")
                    static final class AgentStarted extends Event {
                        boolean exhaustiveFileReads;
                    }

                    public static void main(String[] args) throws Exception {
                        try (Recording recording = new Recording()) {
                            recording.enable("jdk.FileRead")
                                    .withThreshold(Duration.ZERO)
                                    .withStackTrace();
                            recording.start();
                            AgentStarted started = new AgentStarted();
                            started.exhaustiveFileReads = true;
                            started.commit();
                            for (int i = 1; i < args.length; i++) {
                                byte[] buffer = new byte[64];
                                try (InputStream in = Files.newInputStream(Path.of(args[i]))) {
                                    while (in.read(buffer) > 0) {
                                        // Read it all, the way something decoding the file would.
                                    }
                                }
                            }
                            recording.stop();
                            recording.dump(Path.of(args[0]));
                        }
                    }
                }
                """);

        Path destination = temporaryDirectory.resolve("relative-" + System.nanoTime() + ".jfr");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                source.toAbsolutePath().toString(),
                destination.toAbsolutePath().toString()));
        command.addAll(List.of(relativePaths));

        Path log = temporaryDirectory.resolve("relative-reader.log");
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the child JVM did not finish");
        assertEquals(0, process.exitValue(), "child JVM failed: " + Files.readString(log));
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

package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bake itself, run as the process that ships, against a real installation.
 *
 * <p>Everything else covering this path stops short of a decode: the loader tests prove the jars
 * are opened and the right entries answer, using jars the test built. Nothing established that the
 * game's decoder produces PCM when it is reached through a loader assembled from arguments rather
 * than from {@code -cp}, which is the arrangement {@code prepare audio} now uses.
 *
 * <p>This spawns the child rather than calling into it, and that is not incidental. The repository
 * carries {@code sound.J} and {@code sound.F} fixtures under {@code src/test/java/sound/}, so any
 * test running inside the build's own JVM has them on the parent class path and parent-first
 * delegation returns the fixture instead of the installation's class. The shipped child has only
 * {@code preflight.jar} in front of the game's jars, which is what this reproduces.
 *
 * <p>Opt-in, like every other installed test here. Point {@code preflight.starsector.sound.jar} at
 * {@code fs.sound_obf.jar} inside an installation and the rest is derived from its directory; the
 * test skips when the property is absent, which is every ordinary build and all of CI.
 */
class PrepareAudioDecodeInstalledIT {
    /** Enough to show a decoder binding serving more than the file it was built for. */
    private static final int SOUNDS = 6;

    // The child validates these as SHA-256 before it decodes anything. Their values carry no
    // meaning here -- nothing downstream of this test reads them back -- but their shape does.
    private static final String DECODER_IDENTITY = "d".repeat(64);
    private static final String PROFILE_FINGERPRINT = "0".repeat(63) + "1";
    private static final String BUILD_IDENTITY = "a".repeat(64);

    @TempDir
    Path directory;

    @Test
    void bakesInstalledSoundsWithTheGamesOwnDecoder() throws Exception {
        Bake bake = bake(directory.resolve("work"));

        assertTrue(bake.prepared() > 1,
                "only " + bake.prepared() + " of " + SOUNDS + " decoded: " + bake.report());
        assertTrue(bake.pcmBytes() > bake.encodedBytes(),
                "decoded PCM should exceed the Vorbis it came from: " + bake.report());
        assertEquals(bake.prepared(), bake.manifestEntries(),
                "every prepared sound belongs in the manifest: " + bake.report());
        assertTrue(Files.isRegularFile(bake.manifest()), "no manifest at " + bake.manifest());
    }

    /**
     * The same bake under a directory the Windows code page cannot carry.
     *
     * <p>This is the arrangement the argument encoding exists for. It cannot fail here for the
     * reason it would fail there -- macOS pins {@code sun.jnu.encoding} to UTF-8 -- but it does
     * establish that encoding the vector has not broken the ordinary crossing, which is the half of
     * that change a Windows machine is not needed to check.
     */
    @Test
    void bakesThroughPathsTheCodePageCannotCarry() throws Exception {
        Bake bake = bake(directory.resolve("Ωμέγα").resolve("work"));

        assertTrue(bake.prepared() > 1, "decoded nothing through a non-ASCII path: " + bake.report());
        assertTrue(bake.report().contains("Ωμέγα"),
                "the child should have written back the path it was given: " + bake.report());
    }

    /** Runs the real child on the current JVM with only {@code preflight.jar} in front of the game. */
    private Bake bake(Path workRoot) throws Exception {
        Path soundJar = installedSoundJar();
        Path javaDirectory = soundJar.getParent();
        List<Path> sounds = someInstalledSounds(javaDirectory.resolve("sounds"));
        Assumptions.assumeTrue(sounds.size() >= 2, "an installation with one sound proves nothing here");

        Path jar = Path.of("target", "preflight.jar").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(jar), jar + " is missing; run `mvn package` first");

        Files.createDirectories(workRoot);
        Path work = workRoot.resolve("sounds.tsv");
        StringBuilder lines = new StringBuilder();
        for (Path sound : sounds) {
            lines.append(javaDirectory.relativize(sound).toString().replace('\\', '/'))
                    .append('\t')
                    .append(sound)
                    .append('\n');
        }
        Files.writeString(work, lines.toString(), StandardCharsets.UTF_8);

        Path cache = workRoot.resolve("cache");
        Path output = workRoot.resolve("bake.json");
        Path manifest = workRoot.resolve("manifest.json");

        List<String> arguments = new ArrayList<>(List.of(
                work.toString(),
                cache.toString(),
                DECODER_IDENTITY,
                output.toString(),
                PROFILE_FINGERPRINT,
                BUILD_IDENTITY,
                manifest.toString()));
        for (Path gameJar : jarsIn(javaDirectory)) {
            arguments.add(gameJar.toString());
        }

        List<String> command = new ArrayList<>(List.of(childJava(javaDirectory).toString()));
        // The command's own options, not a copy of them. Without these the game's classes do not
        // load at all -- obfuscation left them names no verifier accepts -- and every file is
        // reported undecodable, which reads like a decoder fault rather than a missing flag.
        command.addAll(PrepareAudioCommand.CHILD_JVM_OPTIONS);
        command.addAll(List.of("-cp", jar.toString(), PrepareAudioChild.class.getName()));
        command.addAll(List.of(Utf8Argv.encode(arguments.toArray(new String[0]))));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String console = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "the bake child failed: " + console);
        assertTrue(console.contains("prepared-audio-bake-complete"), console);

        return new Bake(Files.readString(output, StandardCharsets.UTF_8), manifest);
    }

    /** The child's report, read the way anything downstream of it reads the numbers. */
    private record Bake(String report, Path manifest) {
        long prepared() {
            return number("prepared");
        }

        long manifestEntries() {
            return number("manifestEntries");
        }

        long pcmBytes() {
            return number("pcmBytes");
        }

        long encodedBytes() {
            return number("encodedBytes");
        }

        private long number(String field) {
            String key = "\"" + field + "\":";
            int at = report.indexOf(key);
            assertTrue(at >= 0, field + " is missing from " + report);
            int from = at + key.length();
            int to = from;
            while (to < report.length() && "-0123456789".indexOf(report.charAt(to)) >= 0) {
                to++;
            }
            return Long.parseLong(report.substring(from, to));
        }
    }

    private static Path installedSoundJar() {
        String configured = System.getProperty("preflight.starsector.sound.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.sound.jar=<install>/fs.sound_obf.jar to run this");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive), archive + " is not a file");
        return archive;
    }

    /**
     * Every jar beside it, which is what {@code prepare audio} forwards. The decoder reaches
     * OpenAL and jorbis as well as its own classes, so a hand-picked subset would be exercising a
     * class path the command never assembles.
     */
    private static List<Path> jarsIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted()
                    .toList();
        }
    }

    /** Chosen by sorted path so a failure names the same files on the next run. */
    private static List<Path> someInstalledSounds(Path sounds) throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(sounds), sounds + " is not a directory");
        try (Stream<Path> walk = Files.walk(sounds)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".ogg"))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(SOUNDS)
                    .toList();
        }
    }

    /**
     * The installation's own Java, which is what {@code prepare audio} spawns.
     *
     * <p>Taken from {@code preflight.starsector.java} when set, otherwise the runtime bundled
     * beside the installation, otherwise the one running this build. The decode has to happen on
     * the runtime the game will use, since that is the pairing the blobs are admitted against.
     */
    private static Path childJava(Path javaDirectory) {
        String configured = System.getProperty("preflight.starsector.java", "").trim();
        if (!configured.isEmpty()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        // <install>/Contents/Resources/Java -> <install>/Contents/Home/bin/java on the macOS bundle.
        Path bundled = javaDirectory.getParent().resolveSibling("Home").resolve("bin").resolve(executable());
        if (Files.isExecutable(bundled)) {
            return bundled;
        }
        return Path.of(System.getProperty("java.home"), "bin", executable());
    }

    private static String executable() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
    }
}

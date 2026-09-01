package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The installation's jars reach the decode child as arguments rather than on a class path.
 *
 * <p>What that has to survive is a path the system code page cannot carry, which is the whole reason
 * they stopped travelling on {@code -cp}. These tests use a directory the code page a Windows runner
 * uses cannot represent, so the jar is only opened if the path made it through intact.
 *
 * <p>Every loader is closed. A URLClassLoader holds its jars open, and Windows will not delete a
 * file something still holds — leaving one open fails the temporary directory's own cleanup, on that
 * platform only, which is how the first version of this class broke the very build it was written
 * to protect.
 */
class PrepareAudioChildTest {
    /** Where the fixed arguments end and the installation's jars begin. */
    private static final int FROM = 7;

    private static final String[] FIXED = {"work", "cache", "identity", "out", "profile", "build", "manifest"};

    @TempDir
    Path directory;

    @Test
    void opensAnInstallationJarAtAPathTheCodePageCannotCarry() throws Exception {
        Path jar = jarUnder("Ωμέγα", "sound/marker.txt");

        try (URLClassLoader loader = PrepareAudioChild.gameClassLoader(withJars(jar), FROM)) {
            assertNotNull(loader.getResource("sound/marker.txt"),
                    "the jar is only readable if its path survived as itself");
        }
    }

    @Test
    void findsJarsAtTheRootOfAFlatLinuxInstallation() throws Exception {
        Path game = Files.createDirectories(directory.resolve("linux-game"));
        Path gameJar = Files.writeString(game.resolve("starfarer_obf.jar"), "game");
        Path soundJar = Files.writeString(game.resolve("fs.sound_obf.jar"), "sound");
        Files.writeString(game.resolve("not-a-jar.txt"), "ignored");

        assertEquals(List.of(soundJar, gameJar), PrepareAudioCommand.jars(game));
    }

    @Test
    void servesWhatOnlyTheInstallationHas() throws Exception {
        Path jar = jarUnder("Ωμέγα", "sound/marker.txt");

        try (URLClassLoader loader = PrepareAudioChild.gameClassLoader(withJars(jar), FROM)) {
            assertNotNull(loader.getResource("sound/marker.txt"));
            assertNull(PrepareAudioChild.class.getClassLoader().getResource("sound/marker.txt"),
                    "the entry must come from the argument, not from Preflight's own class path");
        }
    }

    @Test
    void takesEveryJarItIsGivenAndNothingElse() throws Exception {
        Path first = jarUnder("one", "sound/first.txt");
        Path second = jarUnder("two", "sound/second.txt");

        try (URLClassLoader loader = PrepareAudioChild.gameClassLoader(withJars(first, second), FROM)) {
            assertNotNull(loader.getResource("sound/first.txt"));
            assertNotNull(loader.getResource("sound/second.txt"));
            assertNull(loader.getResource("sound/third.txt"));
        }
    }

    @Test
    void anInstallationWithNoJarsIsNotAnError() throws Exception {
        try (URLClassLoader loader = PrepareAudioChild.gameClassLoader(FIXED.clone(), FROM)) {
            assertNotNull(loader);
            assertNull(loader.getResource("sound/marker.txt"));
        }
    }

    @Test
    void theArgumentVectorSurvivesTheCrossing() {
        Path jar = directory.resolve("Ωμέγα/starfarer_obf.jar");
        String[] sent = withJars(jar);

        String[] received = Utf8Argv.decode(Utf8Argv.encode(sent));

        assertEquals(sent.length, received.length);
        assertEquals(jar.toString(), received[FROM]);
        assertEquals("manifest", received[FROM - 1]);
    }

    @Test
    void readsOnlyTheBoundedParentWorkList() throws Exception {
        Path work = directory.resolve("work.tsv");
        Files.writeString(work,
                "sounds/a.ogg\t/a.ogg\n" + "sounds/b.ogg\t/b.ogg\n",
                StandardCharsets.UTF_8);

        assertEquals(
                java.util.List.of("sounds/a.ogg\t/a.ogg", "sounds/b.ogg\t/b.ogg"),
                PrepareAudioChild.readWorkItems(work));
    }

    @Test
    void rejectsWorkListBeforeReadingPastItsByteBudget() throws Exception {
        Path oversized = directory.resolve("oversized-work.tsv");
        try (var channel = java.nio.channels.FileChannel.open(
                oversized, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(PrepareAudioCommand.MAX_WORK_FILE_BYTES);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }

        assertThrows(IOException.class, () -> PrepareAudioChild.readWorkItems(oversized));
    }

    @Test
    void rejectsAnEncodedAssetBeforeReadingItPastThePerFileBudget() throws Exception {
        Path oversized = directory.resolve("oversized.ogg");
        try (var channel = java.nio.channels.FileChannel.open(
                oversized, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(PrepareAudioCommand.MAX_ENCODED_FILE_BYTES);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }

        assertThrows(IllegalArgumentException.class, () -> PrepareAudioChild.readEncoded(oversized));
    }

    private String[] withJars(Path... jars) {
        String[] args = new String[FIXED.length + jars.length];
        System.arraycopy(FIXED, 0, args, 0, FIXED.length);
        for (int index = 0; index < jars.length; index++) {
            args[FIXED.length + index] = jars[index].toString();
        }
        return args;
    }

    private Path jarUnder(String folder, String entry) throws IOException {
        Path jar = Files.createDirectories(directory.resolve(folder)).resolve("starfarer_obf.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(entry));
            output.write("installed".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }
}

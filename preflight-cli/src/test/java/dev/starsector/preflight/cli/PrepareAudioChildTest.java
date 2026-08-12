package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

        ClassLoader loader = PrepareAudioChild.gameClassLoader(withJars(jar), FROM);

        assertNotNull(loader.getResource("sound/marker.txt"),
                "the jar is only readable if its path survived as itself");
    }

    @Test
    void servesWhatOnlyTheInstallationHas() throws Exception {
        Path jar = jarUnder("Ωμέγα", "sound/marker.txt");

        ClassLoader loader = PrepareAudioChild.gameClassLoader(withJars(jar), FROM);

        // Without this the test above would pass on a loader that found the entry anywhere at all,
        // which is exactly what the class path used to do and what this replaced.
        assertNull(PrepareAudioChild.class.getClassLoader().getResource("sound/marker.txt"),
                "the entry must come from the argument, not from Preflight's own class path");
    }

    @Test
    void takesEveryJarItIsGivenAndNothingElse() throws Exception {
        Path first = jarUnder("one", "sound/first.txt");
        Path second = jarUnder("two", "sound/second.txt");

        ClassLoader loader = PrepareAudioChild.gameClassLoader(withJars(first, second), FROM);

        assertNotNull(loader.getResource("sound/first.txt"));
        assertNotNull(loader.getResource("sound/second.txt"));
        assertNull(loader.getResource("sound/third.txt"));
    }

    @Test
    void anInstallationWithNoJarsIsNotAnError() throws Exception {
        ClassLoader loader = PrepareAudioChild.gameClassLoader(FIXED.clone(), FROM);

        assertNotNull(loader);
        assertNull(loader.getResource("sound/marker.txt"));
    }

    @Test
    void theArgumentVectorSurvivesTheCrossing() {
        Path jar = directory.resolve("Ωμέγα/starfarer_obf.jar");
        String[] sent = withJars(jar);

        // What the parent hands ProcessBuilder and what main() sees after decoding. The jars are at
        // the tail, so an encoding that lost or reordered arguments would move the boundary too.
        String[] received = Utf8Argv.decode(Utf8Argv.encode(sent));

        assertEquals(sent.length, received.length);
        assertEquals(jar.toString(), received[FROM]);
        assertEquals("manifest", received[FROM - 1]);
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

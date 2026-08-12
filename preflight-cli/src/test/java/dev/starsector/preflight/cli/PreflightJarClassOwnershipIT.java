package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The condition that makes parent-first delegation safe in the decode child.
 *
 * <p>{@link PrepareAudioChild} opens the installation's jars in a loader whose parent holds
 * {@code preflight.jar}. On the flat class path that replaced, the game's jars came first and the
 * game won any name the two shared; parent-first reverses that. Nothing about the arrangement
 * detects a collision -- the wrong class simply loads, and a decoder that is not the game's decoder
 * produces blobs the game is then served in place of its own decoding.
 *
 * <p>The overlap is currently zero and there is no reason for it to become anything else, which is
 * exactly why it needs a test: the change that breaks it would be adding a dependency, and nothing
 * about adding a dependency would prompt anyone to check this.
 */
class PreflightJarClassOwnershipIT {
    /**
     * Package roots the installation owns.
     *
     * <p>{@code sound} and {@code com.fs} are the game's own; the rest are libraries it ships and
     * loads from its own jars. Preflight bundling any of them would shadow the copy the game was
     * built against.
     */
    private static final List<String> THE_GAMES = List.of(
            "sound/", "com/fs/", "com/jcraft/", "org/lwjgl/", "org/json/", "com/thoughtworks/xstream/");

    @Test
    void theShippedJarClaimsNoClassTheGameOwns() throws Exception {
        Path jar = Path.of("target", "preflight.jar").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(jar), jar + " is missing; run `mvn package` first");

        List<String> trespassing = new ArrayList<>();
        try (JarFile archive = new JarFile(jar.toFile())) {
            archive.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.endsWith(".class"))
                    .filter(name -> THE_GAMES.stream().anyMatch(name::startsWith))
                    .forEach(trespassing::add);
        }

        assertTrue(trespassing.isEmpty(),
                "preflight.jar declares classes the installation owns, which parent-first delegation "
                        + "would serve in place of the game's own: " + trespassing);
    }
}

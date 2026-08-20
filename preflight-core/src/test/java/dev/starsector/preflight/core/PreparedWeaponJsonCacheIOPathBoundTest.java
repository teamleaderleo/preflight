package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PreparedWeaponJsonCacheIOPathBoundTest {
    private static final String PROFILE = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void pathReadKeepsCheapInitiallyOversizedPrefilter() throws Exception {
        PreparedWeaponJsonCache cache = new PreparedWeaponJsonCache(
                PROFILE, Map.of("data/weapons/a.wpn", JsonTree.encode(Map.of("id", "a"))));
        byte[] bytes = PreparedWeaponJsonCacheIO.toBytes(cache);
        Path file = temporaryDirectory.resolve("already-too-large.spwj");
        Files.write(file, bytes);

        IOException error = assertThrows(
                IOException.class,
                () -> PreparedWeaponJsonCacheIO.read(file, bytes.length - 1));

        assertTrue(error.getMessage().contains("size is invalid"), error.getMessage());
    }
}

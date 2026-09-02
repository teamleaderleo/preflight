package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedFactionPriorityCacheIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsEmptyAndOrderedResultsForAnExactProfile() throws Exception {
        String profile = "ab".repeat(32);
        Path artifact = PreparedFactionPriorityCacheIO.path(temporaryDirectory, profile);
        PreparedFactionPriorityCache expected = new PreparedFactionPriorityCache(profile, Map.of(
                "callback\u001fknownShips\u001fhulls\u001f1", List.of("wolf", "lasher"),
                "callback\u001fpriorityWeapons\u001fweapons\u001f0", List.of()));

        PreparedFactionPriorityCacheIO.write(artifact, expected);

        assertEquals(expected, PreparedFactionPriorityCacheIO.read(artifact));
    }

    @Test
    void rejectsChecksumDamage() throws Exception {
        String profile = "cd".repeat(32);
        Path artifact = PreparedFactionPriorityCacheIO.path(temporaryDirectory, profile);
        PreparedFactionPriorityCacheIO.write(artifact,
                new PreparedFactionPriorityCache(profile, Map.of("key", List.of("id"))));
        byte[] bytes = Files.readAllBytes(artifact);
        bytes[bytes.length - 1] ^= 1;
        Files.write(artifact, bytes);

        assertThrows(IOException.class, () -> PreparedFactionPriorityCacheIO.read(artifact));
    }
}

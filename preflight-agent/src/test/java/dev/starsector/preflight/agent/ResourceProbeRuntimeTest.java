package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceProbeRuntimeTest {
    @TempDir
    Path root;

    @BeforeEach
    void enable() {
        ResourceProbeRuntime.reset();
        ResourceProbeRuntime.enable(true);
    }

    @AfterEach
    void disable() {
        ResourceProbeRuntime.enable(false);
        ResourceProbeRuntime.reset();
    }

    @Test
    void answersTheSameAsTheFilesystem() throws Exception {
        Files.createDirectories(root.resolve("data/hulls"));
        Files.writeString(root.resolve("data/hulls/present.ship"), "{}");

        assertTrue(ResourceProbeRuntime.exists(root.resolve("data/hulls/present.ship").toFile()));
        assertFalse(ResourceProbeRuntime.exists(root.resolve("data/hulls/absent.ship").toFile()));
        // The whole point: no data/weapons here, so nothing under it can exist either.
        assertFalse(ResourceProbeRuntime.exists(root.resolve("data/weapons/absent.wpn").toFile()));
    }

    @Test
    void aProbeIntoAMissingDirectoryNeverTouchesTheFilesystem() {
        for (int index = 0; index < 50; index++) {
            assertFalse(ResourceProbeRuntime.exists(
                    root.resolve("data/hulls/hull-" + index + ".ship").toFile()));
        }
        Map<String, Object> report = ResourceProbeRuntime.report();
        assertEquals(50L, report.get("probes"));
        assertEquals(50L, report.get("probesAnsweredWithoutSyscall"));
        // One directory remembered, not fifty answers cached: the memo is bounded by the shape of
        // the tree rather than by how hard the game searched it.
        assertEquals(1, report.get("directoriesRemembered"));
    }

    @Test
    void aFileWrittenAfterItsDirectoryWasReadIsNotSeen() throws Exception {
        // The one behaviour this trades away, asserted rather than left to be discovered. A launch
        // already assumes the game data does not change underneath it -- the profile fingerprint,
        // the resource index and every prepared artifact are computed before the JVM starts.
        Files.createDirectories(root.resolve("data/hulls"));
        assertFalse(ResourceProbeRuntime.exists(root.resolve("data/hulls/generated.ship").toFile()));

        Files.writeString(root.resolve("data/hulls/generated.ship"), "{}");
        assertFalse(ResourceProbeRuntime.exists(root.resolve("data/hulls/generated.ship").toFile()),
                "the listing was taken before the write, and it is not re-taken");
    }

    @Test
    void aDisabledRuntimeIsExactlyFileExists() throws Exception {
        ResourceProbeRuntime.enable(false);
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/present.json"), "{}");

        assertTrue(ResourceProbeRuntime.exists(root.resolve("data/present.json").toFile()));
        assertFalse(ResourceProbeRuntime.exists(root.resolve("data/absent.json").toFile()));
        assertEquals(0L, ResourceProbeRuntime.report().get("probes"));
    }

    @Test
    void aPathWithNoParentFallsThroughRatherThanGuessing() {
        assertEquals(new File("nonexistent-relative-name").exists(),
                ResourceProbeRuntime.exists(new File("nonexistent-relative-name")));
    }
}

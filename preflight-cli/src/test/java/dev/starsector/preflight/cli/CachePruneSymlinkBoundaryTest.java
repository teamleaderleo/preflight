package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.starsector.preflight.core.ResourceIndexIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachePruneSymlinkBoundaryTest {
    @TempDir
    Path directory;

    @Test
    void planningRefusesASymlinkedCacheNamespaceAndPreservesOutsideFiles() throws Exception {
        PreflightHome home = home();
        String current = "a".repeat(64);
        String stale = "b".repeat(64);
        Path outside = directory.resolve("outside-indexes");
        Files.createDirectories(outside);
        Path outsideFile = outside.resolve(stale + ".spfi");
        Files.writeString(outsideFile, "outside data must survive");

        Path indexes = ResourceIndexIO.directory(home.cache());
        Files.createDirectories(indexes.getParent());
        createSymlinkOrSkip(indexes, outside);

        CachePrune.Plan plan = CachePrune.plan(home, Set.of(current), Set.of());

        assertFalse(plan.safe());
        assertTrue(plan.refusals().stream().anyMatch(text -> text.contains("symbolic")),
                plan.refusals().toString());
        assertTrue(plan.removals().isEmpty());
        assertThrows(IOException.class, () -> CachePrune.apply(plan));
        assertEquals("outside data must survive", Files.readString(outsideFile));
    }

    @Test
    void applyRevalidatesTheCacheBoundaryAfterPlanning() throws Exception {
        PreflightHome home = home();
        String current = "c".repeat(64);
        String stale = "d".repeat(64);
        Path indexes = ResourceIndexIO.directory(home.cache());
        Files.createDirectories(indexes);
        Path plannedFile = indexes.resolve(stale + ".spfi");
        Files.writeString(plannedFile, "owned stale data");

        CachePrune.Plan plan = CachePrune.plan(home, Set.of(current), Set.of());
        assertTrue(plan.safe(), plan.refusals().toString());
        assertTrue(plan.removals().stream().anyMatch(removal -> removal.path().equals(plannedFile)));

        Files.delete(plannedFile);
        Files.delete(indexes);
        Path outside = directory.resolve("outside-after-plan");
        Files.createDirectories(outside);
        Path outsideFile = outside.resolve(stale + ".spfi");
        Files.writeString(outsideFile, "outside replacement must survive");
        createSymlinkOrSkip(indexes, outside);

        assertThrows(IOException.class, () -> CachePrune.apply(plan));
        assertEquals("outside replacement must survive", Files.readString(outsideFile));
    }

    @Test
    void ordinaryPruneStillDeletesAnOwnedStaleFile() throws Exception {
        PreflightHome home = home();
        String current = "e".repeat(64);
        String stale = "f".repeat(64);
        Path indexes = ResourceIndexIO.directory(home.cache());
        Files.createDirectories(indexes);
        Path staleFile = indexes.resolve(stale + ".spfi");
        Files.writeString(staleFile, "owned stale data");

        CachePrune.Plan plan = CachePrune.plan(home, Set.of(current), Set.of());
        assertTrue(plan.safe(), plan.refusals().toString());

        assertTrue(CachePrune.apply(plan) > 0);
        assertFalse(Files.exists(staleFile));
    }

    private PreflightHome home() {
        return PreflightHome.resolve(Platform.OTHER, directory, Map.of());
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException | IOException error) {
            assumeTrue(false, "Symbolic links aren't available in this test environment: " + error);
        }
    }
}

package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceProviderObservationIsolationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exactObservationAuthorsNoEntryAndDoesNotChangeAnUnchangedIndex() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("root"));
        Files.writeString(root.resolve("shared.bin"), "AAAA");

        ResourceIndex first = ResourceIndexBuilder.buildStandalone(root, "mod").index();
        List<String> beforeNames;
        try (var entries = Files.list(root)) {
            beforeNames = entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
        ResourceIndex.Provider provider = first.winner("shared.bin").orElseThrow();
        ResourceProviderComparison.ContentObservation observed =
                ResourceProviderContentIdentity.direct(first).observe("shared.bin", provider);
        ResourceIndex second = ResourceIndexBuilder.buildStandalone(root, "mod").index();
        List<String> afterNames;
        try (var entries = Files.list(root)) {
            afterNames = entries.map(path -> path.getFileName().toString()).sorted().toList();
        }

        assertEquals(ResourceProviderComparison.ContentEvidence.HASHED, observed.evidence());
        assertEquals(beforeNames, afterNames, "exact observation must author no helper file or directory");
        assertEquals(first.entries(), second.entries(), "unchanged input must keep the exact provider generation");
        assertEquals(first.profileFingerprint(), second.profileFingerprint(),
                "unchanged input must keep the v2 profile/index authority key");
    }

    @Test
    void nestedResourceRootsRemainUnchangedByExactObservation() throws Exception {
        Path outer = Files.createDirectories(temporaryDirectory.resolve("outer"));
        Path nested = Files.createDirectories(outer.resolve("submod"));
        Files.writeString(nested.resolve("shared.bin"), "AAAA");

        ResourceIndex index = ResourceIndexBuilder.buildStandalone(outer, "outer").index();
        ResourceIndex.Provider provider = index.winner("submod/shared.bin").orElseThrow();
        assertEquals(
                ResourceProviderComparison.ContentEvidence.HASHED,
                ResourceProviderContentIdentity.direct(index)
                        .observe("submod/shared.bin", provider)
                        .evidence());
        try (var entries = Files.list(outer)) {
            assertEquals(List.of("submod"), entries.map(path -> path.getFileName().toString()).sorted().toList());
        }
        try (var entries = Files.list(nested)) {
            assertEquals(List.of("shared.bin"), entries.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }
}

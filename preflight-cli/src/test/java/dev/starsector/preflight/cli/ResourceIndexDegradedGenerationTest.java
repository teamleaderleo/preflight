package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ResourceIndexDegradedGenerationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void unavailableNativeGenerationKeepsOrdinaryProviderButExactObservationHashesNothing()
            throws Exception {
        try (Fixture fixture = fixture("degraded-basic.zip", 1)) {
            ResourceIndexBuilder.BuildResult built = ResourceIndexBuilder.buildStandalone(fixture.root(), "mod");
            ResourceIndex.Provider provider = built.index().winner("file-0.bin").orElseThrow();

            assertFalse(provider.hasGenerationAuthority());
            assertEquals(1, built.index().providerCount());
            assertEquals(1, generationDiagnostics(built));

            AtomicInteger hashes = new AtomicInteger();
            ResourceProviderComparison.ContentObservation observed = ResourceProviderContentIdentity.direct(
                            built.index(),
                            input -> {
                                hashes.incrementAndGet();
                                return Hashes.sha256(input);
                            })
                    .observe("file-0.bin", provider);
            assertEquals(ResourceProviderComparison.ContentEvidence.STALE, observed.evidence());
            assertEquals(0, hashes.get(), "degraded provider must refuse before payload hashing");
        }
    }

    @Test
    void unavailableGenerationDiagnosticsAreBoundedPerRoot() throws Exception {
        try (Fixture fixture = fixture("degraded-many.zip", 64)) {
            ResourceIndexBuilder.BuildResult built = ResourceIndexBuilder.buildStandalone(fixture.root(), "mod");
            assertEquals(64, built.index().providerCount());
            assertEquals(1, generationDiagnostics(built));
            assertTrue(built.diagnostics().stream().anyMatch(value ->
                    value.contains("provider metadata retained without exact generation authority")));
        }
    }

    @Test
    void degradedFingerprintIsDeterministicButStillTracksOrdinaryMetadata() throws Exception {
        try (Fixture fixture = fixture("degraded-fingerprint.zip", 1)) {
            ResourceIndex first = ResourceIndexBuilder.buildStandalone(fixture.root(), "mod").index();
            ResourceIndex second = ResourceIndexBuilder.buildStandalone(fixture.root(), "mod").index();
            assertEquals(first.profileFingerprint(), second.profileFingerprint());

            Files.write(fixture.root().resolve("file-0.bin"), new byte[] {1, 2, 3, 4, 5});
            ResourceIndex changed = ResourceIndexBuilder.buildStandalone(fixture.root(), "mod").index();
            assertNotEquals(first.profileFingerprint(), changed.profileFingerprint());
        }
    }

    @Test
    void degradedPublicationStillRejectsOrdinaryMetadataChange() throws Exception {
        try (Fixture fixture = fixture("degraded-publication.zip", 1)) {
            Path source = fixture.root().resolve("file-0.bin");
            assertThrows(IOException.class, () -> ResourceIndexBuilder.buildStandalone(
                    fixture.root(), "mod", () -> Files.write(source, new byte[] {1, 2, 3, 4, 5})));
        }
    }

    private static long generationDiagnostics(ResourceIndexBuilder.BuildResult built) {
        return built.diagnostics().stream()
                .filter(value -> value.startsWith("Exact file-generation authority unavailable in resource root "))
                .count();
    }

    private Fixture fixture(String name, int files) throws Exception {
        Path archive = temporaryDirectory.resolve(name);
        FileSystem fileSystem = FileSystems.newFileSystem(
                URI.create("jar:" + archive.toUri()), Map.of("create", "true"));
        Path root = Files.createDirectories(fileSystem.getPath("/root"));
        for (int index = 0; index < files; index++) {
            Files.write(root.resolve("file-" + index + ".bin"), new byte[] {1, 2, 3, 4});
        }
        return new Fixture(fileSystem, root);
    }

    private record Fixture(FileSystem fileSystem, Path root) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            fileSystem.close();
        }
    }
}

package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Adversarial proof seam for same-inode mutation hidden by restored size/mtime. */
class ResourceProviderContentIdentitySameInodeAbaTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sameInodeMutationRestoredBeforeIdentityRecheckCannotPublishRacedBytes() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("same-inode/root"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        assumeTrue(hardLinkProofAvailable(file), "requires the production hard-link proof primitive");

        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        FileTime originalModified = Files.getLastModifiedTime(file);
        String originalDigest = Hashes.sha256(file);

        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                bytes -> {
                    // Mutate the exact same inode while the proof-link stream is already open. Keep
                    // length constant, hash the raced generation, then restore both bytes and the
                    // full observed mtime before the post-read identity sample.
                    Files.writeString(file, "BBBB");
                    String racedDigest = Hashes.sha256(bytes);
                    Files.writeString(file, "AAAA");
                    Files.setLastModifiedTime(file, originalModified);
                    return racedDigest;
                });

        ResourceProviderComparison.ContentObservation observed = identities.observe("shared.bin", provider);

        assertEquals("AAAA", Files.readString(file), "the public file is restored before observation returns");
        assertEquals(
                ResourceProviderComparison.ContentEvidence.STALE,
                observed.evidence(),
                "same-inode bytes changed during the read must not be published as exact content evidence");
        assertEquals(originalDigest, Hashes.sha256(file), "the public file has the original final content");
        assertNoProofArtifacts(root);
    }

    private static boolean hardLinkProofAvailable(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            return false;
        }
        ResourceProviderContentIdentity.ProofLink proof =
                ResourceProviderContentIdentity.createProofLink(file, parent);
        if (proof == null) {
            return false;
        }
        try (proof) {
            return Files.isSameFile(proof.path(), file);
        }
    }

    private static void assertNoProofArtifacts(Path root) throws IOException {
        try (var entries = Files.list(root)) {
            assertEquals(
                    0,
                    entries.filter(path -> path.getFileName().toString()
                                    .startsWith(ResourceProviderContentIdentity.PROOF_DIRECTORY_PREFIX))
                            .count(),
                    "proof-link namespaces must be cleaned after every observation");
        }
    }

    private static ResourceIndex index(Path root, ResourceIndex.Provider provider) {
        return new ResourceIndex(
                "a".repeat(64),
                List.of(new ResourceIndex.Root("mod", root, false)),
                Map.of("shared.bin", List.of(provider)));
    }

    private static ResourceIndex.Provider provider(Path root, String relative) throws Exception {
        Path file = root.resolve(relative);
        return new ResourceIndex.Provider(
                0,
                relative,
                Files.size(file),
                Math.max(0, Files.getLastModifiedTime(file).toMillis()));
    }
}

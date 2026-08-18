package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ResourceProviderProofCleanupIsolationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void actualCleanupFailureCannotPolluteTheResourceIndexOrSeedTheMemo() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("cleanup-isolation/root"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        Path outsideRoot = root.toRealPath().getParent();
        assumeTrue(outsideRoot != null, "requires a parent outside the resource root");
        assumeTrue(
                Files.getFileStore(file).equals(Files.getFileStore(outsideRoot)),
                "requires an outside directory on the provider FileStore");
        assumeTrue(hardLinkProofAvailable(file, outsideRoot), "requires the production hard-link proof primitive");
        assumeTrue(
                Files.readAttributes(file, BasicFileAttributes.class).fileKey() != null,
                "requires a filesystem identity strong enough to exercise the memo");

        ResourceIndexBuilder.BuildResult baseline = ResourceIndexBuilder.buildStandalone(root, "mod");
        ResourceIndex index = baseline.index();
        ResourceIndex.Provider provider = index.providers("shared.bin").get(0);
        AtomicInteger hashCalls = new AtomicInteger();
        AtomicInteger proofAttempts = new AtomicInteger();
        AtomicReference<ResourceProviderContentIdentity.ProofLink> leakedProof = new AtomicReference<>();
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                bytes -> {
                    hashCalls.incrementAndGet();
                    return Hashes.sha256(bytes);
                },
                leakFirstProof(proofAttempts, leakedProof));

        ResourceProviderComparison.ContentObservation cleanupFailed = identities.observe("shared.bin", provider);

        assertEquals(ResourceProviderComparison.ContentEvidence.UNREADABLE, cleanupFailed.evidence());
        assertEquals(1, hashCalls.get());
        ResourceProviderContentIdentity.ProofLink leaked = leakedProof.get();
        assertTrue(leaked != null, "the injected failure must leave a real proof artifact behind");
        assertTrue(Files.exists(leaked.directory()));
        assertTrue(Files.exists(leaked.path()));
        assertTrue(Files.isSameFile(leaked.path(), file));
        assertEquals(outsideRoot, leaked.directory().getParent());
        assertFalse(
                leaked.directory().startsWith(root.toRealPath()),
                "proof residue must stay outside the indexed resource root");

        ResourceIndexBuilder.BuildResult rescanned = ResourceIndexBuilder.buildStandalone(root, "mod");
        assertSameIndex(baseline, rescanned, "single resource root");

        leaked.close();
        assertNoProofArtifacts(outsideRoot);

        ResourceProviderComparison.ContentObservation retried = identities.observe("shared.bin", provider);
        assertEquals(ResourceProviderComparison.ContentEvidence.HASHED, retried.evidence());
        assertEquals(2, hashCalls.get(), "the cleanup-failed digest must not enter the memo");

        ResourceProviderComparison.ContentObservation memoHit = identities.observe("shared.bin", provider);
        assertEquals(ResourceProviderComparison.ContentEvidence.HASHED, memoHit.evidence());
        assertEquals(2, hashCalls.get(), "a successfully cleaned stable digest should retain memo semantics");
        assertNoProofArtifacts(outsideRoot);
    }

    @Test
    void actualCleanupFailureWithNestedRootsStaysOutsideEveryIndexedRoot() throws Exception {
        Path outerRoot = Files.createDirectories(temporaryDirectory.resolve("nested-roots/work/mod-a"));
        Path innerRoot = Files.createDirectories(outerRoot.resolve("submod"));
        Path file = Files.writeString(innerRoot.resolve("shared.bin"), "AAAA");
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        ResourceIndex.Provider provider = new ResourceIndex.Provider(
                1,
                "shared.bin",
                attributes.size(),
                Math.max(0, attributes.lastModifiedTime().toMillis()));
        ResourceIndex index = new ResourceIndex(
                "0".repeat(64),
                List.of(
                        new ResourceIndex.Root("outer", outerRoot, false),
                        new ResourceIndex.Root("inner", innerRoot, false)),
                Map.of("shared.bin", List.of(provider)));

        Path outsideEveryRoot = outerRoot.toRealPath().getParent();
        assumeTrue(outsideEveryRoot != null, "requires an ancestor outside both resource roots");
        assumeTrue(
                Files.getFileStore(file).equals(Files.getFileStore(outsideEveryRoot)),
                "requires an all-roots-safe directory on the provider FileStore");
        assumeTrue(
                hardLinkProofAvailable(file, outsideEveryRoot),
                "requires the production hard-link proof primitive");

        ResourceIndexBuilder.BuildResult outerBefore = ResourceIndexBuilder.buildStandalone(outerRoot, "outer");
        ResourceIndexBuilder.BuildResult innerBefore = ResourceIndexBuilder.buildStandalone(innerRoot, "inner");
        AtomicInteger proofAttempts = new AtomicInteger();
        AtomicReference<ResourceProviderContentIdentity.ProofLink> leakedProof = new AtomicReference<>();
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                Hashes::sha256,
                leakFirstProof(proofAttempts, leakedProof));

        ResourceProviderComparison.ContentObservation cleanupFailed = identities.observe("shared.bin", provider);

        assertEquals(ResourceProviderComparison.ContentEvidence.UNREADABLE, cleanupFailed.evidence());
        ResourceProviderContentIdentity.ProofLink leaked = leakedProof.get();
        assertTrue(leaked != null, "the injected failure must leave the nested-root proof behind");
        assertEquals(outsideEveryRoot, leaked.directory().getParent());
        assertTrue(Files.exists(leaked.path()));
        assertTrue(Files.isSameFile(leaked.path(), file));
        for (ResourceIndex.Root root : index.roots()) {
            assertFalse(
                    leaked.directory().startsWith(root.path().toRealPath()),
                    "proof residue must stay outside every indexed root: " + root.id());
        }

        ResourceIndexBuilder.BuildResult outerAfter = ResourceIndexBuilder.buildStandalone(outerRoot, "outer");
        ResourceIndexBuilder.BuildResult innerAfter = ResourceIndexBuilder.buildStandalone(innerRoot, "inner");
        assertSameIndex(outerBefore, outerAfter, "outer nested resource root");
        assertSameIndex(innerBefore, innerAfter, "inner nested resource root");

        leaked.close();
        assertNoProofArtifacts(outsideEveryRoot);

        ResourceProviderComparison.ContentObservation retried = identities.observe("shared.bin", provider);
        assertEquals(ResourceProviderComparison.ContentEvidence.HASHED, retried.evidence());
        assertNoProofArtifacts(outsideEveryRoot);
    }

    private static ResourceProviderContentIdentity.ProofLinkFactory leakFirstProof(
            AtomicInteger proofAttempts,
            AtomicReference<ResourceProviderContentIdentity.ProofLink> leakedProof) {
        return (candidate, directory) -> {
            ResourceProviderContentIdentity.ProofLink real =
                    ResourceProviderContentIdentity.createProofLink(candidate, directory);
            if (real == null || proofAttempts.incrementAndGet() != 1) {
                return real;
            }
            leakedProof.set(real);
            return new ResourceProviderContentIdentity.ProofLink(
                    real.path(),
                    real.directory(),
                    (path, ownedDirectory) -> {
                        throw new IOException("synthetic delete failure before any proof artifact is removed");
                    });
        };
    }

    private static boolean hardLinkProofAvailable(Path file, Path directory) throws IOException {
        ResourceProviderContentIdentity.ProofLink proof =
                ResourceProviderContentIdentity.createProofLink(file, directory);
        if (proof == null) {
            return false;
        }
        try (proof) {
            return Files.isSameFile(proof.path(), file);
        }
    }

    private static void assertSameIndex(
            ResourceIndexBuilder.BuildResult expected,
            ResourceIndexBuilder.BuildResult actual,
            String description) {
        assertEquals(
                expected.index().profileFingerprint(),
                actual.index().profileFingerprint(),
                "a leaked proof namespace must not perturb the " + description + " fingerprint");
        assertEquals(
                expected.index().entries(),
                actual.index().entries(),
                "a leaked provider hard link must not enter the " + description);
    }

    private static void assertNoProofArtifacts(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            assertEquals(
                    0,
                    entries.filter(path -> path.getFileName().toString()
                                    .startsWith(ResourceProviderContentIdentity.PROOF_DIRECTORY_PREFIX))
                            .count(),
                    "proof-link namespaces must be cleaned after a successful observation");
        }
    }
}

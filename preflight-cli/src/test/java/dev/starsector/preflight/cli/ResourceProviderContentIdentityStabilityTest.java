package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceProviderContentIdentityStabilityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void mutationDuringHashIsStaleAndRacedDigestIsNotMemoized() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("mutating/root"));
        Path file = Files.writeString(root.resolve("shared.bin"), "same");
        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        AtomicInteger hashCalls = new AtomicInteger();
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                bytes -> {
                    int call = hashCalls.incrementAndGet();
                    if (call == 1) {
                        Files.writeString(file, "changed-during-hash");
                    }
                    return Hashes.sha256(bytes);
                });

        ResourceProviderComparison.ContentObservation raced = identities.observe("shared.bin", provider);

        assertEquals(ResourceProviderComparison.ContentEvidence.STALE, raced.evidence());
        assertEquals(1, hashCalls.get());

        Files.writeString(file, "same");
        Files.setLastModifiedTime(file, FileTime.fromMillis(provider.modifiedMillis()));
        ResourceProviderComparison.ContentObservation stable = identities.observe("shared.bin", provider);

        assertEquals(ResourceProviderComparison.ContentEvidence.HASHED, stable.evidence());
        assertEquals(2, hashCalls.get(), "the raced digest must not enter the direct-hash memo");
        assertNoProofArtifacts(root);
    }

    @Test
    void subMillisecondMtimeChangeDuringHashIsStaleWhenFilesystemPreservesPrecision() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("submillisecond/root"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        FileTime requestedBefore = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 100_000));
        FileTime requestedAfter = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 900_000));

        Files.setLastModifiedTime(file, requestedBefore);
        FileTime observedBefore = Files.getLastModifiedTime(file);
        Files.setLastModifiedTime(file, requestedAfter);
        FileTime observedAfter = Files.getLastModifiedTime(file);
        if (observedBefore.equals(observedAfter) || observedBefore.toMillis() != observedAfter.toMillis()) {
            return; // This filesystem cannot expose the precision this regression exercises.
        }
        Files.setLastModifiedTime(file, observedBefore);
        if (!observedBefore.equals(Files.getLastModifiedTime(file))) {
            return; // The first precise timestamp cannot be restored reliably on this filesystem.
        }

        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        AtomicInteger hashCalls = new AtomicInteger();
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                bytes -> {
                    int call = hashCalls.incrementAndGet();
                    if (call == 1) {
                        Files.writeString(file, "BBBB");
                        Files.setLastModifiedTime(file, observedAfter);
                    }
                    return Hashes.sha256(bytes);
                });

        ResourceProviderComparison.ContentObservation raced = identities.observe("shared.bin", provider);

        assertEquals(ResourceProviderComparison.ContentEvidence.STALE, raced.evidence());
        assertEquals(1, hashCalls.get());

        Files.writeString(file, "AAAA");
        Files.setLastModifiedTime(file, observedBefore);
        ResourceProviderComparison.ContentObservation stable = identities.observe("shared.bin", provider);

        assertEquals(ResourceProviderComparison.ContentEvidence.HASHED, stable.evidence());
        assertEquals(2, hashCalls.get(), "the precision-raced digest must not enter the direct-hash memo");
        assertNoProofArtifacts(root);
    }

    @Test
    void cachedDigestIsRejectedAfterSameMetadataFileReplacementWhenIdentityIsAvailable() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("replacement/root"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        AtomicInteger hashCalls = new AtomicInteger();
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                bytes -> {
                    hashCalls.incrementAndGet();
                    return Hashes.sha256(bytes);
                });

        assertEquals(
                ResourceProviderComparison.ContentEvidence.HASHED,
                identities.observe("shared.bin", provider).evidence());
        Object originalKey = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        if (originalKey == null) {
            return; // This filesystem cannot safely support the replacement memo check.
        }
        assertEquals(
                ResourceProviderComparison.ContentEvidence.HASHED,
                identities.observe("shared.bin", provider).evidence());
        assertEquals(1, hashCalls.get(), "an unchanged provider with a file identity must reuse the memo");

        Path replacement = Files.writeString(root.resolve("replacement.tmp"), "BBBB");
        Files.setLastModifiedTime(replacement, FileTime.fromMillis(provider.modifiedMillis()));
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(file, FileTime.fromMillis(provider.modifiedMillis()));
        Object replacementKey = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        if (replacementKey == null || Objects.equals(originalKey, replacementKey)) {
            return; // This filesystem exposes no replacement identity stronger than size/mtime.
        }

        ResourceProviderComparison.ContentObservation replaced = identities.observe("shared.bin", provider);

        assertEquals(ResourceProviderComparison.ContentEvidence.STALE, replaced.evidence());
        assertEquals(1, hashCalls.get(), "a changed file identity must invalidate rather than reuse the memo");
        assertNoProofArtifacts(root);
    }

    @Test
    void proofLinkUsesProviderFilesystemWhenDefaultTempWouldBeForeign() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("foreign-temp/root"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        assumeTrue(hardLinkProofAvailable(file), "requires the production hard-link proof primitive");
        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        String expectedDigest = Hashes.sha256(file);

        Path archive = temporaryDirectory.resolve("foreign-temp.zip");
        URI uri = URI.create("jar:" + archive.toUri());
        try (FileSystem filesystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path simulatedDefaultTemp = Files.createDirectories(filesystem.getPath("/tmp"));

            ResourceProviderContentIdentity.ProofLink misplaced =
                    ResourceProviderContentIdentity.createProofLink(file, simulatedDefaultTemp);
            assertNull(
                    misplaced,
                    "the old default-temp placement cannot create a hard link across filesystems");

            AtomicInteger hashCalls = new AtomicInteger();
            ResourceProviderComparison.ContentIdentitySource identities =
                    ResourceProviderContentIdentity.direct(
                            index,
                            bytes -> {
                                hashCalls.incrementAndGet();
                                return Hashes.sha256(bytes);
                            });

            ResourceProviderComparison.ContentObservation observed =
                    identities.observe("shared.bin", provider);

            assertEquals(ResourceProviderComparison.ContentEvidence.HASHED, observed.evidence());
            assertEquals(expectedDigest, observed.sha256());
            assertEquals(1, hashCalls.get(), "a stable uncached provider should be digested once");
            assertNoProofArtifacts(root);
        }
    }

    @Test
    void sameSizeReplacementDuringTheFirstHashNeverPublishesTheReplacementDigest() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("aba/root"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        assumeTrue(hardLinkProofAvailable(file), "requires the production hard-link proof primitive");
        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        String originalDigest = Hashes.sha256(file);
        FileTime originalModified = Files.getLastModifiedTime(file);

        Path parked = root.resolve("parked.tmp");
        Path impostor = Files.writeString(root.resolve("impostor.tmp"), "BBBB");
        Files.setLastModifiedTime(impostor, FileTime.fromMillis(provider.modifiedMillis()));

        // A -> B -> A entirely inside the read, with B the same size and mtime as A. A pathname
        // hasher sees B while endpoint pathname observations can both see A.
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                bytes -> {
                    Files.move(file, parked, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(impostor, file, StandardCopyOption.REPLACE_EXISTING);
                    Files.setLastModifiedTime(file, FileTime.fromMillis(provider.modifiedMillis()));
                    String read = Hashes.sha256(bytes);
                    Files.move(file, impostor, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(parked, file, StandardCopyOption.REPLACE_EXISTING);
                    Files.setLastModifiedTime(file, originalModified);
                    return read;
                });

        ResourceProviderComparison.ContentObservation observed = identities.observe("shared.bin", provider);

        assertEquals("AAAA", Files.readString(file), "the file on disk is the original again");
        assertEquals(ResourceProviderComparison.ContentEvidence.HASHED, observed.evidence());
        assertEquals(
                originalDigest,
                observed.sha256(),
                "a digest published as exact evidence must belong to the anchored provider file");
        assertNoProofArtifacts(root);
    }

    @Test
    void providerWithoutTheHardLinkProofPrimitiveIsConservativelyStale() throws Exception {
        Path archive = temporaryDirectory.resolve("unsupported-proof.zip");
        URI uri = URI.create("jar:" + archive.toUri());
        try (FileSystem filesystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path root = Files.createDirectories(filesystem.getPath("/root"));
            Files.writeString(root.resolve("shared.bin"), "AAAA");
            ResourceIndex.Provider provider = provider(root, "shared.bin");
            ResourceIndex index = index(root, provider);
            AtomicInteger hashCalls = new AtomicInteger();
            ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                    index,
                    bytes -> {
                        hashCalls.incrementAndGet();
                        return Hashes.sha256(bytes);
                    });

            ResourceProviderComparison.ContentObservation observed = identities.observe("shared.bin", provider);

            assertEquals(ResourceProviderComparison.ContentEvidence.STALE, observed.evidence());
            assertEquals(0, hashCalls.get(), "bytes without an exact file-identity proof must not be published");
            assertNoProofArtifacts(root);
        }
    }

    @Test
    void proofLinkIsCleanedWhenHashingThrows() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("hash-failure/root"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        assumeTrue(hardLinkProofAvailable(file), "requires the production hard-link proof primitive");
        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                bytes -> {
                    throw new IOException("synthetic hash failure");
                });

        ResourceProviderComparison.ContentObservation observed = identities.observe("shared.bin", provider);

        assertEquals(ResourceProviderComparison.ContentEvidence.UNREADABLE, observed.evidence());
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

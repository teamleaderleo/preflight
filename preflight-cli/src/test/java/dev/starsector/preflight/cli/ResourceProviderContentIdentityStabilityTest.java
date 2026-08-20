package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceProviderContentIdentityStabilityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void twoUnchangedObservationsReuseOneDigestWithoutSelfInvalidation() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("stable"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        AtomicInteger hashCalls = new AtomicInteger();
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                input -> {
                    hashCalls.incrementAndGet();
                    return Hashes.sha256(input);
                });

        ResourceProviderComparison.ContentObservation first = identities.observe("shared.bin", provider);
        ResourceProviderComparison.ContentObservation second = identities.observe("shared.bin", provider);

        assertEquals(ResourceProviderComparison.ContentEvidence.HASHED, first.evidence());
        assertEquals(ResourceProviderComparison.ContentEvidence.HASHED, second.evidence());
        assertEquals(Hashes.sha256(file), first.sha256());
        assertEquals(first.sha256(), second.sha256());
        assertEquals(1, hashCalls.get(), "unchanged exact evidence should reuse one accepted digest");
    }

    @Test
    void sameInodeMutationDuringHashIsStaleAndRacedDigestIsNotMemoized() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("mutating"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        FileTime modified = Files.getLastModifiedTime(file);
        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        AtomicInteger hashCalls = new AtomicInteger();
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                input -> {
                    int call = hashCalls.incrementAndGet();
                    if (call == 1) {
                        try {
                            Files.writeString(file, "BBBB");
                        } catch (IOException writerRefused) {
                            throw new OpenedFileGenerationAuthority.StaleGenerationException(
                                    "concurrent mutation was refused by the pinned exact-read handle",
                                    writerRefused);
                        }
                        Files.setLastModifiedTime(file, modified);
                        String raced = Hashes.sha256(input);
                        Files.writeString(file, "AAAA");
                        Files.setLastModifiedTime(file, modified);
                        return raced;
                    }
                    return Hashes.sha256(input);
                });

        ResourceProviderComparison.ContentObservation raced = identities.observe("shared.bin", provider);
        assertEquals(ResourceProviderComparison.ContentEvidence.STALE, raced.evidence());
        assertEquals(1, hashCalls.get());

        ResourceIndex.Provider repaired = provider(root, "shared.bin");
        ResourceProviderComparison.ContentIdentitySource retried = ResourceProviderContentIdentity.direct(
                index(root, repaired),
                input -> {
                    hashCalls.incrementAndGet();
                    return Hashes.sha256(input);
                });
        ResourceProviderComparison.ContentObservation stable = retried.observe("shared.bin", repaired);
        assertEquals(ResourceProviderComparison.ContentEvidence.HASHED, stable.evidence());
        assertEquals(2, hashCalls.get(), "the raced digest must not become reusable evidence");
    }

    @Test
    void sameSizeRestoredMtimeMutationAfterIndexBeforeObservationIsStaleWithoutHashing() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("between-index-and-read"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        FileTime modified = Files.getLastModifiedTime(file);

        Files.writeString(file, "BBBB");
        Files.setLastModifiedTime(file, modified);

        AtomicInteger hashCalls = new AtomicInteger();
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                input -> {
                    hashCalls.incrementAndGet();
                    return Hashes.sha256(input);
                });
        ResourceProviderComparison.ContentObservation observed = identities.observe("shared.bin", provider);

        assertEquals(ResourceProviderComparison.ContentEvidence.STALE, observed.evidence());
        assertEquals(0, hashCalls.get(), "indexed-generation mismatch must refuse before reading payload bytes");
    }

    @Test
    void sameMetadataPathReplacementInvalidatesAnExistingMemo() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("replacement"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        AtomicInteger hashCalls = new AtomicInteger();
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                input -> {
                    hashCalls.incrementAndGet();
                    return Hashes.sha256(input);
                });

        assertEquals(
                ResourceProviderComparison.ContentEvidence.HASHED,
                identities.observe("shared.bin", provider).evidence());
        Path replacement = Files.writeString(root.resolve("replacement.tmp"), "BBBB");
        Files.setLastModifiedTime(replacement, FileTime.fromMillis(provider.modifiedMillis()));
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(file, FileTime.fromMillis(provider.modifiedMillis()));

        ResourceProviderComparison.ContentObservation replaced = identities.observe("shared.bin", provider);
        assertEquals(ResourceProviderComparison.ContentEvidence.STALE, replaced.evidence());
        assertEquals(1, hashCalls.get(), "a changed public generation must not reuse or recompute under the stale index");
    }

    @Test
    void pathnameAbaNeverPublishesImpostorBytes() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("path-aba"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        String originalDigest = Hashes.sha256(file);
        String impostorDigest = Hashes.sha256("BBBB".getBytes(StandardCharsets.UTF_8));
        Path parked = root.resolve("parked.tmp");
        Path impostor = Files.writeString(root.resolve("impostor.tmp"), "BBBB");

        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                input -> {
                    Files.move(file, parked, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(impostor, file, StandardCopyOption.REPLACE_EXISTING);
                    String openedDigest = Hashes.sha256(input);
                    Files.move(file, impostor, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(parked, file, StandardCopyOption.REPLACE_EXISTING);
                    return openedDigest;
                });

        ResourceProviderComparison.ContentObservation observed = identities.observe("shared.bin", provider);
        if (observed.evidence() == ResourceProviderComparison.ContentEvidence.HASHED) {
            assertEquals(originalDigest, observed.sha256(),
                    "a successful ABA observation must belong to the pinned original");
            assertNotEquals(impostorDigest, observed.sha256(),
                    "impostor bytes must never publish as exact evidence");
        } else {
            assertEquals(ResourceProviderComparison.ContentEvidence.STALE, observed.evidence());
        }
    }

    @Test
    void providerWithoutGenerationAuthorityIsConservativelyStale() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("missing-authority"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        ResourceIndex.Provider provider = new ResourceIndex.Provider(
                0, "shared.bin", Files.size(file), Files.getLastModifiedTime(file).toMillis());
        ResourceIndex index = index(root, provider);
        AtomicInteger hashCalls = new AtomicInteger();
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                input -> {
                    hashCalls.incrementAndGet();
                    return Hashes.sha256(input);
                });

        ResourceProviderComparison.ContentObservation observed = identities.observe("shared.bin", provider);
        assertEquals(ResourceProviderComparison.ContentEvidence.STALE, observed.evidence());
        assertEquals(0, hashCalls.get());
    }

    @Test
    void unsupportedGenerationProviderIsConservativelyStaleWithoutHashing() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("unsupported-authority"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        ResourceIndex.Provider provider = new ResourceIndex.Provider(
                0,
                "shared.bin",
                Files.size(file),
                Files.getLastModifiedTime(file).toMillis(),
                "unsupported-generation-v1",
                "opaque-token");
        ResourceIndex index = index(root, provider);
        AtomicInteger hashCalls = new AtomicInteger();
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                input -> {
                    hashCalls.incrementAndGet();
                    return Hashes.sha256(input);
                });

        ResourceProviderComparison.ContentObservation observed = identities.observe("shared.bin", provider);
        assertEquals(ResourceProviderComparison.ContentEvidence.STALE, observed.evidence());
        assertEquals(0, hashCalls.get());
    }

    @Test
    void hashFailureDoesNotEnterTheMemo() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("hash-failure"));
        Files.writeString(root.resolve("shared.bin"), "AAAA");
        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        AtomicInteger calls = new AtomicInteger();
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                input -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IOException("synthetic hash failure");
                    }
                    return Hashes.sha256(input);
                });

        assertEquals(
                ResourceProviderComparison.ContentEvidence.UNREADABLE,
                identities.observe("shared.bin", provider).evidence());
        assertEquals(
                ResourceProviderComparison.ContentEvidence.HASHED,
                identities.observe("shared.bin", provider).evidence());
        assertEquals(2, calls.get());
    }

    private static ResourceIndex index(Path root, ResourceIndex.Provider provider) {
        return new ResourceIndex(
                "a".repeat(64),
                List.of(new ResourceIndex.Root("mod", root, false)),
                Map.of("shared.bin", List.of(provider)));
    }

    private static ResourceIndex.Provider provider(Path root, String relative) throws Exception {
        Path file = root.resolve(relative);
        OpenedFileGenerationAuthority.Generation generation = OpenedFileGenerationAuthority.capture(file);
        return new ResourceIndex.Provider(
                0,
                relative,
                Files.size(file),
                Math.max(0, Files.getLastModifiedTime(file).toMillis()),
                generation.provider(),
                generation.token());
    }
}

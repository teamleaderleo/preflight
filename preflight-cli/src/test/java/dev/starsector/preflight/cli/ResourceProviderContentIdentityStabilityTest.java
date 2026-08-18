package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
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

        Path replacement = Files.writeString(root.resolve("replacement.tmp"), "BBBB");
        Files.setLastModifiedTime(replacement, FileTime.fromMillis(provider.modifiedMillis()));
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(file, FileTime.fromMillis(provider.modifiedMillis()));
        Object replacementKey = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        if (originalKey == null || replacementKey == null || Objects.equals(originalKey, replacementKey)) {
            return; // This filesystem exposes no replacement identity stronger than size/mtime.
        }

        ResourceProviderComparison.ContentObservation replaced = identities.observe("shared.bin", provider);

        assertEquals(ResourceProviderComparison.ContentEvidence.STALE, replaced.evidence());
        assertEquals(1, hashCalls.get(), "a changed file identity must invalidate rather than reuse the memo");
    }

    @Test
    void sameSizeReplacementDuringTheFirstHashNeverPublishesTheReplacementDigest() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("aba/root"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        String originalDigest = Hashes.sha256(file);
        Object originalKey = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        FileTime originalModified = Files.getLastModifiedTime(file);

        Path parked = root.resolve("parked.tmp");
        Path impostor = Files.writeString(root.resolve("impostor.tmp"), "BBBB");
        Files.setLastModifiedTime(impostor, FileTime.fromMillis(provider.modifiedMillis()));

        // A -> B -> A entirely inside the read, with B the same size and mtime as A. Both pathname
        // observations therefore see A, and A keeps its identity because a rename preserves it.
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

        Object restoredKey = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        if (originalKey == null || !Objects.equals(originalKey, restoredKey)) {
            return; // Without a stable identity across the rename there is nothing to prove here.
        }
        assertEquals("AAAA", Files.readString(file), "the file on disk is the original again");
        if (observed.evidence() == ResourceProviderComparison.ContentEvidence.HASHED) {
            assertEquals(
                    originalDigest,
                    observed.sha256(),
                    "a digest published as exact evidence must belong to the observed file");
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

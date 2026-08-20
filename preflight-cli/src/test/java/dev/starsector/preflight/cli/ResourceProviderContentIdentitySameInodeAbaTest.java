package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Adversarial #826 regressions for same-inode mutation hidden by restored size/mtime. */
class ResourceProviderContentIdentitySameInodeAbaTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sameInodeMutationRestoredBeforeIdentityRecheckCannotPublishRacedBytes() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("same-inode/root"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");

        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        FileTime originalModified = Files.getLastModifiedTime(file);
        String originalDigest = Hashes.sha256(file);

        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                bytes -> mutateRestoreOrFailClosed(file, originalModified, bytes));

        ResourceProviderComparison.ContentObservation observed = identities.observe("shared.bin", provider);

        assertEquals("AAAA", Files.readString(file), "the public file is restored before observation returns");
        assertEquals(
                ResourceProviderComparison.ContentEvidence.STALE,
                observed.evidence(),
                "same-inode bytes changed during the read must not be published as exact content evidence");
        assertEquals(originalDigest, Hashes.sha256(file), "the public file has the original final content");
        assertEquals(List.of("shared.bin"), names(root), "exact observation must author no helper entry");
    }

    @Test
    void sameInodeMutationAfterIndexBeforeObservationCannotInheritIndexedGeneration() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("pre-observation/root"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");

        ResourceIndex.Provider provider = provider(root, "shared.bin");
        ResourceIndex index = index(root, provider);
        FileTime indexedModified = Files.getLastModifiedTime(file);

        Files.writeString(file, "BBBB");
        Files.setLastModifiedTime(file, indexedModified);

        AtomicInteger hashCalls = new AtomicInteger();
        ResourceProviderComparison.ContentIdentitySource identities = ResourceProviderContentIdentity.direct(
                index,
                bytes -> {
                    hashCalls.incrementAndGet();
                    return Hashes.sha256(bytes);
                });
        ResourceProviderComparison.ContentObservation observed = identities.observe("shared.bin", provider);

        assertEquals("BBBB", Files.readString(file));
        assertEquals(
                ResourceProviderComparison.ContentEvidence.STALE,
                observed.evidence(),
                "a stable newer same-inode generation must not inherit the older ResourceIndex authority");
        assertEquals(0, hashCalls.get(), "generation mismatch must reject before payload hashing");
        assertEquals(List.of("shared.bin"), names(root), "exact observation must author no helper entry");
    }

    private static String mutateRestoreOrFailClosed(Path file, FileTime originalModified, InputStream bytes)
            throws IOException {
        try {
            Files.writeString(file, "BBBB");
        } catch (IOException writerRefused) {
            throw new OpenedFileGenerationAuthority.StaleGenerationException(
                    "the pinned exact-read handle refused the adversarial writer",
                    writerRefused);
        }
        String racedDigest = Hashes.sha256(bytes);
        Files.writeString(file, "AAAA");
        Files.setLastModifiedTime(file, originalModified);
        return racedDigest;
    }

    private static List<String> names(Path root) throws IOException {
        try (var entries = Files.list(root)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
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

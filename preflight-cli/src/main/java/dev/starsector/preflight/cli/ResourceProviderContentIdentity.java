package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact byte-identity sources for resource-provider comparison. */
final class ResourceProviderContentIdentity {
    private final ResourceIndex index;
    private final ProfileIdentityContext profileContext;
    private final DirectHasher directHasher;
    private final Map<Path, DirectHash> directHashes = new HashMap<>();

    private ResourceProviderContentIdentity(
            ResourceIndex index,
            ProfileIdentityContext profileContext,
            DirectHasher directHasher) {
        this.index = index;
        this.profileContext = profileContext;
        this.directHasher = directHasher;
    }

    /**
     * Reads only providers the comparison asks for. Metadata is checked first so a stale persisted
     * index becomes ambiguous instead of classifying bytes from a different profile snapshot.
     */
    static ResourceProviderComparison.ContentIdentitySource direct(ResourceIndex index) {
        return direct(index, Hashes::sha256);
    }

    /** Test seam for changing a provider while its bytes are being hashed. */
    static ResourceProviderComparison.ContentIdentitySource direct(
            ResourceIndex index, DirectHasher hasher) {
        ResourceProviderContentIdentity source =
                new ResourceProviderContentIdentity(index, null, Objects.requireNonNull(hasher, "hasher"));
        return source::observeDirect;
    }

    /**
     * Reuses the exact per-launch provider resolution and SHA-256 memo already owned by the shared
     * profile identity context. Callers must pass the current index represented by that context.
     */
    static ResourceProviderComparison.ContentIdentitySource cached(ProfileIdentityContext context) {
        ResourceProviderContentIdentity source =
                new ResourceProviderContentIdentity(context.resources(), context, Hashes::sha256);
        return source::observeCached;
    }

    private ResourceProviderComparison.ContentObservation observeDirect(
            String logicalPath, ResourceIndex.Provider provider) {
        try {
            Path proofLink = null;
            try {
                Path file = index.resolveExisting(provider).toAbsolutePath().normalize();
                BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
                if (!before.isRegularFile()) {
                    return ResourceProviderComparison.ContentObservation.unreadable();
                }
                if (!matchesIndexedMetadata(provider, before)) {
                    return ResourceProviderComparison.ContentObservation.stale();
                }

                FileIdentity beforeIdentity = FileIdentity.from(before);
                DirectHash cached = directHashes.get(file);
                if (cached != null) {
                    if (!cached.identity().equals(beforeIdentity)) {
                        directHashes.remove(file);
                        return ResourceProviderComparison.ContentObservation.stale();
                    }
                    return ResourceProviderComparison.ContentObservation.hashed(cached.sha256());
                }

                /*
                 * The portable Java 17 channel APIs expose no identity for an already-open handle.
                 * Anchor the selected entry under a second name instead: a hard link keeps naming
                 * the same file if the provider pathname is replaced, and isSameFile ties that
                 * anchored file back to the provider pathname before and after the read. If the
                 * provider and temporary directory cannot support that proof, exact evidence is
                 * unavailable and the caller gets conservative stale evidence.
                 */
                proofLink = createProofLink(file);
                if (proofLink == null) {
                    return ResourceProviderComparison.ContentObservation.stale();
                }

                BasicFileAttributes proofBefore = Files.readAttributes(proofLink, BasicFileAttributes.class);
                FileIdentity proofIdentity = FileIdentity.from(proofBefore);
                if (!proofBefore.isRegularFile()
                        || !matchesIndexedMetadata(provider, proofBefore)
                        || !beforeIdentity.equals(proofIdentity)
                        || !Files.isSameFile(file, proofLink)) {
                    return ResourceProviderComparison.ContentObservation.stale();
                }

                String digest;
                try (InputStream bytes = Files.newInputStream(proofLink)) {
                    digest = directHasher.sha256(bytes);
                }

                Path afterFile = index.resolveExisting(provider).toAbsolutePath().normalize();
                if (!file.equals(afterFile)) {
                    return ResourceProviderComparison.ContentObservation.stale();
                }

                BasicFileAttributes proofAfter = Files.readAttributes(proofLink, BasicFileAttributes.class);
                BasicFileAttributes after = Files.readAttributes(afterFile, BasicFileAttributes.class);
                FileIdentity proofAfterIdentity = FileIdentity.from(proofAfter);
                FileIdentity afterIdentity = FileIdentity.from(after);
                if (!proofAfter.isRegularFile()
                        || !after.isRegularFile()
                        || !matchesIndexedMetadata(provider, proofAfter)
                        || !matchesIndexedMetadata(provider, after)
                        || !proofIdentity.equals(proofAfterIdentity)
                        || !proofAfterIdentity.equals(afterIdentity)
                        || !Files.isSameFile(afterFile, proofLink)) {
                    return ResourceProviderComparison.ContentObservation.stale();
                }

                // A null file key cannot safely distinguish a later same-metadata replacement, so
                // exact evidence can still be returned for this read but must not enter the memo.
                if (afterIdentity.fileKey() != null) {
                    directHashes.put(file, new DirectHash(digest, afterIdentity));
                }
                return ResourceProviderComparison.ContentObservation.hashed(digest);
            } finally {
                if (proofLink != null) {
                    Files.deleteIfExists(proofLink);
                }
            }
        } catch (NoSuchFileException missing) {
            return ResourceProviderComparison.ContentObservation.missing();
        } catch (IllegalArgumentException invalidPath) {
            return ResourceProviderComparison.ContentObservation.invalidPath();
        } catch (IOException unreadable) {
            return ResourceProviderComparison.ContentObservation.unreadable();
        }
    }

    private ResourceProviderComparison.ContentObservation observeCached(
            String logicalPath, ResourceIndex.Provider provider) {
        try {
            Path file = profileContext.resolve(provider);
            String digest = profileContext.sha256All(List.of(file)).get(0);
            return ResourceProviderComparison.ContentObservation.hashed(digest);
        } catch (NoSuchFileException missing) {
            return ResourceProviderComparison.ContentObservation.missing();
        } catch (IllegalArgumentException invalidPath) {
            return ResourceProviderComparison.ContentObservation.invalidPath();
        } catch (IOException unreadable) {
            return ResourceProviderComparison.ContentObservation.unreadable();
        }
    }

    private static Path createProofLink(Path file) throws IOException {
        Path candidate = null;
        boolean linked = false;
        try {
            candidate = Files.createTempFile("preflight-provider-proof-", ".link");
            Files.delete(candidate);
            Files.createLink(candidate, file);
            linked = true;
            return candidate;
        } catch (UnsupportedOperationException | SecurityException | ProviderMismatchException | IOException unavailable) {
            return null;
        } finally {
            if (!linked && candidate != null) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private static boolean matchesIndexedMetadata(
            ResourceIndex.Provider provider, BasicFileAttributes attributes) {
        long modifiedMillis = Math.max(0, attributes.lastModifiedTime().toMillis());
        return attributes.size() == provider.size() && modifiedMillis == provider.modifiedMillis();
    }

    /** Digests an already-open stream supplied by the exact-evidence observer. */
    @FunctionalInterface
    interface DirectHasher {
        String sha256(InputStream bytes) throws IOException;
    }

    private record DirectHash(String sha256, FileIdentity identity) {
    }

    /**
     * Same-read stability keeps the filesystem's full timestamp precision. The persisted index has
     * a millisecond metadata contract, which remains isolated in {@link #matchesIndexedMetadata}.
     */
    private record FileIdentity(long size, FileTime modified, Object fileKey) {
        static FileIdentity from(BasicFileAttributes attributes) {
            return new FileIdentity(
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    attributes.fileKey());
        }
    }
}

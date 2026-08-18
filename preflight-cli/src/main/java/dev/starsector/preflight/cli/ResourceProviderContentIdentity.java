package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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
            Path file = index.resolveExisting(provider).toAbsolutePath().normalize();
            /*
             * Opened before anything is observed about it, and never re-opened by name.
             *
             * Two pathname stats around a read prove only that the name pointed at the same entry
             * at two instants. They cannot see a same-size, same-timestamp file moved over the name
             * for the duration of the read and moved away again: both stats observe the original,
             * the original keeps its identity because a rename preserves it, and the digest that
             * gets published belongs to bytes that were never there before or after. Holding one
             * handle open across the whole observation is what closes that window -- a rename over
             * the name does not move an open handle, so the bytes hashed here are the bytes of the
             * entry this stream was opened on.
             */
            try (InputStream bytes = Files.newInputStream(file)) {
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

                String digest = directHasher.sha256(bytes);
                Path afterFile = index.resolveExisting(provider).toAbsolutePath().normalize();
                if (!file.equals(afterFile)) {
                    return ResourceProviderComparison.ContentObservation.stale();
                }
                BasicFileAttributes after = Files.readAttributes(afterFile, BasicFileAttributes.class);
                if (!after.isRegularFile()
                        || !matchesIndexedMetadata(provider, after)
                        || !beforeIdentity.equals(FileIdentity.from(after))) {
                    return ResourceProviderComparison.ContentObservation.stale();
                }

                // Publish to the memo only after the pathname, indexed metadata, and filesystem
                // identity are stable across the complete read. A raced digest must never become
                // reusable evidence.
                directHashes.put(file, new DirectHash(digest, FileIdentity.from(after)));
                return ResourceProviderComparison.ContentObservation.hashed(digest);
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

    private static boolean matchesIndexedMetadata(
            ResourceIndex.Provider provider, BasicFileAttributes attributes) {
        long modifiedMillis = Math.max(0, attributes.lastModifiedTime().toMillis());
        return attributes.size() == provider.size() && modifiedMillis == provider.modifiedMillis();
    }

    /** Digests an already-open handle, so no implementation can re-open the provider by name. */
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

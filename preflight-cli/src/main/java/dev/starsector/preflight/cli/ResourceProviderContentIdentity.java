package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exact byte-identity sources for resource-provider comparison. */
final class ResourceProviderContentIdentity {
    private final ResourceIndex index;
    private final ProfileIdentityContext profileContext;
    private final Map<Path, String> directHashes = new HashMap<>();

    private ResourceProviderContentIdentity(ResourceIndex index, ProfileIdentityContext profileContext) {
        this.index = index;
        this.profileContext = profileContext;
    }

    /**
     * Reads only providers the comparison asks for. Metadata is checked first so a stale persisted
     * index becomes ambiguous instead of classifying bytes from a different profile snapshot.
     */
    static ResourceProviderComparison.ContentIdentitySource direct(ResourceIndex index) {
        ResourceProviderContentIdentity source = new ResourceProviderContentIdentity(index, null);
        return source::observeDirect;
    }

    /**
     * Reuses the exact per-launch provider resolution and SHA-256 memo already owned by the shared
     * profile identity context. Callers must pass the current index represented by that context.
     */
    static ResourceProviderComparison.ContentIdentitySource cached(ProfileIdentityContext context) {
        ResourceProviderContentIdentity source =
                new ResourceProviderContentIdentity(context.resources(), context);
        return source::observeCached;
    }

    private ResourceProviderComparison.ContentObservation observeDirect(
            String logicalPath, ResourceIndex.Provider provider) {
        try {
            Path file = index.resolveExisting(provider);
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                return ResourceProviderComparison.ContentObservation.unreadable();
            }
            long modifiedMillis = Math.max(0, attributes.lastModifiedTime().toMillis());
            if (attributes.size() != provider.size() || modifiedMillis != provider.modifiedMillis()) {
                return ResourceProviderComparison.ContentObservation.stale();
            }
            Path key = file.toAbsolutePath().normalize();
            String digest = directHashes.get(key);
            if (digest == null) {
                digest = Hashes.sha256(key);
                directHashes.put(key, digest);
            }
            return ResourceProviderComparison.ContentObservation.hashed(digest);
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
}

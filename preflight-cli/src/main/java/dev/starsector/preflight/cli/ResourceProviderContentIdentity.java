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
import java.util.HashMap;
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

    /** Test seam for changing a provider while its already-open bytes are being hashed. */
    static ResourceProviderComparison.ContentIdentitySource direct(
            ResourceIndex index, DirectHasher hasher) {
        ResourceProviderContentIdentity source = new ResourceProviderContentIdentity(
                index, null, Objects.requireNonNull(hasher, "hasher"));
        return source::observeDirect;
    }

    /**
     * Reuses the exact per-launch provider resolution and SHA-256 memo owned by the shared profile
     * identity context. The provider itself selects the persisted generation authority.
     */
    static ResourceProviderComparison.ContentIdentitySource cached(ProfileIdentityContext context) {
        ResourceProviderContentIdentity source = new ResourceProviderContentIdentity(
                context.resources(), context, Hashes::sha256);
        return source::observeCached;
    }

    private ResourceProviderComparison.ContentObservation observeDirect(
            String logicalPath, ResourceIndex.Provider provider) {
        try {
            Path file = index.resolveExisting(provider).toAbsolutePath().normalize();
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
            if (!before.isRegularFile()) {
                return ResourceProviderComparison.ContentObservation.unreadable();
            }
            if (!matchesIndexedMetadata(provider, before) || !provider.hasGenerationAuthority()) {
                return ResourceProviderComparison.ContentObservation.stale();
            }

            OpenedFileGenerationAuthority.Generation expected = generation(provider);
            DirectHash cached = directHashes.get(file);
            if (cached != null) {
                if (!cached.generation().equals(expected)) {
                    directHashes.remove(file);
                    return ResourceProviderComparison.ContentObservation.stale();
                }
                OpenedFileGenerationAuthority.requireCurrent(file, expected);
                return ResourceProviderComparison.ContentObservation.hashed(cached.sha256());
            }

            OpenedFileGenerationAuthority.HashEvidence evidence =
                    OpenedFileGenerationAuthority.hash(
                            file,
                            expected,
                            (ignored, input) -> directHasher.sha256(input));
            directHashes.put(file, new DirectHash(evidence.sha256(), evidence.generation()));
            return ResourceProviderComparison.ContentObservation.hashed(evidence.sha256());
        } catch (OpenedFileGenerationAuthority.StaleGenerationException stale) {
            return ResourceProviderComparison.ContentObservation.stale();
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
            return ResourceProviderComparison.ContentObservation.hashed(
                    profileContext.sha256(provider));
        } catch (OpenedFileGenerationAuthority.StaleGenerationException stale) {
            return ResourceProviderComparison.ContentObservation.stale();
        } catch (NoSuchFileException missing) {
            return ResourceProviderComparison.ContentObservation.missing();
        } catch (IllegalArgumentException invalidPath) {
            return ResourceProviderComparison.ContentObservation.invalidPath();
        } catch (IOException unreadable) {
            return ResourceProviderComparison.ContentObservation.unreadable();
        }
    }

    private static OpenedFileGenerationAuthority.Generation generation(ResourceIndex.Provider provider) {
        return new OpenedFileGenerationAuthority.Generation(
                provider.generationProvider(), provider.generationToken());
    }

    private static boolean matchesIndexedMetadata(
            ResourceIndex.Provider provider, BasicFileAttributes attributes) {
        long modifiedMillis = Math.max(0, attributes.lastModifiedTime().toMillis());
        return attributes.size() == provider.size() && modifiedMillis == provider.modifiedMillis();
    }

    /** Digests the already-open stream supplied by the exact-evidence observer. */
    @FunctionalInterface
    interface DirectHasher {
        String sha256(InputStream bytes) throws IOException;
    }

    private record DirectHash(
            String sha256,
            OpenedFileGenerationAuthority.Generation generation) {
    }
}

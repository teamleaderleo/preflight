package dev.starsector.preflight.core;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Opaque per-source generation evidence captured while a prepared texture manifest is built.
 *
 * <p>The provider owns the token semantics. Preflight persists tokens only after the prepared
 * source snapshot has been proven to hash to the manifest entry and after the provider confirms
 * that the source generation stayed unchanged across preparation. Launch re-asks the same provider
 * whether each token still identifies the current source generation before the game JVM starts.
 */
public final class TextureSourceGenerationProof {
    public static final int FORMAT_VERSION = 1;

    private final String profileFingerprint;
    private final String manifestSha256;
    private final String provider;
    private final NavigableMap<String, String> entries;

    public TextureSourceGenerationProof(
            String profileFingerprint,
            String manifestSha256,
            String provider,
            Map<String, String> entries) {
        if (profileFingerprint == null || profileFingerprint.isBlank()) {
            throw new IllegalArgumentException("profileFingerprint is required");
        }
        Hashes.decodeSha256(manifestSha256);
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("generation provider is required");
        }
        this.profileFingerprint = profileFingerprint;
        this.manifestSha256 = manifestSha256.toLowerCase(java.util.Locale.ROOT);
        this.provider = provider;

        TreeMap<String, String> copy = new TreeMap<>();
        for (Map.Entry<String, String> source : entries.entrySet()) {
            String path = ResourceIndex.normalizeLogicalPath(source.getKey());
            String token = Objects.requireNonNull(source.getValue(), "generation token");
            if (token.isBlank()) {
                throw new IllegalArgumentException("generation token may not be blank: " + path);
            }
            if (copy.put(path, token) != null) {
                throw new IllegalArgumentException("Duplicate generation proof path: " + path);
            }
        }
        this.entries = Collections.unmodifiableNavigableMap(copy);
    }

    public String profileFingerprint() {
        return profileFingerprint;
    }

    public String manifestSha256() {
        return manifestSha256;
    }

    public String provider() {
        return provider;
    }

    public NavigableMap<String, String> entries() {
        return entries;
    }

    public int entryCount() {
        return entries.size();
    }

    public Optional<String> entry(String logicalPath) {
        return Optional.ofNullable(entries.get(ResourceIndex.normalizeLogicalPath(logicalPath)));
    }
}

package dev.starsector.preflight.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable mapping from Starsector logical resource paths to ordered physical providers.
 *
 * <p>Provider order is resolution order: core first, then enabled mods. The last provider is
 * therefore the winning provider for ordinary override lookup.</p>
 */
public final class ResourceIndex {
    public static final int FORMAT_VERSION = 1;

    private final String profileFingerprint;
    private final List<Root> roots;
    private final NavigableMap<String, List<Provider>> entries;
    private final long providerCount;

    public ResourceIndex(
            String profileFingerprint,
            List<Root> roots,
            Map<String, ? extends List<Provider>> entries) {
        if (profileFingerprint == null || profileFingerprint.isBlank()) {
            throw new IllegalArgumentException("profileFingerprint is required");
        }
        this.profileFingerprint = profileFingerprint;
        this.roots = List.copyOf(roots);
        if (this.roots.isEmpty()) {
            throw new IllegalArgumentException("At least one resource root is required");
        }

        TreeMap<String, List<Provider>> copy = new TreeMap<>();
        long providers = 0;
        for (Map.Entry<String, ? extends List<Provider>> entry : entries.entrySet()) {
            String key = normalizeLogicalPath(entry.getKey());
            List<Provider> value = List.copyOf(entry.getValue());
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Resource entry has no providers: " + key);
            }
            int previousRoot = -1;
            for (Provider provider : value) {
                if (provider.rootIndex() < 0 || provider.rootIndex() >= this.roots.size()) {
                    throw new IllegalArgumentException("Provider root index is out of range for " + key);
                }
                if (provider.rootIndex() < previousRoot) {
                    throw new IllegalArgumentException("Provider roots are out of resolution order for " + key);
                }
                previousRoot = provider.rootIndex();
                normalizeRelativePath(provider.relativePath());
            }
            List<Provider> prior = copy.put(key, value);
            if (prior != null) {
                throw new IllegalArgumentException("Duplicate normalized resource path: " + key);
            }
            providers += value.size();
        }
        this.entries = Collections.unmodifiableNavigableMap(copy);
        this.providerCount = providers;
    }

    public String profileFingerprint() {
        return profileFingerprint;
    }

    public List<Root> roots() {
        return roots;
    }

    public NavigableMap<String, List<Provider>> entries() {
        return entries;
    }

    public int entryCount() {
        return entries.size();
    }

    public long providerCount() {
        return providerCount;
    }

    public List<Provider> providers(String logicalPath) {
        List<Provider> providers = entries.get(normalizeLogicalPath(logicalPath));
        return providers == null ? List.of() : providers;
    }

    public Optional<Provider> winner(String logicalPath) {
        List<Provider> providers = providers(logicalPath);
        return providers.isEmpty() ? Optional.empty() : Optional.of(providers.get(providers.size() - 1));
    }

    public Optional<Path> winningFile(String logicalPath) {
        return winner(logicalPath).map(this::resolve);
    }

    public Path resolve(Provider provider) {
        if (provider.rootIndex() < 0 || provider.rootIndex() >= roots.size()) {
            throw new IllegalArgumentException("Provider root index is out of range: " + provider.rootIndex());
        }
        Root root = roots.get(provider.rootIndex());
        Path rootPath = root.path().toAbsolutePath().normalize();
        Path resolved = rootPath.resolve(provider.relativePath()).normalize();
        if (!resolved.startsWith(rootPath)) {
            throw new IllegalArgumentException("Provider escapes its resource root: " + provider.relativePath());
        }
        return resolved;
    }

    /** Resolves an existing provider after following links and checking the resulting real path. */
    public Path resolveExisting(Provider provider) throws IOException {
        Path resolved = resolve(provider);
        return PathContainment.existingInside(roots.get(provider.rootIndex()).path(), resolved);
    }

    public static String normalizeLogicalPath(String raw) {
        return normalize(raw).toLowerCase(Locale.ROOT);
    }

    public static String normalizeRelativePath(String raw) {
        return normalize(raw);
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Resource path is required");
        }
        int length = raw.length();
        char first = raw.charAt(0);
        if (first == '/' || first == '\\'
                || (length >= 2 && asciiLetter(first) && raw.charAt(1) == ':')) {
            throw new IllegalArgumentException("Resource path must be relative: " + raw);
        }

        boolean rewrite = false;
        int segmentStart = 0;
        for (int index = 0; index <= length; index++) {
            char current = index == length ? '/' : raw.charAt(index);
            if (current != '/' && current != '\\') {
                continue;
            }

            int segmentLength = index - segmentStart;
            if ((index < length && current == '\\') || segmentLength == 0) {
                rewrite = true;
            }
            if (segmentLength == 1 && raw.charAt(segmentStart) == '.') {
                rewrite = true;
                segmentStart = index + 1;
                continue;
            }
            if (segmentLength == 2
                    && raw.charAt(segmentStart) == '.'
                    && raw.charAt(segmentStart + 1) == '.') {
                throw new IllegalArgumentException("Resource path may not contain '..': " + raw);
            }
            segmentStart = index + 1;
        }
        if (!rewrite) {
            return raw;
        }

        StringBuilder normalized = new StringBuilder(length);
        segmentStart = 0;
        for (int index = 0; index <= length; index++) {
            char current = index == length ? '/' : raw.charAt(index);
            if (current != '/' && current != '\\') {
                continue;
            }
            int segmentLength = index - segmentStart;
            if (segmentLength > 0
                    && !(segmentLength == 1 && raw.charAt(segmentStart) == '.')) {
                if (!normalized.isEmpty()) {
                    normalized.append('/');
                }
                normalized.append(raw, segmentStart, index);
            }
            segmentStart = index + 1;
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Resource path is empty after normalization: " + raw);
        }
        return normalized.toString();
    }

    private static boolean asciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    public record Root(String id, Path path, boolean core) {
        public Root {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Root id is required");
            }
            if (path == null) {
                throw new IllegalArgumentException("Root path is required");
            }
            path = path.toAbsolutePath().normalize();
        }
    }

    public record Provider(int rootIndex, String relativePath, long size, long modifiedMillis) {
        public Provider {
            relativePath = normalizeRelativePath(relativePath);
            if (size < 0) {
                throw new IllegalArgumentException("Provider size may not be negative");
            }
            if (modifiedMillis < 0) {
                throw new IllegalArgumentException("Provider modification time may not be negative");
            }
        }
    }
}

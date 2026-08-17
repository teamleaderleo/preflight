package dev.starsector.preflight.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Classifies logical resource paths that have more than one ordered provider.
 *
 * <p>The resource index is authoritative for provider order and the selected winner. Content
 * equivalence is deliberately supplied by a separate authority: callers must provide an exact
 * content identity for each provider, or an explicit reason that identity could not be established.
 * Filename, provider ID, size, modification time, and display labels never prove equivalence here.
 */
public final class ResourceProviderComparison {
    public static final int DEFAULT_PATH_SAMPLE_LIMIT = 25;
    public static final int DEFAULT_PROVIDER_SAMPLE_LIMIT = 12;

    private ResourceProviderComparison() {
    }

    public static Result analyze(ResourceIndex index, ContentIdentitySource identities) {
        return analyze(index, identities, DEFAULT_PATH_SAMPLE_LIMIT, DEFAULT_PROVIDER_SAMPLE_LIMIT);
    }

    public static Result analyze(
            ResourceIndex index,
            ContentIdentitySource identities,
            int pathSampleLimit,
            int providerSampleLimit) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(identities, "identities");
        if (pathSampleLimit < 1) {
            throw new IllegalArgumentException("pathSampleLimit must be positive");
        }
        if (providerSampleLimit < 1) {
            throw new IllegalArgumentException("providerSampleLimit must be positive");
        }

        long multiProviderLogicalPaths = 0;
        long identicalDuplicates = 0;
        long differingOverrides = 0;
        long ambiguousComparisons = 0;
        List<PathComparison> identicalSamples = new ArrayList<>();
        List<PathComparison> overrideSamples = new ArrayList<>();
        List<PathComparison> ambiguousSamples = new ArrayList<>();

        for (Map.Entry<String, List<ResourceIndex.Provider>> entry : index.entries().entrySet()) {
            List<ResourceIndex.Provider> providers = entry.getValue();
            if (providers.size() < 2) {
                continue;
            }
            multiProviderLogicalPaths = Math.addExact(multiProviderLogicalPaths, 1);
            PathComparison comparison = compare(
                    index, entry.getKey(), providers, identities, providerSampleLimit);
            switch (comparison.classification()) {
                case IDENTICAL_DUPLICATE -> {
                    identicalDuplicates = Math.addExact(identicalDuplicates, 1);
                    addSample(identicalSamples, pathSampleLimit, comparison);
                }
                case DIFFERING_OVERRIDE -> {
                    differingOverrides = Math.addExact(differingOverrides, 1);
                    addSample(overrideSamples, pathSampleLimit, comparison);
                }
                case AMBIGUOUS -> {
                    ambiguousComparisons = Math.addExact(ambiguousComparisons, 1);
                    addSample(ambiguousSamples, pathSampleLimit, comparison);
                }
            }
        }

        return new Result(
                multiProviderLogicalPaths,
                identicalDuplicates,
                differingOverrides,
                ambiguousComparisons,
                pathSampleLimit,
                providerSampleLimit,
                identicalSamples,
                overrideSamples,
                ambiguousSamples);
    }

    private static PathComparison compare(
            ResourceIndex index,
            String logicalPath,
            List<ResourceIndex.Provider> providers,
            ContentIdentitySource identities,
            int providerSampleLimit) {
        List<ContentObservation> observations = new ArrayList<>(providers.size());
        boolean allHashed = true;
        boolean identical = true;
        String firstHash = null;
        for (ResourceIndex.Provider provider : providers) {
            ContentObservation observation = Objects.requireNonNull(
                    identities.observe(logicalPath, provider),
                    "content identity source returned null");
            observations.add(observation);
            if (observation.evidence() != ContentEvidence.HASHED) {
                allHashed = false;
                continue;
            }
            if (firstHash == null) {
                firstHash = observation.sha256();
            } else if (!firstHash.equals(observation.sha256())) {
                identical = false;
            }
        }

        Classification classification = !allHashed
                ? Classification.AMBIGUOUS
                : identical ? Classification.IDENTICAL_DUPLICATE : Classification.DIFFERING_OVERRIDE;
        int winnerOrder = providers.size() - 1;
        ResourceIndex.Provider winner = providers.get(winnerOrder);
        ResourceIndex.Root winnerRoot = index.roots().get(winner.rootIndex());
        List<ProviderSummary> providerSamples = boundedProviders(
                index, providers, observations, providerSampleLimit);
        return new PathComparison(
                logicalPath,
                classification,
                winnerRoot.id(),
                winnerOrder,
                winner.rootIndex(),
                providers.size(),
                providerSamples,
                providers.size() > providerSamples.size());
    }

    private static List<ProviderSummary> boundedProviders(
            ResourceIndex index,
            List<ResourceIndex.Provider> providers,
            List<ContentObservation> observations,
            int limit) {
        int count = providers.size();
        List<ProviderSummary> result = new ArrayList<>(Math.min(count, limit));
        if (count <= limit) {
            for (int order = 0; order < count; order++) {
                result.add(providerSummary(index, providers, observations, order));
            }
            return List.copyOf(result);
        }
        if (limit > 1) {
            for (int order = 0; order < limit - 1; order++) {
                result.add(providerSummary(index, providers, observations, order));
            }
        }
        result.add(providerSummary(index, providers, observations, count - 1));
        return List.copyOf(result);
    }

    private static ProviderSummary providerSummary(
            ResourceIndex index,
            List<ResourceIndex.Provider> providers,
            List<ContentObservation> observations,
            int order) {
        ResourceIndex.Provider provider = providers.get(order);
        ResourceIndex.Root root = index.roots().get(provider.rootIndex());
        return new ProviderSummary(
                root.id(),
                order,
                provider.rootIndex(),
                root.core(),
                order == providers.size() - 1,
                observations.get(order).evidence());
    }

    private static void addSample(List<PathComparison> samples, int limit, PathComparison comparison) {
        if (samples.size() < limit) {
            samples.add(comparison);
        }
    }

    @FunctionalInterface
    public interface ContentIdentitySource {
        ContentObservation observe(String logicalPath, ResourceIndex.Provider provider);
    }

    public enum Classification {
        IDENTICAL_DUPLICATE("identical-duplicate"),
        DIFFERING_OVERRIDE("differing-override"),
        AMBIGUOUS("ambiguous");

        private final String label;

        Classification(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Evidence state for one provider's byte comparison. */
    public enum ContentEvidence {
        HASHED("hashed"),
        MISSING("missing"),
        UNREADABLE("unreadable"),
        INVALID_PATH("invalid-path"),
        STALE("stale");

        private final String label;

        ContentEvidence(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Exact content observation used for classification. The digest is internal evidence and is
     * intentionally omitted from the public map emitted by {@link Result#toPublicMap()}.
     */
    public record ContentObservation(ContentEvidence evidence, String sha256) {
        public ContentObservation {
            Objects.requireNonNull(evidence, "evidence");
            if (evidence == ContentEvidence.HASHED) {
                if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
                    throw new IllegalArgumentException("hashed content requires a SHA-256 identity");
                }
                sha256 = sha256.toLowerCase(java.util.Locale.ROOT);
            } else if (sha256 != null) {
                throw new IllegalArgumentException("non-hashed content may not carry a SHA-256 identity");
            }
        }

        public static ContentObservation hashed(String sha256) {
            return new ContentObservation(ContentEvidence.HASHED, sha256);
        }

        public static ContentObservation missing() {
            return new ContentObservation(ContentEvidence.MISSING, null);
        }

        public static ContentObservation unreadable() {
            return new ContentObservation(ContentEvidence.UNREADABLE, null);
        }

        public static ContentObservation invalidPath() {
            return new ContentObservation(ContentEvidence.INVALID_PATH, null);
        }

        public static ContentObservation stale() {
            return new ContentObservation(ContentEvidence.STALE, null);
        }
    }

    /** Public-safe provider chain element: IDs and ordering only, never physical paths or digests. */
    public record ProviderSummary(
            String providerId,
            int providerOrder,
            int rootOrder,
            boolean core,
            boolean winner,
            ContentEvidence evidence) {
        public ProviderSummary {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("providerId is required");
            }
            Objects.requireNonNull(evidence, "evidence");
        }

        public Map<String, Object> toPublicMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("providerId", providerId);
            values.put("providerOrder", providerOrder);
            values.put("rootOrder", rootOrder);
            values.put("core", core);
            values.put("winner", winner);
            values.put("evidence", evidence.label());
            return values;
        }
    }

    /** One bounded logical-path comparison. */
    public record PathComparison(
            String logicalPath,
            Classification classification,
            String winnerProviderId,
            int winnerProviderOrder,
            int winnerRootOrder,
            int providerCount,
            List<ProviderSummary> providers,
            boolean providersTruncated) {
        public PathComparison {
            Objects.requireNonNull(logicalPath, "logicalPath");
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(winnerProviderId, "winnerProviderId");
            providers = List.copyOf(providers);
        }

        public Map<String, Object> toPublicMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("path", logicalPath);
            values.put("classification", classification.label());
            values.put("winnerProviderId", winnerProviderId);
            values.put("winnerProviderOrder", winnerProviderOrder);
            values.put("winnerRootOrder", winnerRootOrder);
            values.put("providerCount", providerCount);
            values.put("providers", providers.stream().map(ProviderSummary::toPublicMap).toList());
            values.put("providersTruncated", providersTruncated);
            return values;
        }
    }

    /** Exact category counts plus separately bounded samples for each category. */
    public record Result(
            long multiProviderLogicalPaths,
            long identicalDuplicates,
            long differingOverrides,
            long ambiguousComparisons,
            int pathSampleLimit,
            int providerSampleLimit,
            List<PathComparison> identicalSamples,
            List<PathComparison> overrideSamples,
            List<PathComparison> ambiguousSamples) {
        public Result {
            identicalSamples = List.copyOf(identicalSamples);
            overrideSamples = List.copyOf(overrideSamples);
            ambiguousSamples = List.copyOf(ambiguousSamples);
            long reconciled = Math.addExact(
                    Math.addExact(identicalDuplicates, differingOverrides), ambiguousComparisons);
            if (multiProviderLogicalPaths != reconciled) {
                throw new IllegalArgumentException("comparison counts do not reconcile");
            }
        }

        public Map<String, Object> toPublicMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("multiProviderLogicalPaths", multiProviderLogicalPaths);
            values.put("identicalDuplicates", identicalDuplicates);
            values.put("differingOverrides", differingOverrides);
            values.put("ambiguousComparisons", ambiguousComparisons);
            values.put("pathSampleLimit", pathSampleLimit);
            values.put("providerSampleLimit", providerSampleLimit);

            Map<String, Object> samples = new LinkedHashMap<>();
            samples.put("identicalDuplicates", identicalSamples.stream()
                    .map(PathComparison::toPublicMap).toList());
            samples.put("differingOverrides", overrideSamples.stream()
                    .map(PathComparison::toPublicMap).toList());
            samples.put("ambiguousComparisons", ambiguousSamples.stream()
                    .map(PathComparison::toPublicMap).toList());
            values.put("samples", samples);

            Map<String, Object> truncated = new LinkedHashMap<>();
            truncated.put("identicalDuplicates", identicalDuplicates > identicalSamples.size());
            truncated.put("differingOverrides", differingOverrides > overrideSamples.size());
            truncated.put("ambiguousComparisons", ambiguousComparisons > ambiguousSamples.size());
            values.put("truncated", truncated);
            return values;
        }
    }
}

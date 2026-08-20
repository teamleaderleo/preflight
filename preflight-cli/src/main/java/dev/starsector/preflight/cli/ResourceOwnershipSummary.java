package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.ResourceIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only explanation of ordinary ResourceIndex provider resolution. This is file/provider
 * ownership only: merged CSV rows and runtime-generated game objects need their own evidence models.
 */
final class ResourceOwnershipSummary {
    private static final int MAX_ROOTS = 512;
    private static final int MAX_OVERRIDE_DETAILS = 512;
    private static final int MAX_PROVIDERS_PER_DETAIL = 32;
    private static final String EVIDENCE_KIND = "resource-index-provider-resolution-v1";
    private static final String DERIVATION = "resource-index-provider-order-last-wins";

    private ResourceOwnershipSummary() {
    }

    static Result summarize(ResourceIndex index) {
        List<RootStats> rootStats = new ArrayList<>();
        int rootLimit = Math.min(index.roots().size(), MAX_ROOTS);
        for (int rootIndex = 0; rootIndex < rootLimit; rootIndex++) {
            ResourceIndex.Root root = index.roots().get(rootIndex);
            rootStats.add(new RootStats(rootIndex, root.id(), root.core()));
        }
        boolean truncated = index.roots().size() > MAX_ROOTS;

        long overridePaths = 0;
        long coreOverriddenPaths = 0;
        long modToModOverridePaths = 0;
        long sameRootCollisions = 0;
        Map<String, Long> overrideKinds = new LinkedHashMap<>();
        List<Map<String, Object>> overrideDetails = new ArrayList<>();

        for (Map.Entry<String, List<ResourceIndex.Provider>> entry : index.entries().entrySet()) {
            List<ResourceIndex.Provider> providers = entry.getValue();
            for (int providerIndex = 0; providerIndex < providers.size(); providerIndex++) {
                ResourceIndex.Provider provider = providers.get(providerIndex);
                if (provider.rootIndex() < rootStats.size()) {
                    RootStats stats = rootStats.get(provider.rootIndex());
                    stats.providerOccurrences++;
                    stats.providedBytes += provider.size();
                    if (providerIndex == providers.size() - 1) {
                        stats.winningPaths++;
                    } else {
                        stats.shadowedOccurrences++;
                        stats.shadowedBytes += provider.size();
                    }
                }
            }

            if (providers.size() <= 1) {
                continue;
            }
            overridePaths++;
            String kind = kind(entry.getKey());
            overrideKinds.merge(kind, 1L, Long::sum);

            ResourceIndex.Provider winner = providers.get(providers.size() - 1);
            boolean winnerCore = root(index, winner).core();
            boolean hasCore = providers.stream().anyMatch(provider -> root(index, provider).core());
            boolean hasOtherMod = providers.stream()
                    .anyMatch(provider -> !root(index, provider).core() && provider.rootIndex() != winner.rootIndex());
            if (!winnerCore && hasCore) {
                coreOverriddenPaths++;
            }
            if (!winnerCore && hasOtherMod) {
                modToModOverridePaths++;
            }
            for (int i = 1; i < providers.size(); i++) {
                if (providers.get(i - 1).rootIndex() == providers.get(i).rootIndex()) {
                    sameRootCollisions++;
                    break;
                }
            }

            if (overrideDetails.size() < MAX_OVERRIDE_DETAILS) {
                overrideDetails.add(detail(index, entry.getKey(), providers));
            } else {
                truncated = true;
            }
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("resourcePaths", index.entryCount());
        totals.put("providers", index.providerCount());
        totals.put("roots", index.roots().size());
        totals.put("overridePaths", overridePaths);
        totals.put("coreOverriddenPaths", coreOverriddenPaths);
        totals.put("modToModOverridePaths", modToModOverridePaths);
        totals.put("sameRootCollisionPaths", sameRootCollisions);
        totals.put("overrideDetailsReturned", overrideDetails.size());
        totals.put("truncated", truncated);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("evidenceKind", EVIDENCE_KIND);
        values.put("profileFingerprint", index.profileFingerprint());
        values.put("totals", totals);
        values.put("overrideKinds", sortedCounts(overrideKinds));
        values.put("roots", rootStats.stream().map(RootStats::toMap).toList());
        values.put("overrides", List.copyOf(overrideDetails));
        values.put("notes", List.of(
                "Provider order is ResourceIndex resolution order; the last provider is the ordinary winning provider.",
                "Overrides are information, not errors. Many Starsector mods intentionally replace resources from core or earlier mods.",
                "This evidence describes file providers only; merged table rows and runtime-generated objects are outside this model."));
        return new Result(values);
    }

    static Map<String, Object> explain(ResourceIndex index, String logicalPath) {
        String normalized = ResourceIndex.normalizeLogicalPath(logicalPath);
        List<ResourceIndex.Provider> providers = index.providers(normalized);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("evidenceKind", EVIDENCE_KIND);
        value.put("profileFingerprint", index.profileFingerprint());
        value.put("derivation", DERIVATION);
        value.put("logicalPath", normalized);
        value.put("kind", kind(normalized));
        value.put("providerCount", providers.size());
        if (providers.isEmpty()) {
            value.put("resolved", false);
            return value;
        }
        value.put("resolved", true);
        ResourceIndex.Provider winner = providers.get(providers.size() - 1);
        value.put("winner", provider(index, winner));
        int returned = Math.min(providers.size(), MAX_PROVIDERS_PER_DETAIL);
        value.put("providers", providers.subList(0, returned).stream()
                .map(item -> provider(index, item))
                .toList());
        value.put("providersTruncated", providers.size() > returned);
        value.put("override", providers.size() > 1);
        return value;
    }

    private static Map<String, Object> detail(
            ResourceIndex index, String logicalPath, List<ResourceIndex.Provider> providers) {
        Map<String, Object> value = new LinkedHashMap<>(explain(index, logicalPath));
        ResourceIndex.Provider winner = providers.get(providers.size() - 1);
        ResourceIndex.Root winningRoot = root(index, winner);
        value.put("winnerRootId", winningRoot.id());
        value.put("winnerCore", winningRoot.core());
        value.put("shadowedProviderCount", providers.size() - 1);

        List<String> shadowedRootIds = new ArrayList<>();
        boolean shadowedRootIdsTruncated = false;
        for (int indexInChain = 0; indexInChain < providers.size() - 1; indexInChain++) {
            String rootId = root(index, providers.get(indexInChain)).id();
            if (shadowedRootIds.contains(rootId)) {
                continue;
            }
            if (shadowedRootIds.size() >= MAX_PROVIDERS_PER_DETAIL) {
                shadowedRootIdsTruncated = true;
                break;
            }
            shadowedRootIds.add(rootId);
        }
        value.put("shadowedRootIds", List.copyOf(shadowedRootIds));
        value.put("shadowedRootIdsTruncated", shadowedRootIdsTruncated);
        return value;
    }

    private static Map<String, Object> provider(ResourceIndex index, ResourceIndex.Provider provider) {
        ResourceIndex.Root root = root(index, provider);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("rootId", root.id());
        value.put("core", root.core());
        value.put("relativePath", provider.relativePath());
        value.put("size", provider.size());
        return value;
    }

    private static ResourceIndex.Root root(ResourceIndex index, ResourceIndex.Provider provider) {
        return index.roots().get(provider.rootIndex());
    }

    private static String kind(String logicalPath) {
        String lower = logicalPath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ship")) {
            return "ship";
        }
        if (lower.endsWith(".variant")) {
            return "variant";
        }
        if (lower.endsWith(".wpn")) {
            return "weapon";
        }
        if (lower.endsWith(".proj")) {
            return "projectile";
        }
        if (lower.endsWith(".faction")) {
            return "faction";
        }
        if (lower.endsWith(".skin")) {
            return "skin";
        }
        if (lower.endsWith(".system")) {
            return "ship-system";
        }
        if (lower.endsWith(".skill")) {
            return "skill";
        }
        if (lower.endsWith(".ogg") || lower.endsWith(".wav")) {
            return "audio";
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image";
        }
        if (lower.endsWith(".json") || lower.endsWith(".csv")) {
            return "configuration";
        }
        return "other";
    }

    private static Map<String, Long> sortedCounts(Map<String, Long> input) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        input.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(result);
    }

    record Result(Map<String, Object> values) {
        Result {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        String toJson() {
            return Json.object(values);
        }
    }

    private static final class RootStats {
        private final int index;
        private final String id;
        private final boolean core;
        private long providerOccurrences;
        private long winningPaths;
        private long shadowedOccurrences;
        private long providedBytes;
        private long shadowedBytes;

        private RootStats(int index, String id, boolean core) {
            this.index = index;
            this.id = id;
            this.core = core;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("rootIndex", index);
            value.put("rootId", id);
            value.put("core", core);
            value.put("providerOccurrences", providerOccurrences);
            value.put("winningPaths", winningPaths);
            value.put("shadowedOccurrences", shadowedOccurrences);
            value.put("providedBytes", providedBytes);
            value.put("shadowedBytes", shadowedBytes);
            return value;
        }
    }
}

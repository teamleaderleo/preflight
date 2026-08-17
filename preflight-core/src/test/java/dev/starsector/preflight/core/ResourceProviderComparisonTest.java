package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceProviderComparisonTest {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void classifiesTwoAndThreeProviderByteIdenticalDuplicates() {
        ResourceIndex index = index(Map.of(
                "graphics/two.png", providers(2, 128),
                "graphics/three.png", providers(3, 256)));

        ResourceProviderComparison.Result result = ResourceProviderComparison.analyze(
                index, (path, provider) -> ResourceProviderComparison.ContentObservation.hashed(A));

        assertEquals(2, result.multiProviderLogicalPaths());
        assertEquals(2, result.identicalDuplicates());
        assertEquals(0, result.differingOverrides());
        assertEquals(0, result.ambiguousComparisons());
        assertEquals(ResourceProviderComparison.Classification.IDENTICAL_DUPLICATE,
                result.identicalSamples().get(0).classification());
        assertEquals(ResourceProviderComparison.Classification.IDENTICAL_DUPLICATE,
                result.identicalSamples().get(1).classification());
    }

    @Test
    void sameLengthDifferentBytesAreAnOverrideAndTheLastProviderWins() {
        ResourceIndex index = index(Map.of(
                "data/config/settings.json", providers(2, 4096)));

        ResourceProviderComparison.Result result = ResourceProviderComparison.analyze(index,
                (path, provider) -> ResourceProviderComparison.ContentObservation.hashed(
                        provider.rootIndex() == 0 ? A : B));

        ResourceProviderComparison.PathComparison comparison = result.overrideSamples().get(0);
        assertEquals(1, result.differingOverrides());
        assertEquals("mod-1", comparison.winnerProviderId());
        assertEquals(1, comparison.winnerProviderOrder());
        assertEquals(1, comparison.winnerRootOrder());
        assertTrue(comparison.providers().get(1).winner());
        assertFalse(comparison.providers().get(0).winner());
    }

    @Test
    void differingFirstOrLastProviderDoesNotChangeDeterministicWinnerSemantics() {
        ResourceIndex index = index(Map.of(
                "data/first-diff.json", providers(3, 64),
                "data/last-diff.json", providers(3, 64)));

        ResourceProviderComparison.Result result = ResourceProviderComparison.analyze(index, (path, provider) -> {
            if (path.endsWith("first-diff.json")) {
                return ResourceProviderComparison.ContentObservation.hashed(
                        provider.rootIndex() == 0 ? B : A);
            }
            return ResourceProviderComparison.ContentObservation.hashed(
                    provider.rootIndex() == 2 ? C : A);
        });

        assertEquals(2, result.differingOverrides());
        Map<String, ResourceProviderComparison.PathComparison> byPath = result.overrideSamples().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ResourceProviderComparison.PathComparison::logicalPath, comparison -> comparison));
        assertEquals("mod-2", byPath.get("data/first-diff.json").winnerProviderId());
        assertEquals("mod-2", byPath.get("data/last-diff.json").winnerProviderId());
        assertEquals(2, byPath.get("data/first-diff.json").winnerProviderOrder());
        assertEquals(2, byPath.get("data/last-diff.json").winnerProviderOrder());
    }

    @Test
    void unreadableMissingInvalidAndStaleEvidenceStayAmbiguous() {
        ResourceIndex index = index(Map.of(
                "data/invalid.json", providers(2, 1),
                "data/missing.json", providers(2, 1),
                "data/stale.json", providers(2, 1),
                "data/unreadable.json", providers(2, 1)));

        ResourceProviderComparison.Result result = ResourceProviderComparison.analyze(index, (path, provider) -> {
            if (provider.rootIndex() == 0) {
                return ResourceProviderComparison.ContentObservation.hashed(A);
            }
            if (path.contains("invalid")) {
                return ResourceProviderComparison.ContentObservation.invalidPath();
            }
            if (path.contains("missing")) {
                return ResourceProviderComparison.ContentObservation.missing();
            }
            if (path.contains("stale")) {
                return ResourceProviderComparison.ContentObservation.stale();
            }
            return ResourceProviderComparison.ContentObservation.unreadable();
        });

        assertEquals(4, result.ambiguousComparisons());
        assertEquals(4, result.multiProviderLogicalPaths());
        assertEquals(0, result.identicalDuplicates());
        assertEquals(0, result.differingOverrides());
        assertTrue(result.ambiguousSamples().stream()
                .allMatch(comparison -> comparison.classification()
                        == ResourceProviderComparison.Classification.AMBIGUOUS));
        assertEquals(
                List.of(
                        ResourceProviderComparison.ContentEvidence.INVALID_PATH,
                        ResourceProviderComparison.ContentEvidence.MISSING,
                        ResourceProviderComparison.ContentEvidence.STALE,
                        ResourceProviderComparison.ContentEvidence.UNREADABLE),
                result.ambiguousSamples().stream()
                        .map(comparison -> comparison.providers().get(1).evidence())
                        .toList());
    }

    @Test
    void logicalPathEnumerationOrderCannotChangeTheResult() {
        Map<String, List<ResourceIndex.Provider>> forward = new LinkedHashMap<>();
        forward.put("z/shared.bin", providers(2, 10));
        forward.put("a/shared.bin", providers(2, 10));
        Map<String, List<ResourceIndex.Provider>> reverse = new LinkedHashMap<>();
        reverse.put("a/shared.bin", providers(2, 10));
        reverse.put("z/shared.bin", providers(2, 10));

        ResourceProviderComparison.ContentIdentitySource identities = (path, provider) ->
                ResourceProviderComparison.ContentObservation.hashed(
                        provider.rootIndex() == 0 ? A : B);
        Map<String, Object> first = ResourceProviderComparison.analyze(index(forward), identities).toPublicMap();
        Map<String, Object> second = ResourceProviderComparison.analyze(index(reverse), identities).toPublicMap();

        assertEquals(first, second);
    }

    @Test
    void largeSetsKeepExactReconciledCountsWhileSamplesStayBounded() {
        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            entries.put("identical/" + i + ".bin", providers(2, 7));
        }
        for (int i = 0; i < 30; i++) {
            entries.put("override/" + i + ".bin", providers(2, 7));
        }
        for (int i = 0; i < 20; i++) {
            entries.put("ambiguous/" + i + ".bin", providers(2, 7));
        }
        ResourceIndex index = index(entries);

        ResourceProviderComparison.Result result = ResourceProviderComparison.analyze(index, (path, provider) -> {
            if (path.startsWith("ambiguous/")) {
                return provider.rootIndex() == 0
                        ? ResourceProviderComparison.ContentObservation.hashed(A)
                        : ResourceProviderComparison.ContentObservation.unreadable();
            }
            if (path.startsWith("override/")) {
                return ResourceProviderComparison.ContentObservation.hashed(
                        provider.rootIndex() == 0 ? A : B);
            }
            return ResourceProviderComparison.ContentObservation.hashed(A);
        }, 3, 2);

        assertEquals(90, result.multiProviderLogicalPaths());
        assertEquals(40, result.identicalDuplicates());
        assertEquals(30, result.differingOverrides());
        assertEquals(20, result.ambiguousComparisons());
        assertEquals(result.multiProviderLogicalPaths(),
                result.identicalDuplicates() + result.differingOverrides() + result.ambiguousComparisons());
        assertEquals(3, result.identicalSamples().size());
        assertEquals(3, result.overrideSamples().size());
        assertEquals(3, result.ambiguousSamples().size());
        @SuppressWarnings("unchecked")
        Map<String, Object> truncated = (Map<String, Object>) result.toPublicMap().get("truncated");
        assertEquals(Boolean.TRUE, truncated.get("identicalDuplicates"));
        assertEquals(Boolean.TRUE, truncated.get("differingOverrides"));
        assertEquals(Boolean.TRUE, truncated.get("ambiguousComparisons"));
    }

    @Test
    void boundedProviderChainAlwaysKeepsTheWinner() {
        ResourceIndex index = index(Map.of("data/chain.json", providers(5, 100)));
        ResourceProviderComparison.Result result = ResourceProviderComparison.analyze(
                index,
                (path, provider) -> ResourceProviderComparison.ContentObservation.hashed(
                        provider.rootIndex() == 4 ? B : A),
                5,
                3);

        ResourceProviderComparison.PathComparison comparison = result.overrideSamples().get(0);
        assertEquals(5, comparison.providerCount());
        assertEquals(3, comparison.providers().size());
        assertTrue(comparison.providersTruncated());
        assertEquals(List.of(0, 1, 4), comparison.providers().stream()
                .map(ResourceProviderComparison.ProviderSummary::providerOrder).toList());
        assertTrue(comparison.providers().get(2).winner());
    }

    @Test
    void publicComparisonMapContainsNoPhysicalPathsOrContentDigests() {
        String secretRoot = temporaryDirectory.resolve("Users/alice/Games/Starsector/mods/SecretMod")
                .toAbsolutePath().normalize().toString();
        List<ResourceIndex.Root> roots = List.of(
                new ResourceIndex.Root("core", temporaryDirectory.resolve("core"), true),
                new ResourceIndex.Root("secret-mod", Path.of(secretRoot), false));
        ResourceIndex index = new ResourceIndex("f".repeat(64), roots, Map.of(
                "graphics/shared.png", List.of(
                        new ResourceIndex.Provider(0, "graphics/Shared.PNG", 8, 1),
                        new ResourceIndex.Provider(1, "graphics/shared.png", 8, 1))));

        String publicValue = ResourceProviderComparison.analyze(
                index, (path, provider) -> ResourceProviderComparison.ContentObservation.hashed(A))
                .toPublicMap().toString();

        assertFalse(publicValue.contains(secretRoot));
        assertFalse(publicValue.contains(temporaryDirectory.toAbsolutePath().normalize().toString()));
        assertFalse(publicValue.contains(A));
        assertFalse(publicValue.contains("relativePath"));
        assertTrue(publicValue.contains("secret-mod"));
        assertTrue(publicValue.contains("graphics/shared.png"));
    }

    private ResourceIndex index(Map<String, List<ResourceIndex.Provider>> entries) {
        int rootCount = entries.values().stream()
                .flatMap(List::stream)
                .mapToInt(ResourceIndex.Provider::rootIndex)
                .max()
                .orElse(0) + 1;
        List<ResourceIndex.Root> roots = java.util.stream.IntStream.range(0, rootCount)
                .mapToObj(order -> new ResourceIndex.Root(
                        "mod-" + order,
                        temporaryDirectory.resolve("root-" + order),
                        order == 0))
                .toList();
        return new ResourceIndex("e".repeat(64), roots, entries);
    }

    private static List<ResourceIndex.Provider> providers(int count, long size) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(order -> new ResourceIndex.Provider(
                        order, "shared/resource.bin", size, 1234))
                .toList();
    }
}

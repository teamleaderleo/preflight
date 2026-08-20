package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ResourceIndex;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResourceOwnershipSummaryTest {
    @Test
    void explainsWinningProvidersAndSummarizesOverrideActivityWithoutRootPaths() {
        ResourceIndex index = fixture();

        ResourceOwnershipSummary.Result result = ResourceOwnershipSummary.summarize(index);
        assertEquals("resource-index-provider-resolution-v1", result.values().get("evidenceKind"));
        assertEquals("profile-fingerprint", result.values().get("profileFingerprint"));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.values().get("totals");
        assertEquals(4, ((Number) totals.get("resourcePaths")).intValue());
        assertEquals(6L, ((Number) totals.get("providers")).longValue());
        assertEquals(2L, ((Number) totals.get("overridePaths")).longValue());
        assertEquals(1L, ((Number) totals.get("coreOverriddenPaths")).longValue());
        assertEquals(1L, ((Number) totals.get("modToModOverridePaths")).longValue());
        assertEquals(0L, ((Number) totals.get("sameRootCollisionPaths")).longValue());
        assertEquals(false, totals.get("truncated"));

        @SuppressWarnings("unchecked")
        Map<String, Long> kinds = (Map<String, Long>) result.values().get("overrideKinds");
        assertEquals(Map.of("image", 1L, "variant", 1L), kinds);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roots = (List<Map<String, Object>>) result.values().get("roots");
        Map<String, Object> core = roots.get(0);
        Map<String, Object> modA = roots.get(1);
        Map<String, Object> modB = roots.get(2);
        assertEquals(2L, ((Number) core.get("providerOccurrences")).longValue());
        assertEquals(1L, ((Number) core.get("winningPaths")).longValue());
        assertEquals(1L, ((Number) core.get("shadowedOccurrences")).longValue());
        assertEquals(2L, ((Number) modA.get("providerOccurrences")).longValue());
        assertEquals(1L, ((Number) modA.get("winningPaths")).longValue());
        assertEquals(1L, ((Number) modA.get("shadowedOccurrences")).longValue());
        assertEquals(2L, ((Number) modB.get("providerOccurrences")).longValue());
        assertEquals(2L, ((Number) modB.get("winningPaths")).longValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> overrides = (List<Map<String, Object>>) result.values().get("overrides");
        Map<String, Object> image = override(overrides, "graphics/ships/example.png");
        assertEquals("mod_a", image.get("winnerRootId"));
        assertEquals(List.of("core"), image.get("shadowedRootIds"));
        assertEquals(false, image.get("shadowedRootIdsTruncated"));
        Map<String, Object> variant = override(overrides, "data/variants/example.variant");
        assertEquals("mod_b", variant.get("winnerRootId"));
        assertEquals(List.of("mod_a"), variant.get("shadowedRootIds"));
        assertEquals(false, variant.get("shadowedRootIdsTruncated"));

        String json = result.toJson();
        assertFalse(json.contains("/private/game"));
        assertFalse(json.contains("/private/mod-a"));
        assertTrue(json.contains("Overrides are information, not errors"));
    }

    @Test
    void exactExplanationUsesNormalizedLookupAndKeepsResolutionOrder() {
        ResourceIndex index = fixture();
        Map<String, Object> explained = ResourceOwnershipSummary.explain(
                index, "GRAPHICS\\SHIPS\\EXAMPLE.PNG");

        assertEquals("resource-index-provider-resolution-v1", explained.get("evidenceKind"));
        assertEquals("profile-fingerprint", explained.get("profileFingerprint"));
        assertEquals("resource-index-provider-order-last-wins", explained.get("derivation"));
        assertEquals("graphics/ships/example.png", explained.get("logicalPath"));
        assertEquals("image", explained.get("kind"));
        assertEquals(true, explained.get("resolved"));
        assertEquals(true, explained.get("override"));
        assertEquals(2, explained.get("providerCount"));

        @SuppressWarnings("unchecked")
        Map<String, Object> winner = (Map<String, Object>) explained.get("winner");
        assertEquals("mod_a", winner.get("rootId"));
        assertEquals("graphics/ships/Example.png", winner.get("relativePath"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) explained.get("providers");
        assertEquals(List.of("core", "mod_a"),
                providers.stream().map(provider -> (String) provider.get("rootId")).toList());
    }

    @Test
    void absentResourceProducesProfileBoundExplanationInsteadOfAnError() {
        Map<String, Object> explained = ResourceOwnershipSummary.explain(fixture(), "data/does-not-exist.json");
        assertEquals("resource-index-provider-resolution-v1", explained.get("evidenceKind"));
        assertEquals("profile-fingerprint", explained.get("profileFingerprint"));
        assertEquals("resource-index-provider-order-last-wins", explained.get("derivation"));
        assertEquals(false, explained.get("resolved"));
        assertEquals(0, explained.get("providerCount"));
        assertEquals("configuration", explained.get("kind"));
    }

    @Test
    void longProviderChainsKeepWinnerExplicitAndBoundIdentitySamples() {
        List<ResourceIndex.Root> roots = new ArrayList<>();
        List<ResourceIndex.Provider> providers = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            roots.add(new ResourceIndex.Root(
                    "root_" + index,
                    Path.of("/private/root-" + index),
                    index == 0));
            providers.add(provider(index, "graphics/ships/long.png", 100 + index));
        }
        ResourceIndex index = new ResourceIndex(
                "long-profile",
                roots,
                Map.of("graphics/ships/long.png", List.copyOf(providers)));

        ResourceOwnershipSummary.Result result = ResourceOwnershipSummary.summarize(index);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> overrides = (List<Map<String, Object>>) result.values().get("overrides");
        Map<String, Object> detail = override(overrides, "graphics/ships/long.png");

        assertEquals("long-profile", detail.get("profileFingerprint"));
        assertEquals(40, detail.get("providerCount"));
        assertEquals(true, detail.get("providersTruncated"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> returnedProviders =
                (List<Map<String, Object>>) detail.get("providers");
        assertEquals(32, returnedProviders.size());

        assertEquals("root_39", detail.get("winnerRootId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> winner = (Map<String, Object>) detail.get("winner");
        assertEquals("root_39", winner.get("rootId"));
        assertEquals(39, detail.get("shadowedProviderCount"));
        @SuppressWarnings("unchecked")
        List<String> shadowedRootIds = (List<String>) detail.get("shadowedRootIds");
        assertEquals(32, shadowedRootIds.size());
        assertEquals("root_0", shadowedRootIds.get(0));
        assertEquals("root_31", shadowedRootIds.get(31));
        assertEquals(true, detail.get("shadowedRootIdsTruncated"));
    }

    private static ResourceIndex fixture() {
        List<ResourceIndex.Root> roots = List.of(
                new ResourceIndex.Root("core", Path.of("/private/game/starsector-core"), true),
                new ResourceIndex.Root("mod_a", Path.of("/private/mod-a"), false),
                new ResourceIndex.Root("mod_b", Path.of("/private/mod-b"), false));
        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        entries.put("graphics/ships/example.png", List.of(
                provider(0, "graphics/ships/example.png", 100),
                provider(1, "graphics/ships/Example.png", 120)));
        entries.put("data/variants/example.variant", List.of(
                provider(1, "data/variants/example.variant", 40),
                provider(2, "data/variants/example.variant", 44)));
        entries.put("data/ships/example.ship", List.of(
                provider(0, "data/ships/example.ship", 55)));
        entries.put("sounds/example.ogg", List.of(
                provider(2, "sounds/example.ogg", 72)));
        return new ResourceIndex("profile-fingerprint", roots, entries);
    }

    private static ResourceIndex.Provider provider(int rootIndex, String relativePath, long size) {
        return new ResourceIndex.Provider(rootIndex, relativePath, size, 1);
    }

    private static Map<String, Object> override(List<Map<String, Object>> overrides, String path) {
        return overrides.stream()
                .filter(value -> path.equals(value.get("logicalPath")))
                .findFirst()
                .orElseThrow();
    }
}

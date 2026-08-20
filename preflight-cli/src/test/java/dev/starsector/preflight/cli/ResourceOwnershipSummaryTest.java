package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ResourceIndex;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResourceOwnershipSummaryTest {
    @Test
    void explainsWinningProvidersAndSummarizesOverrideActivityWithoutRootPaths() {
        ResourceIndex index = fixture();

        ResourceOwnershipSummary.Result result = ResourceOwnershipSummary.summarize(index);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.values().get("totals");
        assertEquals(4, ((Number) totals.get("resourcePaths")).intValue());
        assertEquals(7L, ((Number) totals.get("providers")).longValue());
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
        assertEquals(3L, ((Number) core.get("providerOccurrences")).longValue());
        assertEquals(2L, ((Number) core.get("winningPaths")).longValue());
        assertEquals(1L, ((Number) core.get("shadowedOccurrences")).longValue());
        assertEquals(2L, ((Number) modA.get("providerOccurrences")).longValue());
        assertEquals(1L, ((Number) modA.get("winningPaths")).longValue());
        assertEquals(1L, ((Number) modA.get("shadowedOccurrences")).longValue());
        assertEquals(2L, ((Number) modB.get("providerOccurrences")).longValue());
        assertEquals(1L, ((Number) modB.get("winningPaths")).longValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> overrides = (List<Map<String, Object>>) result.values().get("overrides");
        Map<String, Object> image = override(overrides, "graphics/ships/example.png");
        assertEquals("mod_a", image.get("winnerRootId"));
        assertEquals(List.of("core"), image.get("shadowedRootIds"));
        Map<String, Object> variant = override(overrides, "data/variants/example.variant");
        assertEquals("mod_b", variant.get("winnerRootId"));
        assertEquals(List.of("mod_a"), variant.get("shadowedRootIds"));

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
    void absentResourceProducesAnExplanationInsteadOfAnError() {
        Map<String, Object> explained = ResourceOwnershipSummary.explain(fixture(), "data/does-not-exist.json");
        assertEquals(false, explained.get("resolved"));
        assertEquals(0, explained.get("providerCount"));
        assertEquals("configuration", explained.get("kind"));
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
                provider(0, "sounds/example.ogg", 70),
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

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
import org.junit.jupiter.api.io.TempDir;

class ResourceProfileResolutionDiffTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void explainsResolutionChangesWithoutTreatingProfileLocalRootIndexAsSourceIdentity() {
        ResourceIndex before = new ResourceIndex(
                "before-profile",
                List.of(
                        root("core", "before-core", true),
                        root("alpha", "before-alpha", false),
                        root("beta", "before-beta", false)),
                beforeEntries());
        ResourceIndex after = new ResourceIndex(
                "after-profile",
                List.of(
                        root("core", "after-core", true),
                        root("beta", "after-beta", false),
                        root("alpha", "after-alpha", false),
                        root("gamma", "after-gamma", false)),
                afterEntries());

        ResourceProfileResolutionDiff.Result result = ResourceProfileResolutionDiff.compare(before, after);

        assertEquals("resource-index-profile-resolution-diff-v1", result.values().get("evidenceKind"));
        assertEquals("before-profile", result.values().get("beforeProfileFingerprint"));
        assertEquals("after-profile", result.values().get("afterProfileFingerprint"));
        assertEquals("resource-index-resolution-and-provider-snapshot-metadata", result.values().get("comparisonScope"));
        assertFalse((Boolean) result.values().get("filesystemRead"));
        assertFalse((Boolean) result.values().get("sourceGenerationBound"));
        assertEquals("core-root-id-relative-path", result.values().get("providerSourceIdentity"));
        assertEquals("profile-local-provider-occurrence", result.values().get("rootIndexScope"));

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.values().get("totals");
        assertEquals(6L, number(totals, "pathsBefore"));
        assertEquals(6L, number(totals, "pathsAfter"));
        assertEquals(7L, number(totals, "pathsCompared"));
        assertEquals(1L, number(totals, "unchangedPaths"));
        assertEquals(6L, number(totals, "changedPaths"));
        assertEquals(1L, number(totals, "addedPaths"));
        assertEquals(1L, number(totals, "removedPaths"));
        assertEquals(2L, number(totals, "providerSourceSequenceChangedPaths"));
        assertEquals(2L, number(totals, "providerSnapshotMetadataChangedPaths"));
        assertEquals(1L, number(totals, "winnerSourceChangedPaths"));
        assertEquals(1L, number(totals, "winnerSnapshotMetadataChangedPaths"));
        assertEquals(6L, number(totals, "changeDetailsReturned"));
        assertFalse((Boolean) totals.get("changeDetailsTruncated"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) result.values().get("changes");
        assertFalse(changes.stream().anyMatch(change -> "graphics/unchanged.png".equals(change.get("logicalPath"))));

        Map<String, Object> chain = change(changes, "data/config/rules.csv");
        assertEquals("changed", chain.get("status"));
        assertTrue((Boolean) chain.get("providerSourceSequenceChanged"));
        assertFalse((Boolean) chain.get("winnerSourceChanged"));
        assertEquals(List.of("provider-source-sequence-changed"), chain.get("changeReasons"));
        assertWinner(chain, "before", "alpha", 1);
        assertWinner(chain, "after", "alpha", 2);

        Map<String, Object> winner = change(changes, "data/hulls/shared.ship");
        assertTrue((Boolean) winner.get("providerSourceSequenceChanged"));
        assertTrue((Boolean) winner.get("winnerSourceChanged"));
        assertWinner(winner, "before", "alpha", 1);
        assertWinner(winner, "after", "beta", 1);

        Map<String, Object> metadata = change(changes, "data/weapons/meta.wpn");
        assertFalse((Boolean) metadata.get("providerSourceSequenceChanged"));
        assertTrue((Boolean) metadata.get("providerSnapshotMetadataChanged"));
        assertTrue((Boolean) metadata.get("winnerSnapshotMetadataChanged"));

        Map<String, Object> nonwinnerMetadata = change(changes, "data/config/nonwinner.json");
        assertTrue((Boolean) nonwinnerMetadata.get("providerSnapshotMetadataChanged"));
        assertFalse((Boolean) nonwinnerMetadata.get("winnerSnapshotMetadataChanged"));

        Map<String, Object> added = change(changes, "data/factions/added.faction");
        assertEquals("added", added.get("status"));
        assertResolved(added, "before", false);
        assertResolved(added, "after", true);

        Map<String, Object> removed = change(changes, "sounds/removed.ogg");
        assertEquals("removed", removed.get("status"));
        assertResolved(removed, "before", true);
        assertResolved(removed, "after", false);

        String json = result.toJson();
        assertFalse(json.contains(temporaryDirectory.toString()));
        assertTrue(json.contains("Provider size and modifiedMillis differences are ResourceIndex snapshot metadata changes"));
    }

    @Test
    void boundsChangeDetailsWhileKeepingExactTotals() {
        ResourceIndex before = new ResourceIndex(
                "before-empty",
                List.of(root("core", "empty-core", true)),
                Map.of());
        Map<String, List<ResourceIndex.Provider>> afterEntries = new LinkedHashMap<>();
        afterEntries.put("data/a.json", List.of(provider(0, "data/a.json", 1, 1)));
        afterEntries.put("data/b.json", List.of(provider(0, "data/b.json", 1, 1)));
        afterEntries.put("data/c.json", List.of(provider(0, "data/c.json", 1, 1)));
        ResourceIndex after = new ResourceIndex(
                "after-three",
                List.of(root("core", "three-core", true)),
                afterEntries);

        ResourceProfileResolutionDiff.Result result =
                ResourceProfileResolutionDiff.compare(before, after, 2);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.values().get("totals");
        assertEquals(3L, number(totals, "changedPaths"));
        assertEquals(3L, number(totals, "addedPaths"));
        assertEquals(2L, number(totals, "changeDetailsReturned"));
        assertTrue((Boolean) totals.get("changeDetailsTruncated"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) result.values().get("changes");
        assertEquals(List.of("data/a.json", "data/b.json"),
                changes.stream().map(change -> (String) change.get("logicalPath")).toList());
    }

    @Test
    void identicalProviderSourcesAtDifferentRootIndexesRemainUnchanged() {
        ResourceIndex before = new ResourceIndex(
                "before-shift",
                List.of(root("core", "shift-core-before", true), root("alpha", "shift-alpha-before", false)),
                Map.of("data/shared.json", List.of(provider(1, "data/shared.json", 10, 20))));
        ResourceIndex after = new ResourceIndex(
                "after-shift",
                List.of(
                        root("core", "shift-core-after", true),
                        root("unrelated", "shift-unrelated", false),
                        root("alpha", "shift-alpha-after", false)),
                Map.of("data/shared.json", List.of(provider(2, "data/shared.json", 10, 20))));

        ResourceProfileResolutionDiff.Result result = ResourceProfileResolutionDiff.compare(before, after);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.values().get("totals");
        assertEquals(1L, number(totals, "pathsCompared"));
        assertEquals(1L, number(totals, "unchangedPaths"));
        assertEquals(0L, number(totals, "changedPaths"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) result.values().get("changes");
        assertTrue(changes.isEmpty());
    }

    private Map<String, List<ResourceIndex.Provider>> beforeEntries() {
        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        entries.put("graphics/unchanged.png", List.of(provider(1, "graphics/unchanged.png", 10, 1)));
        entries.put("sounds/removed.ogg", List.of(provider(2, "sounds/removed.ogg", 20, 2)));
        entries.put("data/hulls/shared.ship", List.of(
                provider(0, "data/hulls/shared.ship", 30, 3),
                provider(1, "data/hulls/shared.ship", 31, 4)));
        entries.put("data/config/rules.csv", List.of(
                provider(0, "data/config/rules.csv", 40, 5),
                provider(1, "data/config/rules.csv", 41, 6)));
        entries.put("data/weapons/meta.wpn", List.of(provider(1, "data/weapons/meta.wpn", 100, 10)));
        entries.put("data/config/nonwinner.json", List.of(
                provider(0, "data/config/nonwinner.json", 50, 5),
                provider(1, "data/config/nonwinner.json", 60, 6)));
        return entries;
    }

    private Map<String, List<ResourceIndex.Provider>> afterEntries() {
        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        entries.put("graphics/unchanged.png", List.of(provider(2, "graphics/unchanged.png", 10, 1)));
        entries.put("data/factions/added.faction", List.of(provider(3, "data/factions/added.faction", 25, 2)));
        entries.put("data/hulls/shared.ship", List.of(
                provider(0, "data/hulls/shared.ship", 30, 3),
                provider(1, "data/hulls/shared.ship", 32, 4)));
        entries.put("data/config/rules.csv", List.of(
                provider(0, "data/config/rules.csv", 40, 5),
                provider(1, "data/config/rules.csv", 39, 7),
                provider(2, "data/config/rules.csv", 41, 6)));
        entries.put("data/weapons/meta.wpn", List.of(provider(2, "data/weapons/meta.wpn", 101, 11)));
        entries.put("data/config/nonwinner.json", List.of(
                provider(0, "data/config/nonwinner.json", 51, 5),
                provider(2, "data/config/nonwinner.json", 60, 6)));
        return entries;
    }

    private ResourceIndex.Root root(String id, String directory, boolean core) {
        return new ResourceIndex.Root(id, temporaryDirectory.resolve(directory), core);
    }

    private static ResourceIndex.Provider provider(
            int rootIndex, String relativePath, long size, long modifiedMillis) {
        return new ResourceIndex.Provider(rootIndex, relativePath, size, modifiedMillis);
    }

    private static long number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).longValue();
    }

    private static Map<String, Object> change(List<Map<String, Object>> changes, String logicalPath) {
        return changes.stream()
                .filter(change -> logicalPath.equals(change.get("logicalPath")))
                .findFirst()
                .orElseThrow();
    }

    private static void assertWinner(
            Map<String, Object> change, String side, String expectedRootId, int expectedRootIndex) {
        @SuppressWarnings("unchecked")
        Map<String, Object> explanation = (Map<String, Object>) change.get(side);
        @SuppressWarnings("unchecked")
        Map<String, Object> winner = (Map<String, Object>) explanation.get("winner");
        assertEquals(expectedRootId, winner.get("rootId"));
        assertEquals(expectedRootIndex, ((Number) winner.get("rootIndex")).intValue());
    }

    private static void assertResolved(Map<String, Object> change, String side, boolean expected) {
        @SuppressWarnings("unchecked")
        Map<String, Object> explanation = (Map<String, Object>) change.get(side);
        assertEquals(expected, explanation.get("resolved"));
    }
}

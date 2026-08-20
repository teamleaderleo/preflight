package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ResourceIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergedSpreadsheetProvenanceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsProviderContributionsWithoutClaimingAMergedWinner() throws Exception {
        Path core = temporaryDirectory.resolve("private-game");
        Path modA = temporaryDirectory.resolve("private-mod-a");
        Path modB = temporaryDirectory.resolve("private-mod-b");
        String relative = "data/hulls/ship_data.csv";
        Path coreFile = write(core, relative,
                "\ufeffid,name,manufacturer\r\n"
                        + "alpha,\"Lasher, (D)\",Core\r\n"
                        + "shared,Wolf,Core\r\n"
                        + "shared,Wolf2,CoreDuplicate\r\n"
                        + ",blank,Ignored\r\n");
        Path modAFile = write(modA, relative,
                "id,name,manufacturer\n"
                        + "shared,\"Wolf \"\"P\"\"\",A\n"
                        + "moda,Custom,A\n");
        Path modBFile = write(modB, relative, "id,name\nmalformed,\"unterminated\n");

        ResourceIndex index = index(
                List.of(
                        new ResourceIndex.Root("core", core, true),
                        new ResourceIndex.Root("mod_a", modA, false),
                        new ResourceIndex.Root("mod_b", modB, false)),
                relative,
                List.of(provider(0, relative, coreFile), provider(1, relative, modAFile), provider(2, relative, modBFile)));

        MergedSpreadsheetProvenance.Result result =
                MergedSpreadsheetProvenance.inspect(index, relative, List.of("id"));

        assertEquals("spreadsheet-provider-row-contribution-v1", result.values().get("evidenceKind"));
        assertEquals("provider-contribution-only", result.values().get("resolution"));
        assertEquals("resource-index-profile-fingerprint", result.values().get("providerOrderBinding"));
        assertEquals("current-provider-path-read", result.values().get("contentBinding"));
        assertFalse((Boolean) result.values().get("sourceGenerationBound"));
        assertTrue((Boolean) result.values().get("resolved"));

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.values().get("totals");
        assertEquals(3L, number(totals, "providers"));
        assertEquals(2L, number(totals, "parsedProviders"));
        assertEquals(2L, number(totals, "keyableProviders"));
        assertEquals(1L, number(totals, "providersWithErrors"));
        assertEquals(6L, number(totals, "dataRows"));
        assertEquals(5L, number(totals, "keyedRows"));
        assertEquals(3L, number(totals, "uniqueKeysObserved"));
        assertEquals(1L, number(totals, "duplicateKeyRowsWithinProvider"));
        assertEquals(1L, number(totals, "blankKeyRows"));
        assertFalse((Boolean) totals.get("truncated"));
        assertFalse((Boolean) totals.get("completeForRequestedKeys"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) result.values().get("keys");
        Map<String, Object> shared = key(keys, "id", "shared");
        assertEquals(2, ((Number) shared.get("contributorCountLowerBound")).intValue());
        assertEquals(2, ((Number) shared.get("contributorsReturned")).intValue());
        assertTrue((Boolean) shared.get("contributorCountComplete"));
        assertTrue((Boolean) shared.get("duplicateWithinProvider"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contributors = (List<Map<String, Object>>) shared.get("contributors");
        assertEquals(List.of("core", "mod_a"),
                contributors.stream().map(item -> (String) item.get("rootId")).toList());
        assertEquals(2, ((Number) contributors.get(0).get("rowsForKey")).intValue());
        assertEquals(1, ((Number) contributors.get(1).get("rowsForKey")).intValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) result.values().get("providers");
        assertFalse((Boolean) providers.get(2).get("parsed"));
        assertFalse((Boolean) providers.get(2).get("keyable"));
        assertEquals(List.of("malformed-csv"), providers.get(2).get("errors"));

        String json = result.toJson();
        assertFalse(json.contains(temporaryDirectory.toString()));
        assertFalse(json.contains("\"winner\":"));
        assertTrue(json.contains("\"sourceGenerationBound\":false"));
        assertTrue(json.contains("does not establish Starsector's final merged-row winner"));
    }

    @Test
    void supportsCompositeKeysAndReportsRowsMissingAKeyCell() throws Exception {
        Path mod = temporaryDirectory.resolve("composite-private-mod");
        String relative = "data/ships/special.csv";
        Path file = write(mod, relative,
                "id,variant,name\n"
                        + "alpha,v1,One\n"
                        + "alpha,v2,Two\n"
                        + "beta\n");
        ResourceIndex index = index(
                List.of(new ResourceIndex.Root("mod", mod, false)),
                relative,
                List.of(provider(0, relative, file)));

        MergedSpreadsheetProvenance.Result result =
                MergedSpreadsheetProvenance.inspect(index, relative, List.of("id", "variant"));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.values().get("totals");
        assertEquals(2L, number(totals, "keyedRows"));
        assertEquals(1L, number(totals, "rowsMissingKeyCells"));
        assertEquals(2L, number(totals, "uniqueKeysObserved"));
        assertFalse((Boolean) totals.get("completeForRequestedKeys"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) result.values().get("keys");
        @SuppressWarnings("unchecked")
        Map<String, String> firstKey = (Map<String, String>) keys.get(0).get("key");
        assertEquals(Map.of("id", "alpha", "variant", "v1"), firstKey);

        MergedSpreadsheetProvenance.Result missing =
                MergedSpreadsheetProvenance.inspect(index, relative, List.of("missing"));
        @SuppressWarnings("unchecked")
        Map<String, Object> missingTotals = (Map<String, Object>) missing.values().get("totals");
        assertEquals(1L, number(missingTotals, "parsedProviders"));
        assertEquals(0L, number(missingTotals, "keyableProviders"));
        assertEquals(1L, number(missingTotals, "providersWithErrors"));
        assertFalse((Boolean) missingTotals.get("completeForRequestedKeys"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) missing.values().get("providers");
        assertTrue((Boolean) providers.get(0).get("parsed"));
        assertFalse((Boolean) providers.get(0).get("keyable"));
        assertEquals(List.of("missing-key-column:missing"), providers.get(0).get("errors"));
    }

    @Test
    void marksFullyParsedFullyKeyedUnboundedEvidenceComplete() throws Exception {
        Path mod = temporaryDirectory.resolve("complete-private-mod");
        String relative = "data/config/complete.csv";
        Path file = write(mod, relative, "id,name\nalpha,One\nbeta,Two\n");
        ResourceIndex index = index(
                List.of(new ResourceIndex.Root("mod", mod, false)),
                relative,
                List.of(provider(0, relative, file)));

        MergedSpreadsheetProvenance.Result result =
                MergedSpreadsheetProvenance.inspect(index, relative, List.of("id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.values().get("totals");
        assertTrue((Boolean) totals.get("completeForRequestedKeys"));
        assertEquals(1L, number(totals, "parsedProviders"));
        assertEquals(1L, number(totals, "keyableProviders"));
        assertEquals(0L, number(totals, "providersWithErrors"));
    }

    @Test
    void capsKeysAndContributorsWithExplicitTruncation() throws Exception {
        Path first = temporaryDirectory.resolve("limit-first");
        Path second = temporaryDirectory.resolve("limit-second");
        String relative = "data/config/limited.csv";
        Path firstFile = write(first, relative, "id,name\na,One\nb,Two\n");
        Path secondFile = write(second, relative, "id,name\na,Other\nb,OtherTwo\n");
        ResourceIndex index = index(
                List.of(
                        new ResourceIndex.Root("first", first, false),
                        new ResourceIndex.Root("second", second, false)),
                relative,
                List.of(provider(0, relative, firstFile), provider(1, relative, secondFile)));
        MergedSpreadsheetProvenance.ScanLimits limits = new MergedSpreadsheetProvenance.ScanLimits(
                2,
                1,
                1,
                2,
                new SpreadsheetCsv.Limits(1_024, 20, 10, 100));

        MergedSpreadsheetProvenance.Result result =
                MergedSpreadsheetProvenance.inspect(index, relative, List.of("id"), limits);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.values().get("totals");
        assertEquals(1L, number(totals, "uniqueKeysObserved"));
        assertTrue((Boolean) totals.get("keysTruncated"));
        assertTrue((Boolean) totals.get("truncated"));
        assertFalse((Boolean) totals.get("completeForRequestedKeys"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) result.values().get("keys");
        assertEquals(1, keys.size());
        assertTrue((Boolean) keys.get(0).get("contributorsTruncated"));
        assertEquals(1, ((Number) keys.get(0).get("contributorCountLowerBound")).intValue());
        assertEquals(1, ((Number) keys.get(0).get("contributorsReturned")).intValue());
        assertFalse((Boolean) keys.get(0).get("contributorCountComplete"));
    }

    private ResourceIndex index(
            List<ResourceIndex.Root> roots,
            String logicalPath,
            List<ResourceIndex.Provider> providers) {
        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        entries.put(logicalPath, providers);
        return new ResourceIndex("profile-fingerprint", roots, entries);
    }

    private Path write(Path root, String relative, String contents) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return file;
    }

    private ResourceIndex.Provider provider(int rootIndex, String relative, Path file) throws Exception {
        return new ResourceIndex.Provider(rootIndex, relative, Files.size(file), 1);
    }

    private static long number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).longValue();
    }

    private static Map<String, Object> key(List<Map<String, Object>> keys, String column, String wanted) {
        return keys.stream()
                .filter(value -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> rowKey = (Map<String, String>) value.get("key");
                    return wanted.equals(rowKey.get(column));
                })
                .findFirst()
                .orElseThrow();
    }
}

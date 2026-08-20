package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Read-only per-provider row contribution evidence for merged Starsector spreadsheets. */
final class MergedSpreadsheetProvenance {
    private static final String EVIDENCE_KIND = "spreadsheet-provider-row-contribution-v1";
    private static final ScanLimits STANDARD_LIMITS =
            new ScanLimits(256, 100_000, 64, 16, SpreadsheetCsv.STANDARD_LIMITS);

    private MergedSpreadsheetProvenance() {
    }

    static Result inspect(ResourceIndex index, String logicalPath, List<String> keyColumns) {
        return inspect(index, logicalPath, keyColumns, STANDARD_LIMITS);
    }

    static Result inspect(
            ResourceIndex index, String logicalPath, List<String> keyColumns, ScanLimits limits) {
        String normalized = ResourceIndex.normalizeLogicalPath(logicalPath);
        if (!normalized.endsWith(".csv")) {
            throw new IllegalArgumentException("Spreadsheet provenance requires a .csv logical path");
        }
        List<String> keys = validateKeyColumns(keyColumns, limits.maxKeyColumns());
        List<ResourceIndex.Provider> allProviders = index.providers(normalized);
        int providerCount = Math.min(allProviders.size(), limits.maxProviders());
        boolean providersTruncated = allProviders.size() > providerCount;

        List<Map<String, Object>> providerReports = new ArrayList<>();
        Map<RowKey, KeyContributors> contributions = new TreeMap<>();
        boolean keysTruncated = false;
        long parsedProviders = 0;
        long keyableProviders = 0;
        long providersWithErrors = 0;
        long dataRows = 0;
        long keyedRows = 0;
        long duplicateKeyRows = 0;
        long blankKeyRows = 0;
        long rowsMissingKeyCells = 0;

        for (int providerIndex = 0; providerIndex < providerCount; providerIndex++) {
            ResourceIndex.Provider provider = allProviders.get(providerIndex);
            ProviderScan scan = scanProvider(index, provider, keys, limits);
            providerReports.add(scan.toMap(index, provider));
            if (scan.parsed()) {
                parsedProviders++;
            }
            if (scan.keyable()) {
                keyableProviders++;
            }
            if (!scan.errors().isEmpty()) {
                providersWithErrors++;
            }
            dataRows += scan.dataRows();
            keyedRows += scan.keyedRows();
            duplicateKeyRows += scan.duplicateKeyRows();
            blankKeyRows += scan.blankKeyRows();
            rowsMissingKeyCells += scan.rowsMissingKeyCells();

            ProviderIdentity identity = new ProviderIdentity(provider.rootIndex(), provider.relativePath());
            for (Map.Entry<RowKey, Integer> entry : scan.rowCounts().entrySet()) {
                KeyContributors existing = contributions.get(entry.getKey());
                if (existing == null) {
                    if (contributions.size() >= limits.maxKeys()) {
                        keysTruncated = true;
                        continue;
                    }
                    existing = new KeyContributors(limits.maxContributorsPerKey());
                    contributions.put(entry.getKey(), existing);
                }
                existing.add(identity, entry.getValue());
                keysTruncated |= existing.truncated();
            }
            keysTruncated |= scan.truncated();
        }

        boolean completeForRequestedKeys = !allProviders.isEmpty()
                && !providersTruncated
                && !keysTruncated
                && providersWithErrors == 0
                && rowsMissingKeyCells == 0;
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("providers", allProviders.size());
        totals.put("providersScanned", providerCount);
        totals.put("parsedProviders", parsedProviders);
        totals.put("keyableProviders", keyableProviders);
        totals.put("providersWithErrors", providersWithErrors);
        totals.put("dataRows", dataRows);
        totals.put("keyedRows", keyedRows);
        totals.put("uniqueKeysObserved", contributions.size());
        totals.put("duplicateKeyRowsWithinProvider", duplicateKeyRows);
        totals.put("blankKeyRows", blankKeyRows);
        totals.put("rowsMissingKeyCells", rowsMissingKeyCells);
        totals.put("providersTruncated", providersTruncated);
        totals.put("keysTruncated", keysTruncated);
        totals.put("truncated", providersTruncated || keysTruncated);
        totals.put("completeForRequestedKeys", completeForRequestedKeys);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("evidenceKind", EVIDENCE_KIND);
        values.put("profileFingerprint", index.profileFingerprint());
        values.put("providerOrderBinding", "resource-index-profile-fingerprint");
        values.put("contentBinding", "current-provider-path-read");
        values.put("sourceGenerationBound", false);
        values.put("logicalPath", normalized);
        values.put("keyColumns", keys);
        values.put("resolved", !allProviders.isEmpty());
        values.put("resolution", "provider-contribution-only");
        values.put("totals", totals);
        values.put("providers", List.copyOf(providerReports));
        values.put("keys", contributions.entrySet().stream()
                .map(entry -> keyMap(index, keys, entry.getKey(), entry.getValue()))
                .toList());
        values.put("notes", List.of(
                "This evidence reports keyed rows physically present in each ResourceIndex provider spreadsheet.",
                "The profile fingerprint binds provider selection/order; row bytes are current reads from those resolved provider paths, not exact generation-bound content evidence.",
                "It does not establish Starsector's final merged-row winner or reproduce the game's spreadsheet merge policy.",
                "Duplicate keys inside one provider remain explicit instead of being silently collapsed into an ownership claim.",
                "completeForRequestedKeys is false when a provider could not be parsed/keyed, a keyed row was incomplete, or a bound truncated evidence.",
                "Counts with truncated=true are lower bounds."));
        return new Result(values);
    }

    private static ProviderScan scanProvider(
            ResourceIndex index,
            ResourceIndex.Provider provider,
            List<String> keyColumns,
            ScanLimits limits) {
        byte[] bytes;
        try {
            Path file = index.resolveExisting(provider);
            if (provider.size() > limits.csvLimits().maxBytes()) {
                return ProviderScan.readFailure("provider-exceeds-byte-limit", true);
            }
            try (InputStream input = Files.newInputStream(file)) {
                bytes = input.readNBytes(limits.csvLimits().maxBytes() + 1);
            }
            if (bytes.length > limits.csvLimits().maxBytes()) {
                return ProviderScan.readFailure("provider-exceeds-byte-limit", true);
            }
        } catch (IOException | IllegalArgumentException error) {
            return ProviderScan.readFailure("provider-read-failed", false);
        }

        SpreadsheetCsv.Table table;
        try {
            table = SpreadsheetCsv.parse(bytes, limits.csvLimits());
        } catch (IOException error) {
            return ProviderScan.parseFailure(bytes.length, "malformed-csv");
        }
        if (table.rows().isEmpty()) {
            return ProviderScan.unkeyable(bytes.length, 0, List.of("empty-spreadsheet"));
        }

        long dataRows = Math.max(0, table.rows().size() - 1L);
        HeaderResolution header = resolveHeader(table.rows().get(0), keyColumns);
        if (!header.errors().isEmpty()) {
            return ProviderScan.unkeyable(bytes.length, dataRows, header.errors());
        }

        Map<RowKey, Integer> rowCounts = new TreeMap<>();
        long keyedRows = 0;
        long duplicateKeyRows = 0;
        long blankKeyRows = 0;
        long rowsMissingKeyCells = 0;
        boolean truncated = false;
        List<List<String>> rows = table.rows();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            List<String> values = new ArrayList<>(header.indexes().size());
            boolean missing = false;
            boolean anyValue = false;
            for (int columnIndex : header.indexes()) {
                if (columnIndex >= row.size()) {
                    missing = true;
                    break;
                }
                String value = row.get(columnIndex);
                values.add(value);
                anyValue |= !value.isEmpty();
            }
            if (missing) {
                rowsMissingKeyCells++;
                continue;
            }
            if (!anyValue) {
                blankKeyRows++;
                continue;
            }

            keyedRows++;
            RowKey key = new RowKey(values);
            Integer prior = rowCounts.get(key);
            if (prior != null) {
                rowCounts.put(key, prior + 1);
                duplicateKeyRows++;
            } else if (rowCounts.size() < limits.maxKeys()) {
                rowCounts.put(key, 1);
            } else {
                truncated = true;
            }
        }
        return new ProviderScan(
                true,
                true,
                bytes.length,
                dataRows,
                keyedRows,
                duplicateKeyRows,
                blankKeyRows,
                rowsMissingKeyCells,
                truncated,
                Collections.unmodifiableMap(rowCounts),
                List.of());
    }

    private static HeaderResolution resolveHeader(List<String> header, List<String> keyColumns) {
        List<Integer> indexes = new ArrayList<>(keyColumns.size());
        List<String> errors = new ArrayList<>();
        for (String keyColumn : keyColumns) {
            int match = -1;
            int matches = 0;
            for (int index = 0; index < header.size(); index++) {
                if (keyColumn.equals(header.get(index))) {
                    match = index;
                    matches++;
                }
            }
            if (matches == 0) {
                errors.add("missing-key-column:" + keyColumn);
            } else if (matches > 1) {
                errors.add("ambiguous-key-column:" + keyColumn);
            } else {
                indexes.add(match);
            }
        }
        return new HeaderResolution(List.copyOf(indexes), List.copyOf(errors));
    }

    private static List<String> validateKeyColumns(List<String> keyColumns, int limit) {
        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new IllegalArgumentException("At least one spreadsheet key column is required");
        }
        if (keyColumns.size() > limit) {
            throw new IllegalArgumentException("Too many spreadsheet key columns");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String keyColumn : keyColumns) {
            if (keyColumn == null || keyColumn.isBlank()) {
                throw new IllegalArgumentException("Spreadsheet key columns must be non-blank");
            }
            if (!seen.add(keyColumn)) {
                throw new IllegalArgumentException("Duplicate spreadsheet key column: " + keyColumn);
            }
        }
        return List.copyOf(seen);
    }

    private static Map<String, Object> keyMap(
            ResourceIndex index,
            List<String> keyColumns,
            RowKey key,
            KeyContributors contributors) {
        Map<String, String> keyValues = new LinkedHashMap<>();
        for (int keyIndex = 0; keyIndex < keyColumns.size(); keyIndex++) {
            keyValues.put(keyColumns.get(keyIndex), key.values().get(keyIndex));
        }
        List<Map<String, Object>> contributorValues = contributors.rowsByProvider().entrySet().stream()
                .map(entry -> contributorMap(index, entry.getKey(), entry.getValue()))
                .toList();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("key", keyValues);
        value.put("contributorCountLowerBound", contributorValues.size());
        value.put("contributorsReturned", contributorValues.size());
        value.put("contributorCountComplete", !contributors.truncated());
        value.put("contributors", contributorValues);
        value.put("duplicateWithinProvider", contributors.rowsByProvider().values().stream().anyMatch(count -> count > 1));
        value.put("contributorsTruncated", contributors.truncated());
        return value;
    }

    private static Map<String, Object> contributorMap(
            ResourceIndex index, ProviderIdentity identity, int rowsForKey) {
        ResourceIndex.Root root = index.roots().get(identity.rootIndex());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("rootId", root.id());
        value.put("core", root.core());
        value.put("relativePath", identity.relativePath());
        value.put("rowsForKey", rowsForKey);
        return value;
    }

    record Result(Map<String, Object> values) {
        Result {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        String toJson() {
            return Json.object(values);
        }
    }

    record ScanLimits(
            int maxProviders,
            int maxKeys,
            int maxContributorsPerKey,
            int maxKeyColumns,
            SpreadsheetCsv.Limits csvLimits) {
        ScanLimits {
            if (maxProviders < 1 || maxKeys < 1 || maxContributorsPerKey < 1 || maxKeyColumns < 1) {
                throw new IllegalArgumentException("Spreadsheet scan limits must be positive");
            }
            if (csvLimits == null) {
                throw new IllegalArgumentException("CSV limits are required");
            }
        }
    }

    private record HeaderResolution(List<Integer> indexes, List<String> errors) {
    }

    private record ProviderScan(
            boolean parsed,
            boolean keyable,
            long bytesRead,
            long dataRows,
            long keyedRows,
            long duplicateKeyRows,
            long blankKeyRows,
            long rowsMissingKeyCells,
            boolean truncated,
            Map<RowKey, Integer> rowCounts,
            List<String> errors) {
        static ProviderScan readFailure(String error, boolean truncated) {
            return new ProviderScan(false, false, 0, 0, 0, 0, 0, 0, truncated, Map.of(), List.of(error));
        }

        static ProviderScan parseFailure(long bytesRead, String error) {
            return new ProviderScan(false, false, bytesRead, 0, 0, 0, 0, 0, false, Map.of(), List.of(error));
        }

        static ProviderScan unkeyable(long bytesRead, long dataRows, List<String> errors) {
            return new ProviderScan(
                    true, false, bytesRead, dataRows, 0, 0, 0, 0, false, Map.of(), List.copyOf(errors));
        }

        Map<String, Object> toMap(ResourceIndex index, ResourceIndex.Provider provider) {
            ResourceIndex.Root root = index.roots().get(provider.rootIndex());
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("rootId", root.id());
            value.put("core", root.core());
            value.put("relativePath", provider.relativePath());
            value.put("declaredBytes", provider.size());
            value.put("parsed", parsed);
            value.put("keyable", keyable);
            value.put("bytesRead", bytesRead);
            value.put("dataRows", dataRows);
            value.put("keyedRows", keyedRows);
            value.put("uniqueKeysObserved", rowCounts.size());
            value.put("duplicateKeyRows", duplicateKeyRows);
            value.put("blankKeyRows", blankKeyRows);
            value.put("rowsMissingKeyCells", rowsMissingKeyCells);
            value.put("truncated", truncated);
            value.put("errors", errors);
            return value;
        }
    }

    private record RowKey(List<String> values) implements Comparable<RowKey> {
        RowKey {
            values = List.copyOf(values);
        }

        @Override
        public int compareTo(RowKey other) {
            int common = Math.min(values.size(), other.values.size());
            for (int index = 0; index < common; index++) {
                int comparison = values.get(index).compareTo(other.values().get(index));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Integer.compare(values.size(), other.values().size());
        }
    }

    private record ProviderIdentity(int rootIndex, String relativePath) implements Comparable<ProviderIdentity> {
        @Override
        public int compareTo(ProviderIdentity other) {
            int root = Integer.compare(rootIndex, other.rootIndex);
            return root != 0 ? root : relativePath.compareTo(other.relativePath);
        }
    }

    private static final class KeyContributors {
        private final int limit;
        private final Map<ProviderIdentity, Integer> rowsByProvider = new TreeMap<>();
        private boolean truncated;

        private KeyContributors(int limit) {
            this.limit = limit;
        }

        private void add(ProviderIdentity provider, int rows) {
            Integer prior = rowsByProvider.get(provider);
            if (prior != null) {
                rowsByProvider.put(provider, prior + rows);
                return;
            }
            if (rowsByProvider.size() >= limit) {
                truncated = true;
                return;
            }
            rowsByProvider.put(provider, rows);
        }

        private Map<ProviderIdentity, Integer> rowsByProvider() {
            return Collections.unmodifiableMap(rowsByProvider);
        }

        private boolean truncated() {
            return truncated;
        }
    }
}

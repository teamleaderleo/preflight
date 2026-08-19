package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Shared read-only finding model for current-setup readiness providers. */
final class SetupAnalysis {
    static final String FORMAT = "starsector-preflight-setup-analysis-v1";

    private static final int MAX_FINDINGS = 256;
    private static final int MAX_PARAMETERS = 32;
    private static final int MAX_AFFECTED_MOD_IDS = 64;
    private static final int MAX_ACTIONS = 16;
    private static final int MAX_UNAVAILABLE_PROVIDERS = 32;
    private static final int MAX_IDENTITY_CHARS = 256;
    private static final int MAX_CODE_CHARS = 128;
    private static final int MAX_PROVIDER_CHARS = 128;
    private static final int MAX_SUMMARY_CHARS = 512;
    private static final int MAX_PARAMETER_KEY_CHARS = 64;
    private static final int MAX_PARAMETER_STRING_CHARS = 1_024;
    private static final int MAX_PARAMETER_NUMBER_CHARS = 128;
    private static final int MAX_LIST_VALUE_CHARS = 256;
    private static final int MAX_RAW_FINDINGS = MAX_FINDINGS * MAX_UNAVAILABLE_PROVIDERS;
    private static final int MAX_SUPPRESSION_GROUP_SUMMARIES = 31;
    private static final String ORCHESTRATION_PROVIDER = "setup-orchestration";

    enum Severity {
        BLOCKING,
        WARNING,
        INFO,
        UNKNOWN
    }

    record Finding(
            String code,
            String provider,
            Severity severity,
            String summary,
            Map<String, Object> parameters,
            List<String> affectedModIds,
            List<String> actions) {
        Finding {
            code = requiredBounded(code, "setup finding code", MAX_CODE_CHARS);
            provider = requiredBounded(provider, "setup finding provider", MAX_PROVIDER_CHARS);
            if (severity == null) {
                throw new IllegalArgumentException("setup finding severity is required");
            }
            summary = requiredBounded(summary, "setup finding summary", MAX_SUMMARY_CHARS);
            parameters = canonicalParameters(parameters);
            affectedModIds = boundedSorted(
                    affectedModIds, MAX_AFFECTED_MOD_IDS, MAX_LIST_VALUE_CHARS, "affected mod IDs");
            actions = boundedSorted(actions, MAX_ACTIONS, MAX_LIST_VALUE_CHARS, "action IDs");
        }
    }

    record Result(
            String installationIdentity,
            String profileFingerprint,
            List<Finding> findings,
            List<String> unavailableProviders) {
        Result {
            installationIdentity = requiredBounded(
                    installationIdentity, "setup installation identity", MAX_IDENTITY_CHARS);
            profileFingerprint = requiredBounded(
                    profileFingerprint, "setup profile fingerprint", MAX_IDENTITY_CHARS);
            findings = canonicalFindings(findings);
            unavailableProviders = boundedSorted(
                    unavailableProviders,
                    MAX_UNAVAILABLE_PROVIDERS,
                    MAX_PROVIDER_CHARS,
                    "unavailable providers");
        }

        boolean ready() {
            return findings.stream().noneMatch(finding -> finding.severity() == Severity.BLOCKING);
        }

        long count(Severity severity) {
            return findings.stream().filter(finding -> finding.severity() == severity).count();
        }

        String toJson() {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("format", FORMAT);
            root.put("installationIdentity", installationIdentity);
            root.put("profileFingerprint", profileFingerprint);
            root.put("ready", ready());
            Map<String, Object> counts = new LinkedHashMap<>();
            for (Severity severity : Severity.values()) {
                counts.put(severity.name().toLowerCase(Locale.ROOT), count(severity));
            }
            root.put("counts", counts);
            root.put("findings", findings.stream().map(SetupAnalysis::view).toList());
            root.put("unavailableProviders", unavailableProviders);
            return Json.object(root);
        }
    }

    private SetupAnalysis() {
    }

    /**
     * Global provider fan-in. Individual providers may each be locally bounded while their combined
     * output still exceeds the public {@link Result} ceiling, so orchestration selects evidence
     * before constructing the result.
     *
     * <p>Selection is severity-first and provider-fair: within each severity, providers are visited
     * in stable lexical round-robin order. Suppressed findings retain bounded provider/code/severity
     * evidence, plus one global truncation finding. Raw provider iteration order therefore cannot
     * decide which provider survives a noisy fan-in.</p>
     */
    static Result compose(
            String installationIdentity,
            String profileFingerprint,
            List<Finding> providerFindings,
            List<String> unavailableProviders) {
        List<Finding> bounded = aggregateFindings(providerFindings);
        return new Result(installationIdentity, profileFingerprint, bounded, unavailableProviders);
    }

    private static List<Finding> aggregateFindings(List<Finding> providerFindings) {
        if (providerFindings == null || providerFindings.isEmpty()) {
            return List.of();
        }
        if (providerFindings.size() > MAX_RAW_FINDINGS) {
            throw new IllegalArgumentException(
                    "setup provider fan-in exceeds the " + MAX_RAW_FINDINGS + "-finding raw-input limit");
        }
        if (providerFindings.size() <= MAX_FINDINGS) {
            return List.copyOf(providerFindings);
        }

        List<Finding> ordered = new ArrayList<>(providerFindings);
        ordered.sort(findingOrder());

        int groupSummarySlots = MAX_SUPPRESSION_GROUP_SUMMARIES;
        List<Finding> selected = List.of();
        List<Finding> suppressed = List.of();
        List<SuppressedGroup> groups = List.of();
        while (true) {
            int detailLimit = MAX_FINDINGS - 1 - groupSummarySlots;
            selected = selectProviderFairDetails(ordered, detailLimit);
            suppressed = subtractOccurrences(ordered, selected);
            groups = suppressionGroups(suppressed);
            int requiredGroupSlots = Math.min(MAX_SUPPRESSION_GROUP_SUMMARIES, groups.size());
            if (requiredGroupSlots == groupSummarySlots) {
                break;
            }
            groupSummarySlots = requiredGroupSlots;
        }

        List<Finding> result = new ArrayList<>(MAX_FINDINGS);
        result.addAll(selected);
        for (int index = 0; index < groupSummarySlots; index++) {
            SuppressedGroup group = groups.get(index);
            result.add(new Finding(
                    "orchestration.suppressed-finding-group",
                    ORCHESTRATION_PROVIDER,
                    group.severity(),
                    "Some findings from a readiness provider were omitted by the global result limit.",
                    Map.of(
                            "sourceProvider", group.provider(),
                            "sourceCode", group.code(),
                            "count", group.count()),
                    List.of(),
                    List.of()));
        }

        result.add(globalSuppressionFinding(suppressed, groups.size(), groupSummarySlots));
        if (result.size() > MAX_FINDINGS) {
            throw new IllegalStateException("setup orchestration exceeded its own finding budget");
        }
        return List.copyOf(result);
    }

    private static List<Finding> selectProviderFairDetails(List<Finding> ordered, int limit) {
        List<Finding> selected = new ArrayList<>(limit);
        for (Severity severity : Severity.values()) {
            TreeMap<String, ArrayDeque<Finding>> byProvider = new TreeMap<>();
            for (Finding finding : ordered) {
                if (finding.severity() == severity) {
                    byProvider.computeIfAbsent(finding.provider(), ignored -> new ArrayDeque<>())
                            .addLast(finding);
                }
            }
            boolean progress = true;
            while (selected.size() < limit && progress) {
                progress = false;
                for (ArrayDeque<Finding> queue : byProvider.values()) {
                    if (selected.size() >= limit) {
                        break;
                    }
                    Finding next = queue.pollFirst();
                    if (next != null) {
                        selected.add(next);
                        progress = true;
                    }
                }
            }
            if (selected.size() >= limit) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private static List<Finding> subtractOccurrences(List<Finding> ordered, List<Finding> selected) {
        Map<Finding, Integer> selectedCounts = new HashMap<>();
        for (Finding finding : selected) {
            selectedCounts.merge(finding, 1, Integer::sum);
        }
        List<Finding> suppressed = new ArrayList<>();
        for (Finding finding : ordered) {
            int count = selectedCounts.getOrDefault(finding, 0);
            if (count > 0) {
                if (count == 1) {
                    selectedCounts.remove(finding);
                } else {
                    selectedCounts.put(finding, count - 1);
                }
            } else {
                suppressed.add(finding);
            }
        }
        return List.copyOf(suppressed);
    }

    private static List<SuppressedGroup> suppressionGroups(List<Finding> suppressed) {
        Map<SuppressionKey, Integer> counts = new HashMap<>();
        for (Finding finding : suppressed) {
            SuppressionKey key = new SuppressionKey(
                    finding.severity(), finding.provider(), finding.code());
            counts.merge(key, 1, Integer::sum);
        }
        List<SuppressedGroup> groups = new ArrayList<>(counts.size());
        for (Map.Entry<SuppressionKey, Integer> entry : counts.entrySet()) {
            SuppressionKey key = entry.getKey();
            groups.add(new SuppressedGroup(
                    key.severity(), key.provider(), key.code(), entry.getValue()));
        }
        groups.sort(Comparator.comparingInt((SuppressedGroup group) -> severityRank(group.severity()))
                .thenComparing(SuppressedGroup::provider)
                .thenComparing(SuppressedGroup::code));
        return List.copyOf(groups);
    }

    private static Finding globalSuppressionFinding(
            List<Finding> suppressed,
            int suppressedGroups,
            int representedGroups) {
        long blocking = countSeverity(suppressed, Severity.BLOCKING);
        long warning = countSeverity(suppressed, Severity.WARNING);
        long info = countSeverity(suppressed, Severity.INFO);
        long unknown = countSeverity(suppressed, Severity.UNKNOWN);
        Severity severity = blocking > 0
                ? Severity.BLOCKING
                : warning > 0
                        ? Severity.WARNING
                        : info > 0 ? Severity.INFO : Severity.UNKNOWN;
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("suppressedFindings", suppressed.size());
        parameters.put("suppressedGroups", suppressedGroups);
        parameters.put("unrepresentedGroups", Math.max(0, suppressedGroups - representedGroups));
        parameters.put("blocking", blocking);
        parameters.put("warning", warning);
        parameters.put("info", info);
        parameters.put("unknown", unknown);
        return new Finding(
                "orchestration.findings-truncated",
                ORCHESTRATION_PROVIDER,
                severity,
                "Additional provider findings were omitted by the global setup-analysis limit.",
                parameters,
                List.of(),
                List.of());
    }

    private static long countSeverity(List<Finding> findings, Severity severity) {
        return findings.stream().filter(finding -> finding.severity() == severity).count();
    }

    private static Map<String, Object> view(Finding finding) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", finding.code());
        value.put("provider", finding.provider());
        value.put("severity", finding.severity().name().toLowerCase(Locale.ROOT));
        value.put("summary", finding.summary());
        value.put("parameters", finding.parameters());
        value.put("affectedModIds", finding.affectedModIds());
        value.put("actions", finding.actions());
        return value;
    }

    private static Map<String, Object> canonicalParameters(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        if (parameters.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException(
                    "setup finding parameters exceed the " + MAX_PARAMETERS + "-key limit");
        }
        List<String> keys = new ArrayList<>(parameters.keySet());
        for (String key : keys) {
            requiredBounded(key, "setup finding parameter key", MAX_PARAMETER_KEY_CHARS);
        }
        keys.sort(String::compareTo);
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String key : keys) {
            ordered.put(key, canonicalParameterValue(key, parameters.get(key)));
        }
        return Collections.unmodifiableMap(ordered);
    }

    private static Object canonicalParameterValue(String key, Object value) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence text) {
            String scalar = text.toString();
            if (scalar.length() > MAX_PARAMETER_STRING_CHARS) {
                throw new IllegalArgumentException("setup finding parameter '" + key
                        + "' exceeds the " + MAX_PARAMETER_STRING_CHARS + "-character string limit");
            }
            return scalar;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException("setup finding parameter '" + key + "' must be finite");
            }
            return number.doubleValue();
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("setup finding parameter '" + key + "' must be finite");
            }
            return number;
        }
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            String encoded = value.toString();
            if (encoded.length() > MAX_PARAMETER_NUMBER_CHARS) {
                throw new IllegalArgumentException("setup finding parameter '" + key
                        + "' exceeds the " + MAX_PARAMETER_NUMBER_CHARS + "-character numeric limit");
            }
            return value;
        }
        throw new IllegalArgumentException("setup finding parameter '" + key
                + "' must be a JSON scalar (string, boolean, finite number, or null)");
    }

    private static List<Finding> canonicalFindings(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        if (findings.size() > MAX_FINDINGS) {
            throw new IllegalArgumentException(
                    "setup analysis exceeds the " + MAX_FINDINGS + "-finding limit");
        }
        List<Finding> ordered = new ArrayList<>(findings);
        ordered.sort(findingOrder());
        return List.copyOf(ordered);
    }

    private static Comparator<Finding> findingOrder() {
        return Comparator.comparingInt((Finding finding) -> severityRank(finding.severity()))
                .thenComparing(Finding::provider)
                .thenComparing(Finding::code)
                .thenComparing(Finding::summary);
    }

    private static int severityRank(Severity severity) {
        return switch (severity) {
            case BLOCKING -> 0;
            case WARNING -> 1;
            case INFO -> 2;
            case UNKNOWN -> 3;
        };
    }

    private static List<String> boundedSorted(
            List<String> values, int limit, int maxChars, String label) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > limit) {
            throw new IllegalArgumentException(
                    "setup analysis " + label + " exceed the " + limit + "-item limit");
        }
        List<String> canonical = new ArrayList<>(values.size());
        for (String value : values) {
            canonical.add(requiredBounded(value, "setup analysis " + label + " value", maxChars));
        }
        return canonical.stream().distinct().sorted().toList();
    }

    private static String requiredBounded(String value, String label, int maxChars) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        if (value.length() > maxChars) {
            throw new IllegalArgumentException(label + " exceeds the " + maxChars + "-character limit");
        }
        return value;
    }

    private record SuppressionKey(Severity severity, String provider, String code) {
    }

    private record SuppressedGroup(Severity severity, String provider, String code, int count) {
    }
}

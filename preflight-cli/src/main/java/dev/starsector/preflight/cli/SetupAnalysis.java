package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared read-only finding model for current-setup readiness providers. */
final class SetupAnalysis {
    static final String FORMAT = "starsector-preflight-setup-analysis-v1";

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
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("setup finding code is required");
            }
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("setup finding provider is required");
            }
            if (severity == null) {
                throw new IllegalArgumentException("setup finding severity is required");
            }
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("setup finding summary is required");
            }
            parameters = canonicalParameters(parameters);
            affectedModIds = boundedSorted(affectedModIds, 64);
            actions = boundedSorted(actions, 16);
        }
    }

    record Result(
            String installationIdentity,
            String profileFingerprint,
            List<Finding> findings,
            List<String> unavailableProviders) {
        Result {
            findings = canonicalFindings(findings);
            unavailableProviders = boundedSorted(unavailableProviders, 32);
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
                counts.put(severity.name().toLowerCase(), count(severity));
            }
            root.put("counts", counts);
            root.put("findings", findings.stream().map(SetupAnalysis::view).toList());
            root.put("unavailableProviders", unavailableProviders);
            return Json.object(root);
        }
    }

    private SetupAnalysis() {
    }

    private static Map<String, Object> view(Finding finding) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", finding.code());
        value.put("provider", finding.provider());
        value.put("severity", finding.severity().name().toLowerCase());
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
        if (parameters.size() > 32) {
            throw new IllegalArgumentException("setup finding parameters exceed the 32-key limit");
        }
        List<String> keys = new ArrayList<>(parameters.keySet());
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("setup finding parameter keys must be nonblank");
            }
        }
        keys.sort(String::compareTo);
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String key : keys) {
            ordered.put(key, parameters.get(key));
        }
        return Collections.unmodifiableMap(ordered);
    }

    private static List<Finding> canonicalFindings(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        if (findings.size() > 256) {
            throw new IllegalArgumentException("setup analysis exceeds the 256-finding limit");
        }
        List<Finding> ordered = new ArrayList<>(findings);
        ordered.sort(Comparator.comparing((Finding finding) -> finding.severity().ordinal())
                .thenComparing(Finding::provider)
                .thenComparing(Finding::code)
                .thenComparing(Finding::summary));
        return List.copyOf(ordered);
    }

    private static List<String> boundedSorted(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > limit) {
            throw new IllegalArgumentException("setup analysis list exceeds the " + limit + "-item limit");
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }
}

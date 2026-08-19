package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        ordered.sort(Comparator.comparingInt((Finding finding) -> severityRank(finding.severity()))
                .thenComparing(Finding::provider)
                .thenComparing(Finding::code)
                .thenComparing(Finding::summary));
        return List.copyOf(ordered);
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
}

package dev.starsector.preflight.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Stable, metadata-level readiness vocabulary for the selected enabled-mod profile.
 *
 * <p>The evaluator that produces this result deliberately lives outside core: filesystem layout,
 * launcher settings, and installed-mod parsing are environment concerns. This type is the shared
 * contract consumed by CLI, desktop, receipts, comparisons, and recovery surfaces.
 */
public final class ModCompatibilityReadiness {
    public static final String FORMAT = "starsector-preflight-mod-readiness-v1";

    private ModCompatibilityReadiness() {
    }

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public enum ReasonCode {
        ENABLED_MODS_UNAVAILABLE("enabled_mods_unavailable"),
        METADATA_ABSENT("mod_metadata_absent"),
        METADATA_INVALID("mod_metadata_invalid"),
        METADATA_PARTIAL("mod_metadata_partial"),
        DUPLICATE_MOD_ID("mod_id_ambiguous"),
        REQUIRED_DEPENDENCY_MISSING("mod_dependency_missing"),
        REQUIRED_DEPENDENCY_DISABLED("mod_dependency_disabled"),
        DEPENDENCY_VERSION_MAJOR_MISMATCH("mod_dependency_version_major_mismatch"),
        DEPENDENCY_VERSION_ADVISORY("mod_dependency_version_advisory"),
        OPTIONAL_DEPENDENCY("mod_dependency_optional"),
        GAME_VERSION_MISMATCH("mod_game_version_mismatch"),
        GAME_VERSION_UNKNOWN("game_version_unknown"),
        MEMORY_REQUIREMENT_INVALID("mod_memory_requirement_invalid"),
        MEMORY_REQUIREMENT_EXCEEDS_HEAP("mod_memory_requirement_exceeds_heap"),
        MEMORY_REQUIREMENT_UNKNOWN("mod_memory_requirement_unknown");

        private final String code;

        ReasonCode(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public record Finding(
            ReasonCode reason,
            Severity severity,
            String modId,
            String subjectId,
            String summary) {
        public Finding {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(summary, "summary");
        }

        public Map<String, Object> toPublicMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("reasonCode", reason.code());
            map.put("severity", severity.name().toLowerCase(Locale.ROOT));
            map.put("modId", modId);
            map.put("subjectId", subjectId);
            map.put("summary", summary);
            return map;
        }
    }

    /** Exact, order-preserving enabled-mod change proposed for explicit review and application. */
    public record ProfileChange(List<String> before, List<String> after, List<String> enable) {
        public ProfileChange {
            before = List.copyOf(before);
            after = List.copyOf(after);
            enable = List.copyOf(enable);
        }

        public Map<String, Object> toPublicMap() {
            return Map.of("before", before, "after", after, "enable", enable);
        }
    }

    public record Result(
            List<String> enabledMods,
            List<Finding> findings,
            ProfileChange suggestedProfileChange,
            boolean evidenceComplete,
            boolean launchAllowed) {
        public Result {
            enabledMods = List.copyOf(enabledMods);
            findings = List.copyOf(findings);
        }

        public boolean hasErrors() {
            return findings.stream().anyMatch(finding -> finding.severity() == Severity.ERROR);
        }

        public Map<String, Object> toPublicMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("format", FORMAT);
            map.put("enabledMods", enabledMods);
            map.put("hasErrors", hasErrors());
            map.put("evidenceComplete", evidenceComplete);
            map.put("launchAllowed", launchAllowed);
            map.put("findings", findings.stream().map(Finding::toPublicMap).toList());
            map.put("suggestedProfileChange",
                    suggestedProfileChange == null ? null : suggestedProfileChange.toPublicMap());
            return map;
        }
    }
}

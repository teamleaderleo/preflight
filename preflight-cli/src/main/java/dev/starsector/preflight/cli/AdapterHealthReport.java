package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts exact-match telemetry into a compact post-run compatibility verdict. */
final class AdapterHealthReport {
    static final String FORMAT = "starsector-preflight-adapter-health-v1";
    private static final long MAX_ADAPTER_REPORT_BYTES = 32L * 1024 * 1024;
    private static final int DETAIL_LIMIT = 16;
    private static final List<String> CACHE_SECTIONS = List.of(
            "adapterTransformationCache",
            "textureCompatibility",
            "variantJsonCache",
            "weaponJsonCache",
            "projectileJsonCache",
            "hullJsonCache",
            "rulesCsvCache",
            "ruleCommandClassCache",
            "ruleTokenCache",
            "mergedReadCache",
            "resourceProbeCache",
            "loadJsonMemo",
            "preparedAudio",
            "janinoBytecodeCache",
            "graphicsLibInsigniaManagerCache");

    private AdapterHealthReport() {
    }

    static Result analyze(Path adapterReport, Path output) throws IOException {
        Path source = requireFile(adapterReport, "adapter report");
        Path destination = output.toAbsolutePath().normalize();
        Map<String, Object> adapter = BoundedEvidenceJson.readObject(
                source, MAX_ADAPTER_REPORT_BYTES, "Adapter report");
        Result result = evaluate(adapter, source, destination);
        writeAtomic(destination, Json.object(result.toMap()) + System.lineSeparator());
        return result;
    }

    static Result evaluate(Map<String, Object> adapter, Path source, Path output) {
        String mode = string(adapter, "mode").toUpperCase(java.util.Locale.ROOT);
        boolean transformerInstalled = bool(adapter, "transformerInstalled");
        boolean killSwitchActive = bool(adapter, "killSwitchActive");
        long registryTargets = number(adapter, "registryTargets");
        long exactMatches = number(adapter, "exactMatches");
        long sourceBindingRejected = number(adapter, "sourceBindingRejected");
        long unavailablePlans = number(adapter, "transformationEligible");
        long declined = number(adapter, "transformationDeclined");
        long applied = number(adapter, "transformationsApplied");
        long shadowed = number(adapter, "shadowedTargets");
        long containedFailures = number(adapter, "containedFailures");
        long cacheMisses = sectionMetrics(adapter, CACHE_SECTIONS,
                "misses", "preparedMisses", "packMisses", "pathMisses");
        long cacheRejectionSignals = sectionMetrics(adapter, CACHE_SECTIONS,
                "corruptions", "internalErrors", "failures", "readFailures", "writeFailures",
                "loadFailures", "keyCollisions", "comparisonMismatches", "packErrors");
        long wrapperFailureSignals = sectionMetrics(adapter, List.of(
                        "audioResourceFallback",
                        "magicLibPaintjob",
                        "magicLibPaintjobNotification",
                        "magicLibPaintjobLoad",
                        "simOpponentSafety",
                        "macMemoryWarning"),
                "fallbackFailures", "failures", "failOpen", "probeFailures");
        long runtimeIntegrityFailures = runtimeIntegrityFailures(adapter);

        List<String> mismatchDetails = mismatchDetails(adapter.get("evaluations"));
        List<String> shadowedBy = strings(adapter.get("shadowedBy"));
        boolean fallbackEvidence = sourceBindingRejected > 0
                || unavailablePlans > 0
                || declined > 0
                || shadowed > 0
                || containedFailures > 0
                || cacheRejectionSignals > 0
                || wrapperFailureSignals > 0
                || runtimeIntegrityFailures > 0
                || !mismatchDetails.isEmpty();

        Status status;
        if (killSwitchActive) {
            status = Status.DISABLED;
        } else if ("PROBE".equals(mode)) {
            status = transformerInstalled ? Status.PROBE_ONLY : Status.ERROR;
        } else if (!transformerInstalled) {
            status = Status.ERROR;
        } else if (registryTargets == 0) {
            status = Status.NO_TARGETS;
        } else if (applied == 0) {
            status = Status.SAFE_FALLBACK;
        } else if (fallbackEvidence) {
            status = Status.PARTIAL;
        } else {
            status = Status.ACTIVE;
        }

        boolean originalCodeRetained = applied == 0
                || sourceBindingRejected > 0 || unavailablePlans > 0 || declined > 0 || shadowed > 0;
        boolean reviewRecommended = status == Status.ERROR
                || status == Status.SAFE_FALLBACK || status == Status.PARTIAL;
        List<String> evidenceKinds = evidenceKinds(
                mismatchDetails, sourceBindingRejected, unavailablePlans, declined, shadowed,
                containedFailures, cacheMisses, cacheRejectionSignals, wrapperFailureSignals,
                runtimeIntegrityFailures);
        List<String> actions = actions(
                status, shadowed, containedFailures, cacheRejectionSignals,
                wrapperFailureSignals, runtimeIntegrityFailures);
        String summary = summary(status, applied, registryTargets);

        return new Result(
                Instant.now(), source.toAbsolutePath().normalize(), output,
                status, summary, true, applied > 0, originalCodeRetained, reviewRecommended,
                mode, transformerInstalled, killSwitchActive, registryTargets, exactMatches,
                sourceBindingRejected, unavailablePlans, declined, applied, shadowed,
                containedFailures, cacheMisses, cacheRejectionSignals, wrapperFailureSignals,
                runtimeIntegrityFailures, evidenceKinds, mismatchDetails, shadowedBy, actions);
    }

    private static String summary(Status status, long applied, long targets) {
        return switch (status) {
            case ACTIVE -> "Preflight applied " + applied
                    + " reviewed transformation(s) within the registered exact-target boundaries.";
            case PARTIAL -> "Preflight applied " + applied
                    + " reviewed transformation(s) and retained original code for at least one other target.";
            case SAFE_FALLBACK -> "No reviewed transformation applied across " + targets
                    + " registered target(s); original code was retained wherever a target was observed.";
            case DISABLED -> "The adapter kill switch was active; Starsector ran without Preflight transformations.";
            case PROBE_ONLY -> "Probe-only observation completed; no transformation was permitted.";
            case NO_TARGETS -> "The transformer ran with no registered transformation targets.";
            case ERROR -> "Adapter setup or observation did not complete normally; original game behavior remains the safe default.";
        };
    }

    private static List<String> actions(
            Status status,
            long shadowed,
            long failures,
            long cacheRejections,
            long wrapperFailures,
            long runtimeIntegrityFailures) {
        List<String> values = new ArrayList<>();
        if (status == Status.SAFE_FALLBACK || status == Status.PARTIAL) {
            values.add("Keep playing if the game is otherwise healthy; unmatched targets retain their original bytecode.");
            values.add("Share adapter-health.json and adapter.json when requesting support for this game/mod build.");
        }
        if (shadowed > 0) {
            values.add("A mod or another Java agent owns at least one target class; re-preparing caches will not change that.");
        }
        if (failures > 0 || status == Status.ERROR) {
            values.add("Inspect adapter.json diagnostics; use PREFLIGHT_DISABLE_ADAPTER=1 for a full adapter bypass.");
        }
        if (cacheRejections > 0) {
            values.add("At least one cache artifact was rejected safely; re-run preparation before investigating deeper.");
        }
        if (wrapperFailures > 0) {
            values.add("A runtime wrapper retained its original path after a failure; inspect its named adapter.json section.");
        }
        if (runtimeIntegrityFailures > 0) {
            values.add("Runtime class integrity failed; use PREFLIGHT_DISABLE_ADAPTER=1 for a full "
                    + "adapter bypass before the next launch.");
        }
        return List.copyOf(values);
    }

    private static List<String> evidenceKinds(
            List<String> mismatches,
            long sourceBindingRejected,
            long unavailablePlans,
            long declined,
            long shadowed,
            long containedFailures,
            long cacheMisses,
            long cacheRejections,
            long wrapperFailures,
            long runtimeIntegrityFailures) {
        List<String> values = new ArrayList<>();
        if (!mismatches.isEmpty()) values.add("VERSION_OR_TARGET_MISMATCH");
        if (sourceBindingRejected > 0) values.add("SOURCE_BINDING_REJECTED");
        if (unavailablePlans > 0) values.add("PLAN_UNAVAILABLE");
        if (declined > 0) values.add("TRANSFORMATION_DECLINED");
        if (shadowed > 0) values.add("SHADOWED_TARGET");
        if (containedFailures > 0) values.add("CONTAINED_ADAPTER_FAILURE");
        if (cacheMisses > 0) values.add("CACHE_MISS");
        if (cacheRejections > 0) values.add("CACHE_REJECTION");
        if (wrapperFailures > 0) values.add("WRAPPER_FAILURE");
        if (runtimeIntegrityFailures > 0) values.add("RUNTIME_INTEGRITY_FAILURE");
        return List.copyOf(values);
    }

    private static long sectionMetrics(
            Map<String, Object> adapter, List<String> sections, String... fields) {
        long total = 0;
        for (String section : sections) {
            Map<String, Object> values = object(adapter.get(section));
            for (String field : fields) {
                total = saturatedAdd(total, number(values, field));
            }
        }
        return total;
    }

    private static long runtimeIntegrityFailures(Map<String, Object> adapter) {
        Map<String, Object> integrity = object(adapter.get("combatRuntimeIntegrity"));
        boolean failed = !string(integrity, "failure").isBlank()
                || (bool(integrity, "observed") && !bool(integrity, "assignable"));
        return failed ? 1 : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    @SuppressWarnings("unchecked")
    private static List<String> mismatchDetails(Object value) {
        if (!(value instanceof List<?> evaluations)) {
            return List.of();
        }
        Set<String> exactAlternativeGroups = new LinkedHashSet<>();
        for (Object item : evaluations) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> evaluation = (Map<String, Object>) raw;
            String group = string(evaluation, "alternativeGroup");
            if (bool(evaluation, "exact") && !group.isBlank()) {
                exactAlternativeGroups.add(group);
            }
        }
        Set<String> details = new LinkedHashSet<>();
        for (Object item : evaluations) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> evaluation = (Map<String, Object>) raw;
            if (bool(evaluation, "exact")) {
                continue;
            }
            String alternativeGroup = string(evaluation, "alternativeGroup");
            if (!alternativeGroup.isBlank() && exactAlternativeGroups.contains(alternativeGroup)) {
                continue;
            }
            String target = string(evaluation, "targetId");
            List<String> problems = strings(evaluation.get("problems"));
            String detail = target.isBlank() ? "unknown target" : target;
            if (!problems.isEmpty()) {
                detail += ": " + String.join(", ", problems);
            }
            details.add(detail);
            if (details.size() >= DETAIL_LIMIT) {
                break;
            }
        }
        return List.copyOf(details);
    }

    private static Path requireFile(Path path, String label) throws IOException {
        if (path == null) {
            throw new IOException("Missing " + label + " path");
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IOException("Missing " + label + ": " + absolute);
        }
        return absolute;
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = target.resolveSibling(
                target.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof String text ? text.trim() : "";
    }

    private static boolean bool(Map<String, Object> values, String key) {
        return Boolean.TRUE.equals(values.get(key));
    }

    private static long number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    enum Status {
        ACTIVE,
        PARTIAL,
        SAFE_FALLBACK,
        DISABLED,
        PROBE_ONLY,
        NO_TARGETS,
        ERROR
    }

    record Result(
            Instant generatedAt,
            Path adapterReport,
            Path output,
            Status status,
            String summary,
            boolean failOpenPolicy,
            boolean accelerationsActive,
            boolean originalCodeRetained,
            boolean reviewRecommended,
            String mode,
            boolean transformerInstalled,
            boolean killSwitchActive,
            long registryTargets,
            long exactMatches,
            long sourceBindingRejected,
            long unavailablePlans,
            long transformationsDeclined,
            long transformationsApplied,
            long shadowedTargets,
            long containedFailures,
            long cacheMisses,
            long cacheRejectionSignals,
            long wrapperFailureSignals,
            long runtimeIntegrityFailures,
            List<String> evidenceKinds,
            List<String> mismatchDetails,
            List<String> shadowedBy,
            List<String> suggestedActions) {
        Result {
            evidenceKinds = List.copyOf(evidenceKinds);
            mismatchDetails = List.copyOf(mismatchDetails);
            shadowedBy = List.copyOf(shadowedBy);
            suggestedActions = List.copyOf(suggestedActions);
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("format", FORMAT);
            values.put("generatedAt", generatedAt);
            values.put("adapterReport", adapterReport);
            values.put("output", output);
            values.put("status", status);
            values.put("summary", summary);
            values.put("failOpenPolicy", failOpenPolicy);
            values.put("accelerationsActive", accelerationsActive);
            values.put("originalCodeRetained", originalCodeRetained);
            values.put("reviewRecommended", reviewRecommended);
            values.put("mode", mode);
            values.put("transformerInstalled", transformerInstalled);
            values.put("killSwitchActive", killSwitchActive);
            values.put("registryTargets", registryTargets);
            values.put("exactMatches", exactMatches);
            values.put("sourceBindingRejected", sourceBindingRejected);
            values.put("unavailablePlans", unavailablePlans);
            values.put("transformationsDeclined", transformationsDeclined);
            values.put("transformationsApplied", transformationsApplied);
            values.put("shadowedTargets", shadowedTargets);
            values.put("containedFailures", containedFailures);
            values.put("cacheMisses", cacheMisses);
            values.put("cacheRejectionSignals", cacheRejectionSignals);
            values.put("wrapperFailureSignals", wrapperFailureSignals);
            values.put("runtimeIntegrityFailures", runtimeIntegrityFailures);
            values.put("evidenceKinds", evidenceKinds);
            values.put("mismatchDetails", mismatchDetails);
            values.put("shadowedBy", shadowedBy);
            values.put("suggestedActions", suggestedActions);
            return values;
        }
    }
}

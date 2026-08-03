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
    private static final int DETAIL_LIMIT = 16;

    private AdapterHealthReport() {
    }

    static Result analyze(Path adapterReport, Path output) throws IOException {
        Path source = requireFile(adapterReport, "adapter report");
        Path destination = output.toAbsolutePath().normalize();
        Map<String, Object> adapter = StrictJson.object(Files.readString(source, StandardCharsets.UTF_8));
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

        List<String> mismatchDetails = mismatchDetails(adapter.get("evaluations"));
        List<String> shadowedBy = strings(adapter.get("shadowedBy"));
        boolean fallbackEvidence = sourceBindingRejected > 0
                || unavailablePlans > 0
                || declined > 0
                || shadowed > 0
                || containedFailures > 0
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
        List<String> actions = actions(status, shadowed, containedFailures);
        String summary = summary(status, applied, registryTargets);

        return new Result(
                Instant.now(), source.toAbsolutePath().normalize(), output,
                status, summary, true, applied > 0, originalCodeRetained, reviewRecommended,
                mode, transformerInstalled, killSwitchActive, registryTargets, exactMatches,
                sourceBindingRejected, unavailablePlans, declined, applied, shadowed,
                containedFailures, mismatchDetails, shadowedBy, actions);
    }

    private static String summary(Status status, long applied, long targets) {
        return switch (status) {
            case ACTIVE -> "Preflight applied " + applied + " reviewed transformation(s); no fallback was reported.";
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

    private static List<String> actions(Status status, long shadowed, long failures) {
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
        return List.copyOf(values);
    }

    @SuppressWarnings("unchecked")
    private static List<String> mismatchDetails(Object value) {
        if (!(value instanceof List<?> evaluations)) {
            return List.of();
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
            List<String> mismatchDetails,
            List<String> shadowedBy,
            List<String> suggestedActions) {
        Result {
            mismatchDetails = List.copyOf(mismatchDetails);
            shadowedBy = List.copyOf(shadowedBy);
            suggestedActions = List.copyOf(suggestedActions);
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
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
            values.put("mismatchDetails", mismatchDetails);
            values.put("shadowedBy", shadowedBy);
            values.put("suggestedActions", suggestedActions);
            return values;
        }
    }
}

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Joins exact agent class signatures and source identities with methods observed during image reads. */
final class AdapterProbeAnalysis {
    private static final long MAX_ADAPTER_REPORT_BYTES = 32L * 1024 * 1024;
    private static final long MAX_STARTUP_SUMMARY_BYTES = 32L * 1024 * 1024;
    private static final int OUTPUT_LIMIT = 100;
    private static final int BEHAVIOR_ONLY_LIMIT = 50;
    private static final Comparator<CombinedCandidate> RANKING = Comparator
            .comparingLong(CombinedCandidate::combinedScore)
            .reversed()
            .thenComparing(Comparator.comparingLong(CombinedCandidate::behavioralScore).reversed())
            .thenComparing(Comparator.comparingLong(CombinedCandidate::staticScore).reversed())
            .thenComparing(CombinedCandidate::className)
            .thenComparing(CombinedCandidate::sha256)
            .thenComparing(CombinedCandidate::normalizedSource)
            .thenComparing(CombinedCandidate::loaderClass)
            .thenComparing(CombinedCandidate::loaderName);

    private AdapterProbeAnalysis() {
    }

    static Result analyze(Path adapterReport, Path startupSummary, Path output) throws IOException {
        Path adapter = requireFile(adapterReport, "adapter report");
        Path summary = requireFile(startupSummary, "startup summary");
        Map<String, Object> adapterJson = BoundedEvidenceJson.readObject(
                adapter, MAX_ADAPTER_REPORT_BYTES, "Adapter report");
        Map<String, Object> summaryJson = BoundedEvidenceJson.readObject(
                summary, MAX_STARTUP_SUMMARY_BYTES, "Startup summary");

        List<Map<String, Object>> candidates = candidateReports(adapterJson);
        List<Map<String, Object>> methods = objectList(nested(
                summaryJson, "imageReadStackAttribution", "topMethods"));

        Map<String, BehaviorGroup> behaviorByClass = new TreeMap<>();
        for (Map<String, Object> method : methods) {
            String className = string(method, "className");
            if (className.isBlank()) {
                continue;
            }
            behaviorByClass.computeIfAbsent(className, BehaviorGroup::new).add(method);
        }

        Map<String, Integer> identitiesByClass = new TreeMap<>();
        List<CombinedCandidate> combined = new ArrayList<>();
        Set<String> observedCandidateClasses = new LinkedHashSet<>();
        for (Map<String, Object> candidate : candidates) {
            String className = string(candidate, "className");
            String sha256 = string(candidate, "sha256");
            if (className.isBlank() || sha256.isBlank()) {
                continue;
            }
            observedCandidateClasses.add(className);
            identitiesByClass.merge(className, 1, Integer::sum);
            long staticScore = integer(candidate, "relevanceScore");
            BehaviorGroup behavior = behaviorByClass.get(className);
            long behavioralScore = behavior == null ? 0 : behavior.maximumScore();
            combined.add(new CombinedCandidate(
                    className,
                    sha256,
                    string(candidate, "sourceKind"),
                    nullableString(candidate.get("codeSource")),
                    nullableString(candidate.get("normalizedSource")),
                    nullableString(candidate.get("sourceSha256")),
                    nullableString(candidate.get("sourceHashProblem")),
                    nullableString(candidate.get("loaderClass")),
                    nullableString(candidate.get("loaderName")),
                    staticScore,
                    behavioralScore,
                    saturatedAdd(staticScore, behavioralScore),
                    stringList(candidate.get("evidence")),
                    behavior == null ? List.of() : behavior.methods(),
                    behavior == null ? 0 : behavior.maximumEvents(),
                    behavior == null ? 0 : behavior.maximumBytes()));
        }
        combined.sort(RANKING);

        List<String> ambiguousBehavioralClassNames = identitiesByClass.entrySet().stream()
                .filter(entry -> entry.getValue() > 1 && behaviorByClass.containsKey(entry.getKey()))
                .map(Map.Entry::getKey)
                .toList();
        List<BehaviorGroup> allBehaviorOnly = behaviorByClass.values().stream()
                .filter(group -> !observedCandidateClasses.contains(group.className()))
                .sorted(Comparator.comparingLong(BehaviorGroup::maximumScore)
                        .reversed()
                        .thenComparing(BehaviorGroup::className))
                .toList();
        List<Map<String, Object>> behaviorOnly = allBehaviorOnly.stream()
                .limit(BEHAVIOR_ONLY_LIMIT)
                .map(BehaviorGroup::toMap)
                .toList();

        long matchedIdentities = combined.stream().filter(value -> value.behavioralScore() > 0).count();
        long matchedClassNames = combined.stream()
                .filter(value -> value.behavioralScore() > 0)
                .map(CombinedCandidate::className)
                .distinct()
                .count();
        List<Map<String, Object>> ranked = combined.stream()
                .limit(OUTPUT_LIMIT)
                .map(CombinedCandidate::toMap)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", Instant.now());
        result.put("adapterReport", adapter);
        result.put("startupSummary", summary);
        result.put("output", output.toAbsolutePath().normalize());
        result.put("adapterMode", adapterJson.get("mode"));
        result.put("candidateClasses", combined.size());
        result.put("candidateIdentities", combined.size());
        result.put("distinctCandidateClassNames", identitiesByClass.size());
        result.put("behavioralClasses", behaviorByClass.size());
        result.put("matchedClasses", matchedIdentities);
        result.put("matchedIdentities", matchedIdentities);
        result.put("matchedClassNames", matchedClassNames);
        result.put("ambiguousBehavioralClassNames", ambiguousBehavioralClassNames);
        result.put("behaviorOnlyClasses", allBehaviorOnly.size());
        result.put("behaviorOnlyTruncated", allBehaviorOnly.size() > behaviorOnly.size());
        result.put("rankedCandidates", ranked);
        result.put("behaviorOnly", behaviorOnly);
        result.put("exactClassNameJoin", true);
        result.put("sourceIdentityPreserved", true);
        result.put("automaticAllowlistGenerated", false);
        result.put("liveTransformationEligible", false);
        result.put("requiresHumanReview", true);
        result.put("requiresRealInstallForTargetValidation", true);
        result.put("diagnostics", diagnostics(
                candidates, methods, combined, allBehaviorOnly, ambiguousBehavioralClassNames));
        writeAtomic(output.toAbsolutePath().normalize(), Json.object(result) + System.lineSeparator());
        return new Result(
                output.toAbsolutePath().normalize(),
                combined.size(),
                behaviorByClass.size(),
                matchedIdentities,
                allBehaviorOnly.size(),
                List.copyOf(ranked));
    }

    private static List<Map<String, Object>> candidateReports(Map<String, Object> adapterJson) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        addCandidateReports(merged, objectList(adapterJson.get("candidates")));
        addCandidateReports(merged, objectList(adapterJson.get("rankedCandidates")));
        return List.copyOf(merged.values());
    }

    private static void addCandidateReports(
            Map<String, Map<String, Object>> destination,
            List<Map<String, Object>> candidates) {
        for (Map<String, Object> candidate : candidates) {
            String className = string(candidate, "className");
            String sha256 = string(candidate, "sha256");
            if (className.isBlank() || sha256.isBlank()) {
                continue;
            }
            String key = className
                    + "@" + sha256
                    + "@" + string(candidate, "normalizedSource")
                    + "@" + string(candidate, "loaderClass")
                    + "@" + string(candidate, "loaderName");
            destination.put(key, candidate);
        }
    }

    private static List<String> diagnostics(
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> methods,
            List<CombinedCandidate> combined,
            List<BehaviorGroup> behaviorOnly,
            List<String> ambiguousBehavioralClassNames) {
        List<String> values = new ArrayList<>();
        if (candidates.isEmpty()) {
            values.add("Adapter report contains no ranked or retained candidates");
        }
        if (methods.isEmpty()) {
            values.add("Startup summary contains no image-read stack methods");
        }
        if (!candidates.isEmpty() && !methods.isEmpty()
                && combined.stream().noneMatch(value -> value.behavioralScore() > 0)) {
            values.add("No exact class names overlap between adapter candidates and image-read stack methods");
        }
        if (!behaviorOnly.isEmpty()) {
            values.add("Some behavioral classes were not retained by the adapter probe; inspect behaviorOnly");
        }
        if (!ambiguousBehavioralClassNames.isEmpty()) {
            values.add("Behavioral evidence matches multiple source/loader identities for some class names; human review must choose the correct identity");
        }
        values.add("This report ranks review candidates and never generates an adapter allowlist");
        return List.copyOf(values);
    }

    private static Object nested(Map<String, Object> root, String objectName, String valueName) {
        Object nested = root.get(objectName);
        if (!(nested instanceof Map<?, ?> map)) {
            return null;
        }
        return map.get(valueName);
    }

    private static Path requireFile(Path path, String name) throws IOException {
        if (path == null) {
            throw new IOException("Missing " + name + " path");
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IOException("Missing " + name + ": " + absolute);
        }
        return absolute;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return List.copyOf(result);
    }

    private static String string(Map<String, Object> values, String key) {
        return nullableString(values.get(key));
    }

    private static String nullableString(Object value) {
        return value instanceof String text ? text : "";
    }

    private static long integer(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String text) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
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
            Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
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

    record Result(
            Path output,
            long candidateClasses,
            long behavioralClasses,
            long matchedClasses,
            long behaviorOnlyClasses,
            List<Map<String, Object>> rankedCandidates) {
        Result {
            rankedCandidates = List.copyOf(rankedCandidates);
        }
    }

    private record CombinedCandidate(
            String className,
            String sha256,
            String sourceKind,
            String codeSource,
            String normalizedSource,
            String sourceSha256,
            String sourceHashProblem,
            String loaderClass,
            String loaderName,
            long staticScore,
            long behavioralScore,
            long combinedScore,
            List<String> staticEvidence,
            List<Map<String, Object>> behavioralMethods,
            long maximumEvents,
            long maximumBytes) {
        private CombinedCandidate {
            staticEvidence = List.copyOf(staticEvidence);
            behavioralMethods = List.copyOf(behavioralMethods);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("className", className);
            output.put("sha256", sha256);
            output.put("sourceKind", sourceKind);
            output.put("codeSource", codeSource.isBlank() ? null : codeSource);
            output.put("normalizedSource", normalizedSource.isBlank() ? null : normalizedSource);
            output.put("sourceSha256", sourceSha256.isBlank() ? null : sourceSha256);
            output.put("sourceHashProblem", sourceHashProblem.isBlank() ? null : sourceHashProblem);
            output.put("loaderClass", loaderClass.isBlank() ? null : loaderClass);
            output.put("loaderName", loaderName.isBlank() ? null : loaderName);
            output.put("staticScore", staticScore);
            output.put("behavioralScore", behavioralScore);
            output.put("combinedScore", combinedScore);
            output.put("maximumImageReadEvents", maximumEvents);
            output.put("maximumImageReadBytes", maximumBytes);
            output.put("staticEvidence", staticEvidence);
            output.put("behavioralMethods", behavioralMethods);
            output.put("hasBehavioralEvidence", behavioralScore > 0);
            output.put("reviewCandidate", behavioralScore > 0 || staticScore > 0);
            return output;
        }
    }

    private static final class BehaviorGroup {
        private final String className;
        private final List<Map<String, Object>> methods = new ArrayList<>();
        private long maximumScore;
        private long maximumEvents;
        private long maximumBytes;

        private BehaviorGroup(String className) {
            this.className = className;
        }

        private void add(Map<String, Object> method) {
            methods.add(new LinkedHashMap<>(method));
            maximumScore = Math.max(maximumScore, integer(method, "behavioralScore"));
            maximumEvents = Math.max(maximumEvents, integer(method, "events"));
            maximumBytes = Math.max(maximumBytes, integer(method, "bytes"));
        }

        private String className() {
            return className;
        }

        private long maximumScore() {
            return maximumScore;
        }

        private long maximumEvents() {
            return maximumEvents;
        }

        private long maximumBytes() {
            return maximumBytes;
        }

        private List<Map<String, Object>> methods() {
            return methods.stream()
                    .sorted(Comparator.comparingLong((Map<String, Object> value) -> integer(value, "behavioralScore"))
                            .reversed()
                            .thenComparing(value -> string(value, "methodName"))
                            .thenComparing(value -> string(value, "descriptor")))
                    .map(Map::copyOf)
                    .toList();
        }

        private Map<String, Object> toMap() {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("className", className);
            output.put("maximumBehavioralScore", maximumScore);
            output.put("maximumImageReadEvents", maximumEvents);
            output.put("maximumImageReadBytes", maximumBytes);
            output.put("methods", methods());
            return output;
        }
    }
}

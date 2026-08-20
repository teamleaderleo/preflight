package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compact read-only projection of installation evidence for future CLI/desktop consumers. The model
 * names what evidence exists; it deliberately does not assign compatibility scores or blame.
 */
final class InstallationIntelligence {
    private InstallationIntelligence() {
    }

    static Result scan(Path installRoot) throws IOException {
        ClasspathAudit.Result classpath = ClasspathAudit.scan(installRoot);
        return from(classpath, ModJarApiUsage.scan(classpath));
    }

    static Result from(ClasspathAudit.Result classpath, ModJarApiUsage.Result apiUsage) {
        Map<String, Object> classpathValues = classpath.values();
        Map<String, Object> usageValues = apiUsage.values();
        List<ModInput> mods = mods(classpathValues);
        List<StaticEdge> staticEdges = staticEdges(usageValues);
        Map<String, ApiInput> apiByMod = apiUsageByMod(usageValues);

        Map<String, List<StaticEdge>> edgesBySource = new LinkedHashMap<>();
        for (StaticEdge edge : staticEdges) {
            edgesBySource.computeIfAbsent(edge.fromMod(), ignored -> new ArrayList<>()).add(edge);
        }

        List<Map<String, Object>> modReports = new ArrayList<>();
        for (ModInput mod : mods) {
            List<StaticEdge> outgoing = edgesBySource.getOrDefault(mod.id(), List.of());
            Map<String, StaticEdge> byTarget = new LinkedHashMap<>();
            for (StaticEdge edge : outgoing) {
                byTarget.put(edge.toMod(), edge);
            }

            List<Map<String, Object>> relationships = new ArrayList<>();
            Set<String> emittedTargets = new LinkedHashSet<>();
            for (String dependency : mod.dependencies()) {
                StaticEdge edge = byTarget.get(dependency);
                relationships.add(relationship(
                        dependency,
                        edge == null ? "declared-only" : "declared-and-static",
                        true,
                        edge));
                emittedTargets.add(dependency);
            }
            outgoing.stream()
                    .filter(edge -> !emittedTargets.contains(edge.toMod()))
                    .sorted(Comparator.comparing(StaticEdge::toMod))
                    .forEach(edge -> relationships.add(relationship(
                            edge.toMod(), "static-only", false, edge)));

            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", mod.id());
            value.put("order", mod.order());
            value.put("resolved", mod.resolved());
            value.put("missingDependencies", mod.missingDependencies());
            value.put("dependencyOrderProblems", mod.dependencyOrderProblems());
            value.put("missingDeclaredJars", mod.missingDeclaredJars());
            value.put("undeclaredJars", mod.undeclaredJars());
            value.put("relationships", List.copyOf(relationships));
            ApiInput api = apiByMod.get(mod.id());
            if (api != null) {
                Map<String, Object> apiValue = new LinkedHashMap<>();
                apiValue.put("referencedClasses", api.referencedClasses());
                apiValue.put("sampleClasses", api.sampleClasses());
                apiValue.put("truncated", api.truncated());
                value.put("starsectorApi", apiValue);
            }
            modReports.add(value);
        }

        Map<String, Object> classpathTotals = map(classpathValues.get("totals"));
        Map<String, Object> usageTotals = map(usageValues.get("totals"));
        Map<String, Object> summary = new LinkedHashMap<>();
        copyCount(classpathTotals, summary, "enabledMods");
        copyCount(classpathTotals, summary, "resolvedMods");
        copyCount(classpathTotals, summary, "validJars");
        copyCount(classpathTotals, summary, "malformedJars");
        copyCount(classpathTotals, summary, "missingDependencies");
        copyCount(classpathTotals, summary, "dependencyOrderProblems");
        copyCount(classpathTotals, summary, "duplicateClasses");
        copyCount(usageTotals, summary, "staticModEdges");
        copyCount(usageTotals, summary, "undeclaredStaticModEdges");
        copyCount(usageTotals, summary, "modsReferencingStarsectorApi");
        copyCount(usageTotals, summary, "ambiguousStaticReferences");
        summary.put("truncated", Boolean.TRUE.equals(usageTotals.get("truncated")));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", 1);
        values.put("evidenceKinds", List.of(
                "declared-mod-metadata-v1",
                String.valueOf(usageValues.get("evidenceKind"))));
        values.put("archiveFingerprint", classpathValues.get("archiveFingerprint"));
        values.put("classpathFingerprint", classpathValues.get("classpathFingerprint"));
        values.put("summary", summary);
        values.put("mods", List.copyOf(modReports));
        values.put("ambiguousStaticReferenceSamples",
                listOfMaps(usageValues.get("ambiguousStaticReferenceSamples")));
        values.put("notes", List.of(
                "declared-only means metadata declares the relationship but this static scan did not observe a referenced class supplied by that mod.",
                "static-only means bytecode references a class supplied by another enabled mod without a matching declared dependency; this can be an intentional optional integration.",
                "No relationship kind is a compatibility verdict."));
        return new Result(values);
    }

    private static Map<String, Object> relationship(
            String target, String evidence, boolean declaredDependency, StaticEdge edge) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("targetMod", target);
        value.put("evidence", evidence);
        value.put("declaredDependency", declaredDependency);
        value.put("staticReference", edge != null);
        if (edge != null) {
            value.put("referencedClasses", edge.referencedClasses());
            value.put("sampleClasses", edge.sampleClasses());
            value.put("truncated", edge.truncated());
        }
        return value;
    }

    private static List<ModInput> mods(Map<String, Object> values) {
        List<ModInput> result = new ArrayList<>();
        for (Map<String, Object> mod : listOfMaps(values.get("mods"))) {
            Object idValue = mod.get("id");
            if (!(idValue instanceof String id)) {
                continue;
            }
            int order = mod.get("order") instanceof Number number ? number.intValue() : result.size();
            result.add(new ModInput(
                    id,
                    order,
                    mod.get("directory") != null,
                    strings(mod.get("dependencies")),
                    strings(mod.get("missingDependencies")),
                    strings(mod.get("dependencyOrderProblems")),
                    strings(mod.get("missingDeclaredJars")),
                    strings(mod.get("undeclaredJars"))));
        }
        result.sort(Comparator.comparingInt(ModInput::order).thenComparing(ModInput::id));
        return List.copyOf(result);
    }

    private static List<StaticEdge> staticEdges(Map<String, Object> values) {
        List<StaticEdge> result = new ArrayList<>();
        for (Map<String, Object> edge : listOfMaps(values.get("staticModReferences"))) {
            if (!(edge.get("fromMod") instanceof String from) || !(edge.get("toMod") instanceof String to)) {
                continue;
            }
            result.add(new StaticEdge(
                    from,
                    to,
                    Boolean.TRUE.equals(edge.get("declaredDependency")),
                    number(edge.get("referencedClasses")),
                    strings(edge.get("sampleClasses")),
                    Boolean.TRUE.equals(edge.get("truncated"))));
        }
        result.sort(Comparator.comparing(StaticEdge::fromMod).thenComparing(StaticEdge::toMod));
        return List.copyOf(result);
    }

    private static Map<String, ApiInput> apiUsageByMod(Map<String, Object> values) {
        Map<String, ApiInput> result = new LinkedHashMap<>();
        for (Map<String, Object> usage : listOfMaps(values.get("starsectorApiUsage"))) {
            if (!(usage.get("modId") instanceof String modId)) {
                continue;
            }
            result.put(modId, new ApiInput(
                    number(usage.get("referencedClasses")),
                    strings(usage.get("sampleClasses")),
                    Boolean.TRUE.equals(usage.get("truncated"))));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void copyCount(Map<String, Object> source, Map<String, Object> target, String key) {
        target.put(key, number(source.get(key)));
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> input ? (Map<String, Object>) input : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
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

    record Result(Map<String, Object> values) {
        Result {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        String toJson() {
            return Json.object(values);
        }
    }

    private record ModInput(
            String id,
            int order,
            boolean resolved,
            List<String> dependencies,
            List<String> missingDependencies,
            List<String> dependencyOrderProblems,
            List<String> missingDeclaredJars,
            List<String> undeclaredJars) {
    }

    private record StaticEdge(
            String fromMod,
            String toMod,
            boolean declaredDependency,
            long referencedClasses,
            List<String> sampleClasses,
            boolean truncated) {
    }

    private record ApiInput(long referencedClasses, List<String> sampleClasses, boolean truncated) {
    }
}

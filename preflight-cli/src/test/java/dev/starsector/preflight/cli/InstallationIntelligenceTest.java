package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InstallationIntelligenceTest {
    @Test
    void separatesDeclaredAndStaticEvidenceWithoutLeakingDirectories() {
        Map<String, Object> classpathValues = new LinkedHashMap<>();
        classpathValues.put("archiveFingerprint", "archive-123");
        classpathValues.put("classpathFingerprint", "classpath-456");
        classpathValues.put("totals", Map.of(
                "enabledMods", 4,
                "resolvedMods", 4,
                "validJars", 4,
                "malformedJars", 0,
                "missingDependencies", 1,
                "dependencyOrderProblems", 0,
                "duplicateClasses", 2));
        classpathValues.put("mods", List.of(
                mod("library", 0, List.of(), List.of()),
                mod("declared_only", 1, List.of(), List.of()),
                mod("optional", 2, List.of(), List.of()),
                mod("consumer", 3, List.of("library", "declared_only"), List.of("declared_only"))));

        Map<String, Object> usageValues = new LinkedHashMap<>();
        usageValues.put("evidenceKind", "classfile-static-type-reference-v1");
        usageValues.put("totals", Map.of(
                "staticModEdges", 2,
                "undeclaredStaticModEdges", 1,
                "modsReferencingStarsectorApi", 1,
                "ambiguousStaticReferences", 1,
                "truncated", false));
        usageValues.put("staticModReferences", List.of(
                edge("consumer", "library", true, "library.Api"),
                edge("consumer", "optional", false, "optional.Api")));
        usageValues.put("starsectorApiUsage", List.of(Map.of(
                "modId", "consumer",
                "referencedClasses", 2,
                "sampleClasses", List.of("com.fs.starfarer.api.Global", "com.fs.starfarer.api.combat.ShipAPI"),
                "truncated", false)));
        usageValues.put("ambiguousStaticReferenceSamples", List.of(Map.of(
                "fromMod", "consumer",
                "className", "shared.Api",
                "providerMods", List.of("library", "optional"))));

        InstallationIntelligence.Result result = InstallationIntelligence.from(
                new ClasspathAudit.Result(classpathValues),
                new ModJarApiUsage.Result(usageValues));

        assertEquals(1, result.values().get("schemaVersion"));
        assertEquals(List.of("declared-mod-metadata-v1", "classfile-static-type-reference-v1"),
                result.values().get("evidenceKinds"));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.values().get("summary");
        assertEquals(4L, ((Number) summary.get("enabledMods")).longValue());
        assertEquals(2L, ((Number) summary.get("staticModEdges")).longValue());
        assertEquals(1L, ((Number) summary.get("undeclaredStaticModEdges")).longValue());
        assertEquals(false, summary.get("truncated"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mods = (List<Map<String, Object>>) result.values().get("mods");
        Map<String, Object> consumer = mods.stream()
                .filter(mod -> "consumer".equals(mod.get("id")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relationships = (List<Map<String, Object>>) consumer.get("relationships");
        assertEquals(List.of("library", "declared_only", "optional"),
                relationships.stream().map(item -> (String) item.get("targetMod")).toList());
        assertEquals(List.of("declared-and-static", "declared-only", "static-only"),
                relationships.stream().map(item -> (String) item.get("evidence")).toList());
        assertEquals(List.of("declared_only"), consumer.get("missingDependencies"));

        @SuppressWarnings("unchecked")
        Map<String, Object> api = (Map<String, Object>) consumer.get("starsectorApi");
        assertEquals(2L, ((Number) api.get("referencedClasses")).longValue());

        String json = result.toJson();
        assertFalse(json.contains("/Users/leo/private/Starsector"));
        assertFalse(json.toLowerCase().contains("compatibilityscore"));
    }

    private static Map<String, Object> mod(
            String id, int order, List<String> dependencies, List<String> missingDependencies) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("order", order);
        value.put("directory", Path.of("/Users/leo/private/Starsector/mods/" + id));
        value.put("dependencies", dependencies);
        value.put("missingDependencies", missingDependencies);
        value.put("dependencyOrderProblems", List.of());
        value.put("missingDeclaredJars", List.of());
        value.put("undeclaredJars", List.of());
        return value;
    }

    private static Map<String, Object> edge(
            String from, String to, boolean declaredDependency, String sampleClass) {
        return Map.of(
                "fromMod", from,
                "toMod", to,
                "declaredDependency", declaredDependency,
                "referencedClasses", 1,
                "sampleClasses", List.of(sampleClass),
                "truncated", false);
    }
}

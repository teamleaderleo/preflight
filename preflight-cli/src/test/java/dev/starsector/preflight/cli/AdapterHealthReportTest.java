package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterHealthReportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsActiveWhenEveryObservedTargetApplied() throws Exception {
        Map<String, Object> adapter = base();
        adapter.put("exactMatches", 4);
        adapter.put("transformationsApplied", 4);

        AdapterHealthReport.Result result = analyze(adapter);

        assertEquals(AdapterHealthReport.Status.ACTIVE, result.status());
        assertTrue(result.accelerationsActive());
        assertFalse(result.originalCodeRetained());
        assertFalse(result.reviewRecommended());
    }

    @Test
    void turnsGamePatchMismatchIntoSafeFallbackVerdict() throws Exception {
        Map<String, Object> adapter = base();
        adapter.put("evaluations", List.of(Map.of(
                "targetId", "vanilla-spec-store-0.98a-rc8-json-cache",
                "exact", false,
                "problems", List.of("class SHA-256 differs", "source archive SHA-256 differs"))));

        AdapterHealthReport.Result result = analyze(adapter);

        assertEquals(AdapterHealthReport.Status.SAFE_FALLBACK, result.status());
        assertFalse(result.accelerationsActive());
        assertTrue(result.originalCodeRetained());
        assertTrue(result.reviewRecommended());
        assertTrue(result.mismatchDetails().get(0).contains("class SHA-256 differs"));
    }

    @Test
    void reportsPartialWhenAnotherAgentOwnsOneTarget() throws Exception {
        Map<String, Object> adapter = base();
        adapter.put("exactMatches", 2);
        adapter.put("transformationsApplied", 2);
        adapter.put("shadowedTargets", 1);
        adapter.put("shadowedBy", List.of("com/fs/graphics/TextureLoader <- C:/Starsector/fr.jar"));

        AdapterHealthReport.Result result = analyze(adapter);

        assertEquals(AdapterHealthReport.Status.PARTIAL, result.status());
        assertTrue(result.accelerationsActive());
        assertTrue(result.originalCodeRetained());
        assertTrue(result.suggestedActions().stream().anyMatch(value -> value.contains("another Java agent")));
    }

    @Test
    void killSwitchIsAnIntentionalDisabledState() throws Exception {
        Map<String, Object> adapter = base();
        adapter.put("transformerInstalled", false);
        adapter.put("killSwitchActive", true);

        AdapterHealthReport.Result result = analyze(adapter);

        assertEquals(AdapterHealthReport.Status.DISABLED, result.status());
        assertTrue(result.failOpenPolicy());
        assertTrue(result.originalCodeRetained());
        assertFalse(result.reviewRecommended());
    }

    @Test
    void distinguishesCacheMissesRejectionsWrappersAndRuntimeIntegrity() throws Exception {
        Map<String, Object> adapter = base();
        adapter.put("exactMatches", 2);
        adapter.put("transformationsApplied", 2);
        adapter.put("adapterTransformationCache", Map.of(
                "misses", 3,
                "readFailures", 1));
        adapter.put("textureCompatibility", Map.of(
                "misses", 4,
                "corruptions", 2));
        adapter.put("audioResourceFallback", Map.of("fallbackFailures", 5));
        adapter.put("combatRuntimeIntegrity", Map.of(
                "observed", true,
                "assignable", false,
                "failure", "class identity mismatch"));

        AdapterHealthReport.Result result = analyze(adapter);

        assertEquals(AdapterHealthReport.Status.PARTIAL, result.status());
        assertEquals(7, result.cacheMisses());
        assertEquals(3, result.cacheRejectionSignals());
        assertEquals(5, result.wrapperFailureSignals());
        assertEquals(1, result.runtimeIntegrityFailures());
        assertEquals(List.of(
                "CACHE_MISS",
                "CACHE_REJECTION",
                "WRAPPER_FAILURE",
                "RUNTIME_INTEGRITY_FAILURE"), result.evidenceKinds());
        assertTrue(result.suggestedActions().stream()
                .anyMatch(value -> value.contains("re-run preparation")));
        assertTrue(result.suggestedActions().stream()
                .anyMatch(value -> value.contains("full adapter bypass")));
    }

    @Test
    void ordinaryCacheMissesRemainInformational() throws Exception {
        Map<String, Object> adapter = base();
        adapter.put("exactMatches", 2);
        adapter.put("transformationsApplied", 2);
        adapter.put("mergedReadCache", Map.of("misses", 9));

        AdapterHealthReport.Result result = analyze(adapter);

        assertEquals(AdapterHealthReport.Status.ACTIVE, result.status());
        assertEquals(9, result.cacheMisses());
        assertEquals(List.of("CACHE_MISS"), result.evidenceKinds());
        assertFalse(result.originalCodeRetained());
        assertFalse(result.reviewRecommended());
        assertTrue(result.suggestedActions().isEmpty());
    }

    private AdapterHealthReport.Result analyze(Map<String, Object> adapter) throws Exception {
        Path source = temporaryDirectory.resolve("adapter.json");
        Path output = temporaryDirectory.resolve("adapter-health.json");
        Files.writeString(source, Json.object(adapter));
        AdapterHealthReport.Result result = AdapterHealthReport.analyze(source, output);
        assertTrue(Files.isRegularFile(output));
        Map<String, Object> written = StrictJson.object(Files.readString(output));
        assertEquals(AdapterHealthReport.FORMAT, written.get("format"));
        assertEquals(result.status().name(), written.get("status"));
        return result;
    }

    private static Map<String, Object> base() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("mode", "ENABLED");
        values.put("transformerInstalled", true);
        values.put("killSwitchActive", false);
        values.put("registryTargets", 4);
        values.put("exactMatches", 0);
        values.put("sourceBindingRejected", 0);
        values.put("transformationEligible", 0);
        values.put("transformationDeclined", 0);
        values.put("transformationsApplied", 0);
        values.put("shadowedTargets", 0);
        values.put("containedFailures", 0);
        values.put("evaluations", List.of());
        values.put("shadowedBy", List.of());
        return values;
    }
}

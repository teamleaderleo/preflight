package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SetupAnalysisTest {
    @Test
    void blockersWarningsAndUnknownsHaveOneDeterministicContract() {
        SetupAnalysis.Result result = new SetupAnalysis.Result(
                "game-build-1",
                "profile-1",
                List.of(
                        finding("static.missing-hull", "static-links", SetupAnalysis.Severity.BLOCKING),
                        finding("dependency.optional-missing", "mod-metadata", SetupAnalysis.Severity.WARNING),
                        finding("script.dynamic", "static-links", SetupAnalysis.Severity.UNKNOWN)),
                List.of("save-history"));

        assertFalse(result.ready());
        assertEquals(1, result.count(SetupAnalysis.Severity.BLOCKING));
        assertEquals(1, result.count(SetupAnalysis.Severity.WARNING));
        assertEquals(1, result.count(SetupAnalysis.Severity.UNKNOWN));

        Map<String, Object> json = StrictJson.object(result.toJson());
        assertEquals(SetupAnalysis.FORMAT, json.get("format"));
        assertEquals(false, json.get("ready"));
        assertEquals(List.of("save-history"), json.get("unavailableProviders"));
        List<?> findings = (List<?>) json.get("findings");
        assertEquals("static.missing-hull", ((Map<?, ?>) findings.get(0)).get("code"));
        assertEquals("dependency.optional-missing", ((Map<?, ?>) findings.get(1)).get("code"));
        assertEquals("script.dynamic", ((Map<?, ?>) findings.get(2)).get("code"));
    }

    @Test
    void equivalentInputOrderingProducesByteIdenticalJson() {
        LinkedHashMap<String, Object> firstParameters = new LinkedHashMap<>();
        firstParameters.put("target", "missing-hull");
        firstParameters.put("source", "variant-a");
        LinkedHashMap<String, Object> secondParameters = new LinkedHashMap<>();
        secondParameters.put("source", "variant-a");
        secondParameters.put("target", "missing-hull");

        SetupAnalysis.Finding first = new SetupAnalysis.Finding(
                "static.missing-hull",
                "static-links",
                SetupAnalysis.Severity.BLOCKING,
                "A variant references a missing hull",
                firstParameters,
                List.of("mod-b", "mod-a", "mod-a"),
                List.of("review-profile", "open-details"));
        SetupAnalysis.Finding second = new SetupAnalysis.Finding(
                "static.missing-hull",
                "static-links",
                SetupAnalysis.Severity.BLOCKING,
                "A variant references a missing hull",
                secondParameters,
                List.of("mod-a", "mod-b"),
                List.of("open-details", "review-profile"));

        String a = new SetupAnalysis.Result(
                        "game",
                        "profile",
                        List.of(first, finding("dependency.missing", "mod-metadata", SetupAnalysis.Severity.BLOCKING)),
                        List.of("z-provider", "a-provider"))
                .toJson();
        String b = new SetupAnalysis.Result(
                        "game",
                        "profile",
                        List.of(finding("dependency.missing", "mod-metadata", SetupAnalysis.Severity.BLOCKING), second),
                        List.of("a-provider", "z-provider"))
                .toJson();

        assertEquals(a, b);
    }

    @Test
    void cleanAnalysisIsReady() {
        SetupAnalysis.Result result = new SetupAnalysis.Result("game", "profile", List.of(), List.of());
        assertTrue(result.ready());
    }

    @Test
    void findingAndListBoundsAreExplicit() {
        List<SetupAnalysis.Finding> tooMany = new ArrayList<>();
        for (int i = 0; i < 257; i++) {
            tooMany.add(finding("code-" + i, "provider", SetupAnalysis.Severity.INFO));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new SetupAnalysis.Result("game", "profile", tooMany, List.of()));

        List<String> tooManyMods = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            tooManyMods.add("mod-" + i);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new SetupAnalysis.Finding(
                        "code",
                        "provider",
                        SetupAnalysis.Severity.INFO,
                        "summary",
                        Map.of(),
                        tooManyMods,
                        List.of()));
    }

    private static SetupAnalysis.Finding finding(
            String code, String provider, SetupAnalysis.Severity severity) {
        return new SetupAnalysis.Finding(
                code,
                provider,
                severity,
                "summary " + code,
                Map.of("kind", code),
                List.of(),
                List.of());
    }
}

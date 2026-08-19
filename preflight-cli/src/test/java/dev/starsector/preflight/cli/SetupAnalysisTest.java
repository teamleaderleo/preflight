package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
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
    void composeBoundsTwoFullProvidersWithoutIterationOrderBias() {
        List<SetupAnalysis.Finding> raw = new ArrayList<>();
        for (int index = 0; index < 256; index++) {
            raw.add(finding("a.block", "provider-a", SetupAnalysis.Severity.BLOCKING));
            raw.add(finding("b.block", "provider-b", SetupAnalysis.Severity.BLOCKING));
        }

        SetupAnalysis.Result first = SetupAnalysis.compose(
                "game",
                "profile",
                raw,
                List.of("save-history"));
        List<SetupAnalysis.Finding> reversed = new ArrayList<>(raw);
        Collections.reverse(reversed);
        SetupAnalysis.Result second = SetupAnalysis.compose(
                "game",
                "profile",
                reversed,
                List.of("save-history"));

        assertEquals(256, first.findings().size());
        assertFalse(first.ready());
        assertTrue(first.findings().stream().anyMatch(finding -> finding.provider().equals("provider-a")));
        assertTrue(first.findings().stream().anyMatch(finding -> finding.provider().equals("provider-b")));
        assertTrue(first.findings().stream().anyMatch(finding ->
                finding.code().equals("orchestration.findings-truncated")
                        && finding.severity() == SetupAnalysis.Severity.BLOCKING));
        List<String> summarisedProviders = first.findings().stream()
                .filter(finding -> finding.code().equals("orchestration.suppressed-finding-group"))
                .map(finding -> (String) finding.parameters().get("sourceProvider"))
                .sorted()
                .toList();
        assertEquals(List.of("provider-a", "provider-b"), summarisedProviders);
        assertEquals(first.toJson(), second.toJson());
    }

    @Test
    void blockerNoiseCannotEraseAnotherProvidersSuppressedWarning() {
        List<SetupAnalysis.Finding> raw = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            raw.add(finding("a.block", "provider-a", SetupAnalysis.Severity.BLOCKING));
        }
        for (int index = 0; index < 10; index++) {
            raw.add(finding("b.warn", "provider-b", SetupAnalysis.Severity.WARNING));
        }

        SetupAnalysis.Result result = SetupAnalysis.compose("game", "profile", raw, List.of());

        assertFalse(result.ready());
        assertTrue(result.findings().stream().anyMatch(finding ->
                finding.code().equals("orchestration.suppressed-finding-group")
                        && finding.severity() == SetupAnalysis.Severity.WARNING
                        && "provider-b".equals(finding.parameters().get("sourceProvider"))
                        && "b.warn".equals(finding.parameters().get("sourceCode"))));
        SetupAnalysis.Finding global = result.findings().stream()
                .filter(finding -> finding.code().equals("orchestration.findings-truncated"))
                .findFirst()
                .orElseThrow();
        assertEquals(10L, global.parameters().get("warning"));
        assertTrue((Long) global.parameters().get("blocking") > 0L);
    }

    @Test
    void composeRejectsUnboundedRawProviderFanIn() {
        List<SetupAnalysis.Finding> raw = new ArrayList<>();
        for (int index = 0; index < 8_193; index++) {
            raw.add(finding("info", "provider", SetupAnalysis.Severity.INFO));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> SetupAnalysis.compose("game", "profile", raw, List.of()));
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

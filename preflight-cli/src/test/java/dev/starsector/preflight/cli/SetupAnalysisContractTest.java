package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SetupAnalysisContractTest {
    @Test
    void parametersAreCanonicalizedByKeyAndScalarType() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("z", 2);
        parameters.put("a", new StringBuilder("value"));
        SetupAnalysis.Finding finding = new SetupAnalysis.Finding(
                "dependency.missing",
                "mod-metadata",
                SetupAnalysis.Severity.BLOCKING,
                "A required dependency is missing",
                parameters,
                List.of(),
                List.of());

        assertEquals(List.of("a", "z"), finding.parameters().keySet().stream().toList());
        assertEquals("value", finding.parameters().get("a"));
        assertEquals(2L, finding.parameters().get("z"));
    }

    @Test
    void parameterMapsHaveAnExplicitBound() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int i = 0; i < 33; i++) {
            parameters.put("key-" + i, i);
        }
        assertThrows(IllegalArgumentException.class, () -> finding(parameters));
    }

    @Test
    void nestedAndNonFiniteParameterValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> finding(Map.of("nested", Map.of("key", "value"))));
        assertThrows(IllegalArgumentException.class, () -> finding(Map.of("list", List.of("value"))));
        assertThrows(IllegalArgumentException.class, () -> finding(Map.of("nan", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> finding(Map.of("infinity", Float.POSITIVE_INFINITY)));
    }

    @Test
    void parameterStringsAndResultIdentityAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> finding(Map.of("value", "x".repeat(1_025))));
        assertThrows(IllegalArgumentException.class,
                () -> new SetupAnalysis.Result("", "profile", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SetupAnalysis.Result("game", "x".repeat(257), List.of(), List.of()));
    }

    private static SetupAnalysis.Finding finding(Map<String, Object> parameters) {
        return new SetupAnalysis.Finding(
                "code",
                "provider",
                SetupAnalysis.Severity.INFO,
                "summary",
                parameters,
                List.of(),
                List.of());
    }
}

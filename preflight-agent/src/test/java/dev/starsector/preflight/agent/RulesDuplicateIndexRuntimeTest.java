package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RulesDuplicateIndexRuntimeTest {
    @BeforeEach
    void reset() {
        RulesDuplicateIndexRuntime.beginSession();
        RulesDuplicateIndexRuntime.begin();
    }

    @Test
    void rejectsOnlyTheSameRuleIdUnderTheSameTrigger() {
        RulesDuplicateIndexRuntime.check("trigger-a", "rule-1");
        assertDoesNotThrow(() -> RulesDuplicateIndexRuntime.check("trigger-a", "rule-2"));
        assertDoesNotThrow(() -> RulesDuplicateIndexRuntime.check("trigger-b", "rule-1"));

        RuntimeException duplicate = assertThrows(RuntimeException.class,
                () -> RulesDuplicateIndexRuntime.check("trigger-a", "rule-1"));
        assertEquals("Duplicate rule id: rule-1", duplicate.getMessage());
        assertEquals(4L, RulesDuplicateIndexRuntime.telemetry().get("checks"));
        assertEquals(1L, RulesDuplicateIndexRuntime.telemetry().get("duplicates"));
    }

    @Test
    void discardsTheTemporaryIndexAtCompletion() {
        RulesDuplicateIndexRuntime.check("trigger", "rule");
        RulesDuplicateIndexRuntime.complete();
        assertEquals(false, RulesDuplicateIndexRuntime.telemetry().get("active"));
        assertThrows(IllegalStateException.class,
                () -> RulesDuplicateIndexRuntime.check("trigger", "another"));
    }
}

package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodexLazyFleetMemberRuntimeTest {
    @BeforeEach
    void reset() {
        CodexLazyFleetMemberRuntime.beginSession();
    }

    @Test
    void deferralRetainsExactVariantAndCountsMaterialization() {
        Object variant = new Object();
        assertSame(variant, CodexLazyFleetMemberRuntime.defer(variant));
        CodexLazyFleetMemberRuntime.materialized();
        CodexLazyFleetMemberRuntime.codexInstalled();
        CodexLazyFleetMemberRuntime.entryAccessorInstalled();

        var telemetry = CodexLazyFleetMemberRuntime.telemetry();
        assertEquals(1L, telemetry.get("deferred"));
        assertEquals(1L, telemetry.get("materialized"));
        assertTrue((boolean) telemetry.get("active"));
    }
}

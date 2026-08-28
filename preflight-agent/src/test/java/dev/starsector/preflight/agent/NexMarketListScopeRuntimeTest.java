package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NexMarketListScopeRuntimeTest {
    @BeforeEach
    void clearProperties() {
        System.clearProperty(NexMarketListScopeRuntime.ENABLED_PROPERTY);
        System.clearProperty(NexMarketListScopeRuntime.SHADOW_PROPERTY);
        System.clearProperty(NexMarketListScopeRuntime.DISABLED_PROPERTY);
        NexMarketListScopeRuntime.reset();
    }

    @AfterEach
    void reset() {
        clearProperties();
    }

    @Test
    void candidateReusesIdentityKeyedSnapshotOnlyInsideBalancedScope() {
        System.setProperty(NexMarketListScopeRuntime.ENABLED_PROPERTY, "true");
        NexMarketListScopeRuntime.beginSession();
        NexMarketListScopeRuntime.installedNex();
        NexMarketListScopeRuntime.installedCore();

        Object owner = new Object();
        List<Object> supplied = new ArrayList<>(List.of(new Object(), new Object()));
        assertFalse(NexMarketListScopeRuntime.inScope());
        assertNull(NexMarketListScopeRuntime.reuse(owner));

        NexMarketListScopeRuntime.beginScope();
        assertTrue(NexMarketListScopeRuntime.inScope());
        assertNull(NexMarketListScopeRuntime.reuse(owner));
        assertSame(supplied, NexMarketListScopeRuntime.observe(owner, supplied));
        assertSame(supplied, NexMarketListScopeRuntime.reuse(owner));
        NexMarketListScopeRuntime.endScope();

        assertFalse(NexMarketListScopeRuntime.inScope());
        Map<String, Object> report = NexMarketListScopeRuntime.telemetry();
        assertEquals(1L, report.get("scopesBegun"));
        assertEquals(1L, report.get("scopesEnded"));
        assertEquals(1L, report.get("misses"));
        assertEquals(1L, report.get("stores"));
        assertEquals(1L, report.get("hits"));
        assertEquals(1L, report.get("maximumEntries"));
    }

    @Test
    void shadowValidatesFreshIdentityOrderWithoutServingCachedList() {
        System.setProperty(NexMarketListScopeRuntime.SHADOW_PROPERTY, "true");
        NexMarketListScopeRuntime.beginSession();
        NexMarketListScopeRuntime.installedNex();
        NexMarketListScopeRuntime.installedCore();

        Object first = new Object();
        Object second = new Object();
        Object owner = new Object();
        NexMarketListScopeRuntime.beginScope();
        assertNull(NexMarketListScopeRuntime.reuse(owner));
        NexMarketListScopeRuntime.observe(owner, List.of(first, second));
        assertNull(NexMarketListScopeRuntime.reuse(owner));
        NexMarketListScopeRuntime.observe(owner, List.of(first, second));
        NexMarketListScopeRuntime.endScope();

        Map<String, Object> report = NexMarketListScopeRuntime.telemetry();
        assertEquals(1L, report.get("shadowMatches"));
        assertEquals(0L, report.get("shadowMismatches"));
        assertTrue((Boolean) report.get("healthy"));
    }

    @Test
    void shadowMismatchPermanentlyFallsBackAndDropsRetainedReferences() {
        System.setProperty(NexMarketListScopeRuntime.SHADOW_PROPERTY, "true");
        NexMarketListScopeRuntime.beginSession();
        NexMarketListScopeRuntime.installedNex();
        NexMarketListScopeRuntime.installedCore();

        Object first = new Object();
        Object second = new Object();
        Object owner = new Object();
        NexMarketListScopeRuntime.beginScope();
        NexMarketListScopeRuntime.observe(owner, List.of(first, second));
        NexMarketListScopeRuntime.observe(owner, List.of(second, first));

        assertFalse(NexMarketListScopeRuntime.inScope());
        NexMarketListScopeRuntime.endScope();
        Map<String, Object> report = NexMarketListScopeRuntime.telemetry();
        assertEquals(1L, report.get("shadowMismatches"));
        assertFalse((Boolean) report.get("healthy"));
        assertFalse((Boolean) report.get("shadowEnabled"));
    }

    @Test
    void eitherMissingHalfAndIndependentDisablePropertyRetainOriginalPath() {
        System.setProperty(NexMarketListScopeRuntime.ENABLED_PROPERTY, "true");
        NexMarketListScopeRuntime.beginSession();
        NexMarketListScopeRuntime.installedNex();
        NexMarketListScopeRuntime.beginScope();
        assertFalse(NexMarketListScopeRuntime.inScope());

        System.setProperty(NexMarketListScopeRuntime.DISABLED_PROPERTY, "true");
        NexMarketListScopeRuntime.beginSession();
        NexMarketListScopeRuntime.installedNex();
        NexMarketListScopeRuntime.installedCore();
        NexMarketListScopeRuntime.beginScope();
        assertFalse(NexMarketListScopeRuntime.inScope());
        assertFalse((Boolean) NexMarketListScopeRuntime.telemetry().get("requested"));
    }
}

package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LaunchSettingsCommandContractTest {
    @Test
    void applyRequiresAnExplicitQuiescentConfirmation() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LaunchSettingsCommand.requireQuiescentApply(false));

        assertTrue(failure.getMessage().contains(LaunchSettingsCommand.APPLY_CONFIRMATION_OPTION));
        assertTrue(failure.getMessage().contains("Preflight processes only"));
        assertDoesNotThrow(() -> LaunchSettingsCommand.requireQuiescentApply(true));
    }

    @Test
    void reportNamesTheGlobalBoundaryAndLeaseScope() {
        Map<String, Object> boundary = LaunchSettingsCommand.applyBoundary();

        assertEquals("quiescent-apply-v1", boundary.get("kind"));
        assertEquals("global-starsector-settings", boundary.get("scope"));
        assertEquals(LaunchSettingsCommand.APPLY_INSTRUCTION, boundary.get("instruction"));
        assertEquals(LaunchSettingsCommand.APPLY_CONFIRMATION_LABEL, boundary.get("confirmationLabel"));
        assertEquals(LaunchSettingsCommand.APPLY_CONFIRMATION_OPTION, boundary.get("confirmationOption"));
        assertEquals(LaunchSettingsCommand.APPLY_LEASE_SCOPE, boundary.get("leaseScope"));
    }
}

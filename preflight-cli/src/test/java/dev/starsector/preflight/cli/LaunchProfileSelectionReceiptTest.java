package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchProfileSelectionReceiptTest {
    @TempDir
    Path directory;

    @Test
    void roundTripsExactContentDerivedIdentities() throws Exception {
        String profile = "a".repeat(64);
        LaunchProfileSelectionReceipt.Selection expected =
                new LaunchProfileSelectionReceipt.Selection(
                        "1".repeat(64),
                        "2".repeat(64),
                        "3".repeat(64),
                        "4".repeat(64),
                        "5".repeat(64),
                        null,
                        "6".repeat(64));

        LaunchProfileSelectionReceipt.write(directory, profile, expected);

        LaunchProfileSelectionReceipt.Selection actual = LaunchProfileSelectionReceipt.read(
                LaunchProfileSelectionReceipt.path(directory, profile), profile);
        assertEquals(expected.variantJson(), actual.variantJson());
        assertEquals(expected.weaponJson(), actual.weaponJson());
        assertEquals(expected.projectileJson(), actual.projectileJson());
        assertEquals(expected.hullJson(), actual.hullJson());
        assertEquals(expected.rulesCsv(), actual.rulesCsv());
        assertNull(actual.ruleCommand());
        assertEquals(expected.mergedRead(), actual.mergedRead());
    }

    @Test
    void rejectsAnInvalidSavedIdentity() throws Exception {
        String profile = "a".repeat(64);
        Path receipt = LaunchProfileSelectionReceipt.path(directory, profile);
        Files.createDirectories(receipt.getParent());
        Files.writeString(receipt, """
                {"format":"starsector-preflight-launch-profile-selection-v1",\
                "profileFingerprint":"%s","variantJson":"not-a-hash"}
                """.formatted(profile));

        assertThrows(IOException.class, () -> LaunchProfileSelectionReceipt.read(receipt, profile));
    }
}

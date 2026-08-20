package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SaveProfileObservationModOrderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sessionIdentityKeepsEnabledOrderWhileApplyingTheExistingBound() {
        List<SaveProfileObservation.Mod> mods = new ArrayList<>();
        mods.add(new SaveProfileObservation.Mod("z-last-lexically", null, null));
        mods.add(new SaveProfileObservation.Mod("a-first-lexically", null, null));
        for (int index = 0; index < SaveProfileObservation.MAX_MODS + 8; index++) {
            mods.add(new SaveProfileObservation.Mod("mod-" + index, null, null));
        }

        SaveProfileObservation.SessionIdentity identity = new SaveProfileObservation.SessionIdentity(
                "fingerprint", "Ordered", "build", mods);

        assertEquals(SaveProfileObservation.MAX_MODS, identity.mods().size());
        assertEquals(
                List.of("z-last-lexically", "a-first-lexically"),
                identity.mods().stream().limit(2).map(SaveProfileObservation.Mod::id).toList());
    }

    @Test
    void matchingSavedProfileKeepsEnabledModOrder() throws Exception {
        PreflightHome home = new PreflightHome(temporaryDirectory.resolve("home"), List.of());
        Path install = temporaryDirectory.resolve("Starsector");
        Files.createDirectories(install);
        Files.createDirectories(home.profiles());
        Files.writeString(home.profiles().resolve("ordered.json"), """
                {
                    "name": "Ordered",
                    "installRoot": "%s",
                    "profileFingerprint": "ordered-fingerprint",
                    "savedAt": "%s",
                    "enabledMods": ["mod_z", "mod_a"]
                }
                """.formatted(install.toString().replace("\\", "\\\\"), Instant.now()));

        SaveProfileObservation.Prepared prepared = SaveProfileObservation.testPrepare(
                home, install, "ordered-fingerprint", false);

        assertNotNull(prepared.identity());
        assertEquals(
                List.of("mod_z", "mod_a"),
                prepared.identity().mods().stream().map(SaveProfileObservation.Mod::id).toList());
    }

    @Test
    void observationLedgerRoundTripKeepsOrderAndOrderOnlyChangeIsEvidence() throws Exception {
        PreflightHome home = new PreflightHome(temporaryDirectory.resolve("home"), List.of());
        Path install = temporaryDirectory.resolve("Starsector");
        Path save = install.resolve("saves/manual/save.dat");
        Files.createDirectories(save.getParent());
        Files.writeString(save, "before");

        SaveProfileObservation.SessionIdentity observedIdentity = new SaveProfileObservation.SessionIdentity(
                "same-fingerprint",
                "Ordered",
                "same-build",
                List.of(
                        new SaveProfileObservation.Mod("mod_z", null, null),
                        new SaveProfileObservation.Mod("mod_a", null, null)));
        Instant endedAt = Instant.now().plusSeconds(5);
        SaveProfileObservation.Session session = SaveProfileObservation.testSession(
                home, install, observedIdentity, endedAt.minusSeconds(10), false);
        Files.writeString(save, "after-longer");
        session.scanWhileOwned();
        session.finish(endedAt);

        SaveProfileObservation.Observation observed = SaveProfileObservation.observations(home).get(0);
        assertEquals(
                List.of("mod_z", "mod_a"),
                observed.mods().stream().map(SaveProfileObservation.Mod::id).toList());
        String ledger = Files.readString(SaveProfileObservation.storeFile(home));
        assertEquals(true, ledger.contains("\"modsOrdered\":true"));

        SaveProfileObservation.SessionIdentity reordered = new SaveProfileObservation.SessionIdentity(
                "same-fingerprint",
                "Ordered",
                "same-build",
                List.of(
                        new SaveProfileObservation.Mod("mod_a", null, null),
                        new SaveProfileObservation.Mod("mod_z", null, null)));
        assertEquals(
                List.of(SaveProfileObservation.Difference.MOD_METADATA),
                SaveProfileObservation.differences(observed, reordered));
    }

    @Test
    void legacySortedRowsDoNotPretendHistoricalOrderWasObserved() throws Exception {
        PreflightHome home = new PreflightHome(temporaryDirectory.resolve("legacy-home"), List.of());
        Path store = SaveProfileObservation.storeFile(home);
        Files.createDirectories(store.getParent());
        Files.writeString(store, """
                {
                  "format": "starsector-preflight-save-observations-v1",
                  "records": [
                    {
                      "installationKey": "install",
                      "saveKey": "manual",
                      "saveName": "manual",
                      "stateToken": "token",
                      "observedAt": "2026-08-20T00:00:00Z",
                      "missingSince": null,
                      "profileFingerprint": "same-fingerprint",
                      "profileDisplayName": "Ordered",
                      "gameBuild": "same-build",
                      "mods": [{"id":"mod_a"},{"id":"mod_z"}]
                    }
                  ]
                }
                """);

        SaveProfileObservation.Observation legacy = SaveProfileObservation.observations(home).get(0);
        assertNull(legacy.mods(), "legacy v1 rows did not record whether their mod list preserved enabled order");

        SaveProfileObservation.SessionIdentity current = new SaveProfileObservation.SessionIdentity(
                "same-fingerprint",
                "Ordered",
                "same-build",
                List.of(
                        new SaveProfileObservation.Mod("mod_z", null, null),
                        new SaveProfileObservation.Mod("mod_a", null, null)));
        assertEquals(
                List.of(SaveProfileObservation.Difference.MOD_METADATA_UNAVAILABLE),
                SaveProfileObservation.differences(legacy, current));
    }
}

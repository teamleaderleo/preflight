package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SaveProfileObservationModCompletenessTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sessionIdentityRetainsExactTotalBeyondTheBound() {
        List<SaveProfileObservation.Mod> mods = mods(SaveProfileObservation.MAX_MODS + 7);
        SaveProfileObservation.SessionIdentity identity = new SaveProfileObservation.SessionIdentity(
                "fingerprint", "Large", "build", mods);

        assertEquals(SaveProfileObservation.MAX_MODS, identity.mods().size());
        assertEquals(SaveProfileObservation.MAX_MODS + 7, identity.modCount());
        assertEquals(7, identity.omittedModCount());
        assertTrue(identity.modsTruncated());
        assertFalse(identity.modsComplete());
    }

    @Test
    void savedProfileAndLedgerRoundTripRetainExactTotal() throws Exception {
        PreflightHome home = new PreflightHome(temporaryDirectory.resolve("home"), List.of());
        Path install = temporaryDirectory.resolve("Starsector");
        Path save = install.resolve("saves/manual/save.dat");
        Files.createDirectories(save.getParent());
        Files.writeString(save, "before");
        Files.createDirectories(home.profiles());

        int total = SaveProfileObservation.MAX_MODS + 4;
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < total; index++) ids.add("mod-" + index);
        Files.writeString(home.profiles().resolve("large.json"), Json.object(Map.of(
                "name", "Large",
                "installRoot", install.toString(),
                "profileFingerprint", "large-fingerprint",
                "savedAt", Instant.now().toString(),
                "enabledMods", ids)));

        SaveProfileObservation.Prepared prepared = SaveProfileObservation.testPrepare(
                home, install, "large-fingerprint", false);
        assertEquals(total, prepared.identity().modCount());
        assertEquals(4, prepared.identity().omittedModCount());

        Instant endedAt = Instant.now().plusSeconds(5);
        SaveProfileObservation.Session session = SaveProfileObservation.testSession(
                home, install, prepared.identity(), endedAt.minusSeconds(10), false);
        Files.writeString(save, "after-longer");
        session.scanWhileOwned();
        session.finish(endedAt);

        SaveProfileObservation.Observation observed = SaveProfileObservation.observations(home).get(0);
        assertEquals(SaveProfileObservation.MAX_MODS, observed.mods().size());
        assertEquals(total, observed.modCount());
        assertEquals(4, observed.omittedModCount());
        assertTrue(observed.modsTruncated());
        assertFalse(observed.modsComplete());
    }

    @Test
    void legacyOrderedRowsBelowTheBoundStayCompletenessUnknown() throws Exception {
        SaveProfileObservation.Observation observed = legacyOrderedObservation(
                temporaryDirectory.resolve("small-home"), List.of("mod-a", "mod-b"));
        assertNull(observed.modCount());
        assertFalse(observed.modsComplete());
        SaveProfileObservation.SessionIdentity current = new SaveProfileObservation.SessionIdentity(
                "same-fingerprint", "Current", "same-build", mods("mod-a", "mod-b"));
        assertEquals(
                List.of(SaveProfileObservation.Difference.MOD_METADATA_INCOMPLETE),
                SaveProfileObservation.differences(observed, current));
    }

    @Test
    void legacyOrderedRowsAtTheBoundStayCompletenessUnknown() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < SaveProfileObservation.MAX_MODS; index++) ids.add("mod-" + index);
        SaveProfileObservation.Observation observed = legacyOrderedObservation(
                temporaryDirectory.resolve("bound-home"), ids);
        assertNull(observed.modCount());
        assertFalse(observed.modsComplete());
        SaveProfileObservation.SessionIdentity current = new SaveProfileObservation.SessionIdentity(
                "same-fingerprint", "Current", "same-build", mods(SaveProfileObservation.MAX_MODS));
        assertEquals(
                List.of(SaveProfileObservation.Difference.MOD_METADATA_INCOMPLETE),
                SaveProfileObservation.differences(observed, current));
    }

    @Test
    void equalTruncatedPrefixesNeverProveFullEquality() {
        List<SaveProfileObservation.Mod> retained = mods(SaveProfileObservation.MAX_MODS);
        int total = SaveProfileObservation.MAX_MODS + 4;
        SaveProfileObservation.Observation observed = new SaveProfileObservation.Observation(
                "install", "save", "save", "token", Instant.parse("2026-08-20T00:00:00Z"), null,
                "same-fingerprint", "Large", "same-build", retained, total);
        SaveProfileObservation.SessionIdentity current = new SaveProfileObservation.SessionIdentity(
                "same-fingerprint", "Large", "same-build", retained, total);
        assertEquals(
                List.of(SaveProfileObservation.Difference.MOD_METADATA_INCOMPLETE),
                SaveProfileObservation.differences(observed, current));
    }

    @Test
    void knownEmptyProfileIsComplete() {
        SaveProfileObservation.SessionIdentity identity = new SaveProfileObservation.SessionIdentity(
                "fingerprint", "Vanilla", "build", List.of());
        assertEquals(0, identity.modCount());
        assertEquals(0, identity.omittedModCount());
        assertTrue(identity.modsComplete());
        assertFalse(identity.modsTruncated());
    }

    private SaveProfileObservation.Observation legacyOrderedObservation(
            Path homeRoot, List<String> ids) throws Exception {
        PreflightHome home = new PreflightHome(homeRoot, List.of());
        Path store = SaveProfileObservation.storeFile(home);
        Files.createDirectories(store.getParent());
        List<Map<String, Object>> mods = ids.stream()
                .map(id -> Map.<String, Object>of("id", id))
                .toList();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("installationKey", "install");
        record.put("saveKey", "manual");
        record.put("saveName", "manual");
        record.put("stateToken", "token");
        record.put("observedAt", "2026-08-20T00:00:00Z");
        record.put("profileFingerprint", "same-fingerprint");
        record.put("profileDisplayName", "Legacy");
        record.put("gameBuild", "same-build");
        record.put("modsOrdered", true);
        record.put("mods", mods);
        Files.writeString(store, Json.object(Map.of(
                "format", "starsector-preflight-save-observations-v1",
                "records", List.of(record))));
        return SaveProfileObservation.observations(home).get(0);
    }

    private static List<SaveProfileObservation.Mod> mods(String... ids) {
        return java.util.Arrays.stream(ids)
                .map(id -> new SaveProfileObservation.Mod(id, null, null))
                .toList();
    }

    private static List<SaveProfileObservation.Mod> mods(int count) {
        List<SaveProfileObservation.Mod> mods = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            mods.add(new SaveProfileObservation.Mod("mod-" + index, null, null));
        }
        return List.copyOf(mods);
    }
}

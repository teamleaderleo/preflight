package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SaveProfileObservationTest {
    @TempDir
    Path temp;

    @Test
    void recordsOneChangedSaveWithLastObservedWording() throws Exception {
        Fixture fixture = fixture("manual", "before");
        Instant start = Instant.now().minusSeconds(2);
        SaveProfileObservation.Session session = fixture.session(heavy(), start);

        Files.writeString(fixture.saveFile("manual"), "after-longer");
        session.scanWhileOwned();
        session.finish(Instant.now().plusSeconds(2));

        List<SaveProfileObservation.Observation> observations = fixture.observations();
        assertEquals(1, observations.size());
        SaveProfileObservation.Observation observed = observations.get(0);
        assertEquals("Heavy Mods", observed.profileDisplayName());
        assertEquals("profile-heavy", observed.profileFingerprint());
        assertEquals("build-1", observed.gameBuild());
        assertEquals("This save was last observed with ‘Heavy Mods’.", observed.lastObservedMessage());
    }

    @Test
    void recordsAutosaveAndManualSaveFromOneOwnedSession() throws Exception {
        Fixture fixture = fixture("manual", "m0");
        fixture.createSave("autosave", "a0");
        SaveProfileObservation.Session session = fixture.session(heavy(), Instant.now().minusSeconds(2));

        Files.writeString(fixture.saveFile("manual"), "m1-more");
        Files.writeString(fixture.saveFile("autosave"), "a1-more");
        session.scanWhileOwned();
        session.finish(Instant.now().plusSeconds(2));

        assertEquals(List.of("autosave", "manual"), fixture.observations().stream()
                .map(SaveProfileObservation.Observation::saveKey)
                .sorted()
                .toList());
    }

    @Test
    void latestProfileSessionReplacesEarlierAssociation() throws Exception {
        Fixture fixture = fixture("manual", "v0");
        SaveProfileObservation.Session first = fixture.session(heavy(), Instant.now().minusSeconds(5));
        Files.writeString(fixture.saveFile("manual"), "v1-long");
        first.scanWhileOwned();
        first.finish(Instant.now().minusSeconds(3));

        SaveProfileObservation.Session second = fixture.session(light(), Instant.now().minusSeconds(2));
        Files.writeString(fixture.saveFile("manual"), "v2-even-longer");
        second.scanWhileOwned();
        second.finish(Instant.now().plusSeconds(1));

        List<SaveProfileObservation.Observation> observations = fixture.observations();
        assertEquals(1, observations.size());
        assertEquals("profile-light", observations.get(0).profileFingerprint());
        assertEquals("Light Mods", observations.get(0).profileDisplayName());
    }

    @Test
    void finishingAfterAWriteRecordsEvenForAnAbnormalExitPath() throws Exception {
        Fixture fixture = fixture("manual", "before");
        SaveProfileObservation.Session session = fixture.session(heavy(), Instant.now().minusSeconds(2));
        Files.writeString(fixture.saveFile("manual"), "written-before-crash");
        session.scanWhileOwned();

        // Session.finish is deliberately independent of child exit code. ChildProcessOutput invokes
        // it from finally after normal, non-zero, interrupted, and capture-failure termination.
        session.finish(Instant.now().plusSeconds(2));

        assertEquals(1, fixture.observations().size());
    }

    @Test
    void renameCopyAndDeleteOutsidePreflightDoNotTransferAssociation() throws Exception {
        Fixture fixture = fixture("manual", "before");
        SaveProfileObservation.Session session = fixture.session(heavy(), Instant.now().minusSeconds(5));
        Files.writeString(fixture.saveFile("manual"), "during-owned-session");
        session.scanWhileOwned();
        session.finish(Instant.now().minusSeconds(3));

        Path renamed = fixture.saves.resolve("renamed");
        Files.move(fixture.saves.resolve("manual"), renamed);
        Path copied = fixture.saves.resolve("copied");
        Files.createDirectories(copied);
        Files.copy(renamed.resolve("save.dat"), copied.resolve("save.dat"));

        SaveProfileObservation.Session afterRename = fixture.session(light(), Instant.now().minusSeconds(2));
        afterRename.finish(Instant.now().plusSeconds(1));
        List<SaveProfileObservation.Observation> retained = fixture.observations();
        assertEquals(1, retained.size());
        assertEquals("manual", retained.get(0).saveKey());
        assertNotNull(retained.get(0).missingSince());
        assertTrue(retained.stream().noneMatch(value -> value.saveKey().equals("renamed")));
        assertTrue(retained.stream().noneMatch(value -> value.saveKey().equals("copied")));

        deleteRecursively(renamed);
        deleteRecursively(copied);
        SaveProfileObservation.Session afterDelete = fixture.session(light(), Instant.now().plusSeconds(2));
        afterDelete.finish(Instant.now().plusSeconds(3));
        assertEquals(1, fixture.observations().size());
    }

    @Test
    void outsideModificationInvalidatesPreviousAssociationAtNextBaseline() throws Exception {
        Fixture fixture = fixture("manual", "before");
        SaveProfileObservation.Session first = fixture.session(heavy(), Instant.now().minusSeconds(5));
        Files.writeString(fixture.saveFile("manual"), "inside");
        first.scanWhileOwned();
        first.finish(Instant.now().minusSeconds(3));
        assertEquals(1, fixture.observations().size());

        Files.writeString(fixture.saveFile("manual"), "manual-outside-change");
        SaveProfileObservation.Session next = fixture.session(light(), Instant.now().minusSeconds(1));
        next.finish(Instant.now().plusSeconds(1));

        assertTrue(fixture.observations().isEmpty());
    }

    @Test
    void historicalLabelSurvivesProfileDeletionBecauseObservationOwnsItsLabel() throws Exception {
        Fixture fixture = fixture("manual", "before");
        SaveProfileObservation.Session session = fixture.session(heavy(), Instant.now().minusSeconds(2));
        Files.writeString(fixture.saveFile("manual"), "after");
        session.scanWhileOwned();
        session.finish(Instant.now().plusSeconds(2));

        // There is no saved profile file in this fixture at all. The observation remains historical
        // evidence and never recreates or activates a profile.
        SaveProfileObservation.Observation observed = fixture.observations().get(0);
        assertEquals("Heavy Mods", observed.profileDisplayName());
        assertEquals("This save was last observed with ‘Heavy Mods’.", observed.lastObservedMessage());
    }

    @Test
    void gameBuildChangeIsPreservedAndPresentedDeterministically() throws Exception {
        Fixture fixture = fixture("manual", "before");
        SaveProfileObservation.Session session = fixture.session(heavy(), Instant.now().minusSeconds(2));
        Files.writeString(fixture.saveFile("manual"), "after");
        session.scanWhileOwned();
        session.finish(Instant.now().plusSeconds(1));
        SaveProfileObservation.Observation observed = fixture.observations().get(0);

        SaveProfileObservation.SessionIdentity current = new SaveProfileObservation.SessionIdentity(
                "profile-light",
                "Light Mods",
                "build-2",
                List.of(new SaveProfileObservation.Mod("zz", "Zed", "2")));
        assertEquals(
                List.of(
                        SaveProfileObservation.Difference.PROFILE_FINGERPRINT,
                        SaveProfileObservation.Difference.GAME_BUILD,
                        SaveProfileObservation.Difference.MOD_METADATA),
                SaveProfileObservation.differences(observed, current));
    }

    @Test
    void noSaveChangesCreatesNoAssociation() throws Exception {
        Fixture fixture = fixture("manual", "unchanged");
        SaveProfileObservation.Session session = fixture.session(heavy(), Instant.now().minusSeconds(1));
        session.scanWhileOwned();
        session.finish(Instant.now().plusSeconds(1));
        assertTrue(fixture.observations().isEmpty());
    }

    @Test
    void changesBeforeAndAfterOwnedLifetimeStayUnassociated() throws Exception {
        Fixture fixture = fixture("manual", "initial");
        Files.writeString(fixture.saveFile("manual"), "changed-before-start");
        SaveProfileObservation.Session session = fixture.session(heavy(), Instant.now().minusSeconds(1));
        session.finish(Instant.now().plusSeconds(1));
        Files.writeString(fixture.saveFile("manual"), "changed-after-end");
        assertTrue(fixture.observations().isEmpty());

        SaveProfileObservation.Session next = fixture.session(light(), Instant.now().plusSeconds(2));
        next.finish(Instant.now().plusSeconds(3));
        assertTrue(fixture.observations().isEmpty());
    }

    @Test
    void finalScanRejectsMetadataNewerThanOwnedProcessEndBoundary() throws Exception {
        Fixture fixture = fixture("manual", "before");
        Instant end = Instant.now().plusSeconds(2);
        SaveProfileObservation.Session session = fixture.session(heavy(), Instant.now().minusSeconds(2));
        Files.writeString(fixture.saveFile("manual"), "outside-after-process-exit");
        Files.setLastModifiedTime(fixture.saveFile("manual"), FileTime.from(end.plusSeconds(5)));

        session.finish(end);

        assertTrue(fixture.observations().isEmpty());
    }

    @Test
    void finalScanIncludesWriteAtOrBeforeOwnedProcessEndBoundary() throws Exception {
        Fixture fixture = fixture("manual", "before");
        Instant end = Instant.now().plusSeconds(5);
        SaveProfileObservation.Session session = fixture.session(heavy(), Instant.now().minusSeconds(2));
        Files.writeString(fixture.saveFile("manual"), "owned-write");
        Files.setLastModifiedTime(fixture.saveFile("manual"), FileTime.from(end.minusMillis(1)));

        session.finish(end);

        assertEquals(1, fixture.observations().size());
    }

    @Test
    void modMetadataIsBoundedAndSortedForStableDifferencePresentation() {
        SaveProfileObservation.SessionIdentity identity = new SaveProfileObservation.SessionIdentity(
                "fingerprint",
                "Name",
                "build",
                List.of(
                        new SaveProfileObservation.Mod("z", "Zulu", "1"),
                        new SaveProfileObservation.Mod("a", "Alpha", "2")));
        assertEquals(List.of("a", "z"), identity.mods().stream()
                .map(SaveProfileObservation.Mod::id)
                .toList());

        List<SaveProfileObservation.Mod> many = java.util.stream.IntStream.range(0, 120)
                .mapToObj(index -> new SaveProfileObservation.Mod("mod-" + index, "", ""))
                .toList();
        SaveProfileObservation.SessionIdentity bounded = new SaveProfileObservation.SessionIdentity(
                "fingerprint", null, "build", many);
        assertEquals(SaveProfileObservation.MAX_MODS, bounded.mods().size());
    }

    @Test
    void saveNamesStayLocalAndExistingPublicProjectionDropsPrivateLedgerFields() throws Exception {
        Fixture fixture = fixture("Captain Alice Secret Save", "before");
        SaveProfileObservation.Session session = fixture.session(heavy(), Instant.now().minusSeconds(2));
        Files.writeString(fixture.saveFile("Captain Alice Secret Save"), "after");
        session.scanWhileOwned();
        session.finish(Instant.now().plusSeconds(1));

        Path store = SaveProfileObservation.storeFile(fixture.home);
        String local = Files.readString(store);
        assertTrue(local.contains("Captain Alice Secret Save"));
        assertFalse(local.contains("after"));

        String projected = new String(SupportEvidenceProjection.project(FILE_NAME(store), local));
        assertFalse(projected.contains("Captain Alice Secret Save"));
        assertFalse(projected.contains(fixture.install.toString()));
        assertFalse(projected.contains("saveName"));
        assertFalse(projected.contains("stateToken"));
    }

    @Test
    void retentionCapsRecordCountAndAgesOutOldObservations() throws Exception {
        Fixture fixture = fixture("save-000", "before");
        for (int index = 1; index < SaveProfileObservation.MAX_RECORDS + 8; index++) {
            fixture.createSave(String.format("save-%03d", index), "before");
        }
        Instant now = Instant.now();
        SaveProfileObservation.Session session = fixture.session(heavy(), now.minusSeconds(2));
        for (int index = 0; index < SaveProfileObservation.MAX_RECORDS + 8; index++) {
            Files.writeString(fixture.saveFile(String.format("save-%03d", index)), "after-more");
        }
        session.scanWhileOwned();
        session.finish(now.plusSeconds(2));
        assertEquals(SaveProfileObservation.MAX_RECORDS, fixture.observations().size());

        SaveProfileObservation.Session aged = fixture.session(
                light(), now.plus(SaveProfileObservation.MAX_AGE).plusSeconds(10));
        aged.finish(now.plus(SaveProfileObservation.MAX_AGE).plusSeconds(11));
        assertTrue(fixture.observations().isEmpty());
    }

    @Test
    void disappearedSaveIsCleanedAfterMissingRetention() throws Exception {
        Fixture fixture = fixture("manual", "before");
        Instant now = Instant.now();
        SaveProfileObservation.Session first = fixture.session(heavy(), now.minusSeconds(2));
        Files.writeString(fixture.saveFile("manual"), "after");
        first.scanWhileOwned();
        first.finish(now);
        deleteRecursively(fixture.saves.resolve("manual"));

        SaveProfileObservation.Session missing = fixture.session(light(), now.plusSeconds(1));
        missing.finish(now.plusSeconds(2));
        assertNotNull(fixture.observations().get(0).missingSince());

        Instant expired = now.plus(SaveProfileObservation.MISSING_RETENTION).plusSeconds(5);
        SaveProfileObservation.Session cleanup = fixture.session(light(), expired);
        cleanup.finish(expired.plusSeconds(1));
        assertTrue(fixture.observations().isEmpty());
    }

    @Test
    void caseOnlyRenameUsesSameSaveIdentityOnCaseInsensitiveFilesystems() throws Exception {
        Fixture fixture = fixture("Manual", "before");
        Instant now = Instant.now();
        SaveProfileObservation.Session first = SaveProfileObservation.testSession(
                fixture.home, fixture.install, heavy(), now.minusSeconds(2), true);
        Files.writeString(fixture.saveFile("Manual"), "after");
        first.scanWhileOwned();
        first.finish(now);
        Files.move(fixture.saves.resolve("Manual"), fixture.saves.resolve("manual"));

        SaveProfileObservation.Session next = SaveProfileObservation.testSession(
                fixture.home, fixture.install, light(), now.plusSeconds(1), true);
        next.finish(now.plusSeconds(2));

        List<SaveProfileObservation.Observation> observations = fixture.observations();
        assertEquals(1, observations.size());
        assertEquals("manual", observations.get(0).saveKey());
        assertNull(observations.get(0).missingSince());
    }

    private Fixture fixture(String saveName, String contents) throws Exception {
        PreflightHome home = new PreflightHome(temp.resolve("preflight-home"));
        Path install = temp.resolve("Starsector");
        Path saves = install.resolve("saves");
        Files.createDirectories(saves);
        Fixture fixture = new Fixture(home, install, saves);
        fixture.createSave(saveName, contents);
        return fixture;
    }

    private static SaveProfileObservation.SessionIdentity heavy() {
        return new SaveProfileObservation.SessionIdentity(
                "profile-heavy",
                "Heavy Mods",
                "build-1",
                List.of(
                        new SaveProfileObservation.Mod("graphicslib", "GraphicsLib", "1.0"),
                        new SaveProfileObservation.Mod("nexerelin", "Nexerelin", "2.0")));
    }

    private static SaveProfileObservation.SessionIdentity light() {
        return new SaveProfileObservation.SessionIdentity(
                "profile-light",
                "Light Mods",
                "build-1",
                List.of(new SaveProfileObservation.Mod("graphicslib", "GraphicsLib", "1.0")));
    }

    private static String FILE_NAME(Path path) {
        return path.getFileName().toString();
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Fixture(PreflightHome home, Path install, Path saves) {
        void createSave(String name, String contents) throws Exception {
            Path directory = saves.resolve(name);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("save.dat"), contents);
        }

        Path saveFile(String name) {
            return saves.resolve(name).resolve("save.dat");
        }

        SaveProfileObservation.Session session(
                SaveProfileObservation.SessionIdentity identity,
                Instant start) throws Exception {
            return SaveProfileObservation.testSession(home, install, identity, start, false);
        }

        List<SaveProfileObservation.Observation> observations() throws Exception {
            return SaveProfileObservation.observations(home);
        }
    }
}

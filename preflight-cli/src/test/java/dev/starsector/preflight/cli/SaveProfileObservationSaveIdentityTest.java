package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SaveProfileObservationSaveIdentityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void whitespaceDistinctSaveDirectoriesKeepDistinctIdentityKeys() throws Exception {
        Fixture fixture = fixture();
        Path plain = fixture.saves().resolve("Campaign");
        Path spaced = fixture.saves().resolve("Campaign ");
        requireDistinctDirectories(plain, spaced);
        Files.writeString(plain.resolve("save.dat"), "plain-before");
        Files.writeString(spaced.resolve("save.dat"), "spaced-before");

        Instant endedAt = Instant.now().plusSeconds(5);
        SaveProfileObservation.Session session = SaveProfileObservation.testSession(
                fixture.home(), fixture.install(), identity(), endedAt.minusSeconds(10), true);
        Files.writeString(plain.resolve("save.dat"), "plain-after-longer");
        Files.writeString(spaced.resolve("save.dat"), "spaced-after-longer");
        session.scanWhileOwned();
        session.finish(endedAt);

        List<SaveProfileObservation.Observation> observations = SaveProfileObservation.observations(fixture.home());
        assertEquals(
                List.of("Campaign", "Campaign "),
                observations.stream().map(SaveProfileObservation.Observation::saveKey).sorted().toList());
        assertEquals(
                List.of("Campaign", "Campaign"),
                observations.stream().map(SaveProfileObservation.Observation::saveName).sorted().toList(),
                "bounded display labels may normalize whitespace without becoming identity authority");
    }

    @Test
    void whitespaceOnlySaveDirectorySurvivesLedgerRoundTripWhenFilesystemSupportsIt() throws Exception {
        Fixture fixture = fixture();
        Path whitespace = fixture.saves().resolve(" ");
        try {
            Files.createDirectories(whitespace);
        } catch (IOException unsupported) {
            assumeTrue(false, "test filesystem does not support an exact whitespace-only directory name");
        }
        boolean exactNamePresent;
        try (var entries = Files.list(fixture.saves())) {
            exactNamePresent = entries.anyMatch(path -> path.getFileName().toString().equals(" "));
        }
        assumeTrue(exactNamePresent, "test filesystem normalizes the whitespace-only directory name");
        Files.writeString(whitespace.resolve("save.dat"), "before");

        Instant endedAt = Instant.now().plusSeconds(5);
        SaveProfileObservation.Session session = SaveProfileObservation.testSession(
                fixture.home(), fixture.install(), identity(), endedAt.minusSeconds(10), true);
        Files.writeString(whitespace.resolve("save.dat"), "after-longer");
        session.scanWhileOwned();
        session.finish(endedAt);

        List<SaveProfileObservation.Observation> observations = SaveProfileObservation.observations(fixture.home());
        assertEquals(1, observations.size());
        assertEquals(" ", observations.get(0).saveKey());
        assertEquals("", observations.get(0).saveName());
    }

    @Test
    void caseDistinctSaveDirectoriesKeepDistinctIdentityKeysWhenFilesystemSupportsThem() throws Exception {
        Fixture fixture = fixture();
        Path upper = fixture.saves().resolve("Campaign");
        Path lower = fixture.saves().resolve("campaign");
        requireDistinctDirectories(upper, lower);
        Files.writeString(upper.resolve("save.dat"), "upper-before");
        Files.writeString(lower.resolve("save.dat"), "lower-before");

        Instant endedAt = Instant.now().plusSeconds(5);
        SaveProfileObservation.Session session = SaveProfileObservation.testSession(
                fixture.home(), fixture.install(), identity(), endedAt.minusSeconds(10), true);
        Files.writeString(upper.resolve("save.dat"), "upper-after-longer");
        Files.writeString(lower.resolve("save.dat"), "lower-after-longer");
        session.scanWhileOwned();
        session.finish(endedAt);

        List<String> keys = SaveProfileObservation.observations(fixture.home()).stream()
                .map(SaveProfileObservation.Observation::saveKey)
                .sorted()
                .toList();
        assertEquals(List.of("Campaign", "campaign"), keys);
    }

    private Fixture fixture() throws IOException {
        PreflightHome home = new PreflightHome(temporaryDirectory.resolve("preflight-home"), List.of());
        Path install = temporaryDirectory.resolve("Starsector");
        Path saves = install.resolve("saves");
        Files.createDirectories(saves);
        return new Fixture(home, install, saves);
    }

    private static SaveProfileObservation.SessionIdentity identity() {
        return new SaveProfileObservation.SessionIdentity(
                "profile-fingerprint", "Profile", "build", List.of());
    }

    private static void requireDistinctDirectories(Path first, Path second) throws Exception {
        Files.createDirectories(first);
        try {
            Files.createDirectories(second);
        } catch (IOException unsupported) {
            assumeTrue(false, "test filesystem does not support the requested distinct directory spelling");
        }
        assertTrue(Files.isDirectory(first));
        assertTrue(Files.isDirectory(second));
        assumeFalse(
                Files.isSameFile(first, second),
                "test filesystem aliases these directory spellings and cannot exercise this counterexample");
    }

    private record Fixture(PreflightHome home, Path install, Path saves) {
    }
}

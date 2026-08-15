package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The import that lets an existing user see their real total instead of a zero.
 *
 * <p>The property that matters most is that running it twice cannot double anybody's hours.
 */
class LaunchLedgerBackfillTest {
    @TempDir
    Path root;

    @Test
    void pastLaunchesBecomeHistoryOldestFirst() throws IOException {
        PreflightHome home = new PreflightHome(root, List.of());
        writeRun("20260719-072149-398-aaaaaaaa", "2026-07-19T07:21:49Z", "2026-07-19T09:21:49Z");
        writeRun("20260813-021521-920-bbbbbbbb", "2026-08-13T02:15:21Z", "2026-08-13T02:16:31Z");

        assertEquals(2, LaunchLedgerBackfill.runOnce(home));

        List<LaunchLedger.Entry> entries = LaunchLedger.read(home);
        assertEquals(2, entries.size());
        assertEquals("20260719-072149-398-aaaaaaaa", entries.get(0).runDirectory());
        assertEquals(2 * 60 * 60_000L, entries.get(0).elapsedMillis());
        assertEquals(
                "59b01dc050f39a9f07053bd168cc8c1ecd55086b429b2d732456f87ca217a702",
                entries.get(0).profileFingerprint());
        assertTrue(Files.exists(LaunchLedgerBackfill.marker(home)));
    }

    @Test
    void runningItAgainCannotDoubleAnybodysHours() throws IOException {
        PreflightHome home = new PreflightHome(root, List.of());
        writeRun("20260719-072149-398-aaaaaaaa", "2026-07-19T07:21:49Z", "2026-07-19T09:21:49Z");

        assertEquals(1, LaunchLedgerBackfill.runOnce(home));
        assertEquals(0, LaunchLedgerBackfill.runOnce(home), "the marker stops a second pass");
        assertEquals(1, LaunchLedger.read(home).size());
    }

    @Test
    void aLostMarkerStillCannotDoubleAnybodysHours() throws IOException {
        // The marker is the fast path, not the guarantee: an upgrade interrupted between writing
        // the rows and writing the marker must not import the same launches twice.
        PreflightHome home = new PreflightHome(root, List.of());
        writeRun("20260719-072149-398-aaaaaaaa", "2026-07-19T07:21:49Z", "2026-07-19T09:21:49Z");
        LaunchLedgerBackfill.runOnce(home);
        Files.delete(LaunchLedgerBackfill.marker(home));

        assertEquals(0, LaunchLedgerBackfill.runOnce(home));
        assertEquals(1, LaunchLedger.read(home).size());
    }

    @Test
    void aRunDirectoryThatWillNotParseCostsOnlyItself() throws IOException {
        PreflightHome home = new PreflightHome(root, List.of());
        writeRun("20260719-072149-398-aaaaaaaa", "2026-07-19T07:21:49Z", "2026-07-19T09:21:49Z");
        Path broken = Files.createDirectories(root.resolve("runs").resolve("broken"));
        Files.writeString(broken.resolve("run.json"), "{not json", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("runs").resolve("no-metadata-at-all"));

        assertEquals(1, LaunchLedgerBackfill.runOnce(home));
        assertEquals(1, LaunchLedger.read(home).size());
    }

    @Test
    void aLaunchWithNoRecordedEndIsStillHistory() throws IOException {
        PreflightHome home = new PreflightHome(root, List.of());
        writeRun("20260719-072149-398-aaaaaaaa", "2026-07-19T07:21:49Z", null);

        assertEquals(1, LaunchLedgerBackfill.runOnce(home));
        LaunchLedger.Entry entry = LaunchLedger.read(home).get(0);
        assertEquals(null, entry.elapsedMillis());
        assertEquals(0, Playtime.of(List.of(entry)).launches(), "no duration, no hours");
    }

    @Test
    void simultaneousBackfillsCannotDuplicateARecordedLaunch() throws Exception {
        PreflightHome home = new PreflightHome(root, List.of());
        writeRun("20260719-072149-398-aaaaaaaa", "2026-07-19T07:21:49Z", "2026-07-19T09:21:49Z");
        ExecutorService tasks = Executors.newFixedThreadPool(2);
        try {
            List<Integer> results = tasks.invokeAll(List.of(
                            (Callable<Integer>) () -> LaunchLedgerBackfill.runOnce(home),
                            () -> LaunchLedgerBackfill.runOnce(home)))
                    .stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception error) {
                            throw new AssertionError(error);
                        }
                    })
                    .toList();
            assertEquals(1, results.stream().mapToInt(Integer::intValue).sum());
        } finally {
            tasks.shutdownNow();
        }
        assertEquals(1, LaunchLedger.read(home).size());
    }

    @Test
    void aSymlinkedBackfillMarkerCannotRedirectWritesOutsidePreflight() throws IOException {
        PreflightHome home = new PreflightHome(root.resolve("home"), List.of());
        Files.createDirectories(LaunchLedger.path(home).getParent());
        Path outside = root.resolve("outside-marker");
        Files.writeString(outside, "outside stays unchanged\n", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(LaunchLedgerBackfill.marker(home), outside);
        } catch (UnsupportedOperationException | SecurityException | IOException error) {
            assumeTrue(false, "Symbolic links aren't available in this test environment: " + error);
        }
        writeRun("20260719-072149-398-aaaaaaaa", "2026-07-19T07:21:49Z", "2026-07-19T09:21:49Z");

        assertEquals(0, LaunchLedgerBackfill.runOnce(home));
        assertEquals("outside stays unchanged\n", Files.readString(outside));
        assertEquals(0, LaunchLedger.read(home).size());
    }

    @Test
    void aSymlinkedRunDirectoryIsNotImported() throws IOException {
        PreflightHome home = new PreflightHome(root.resolve("home"), List.of());
        Path outside = root.resolve("outside-run");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("run.json"),
                "{\"started\":\"2026-07-19T07:21:49Z\",\"ended\":\"2026-07-19T09:21:49Z\",\"outcome\":\"COMPLETED\"}",
                StandardCharsets.UTF_8);
        Path runs = Files.createDirectories(home.runs());
        try {
            Files.createSymbolicLink(runs.resolve("redirected"), outside);
        } catch (UnsupportedOperationException | SecurityException | IOException error) {
            assumeTrue(false, "Symbolic links aren't available in this test environment: " + error);
        }

        assertEquals(0, LaunchLedgerBackfill.runOnce(home));
        assertEquals(0, LaunchLedger.read(home).size());
    }

    /** The fields a real run.json carries, in the shape RunCommand writes them. */
    private void writeRun(String name, String started, String ended) throws IOException {
        Path directory = Files.createDirectories(root.resolve("runs").resolve(name));
        String endedField = ended == null ? "null" : "\"" + ended + "\"";
        Files.writeString(
                directory.resolve("run.json"),
                "{\"started\":\"" + started + "\","
                        + "\"ended\":" + endedField + ","
                        + "\"exitCode\":0,"
                        + "\"outcome\":\"COMPLETED\","
                        + "\"lifecycleEvidence\":{\"fatalDetected\":false},"
                        + "\"optimizationPreset\":\"recommended\","
                        + "\"disabledOptimizationDomains\":[],"
                        + "\"textureProfileFingerprint\":"
                        + "\"59b01dc050f39a9f07053bd168cc8c1ecd55086b429b2d732456f87ca217a702\"}",
                StandardCharsets.UTF_8);
    }

    @Test
    void aReimportRecognisesTheSameLaunchRatherThanCountingItTwice() throws IOException {
        // The directory name does not identify a launch -- a named trace directory is reused across
        // runs, and eleven rows collided that way on a real machine. The id is derived from what the
        // run recorded, so re-reading the same directory yields the same launch.
        PreflightHome home = new PreflightHome(root, List.of());
        writeRun("20260719-072149-398-aaaaaaaa", "2026-07-19T07:21:49Z", "2026-07-19T09:21:49Z");
        LaunchLedgerBackfill.runOnce(home);
        String firstId = LaunchLedger.read(home).get(0).launchId();

        Files.delete(LaunchLedgerBackfill.marker(home));
        LaunchLedgerBackfill.runOnce(home);

        List<LaunchLedger.Entry> entries = LaunchLedger.read(home);
        assertEquals(1, entries.size());
        assertEquals(firstId, entries.get(0).launchId(), "the same past launch keeps its name");
    }

    @Test
    void twoDifferentLaunchesSharingADirectoryNameAreTwoLaunches() {
        String morning = LaunchIdentity.imported(
                java.time.Instant.parse("2026-08-15T09:00:00Z"), "failed-trace");
        String evening = LaunchIdentity.imported(
                java.time.Instant.parse("2026-08-15T21:00:00Z"), "failed-trace");

        assertTrue(!morning.equals(evening), "same place, different launches");
    }
}

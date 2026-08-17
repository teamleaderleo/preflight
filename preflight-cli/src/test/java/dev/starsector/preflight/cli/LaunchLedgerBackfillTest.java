package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
    void anInterruptedLaunchWithHeartbeatIsRecoveredWithDuration() throws IOException {
        PreflightHome home = new PreflightHome(root, List.of());
        String launchId = UUID.randomUUID().toString();
        Path dir = writeInterrupted("20260817-010000-000-interrupted", launchId, 9000000, 999_999_991L);

        assertEquals(1, LaunchLedgerBackfill.runOnce(home));
        LaunchLedger.Entry entry = LaunchLedger.read(home).get(0);
        assertEquals("INTERRUPTED", entry.outcome());
        assertEquals(9000000L, entry.elapsedMillis());
        assertEquals(1, Playtime.of(List.of(entry)).launches(), "interrupted launch duration counts");
        assertEquals(9000000L, Playtime.of(List.of(entry)).totalMillis());
        assertTrue(Files.notExists(dir.resolve(LaunchHeartbeat.FILE_NAME)));
        assertEquals(0, LaunchLedgerBackfill.runOnce(home), "recovery is exact-once");
    }

    @Test
    void aLaterInterruptedLaunchIsRecoveredAfterHistoricalMarkerExists() throws IOException {
        PreflightHome home = new PreflightHome(root, List.of());
        assertEquals(0, LaunchLedgerBackfill.runOnce(home));
        assertTrue(Files.exists(LaunchLedgerBackfill.marker(home)));
        writeInterrupted("later", UUID.randomUUID().toString(), 42_000L, 999_999_992L);

        assertEquals(1, LaunchLedgerBackfill.runOnce(home));
        assertEquals(42_000L, LaunchLedger.read(home).get(0).elapsedMillis());
    }

    @Test
    void finalizedMetadataKeepsItsMonotonicDurationWhenLedgerAppendWasInterrupted() throws IOException {
        PreflightHome home = new PreflightHome(root, List.of());
        String launchId = UUID.randomUUID().toString();
        Path directory = writeInterrupted("finalized", launchId, 41_000L, 999_999_995L);
        String metadata = Files.readString(directory.resolve("run.json"));
        Files.writeString(
                directory.resolve("run.json"),
                metadata
                        .replace("\"ended\":null", "\"ended\":\"2026-08-17T02:00:00Z\"")
                        .replace("\"outcome\":\"RUNNING\"", "\"elapsedMillis\":42000,\"outcome\":\"COMPLETED\""),
                StandardCharsets.UTF_8);

        assertEquals(1, LaunchLedgerBackfill.runOnce(home));
        LaunchLedger.Entry recovered = LaunchLedger.read(home).get(0);
        assertEquals("COMPLETED", recovered.outcome());
        assertEquals(42_000L, recovered.elapsedMillis());
    }

    @Test
    void anActiveOwnerIsNeverImportedAsInterrupted() throws IOException {
        PreflightHome home = new PreflightHome(root, List.of());
        ProcessHandle owner = ProcessHandle.current();
        Instant ownerStarted = owner.info().startInstant().orElseThrow();
        String launchId = UUID.randomUUID().toString();
        writeInterrupted("active", launchId, 42_000L, owner.pid(), ownerStarted);

        assertEquals(0, LaunchLedgerBackfill.runOnce(home));
        assertEquals(0, LaunchLedger.read(home).size());
        assertTrue(Files.exists(root.resolve("runs/active/heartbeat.json")));
    }

    @Test
    void mismatchedProcessIdentityFailsClosed() throws IOException {
        PreflightHome home = new PreflightHome(root, List.of());
        String launchId = UUID.randomUUID().toString();
        Path directory = writeInterrupted("mismatch", launchId, 42_000L, 999_999_993L);
        String metadata = Files.readString(directory.resolve("run.json"));
        Files.writeString(
                directory.resolve("run.json"),
                metadata.replace("999999993", "999999994"),
                StandardCharsets.UTF_8);

        assertEquals(0, LaunchLedgerBackfill.runOnce(home));
        assertEquals(0, LaunchLedger.read(home).size());
        assertTrue(Files.exists(directory.resolve("heartbeat.json")));
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

    private Path writeInterrupted(String name, String launchId, long elapsedMillis, long ownerPid)
            throws IOException {
        return writeInterrupted(
                name,
                launchId,
                elapsedMillis,
                ownerPid,
                Instant.parse("2026-08-17T00:59:00Z"));
    }

    private Path writeInterrupted(
            String name,
            String launchId,
            long elapsedMillis,
            long ownerPid,
            Instant ownerStartedAt) throws IOException {
        Path directory = Files.createDirectories(root.resolve("runs").resolve(name));
        Instant started = Instant.parse("2026-08-17T01:00:00Z");
        Files.writeString(
                directory.resolve("run.json"),
                "{\"launchId\":\"" + launchId + "\","
                        + "\"wrapperPid\":" + ownerPid + ","
                        + "\"wrapperStartedAt\":\"" + ownerStartedAt + "\","
                        + "\"started\":\"" + started + "\","
                        + "\"ended\":null,\"outcome\":\"RUNNING\"}",
                StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve(LaunchHeartbeat.FILE_NAME),
                "{\"format\":\"" + LaunchHeartbeat.FORMAT + "\","
                        + "\"launchId\":\"" + launchId + "\","
                        + "\"ownerPid\":" + ownerPid + ","
                        + "\"ownerStartedAt\":\"" + ownerStartedAt + "\","
                        + "\"started\":\"" + started + "\","
                        + "\"lastHeartbeat\":\"2026-08-17T03:30:00Z\","
                        + "\"elapsedMillis\":" + elapsedMillis + "}",
                StandardCharsets.UTF_8);
        return directory;
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

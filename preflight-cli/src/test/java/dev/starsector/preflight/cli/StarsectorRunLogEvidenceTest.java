package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StarsectorRunLogEvidenceTest {
    private static final String FATAL =
            "328814 [Thread-3] ERROR com.fs.starfarer.combat.CombatMain  - "
                    + "java.lang.NullPointerException: synthetic fatal\n"
                    + "java.lang.NullPointerException: synthetic fatal\n"
                    + "\tat com.fs.graphics.LayeredRenderer.renderExcluding(Unknown Source)\n"
                    + "\tat com.fs.starfarer.combat.CombatMain.main(Unknown Source)\n";

    @TempDir
    Path temporaryDirectory;

    @Test
    void detectsFatalBytesAppendedDuringRunAndOverridesZeroExit() throws Exception {
        Path log = log("0 [main] INFO com.fs.starfarer.StarfarerLauncher  - Starting\n");
        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(temporaryDirectory);

        Files.writeString(log, FATAL, java.nio.file.StandardOpenOption.APPEND);
        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before);

        assertTrue(evidence.fatalDetected());
        assertEquals(1, evidence.matches().size());
        assertEquals("combat-main-top-level", evidence.matches().get(0).get("category"));
        assertEquals(StarsectorRunLogEvidence.FATAL_LIFECYCLE_EXIT,
                StarsectorRunLogEvidence.effectiveExitCode(0, evidence));
        assertEquals(23, StarsectorRunLogEvidence.effectiveExitCode(23, evidence));
    }

    @Test
    void detectsFatalChildConsoleWhenLauncherReturnsZero() throws Exception {
        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(temporaryDirectory);
        Path console = temporaryDirectory.resolve("run/console.txt");
        Files.createDirectories(console.getParent());
        String text = "FATAL com.fs.starfarer.launcher.opengl.GLLauncher - java.lang.IllegalArgumentException: synthetic\n";
        Files.writeString(console, text);
        ChildProcessOutput.Result capture = new ChildProcessOutput.Result(
                0, console, text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, false);

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before, capture);

        assertTrue(evidence.consoleAvailable());
        assertTrue(evidence.fatalDetected());
        assertEquals("console.txt", evidence.consoleFile());
        assertEquals("launcher-fatal", evidence.matches().get(0).get("category"));
        assertEquals("console.txt", evidence.matches().get(0).get("consoleFile"));
        assertEquals(StarsectorRunLogEvidence.FATAL_LIFECYCLE_EXIT,
                StarsectorRunLogEvidence.effectiveExitCode(0, evidence));
    }

    @Test
    void ignoresFatalEvidenceThatPredatesTheRun() throws Exception {
        log(FATAL);
        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(temporaryDirectory);

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before);

        assertFalse(evidence.fatalDetected());
        assertEquals(0, evidence.bytesExamined());
        assertEquals(0, StarsectorRunLogEvidence.effectiveExitCode(0, evidence));
    }

    @Test
    void doesNotTreatACombatMainLoggerErrorWithoutTopLevelStackAsFatal() throws Exception {
        Path log = log("0 [main] INFO com.fs.starfarer.StarfarerLauncher  - Starting\n");
        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(temporaryDirectory);

        Files.writeString(log,
                "100 [Thread-3] ERROR com.fs.starfarer.combat.CombatMain  - recoverable synthetic error\n"
                        + "\tat example.Mod.recover(Unknown Source)\n",
                java.nio.file.StandardOpenOption.APPEND);

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before);
        assertFalse(evidence.fatalDetected());
    }

    @Test
    void controllerStopDowngradesOnlyReviewedOpenAlCleanupSignatures() throws Exception {
        Path log = log("0 [main] INFO com.fs.starfarer.StarfarerLauncher  - Starting\n");
        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(temporaryDirectory);
        String cleanup = "100 [main] ERROR com.fs.starfarer.combat.CombatMain  - Error cleaning up\n"
                + "101 [main] ERROR com.fs.starfarer.combat.CombatMain  - "
                + "java.lang.UnsatisfiedLinkError: 'int org.lwjgl.openal.AL10.nalGetError()'\n"
                + "java.lang.UnsatisfiedLinkError: synthetic\n"
                + "\tat org.lwjgl.openal.AL10.nalGetError(Native Method)\n"
                + "\tat com.fs.starfarer.combat.CombatMain.main(Unknown Source)\n"
                + "102 [main] ERROR com.fs.starfarer.combat.CombatMain  - "
                + "java.lang.UnsatisfiedLinkError: unrelated\n"
                + "java.lang.UnsatisfiedLinkError: unrelated\n"
                + "\tat example.Native.call(Native Method)\n"
                + "\tat com.fs.starfarer.combat.CombatMain.main(Unknown Source)\n";
        Files.writeString(log, cleanup, java.nio.file.StandardOpenOption.APPEND);

        StarsectorRunLogEvidence.Evidence controlled =
                StarsectorRunLogEvidence.inspect(before, null, true);
        assertTrue(controlled.fatalDetected());
        assertTrue(controlled.controllerStopRequested());
        assertEquals(1, controlled.matches().size());
        assertEquals(1, controlled.ignoredMatches().size());
        assertEquals("controller-stop-openal-cleanup",
                controlled.ignoredMatches().get(0).get("category"));

        StarsectorRunLogEvidence.Evidence ordinary =
                StarsectorRunLogEvidence.inspect(before, null, false);
        assertTrue(ordinary.fatalDetected());
        assertEquals(2, ordinary.matches().size());
        assertTrue(ordinary.ignoredMatches().isEmpty());
    }

    @Test
    void controllerStopReceiptMustMatchTheStrictRuntimeIdentity() throws Exception {
        Path run = temporaryDirectory.resolve("run");
        Files.createDirectories(run);
        Files.writeString(run.resolve("runtime-process.json"), """
                {"format":"starsector-preflight-runtime-process-v1","pid":1234,
                 "parentPid":null,"startedAt":"2026-08-27T01:00:00Z",
                 "observedAt":"2026-08-27T01:00:01Z","state":"stopped",
                 "stoppedAt":"2026-08-27T01:00:02Z"}
                """);
        Path receipt = run.resolve(StarsectorRunLogEvidence.CONTROLLER_STOP_FILE);
        Files.writeString(receipt,
                "{\"pid\":1234,\"startedAt\":\"2026-08-27T01:00:00Z\"}\n");
        assertTrue(StarsectorRunLogEvidence.exactControllerStopRequested(run));

        Files.writeString(receipt,
                "{\"pid\":1235,\"startedAt\":\"2026-08-27T01:00:00Z\"}\n");
        assertFalse(StarsectorRunLogEvidence.exactControllerStopRequested(run));
        Files.writeString(receipt,
                "{\"pid\":1234,\"startedAt\":\"2026-08-27T01:00:00Z\",\"extra\":true}\n");
        assertFalse(StarsectorRunLogEvidence.exactControllerStopRequested(run));
    }

    @Test
    void followsTheSnapshottedFileAcrossLogRotation() throws Exception {
        Path current = log("0 [main] INFO com.fs.starfarer.StarfarerLauncher  - Starting\n");
        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(temporaryDirectory);

        Path rotated = current.resolveSibling("starsector.log.1");
        Files.move(current, rotated, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(rotated, FATAL, java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(current, "0 [main] INFO com.fs.starfarer.StarfarerLauncher  - Restarted\n");

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before);

        assertTrue(evidence.fatalDetected());
        assertEquals("starsector.log.1", evidence.matches().get(0).get("logFile"));
    }

    @Test
    void prioritizesNewestLogsWhenTheFileLimitIsExceeded() throws Exception {
        Path logs = temporaryDirectory.resolve("logs");
        Files.createDirectories(logs);
        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(temporaryDirectory);

        for (int i = 0; i < 10; i++) {
            Files.writeString(logs.resolve("starsector.log." + i), i == 9 ? FATAL : "ordinary log\n");
        }

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before);
        assertTrue(evidence.fatalDetected());
        assertTrue(evidence.truncated());
        assertEquals("starsector.log.9", evidence.matches().get(0).get("logFile"));
    }

    private Path log(String contents) throws Exception {
        Path logs = temporaryDirectory.resolve("logs");
        Files.createDirectories(logs);
        return Files.writeString(logs.resolve("starsector.log"), contents);
    }
}

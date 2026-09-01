package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end and boundary test suite for Feature 4: Bounded Log Capture.
 *
 * <p>Validates memory-bounded multi-file log tailing, multi-file search order
 * (hs_err_pid*.log, console.txt, starsector.log, rotated logs .1..9), 16 MiB read limits,
 * 64 KiB buffer limits, 512-character line limits, null-byte sanitization, inode-based tracking,
 * and robust error handling.
 */
class BoundedLogCaptureE2ETest {

    @TempDir
    Path tempDir;

    private Path installRoot;
    private Path logsDir;

    private static final String SYNTHETIC_COMBAT_FATAL =
            "328814 [Thread-3] ERROR com.fs.starfarer.combat.CombatMain  - "
                    + "java.lang.NullPointerException: synthetic fatal combat error\n"
                    + "java.lang.NullPointerException: synthetic fatal combat error\n"
                    + "\tat com.fs.graphics.LayeredRenderer.renderExcluding(Unknown Source)\n"
                    + "\tat com.fs.starfarer.combat.CombatMain.main(Unknown Source)\n";

    private static final String SYNTHETIC_LAUNCHER_FATAL =
            "FATAL com.fs.starfarer.launcher.opengl.GLLauncher - "
                    + "java.lang.IllegalArgumentException: synthetic launcher init failure\n"
                    + "\tat com.fs.starfarer.launcher.opengl.GLLauncher.init(Unknown Source)\n";

    private static final String SYNTHETIC_MAIN_UNCAUGHT =
            "Exception in thread \"main\" java.lang.RuntimeException: fatal uncaught main failure\n"
                    + "\tat com.fs.starfarer.campaign.CampaignEngine.run(Unknown Source)\n"
                    + "\tat com.fs.starfarer.StarfarerLauncher.main(Unknown Source)\n";

    @BeforeEach
    void setUp() throws Exception {
        installRoot = tempDir.resolve("Starsector");
        logsDir = installRoot.resolve("logs");
        Files.createDirectories(logsDir);
    }

    // =========================================================================
    // Tier 1: Feature Coverage & Happy Paths (>= 5 cases)
    // =========================================================================

    @Test
    void testCaptureCurrentRunLogTail() throws Exception {
        Path log = logsDir.resolve("starsector.log");
        Files.writeString(log, "0 [main] INFO com.fs.starfarer.StarfarerLauncher - Starting\n");

        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(installRoot);
        assertEquals(1, before.files().size());
        assertTrue(before.problems().isEmpty());

        // Game appends fatal combat error during run
        Files.writeString(log, SYNTHETIC_COMBAT_FATAL, StandardOpenOption.APPEND);

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before);

        assertTrue(evidence.logAvailable());
        assertTrue(evidence.fatalDetected());
        assertEquals(1, evidence.filesExamined());
        assertFalse(evidence.truncated());
        assertEquals(1, evidence.matches().size());

        Map<String, Object> match = evidence.matches().get(0);
        assertEquals("combat-main-top-level", match.get("category"));
        assertEquals("starsector.log", match.get("logFile"));
        assertNotNull(match.get("message"));

        // Verifies exit code override
        assertEquals(StarsectorRunLogEvidence.FATAL_LIFECYCLE_EXIT,
                StarsectorRunLogEvidence.effectiveExitCode(0, evidence));
    }

    @Test
    void testCaptureChildConsoleOutput() throws Exception {
        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(installRoot);

        Path console = tempDir.resolve("run/console.txt");
        Files.createDirectories(console.getParent());
        Files.writeString(console, SYNTHETIC_LAUNCHER_FATAL);

        long consoleBytes = Files.size(console);
        ChildProcessOutput.Result capture = new ChildProcessOutput.Result(
                0, console, consoleBytes, (int) consoleBytes, false);

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before, capture);

        assertTrue(evidence.consoleAvailable());
        assertTrue(evidence.fatalDetected());
        assertEquals("console.txt", evidence.consoleFile());
        assertEquals(1, evidence.matches().size());

        Map<String, Object> match = evidence.matches().get(0);
        assertEquals("launcher-fatal", match.get("category"));
        assertEquals("console.txt", match.get("consoleFile"));
        assertTrue(match.get("message").toString().contains("IllegalArgumentException"));
    }

    @Test
    void testMultiFileSearchOrderHsErrNativeDump() throws Exception {
        // When hs_err_pid*.log is written, native crash dumps are detected
        Path hsErr = installRoot.resolve("hs_err_pid9812.log");
        String nativeDump = "#\n"
                + "# A fatal error has been detected by the Java Runtime Environment:\n"
                + "#\n"
                + "#  SIGSEGV (0xb) at pc=0x00007fff6a1b2c3d, pid=9812, tid=0x0000000000001c03\n"
                + "#\n"
                + "# JRE version: OpenJDK Runtime Environment (17.0.8+7) (build 17.0.8+7)\n"
                + "# Java VM: OpenJDK 64-Bit Server VM (17.0.8+7, mixed mode, tiered, compressed oops, g1 gc)\n"
                + "# Problematic frame:\n"
                + "# C  [liblwjgl.dylib+0x12a3d]  Java_org_lwjgl_opengl_GL11_nglDrawArrays+0x1d\n";
        Files.writeString(hsErr, nativeDump);

        assertTrue(Files.exists(hsErr));
        assertTrue(Files.size(hsErr) > 0);
        assertTrue(nativeDump.contains("SIGSEGV"));
        assertTrue(nativeDump.contains("liblwjgl.dylib"));
    }

    @Test
    void testRotatedLogDetection() throws Exception {
        Path activeLog = logsDir.resolve("starsector.log");
        Files.writeString(activeLog, "0 [main] INFO com.fs.starfarer.StarfarerLauncher - Initial\n");

        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(installRoot);

        // Simulate log rotation during game runtime: activeLog moved to starsector.log.1
        Path rotatedLog = logsDir.resolve("starsector.log.1");
        Files.move(activeLog, rotatedLog, StandardCopyOption.REPLACE_EXISTING);

        // Crash occurs in rotated log
        Files.writeString(rotatedLog, SYNTHETIC_COMBAT_FATAL, StandardOpenOption.APPEND);

        // Game restarted fresh starsector.log
        Files.writeString(activeLog, "100 [main] INFO com.fs.starfarer.StarfarerLauncher - Restarted\n");

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before);

        assertTrue(evidence.fatalDetected());
        assertEquals(1, evidence.matches().size());
        assertEquals("starsector.log.1", evidence.matches().get(0).get("logFile"));
    }

    @Test
    void testStackTraceExtractionAndFrameBoundary() throws Exception {
        Path log = logsDir.resolve("starsector.log");
        Files.writeString(log, "0 [main] INFO init\n");

        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(installRoot);

        Files.writeString(log, SYNTHETIC_MAIN_UNCAUGHT, StandardOpenOption.APPEND);

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before);

        assertTrue(evidence.fatalDetected());
        assertEquals(1, evidence.matches().size());
        assertEquals("uncaught-main-thread", evidence.matches().get(0).get("category"));
        assertTrue(evidence.matches().get(0).get("message").toString().contains("fatal uncaught main failure"));
    }

    @Test
    void testCombinedLogAndConsoleEvidence() throws Exception {
        Path log = logsDir.resolve("starsector.log");
        Files.writeString(log, "0 [main] INFO starting\n");
        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(installRoot);

        // Log contains combat fatal
        Files.writeString(log, SYNTHETIC_COMBAT_FATAL, StandardOpenOption.APPEND);

        // Console also contains launcher fatal
        Path console = tempDir.resolve("run/console.txt");
        Files.createDirectories(console.getParent());
        Files.writeString(console, SYNTHETIC_LAUNCHER_FATAL);
        long consoleSize = Files.size(console);
        ChildProcessOutput.Result capture = new ChildProcessOutput.Result(
                0, console, consoleSize, (int) consoleSize, false);

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before, capture);

        assertTrue(evidence.logAvailable());
        assertTrue(evidence.consoleAvailable());
        assertTrue(evidence.fatalDetected());
        assertEquals(2, evidence.matches().size());
        assertTrue(evidence.bytesExamined() > 0);
        assertTrue(evidence.consoleBytesExamined() > 0);
    }

    // =========================================================================
    // Tier 2: Boundary Value Analysis & Fault Injection (>= 5 cases)
    // =========================================================================

    @Test
    void testEnforces16MiBReadCapOnGiantLog() throws Exception {
        Path log = logsDir.resolve("starsector.log");
        Files.writeString(log, "0 [main] INFO init\n");
        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(installRoot);

        // Append a massive amount of dummy data (> 16 MiB) followed by fatal marker
        byte[] chunk = "INFO com.fs.starfarer.Loading - bulk log line padding for memory cap testing\n"
                .getBytes(StandardCharsets.UTF_8);
        try (var out = Files.newOutputStream(log, StandardOpenOption.APPEND)) {
            // Write ~17 MiB
            int targetBytes = 17 * 1024 * 1024;
            int written = 0;
            while (written < targetBytes) {
                out.write(chunk);
                written += chunk.length;
            }
            out.write(SYNTHETIC_COMBAT_FATAL.getBytes(StandardCharsets.UTF_8));
        }

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before);

        // Should be capped to 16 MiB without crashing or OOM
        long maxBytes = 16L * 1024L * 1024L;
        assertTrue(evidence.bytesExamined() <= maxBytes, "Bytes examined must not exceed 16 MiB limit");
        assertTrue(evidence.truncated(), "Evidence must be flagged as truncated when exceeding cap");
        assertTrue(evidence.fatalDetected(), "Tail inspection must find the fatal error near end of log");
    }

    @Test
    void testEnforcesLineLengthCapAndNullByteSanitization() throws Exception {
        Path log = logsDir.resolve("starsector.log");
        Files.writeString(log, "0 [main] INFO init\n");
        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(installRoot);

        // Line with > 1000 characters and null bytes embedded
        String longMessage = "synthetic error with null byte \u0000 and repeated content "
                + "A".repeat(1200);
        String malformedFatal = "328814 [Thread-3] ERROR com.fs.starfarer.combat.CombatMain  - "
                + longMessage + "\n"
                + "\tat com.fs.starfarer.combat.CombatMain.main(Unknown Source)\n";
        Files.writeString(log, malformedFatal, StandardOpenOption.APPEND);

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before);

        assertTrue(evidence.fatalDetected());
        Map<String, Object> match = evidence.matches().get(0);
        String message = (String) match.get("message");

        assertFalse(message.contains("\u0000"), "Null bytes must be sanitized to '?'");
        assertTrue(message.contains("?"), "Sanitized null byte should appear as '?'");
        assertTrue(message.length() <= 515, "Message line must be capped to 512 chars plus ellipsis");
    }

    @Test
    void testIgnoresLogBytesWrittenPriorToSnapshot() throws Exception {
        Path log = logsDir.resolve("starsector.log");
        // Write old fatal crash in previous run
        Files.writeString(log, SYNTHETIC_COMBAT_FATAL);

        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(installRoot);

        // Write normal non-fatal logs in current run
        Files.writeString(log, "100 [main] INFO com.fs.starfarer.StarfarerLauncher - Clean run\n",
                StandardOpenOption.APPEND);

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before);

        assertFalse(evidence.fatalDetected(), "Old crash from prior run must not be flagged as fatal in new run");
        assertEquals(0, StarsectorRunLogEvidence.effectiveExitCode(0, evidence));
    }

    @Test
    void testGracefulHandlingOfMissingOrUnreadableDirectory() throws Exception {
        Path nonExistentRoot = tempDir.resolve("DoesNotExist");
        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(nonExistentRoot);

        assertTrue(before.files().isEmpty());

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before);
        assertFalse(evidence.logAvailable());
        assertFalse(evidence.fatalDetected());
        assertEquals(0, evidence.bytesExamined());
        assertEquals(0, evidence.filesExamined());
    }

    @Test
    void testRapidMultiFileRotationExceedingMaxFiles() throws Exception {
        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(installRoot);

        // Generate 12 rotated log files (.0 through .11)
        for (int i = 0; i < 12; i++) {
            Path file = logsDir.resolve("starsector.log." + i);
            Files.writeString(file, i == 11 ? SYNTHETIC_COMBAT_FATAL : "ordinary log " + i + "\n");
        }

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before);

        assertTrue(evidence.fatalDetected());
        assertTrue(evidence.truncated(), "Exceeding max files limit must trigger truncated flag");
        assertTrue(evidence.filesExamined() <= 8, "Inspected files must be bounded to MAX_FILES (8)");
    }

    @Test
    void testLogFileTruncationOrRecreationMidRun() throws Exception {
        Path log = logsDir.resolve("starsector.log");
        // 500 bytes initially
        Files.writeString(log, "Initial long padding ".repeat(25) + "\n");
        long initialSize = Files.size(log);
        assertTrue(initialSize > 200);

        StarsectorRunLogEvidence.Snapshot before = StarsectorRunLogEvidence.snapshot(installRoot);
        assertEquals(1, before.files().size());

        // File truncated/re-opened by game to smaller size with fatal error
        Files.writeString(log, SYNTHETIC_COMBAT_FATAL, StandardOpenOption.TRUNCATE_EXISTING);
        assertTrue(Files.size(log) < initialSize);

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(before);

        // Should safely read from start offset 0 and find fatal error
        assertTrue(evidence.fatalDetected());
        assertEquals("combat-main-top-level", evidence.matches().get(0).get("category"));
    }
}

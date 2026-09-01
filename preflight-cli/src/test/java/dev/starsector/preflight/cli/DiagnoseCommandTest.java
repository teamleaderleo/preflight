package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnoseCommandTest {

    @TempDir
    Path tempDir;

    private Path installRoot;
    private Path modsDir;
    private Path runDir;

    @BeforeEach
    void setUp() throws Exception {
        installRoot = tempDir.resolve("Starsector");
        modsDir = installRoot.resolve("mods");
        runDir = tempDir.resolve("runs/2026-08-18-001");
        Files.createDirectories(modsDir);
        Files.createDirectories(runDir);
        Files.writeString(installRoot.resolve("starsector.command"), "#!/bin/sh\n");

        Path armaaMod = modsDir.resolve("ArmaArmatura");
        Files.createDirectories(armaaMod);
        Files.writeString(armaaMod.resolve("mod_info.json"), """
                {
                    "id": "armaa",
                    "name": "Arma Armatura",
                    "version": "1.94"
                }
                """);
    }

    @Test
    void runDiagnosisCapturesCrashDetails() throws Exception {
        Path console = runDir.resolve("console.txt");
        Files.writeString(console, """
                4218 [Thread-3] ERROR com.fs.starfarer.combat.CombatMain - java.lang.NullPointerException
                java.lang.NullPointerException
                \tat armaa.hullmods.MountedWep.advanceInCombat(MountedWep.java:142)
                """);

        CrashDiagnosis d = DiagnoseCommand.runDiagnosis(installRoot, runDir);

        assertNotNull(d);
        assertEquals(CrashDiagnosis.CrashCategory.NULL_POINTER_IN_MOD_CODE, d.rootCauseCategory());
        assertEquals(CrashDiagnosis.Confidence.EXACT, d.confidence());
        assertNotNull(d.offendingMod());
        assertEquals("armaa", d.offendingMod().id());
    }

    @Test
    void diagnoseCommandOutputsJson() throws Exception {
        Path console = runDir.resolve("console.txt");
        Files.writeString(console, "java.lang.OutOfMemoryError: Java heap space\n");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
            int exitCode = DiagnoseCommand.execute(new String[] {
                    "--game", installRoot.toString(),
                    "--run", runDir.toString(),
                    "--json"
            }, 0);

            assertEquals(0, exitCode);
            String output = capturedOut.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("starsector-preflight-crash-diagnosis-v1"));
            assertTrue(output.contains("OUT_OF_MEMORY_HEAP"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void desktopDiagnoseOutputsJson() throws Exception {
        Path console = runDir.resolve("console.txt");
        Files.writeString(console, "java.lang.OutOfMemoryError: Java heap space\n");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
            int exitCode = DiagnoseCommand.executeDesktop(new String[] {
                    "--game", installRoot.toString(),
                    "--run", runDir.toString()
            }, 0);

            assertEquals(0, exitCode);
            String output = capturedOut.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("starsector-preflight-crash-diagnosis-v1"));
        } finally {
            System.setOut(originalOut);
        }
    }
}

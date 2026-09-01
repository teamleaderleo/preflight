package dev.starsector.preflight.core.drift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModDriftDetectorTest {
    @TempDir
    Path temp;

    @Test
    void correctlyClassifiesAllDriftSeverities() throws Exception {
        Path modDir = temp.resolve("mod_alpha");
        Files.createDirectories(modDir.resolve("jars"));
        Files.createDirectories(modDir.resolve("data"));

        Files.writeString(modDir.resolve("mod_info.json"), """
                { "id": "alpha", "name": "Alpha Mod", "version": "1.0" }
                """);
        Files.writeString(modDir.resolve("jars/alpha.jar"), "bytecode-v1");
        Files.writeString(modDir.resolve("data/settings.json"), "{\"rate\": 10}");

        ModContentSignature pristineRef = ModContentSignature.compute(modDir);
        ModContentSignature pristineAct = ModContentSignature.compute(modDir);

        ModDriftItem pristineItem = ModDriftDetector.compare(pristineRef, pristineAct);
        assertEquals(ModDriftDetector.DriftSeverity.PRISTINE, pristineItem.severity());
        assertFalse(pristineItem.hasBytecodeDrift());
        assertFalse(pristineItem.hasConfigDrift());
        assertEquals(0, pristineItem.modifiedFiles().size());

        // 1. Same-Version Config Drift
        Files.writeString(modDir.resolve("data/settings.json"), "{\"rate\": 20}");
        ModContentSignature driftAct = ModContentSignature.compute(modDir);
        ModDriftItem driftItem = ModDriftDetector.compare(pristineRef, driftAct);
        assertEquals(ModDriftDetector.DriftSeverity.SAME_VERSION_DRIFT, driftItem.severity());
        assertTrue(driftItem.hasConfigDrift());
        assertFalse(driftItem.hasBytecodeDrift());
        assertEquals(1, driftItem.modifiedFiles().size());
        assertEquals("data/settings.json", driftItem.modifiedFiles().get(0).relativePath());

        // 2. Bytecode Drift
        Files.writeString(modDir.resolve("jars/alpha.jar"), "bytecode-v2-diverged");
        ModContentSignature bytecodeAct = ModContentSignature.compute(modDir);
        ModDriftItem bytecodeItem = ModDriftDetector.compare(pristineRef, bytecodeAct);
        assertEquals(ModDriftDetector.DriftSeverity.BYTECODE_DRIFT, bytecodeItem.severity());
        assertTrue(bytecodeItem.hasBytecodeDrift());
        assertEquals(1, bytecodeItem.jarDiffs().size());
        assertEquals("MODIFIED", bytecodeItem.jarDiffs().get(0).diffType());

        // 3. Version Changed
        Files.writeString(modDir.resolve("mod_info.json"), """
                { "id": "alpha", "name": "Alpha Mod", "version": "1.1" }
                """);
        ModContentSignature verAct = ModContentSignature.compute(modDir);
        ModDriftItem verItem = ModDriftDetector.compare(pristineRef, verAct);
        // Bytecode is also modified here, but let's reset bytecode first for pure version test
        Files.writeString(modDir.resolve("jars/alpha.jar"), "bytecode-v1");
        ModContentSignature pureVerAct = ModContentSignature.compute(modDir);
        ModDriftItem pureVerItem = ModDriftDetector.compare(pristineRef, pureVerAct);
        assertEquals(ModDriftDetector.DriftSeverity.VERSION_CHANGED, pureVerItem.severity());

        // 4. Corrupt Metadata
        Files.deleteIfExists(modDir.resolve("mod_info.json"));
        ModContentSignature corruptAct = ModContentSignature.compute(modDir);
        ModDriftItem corruptItem = ModDriftDetector.compare(pristineRef, corruptAct);
        assertEquals(ModDriftDetector.DriftSeverity.CORRUPT_METADATA, corruptItem.severity());

        // 5. New Mod & Missing Mod
        ModDriftItem newItem = ModDriftDetector.compare(null, pristineAct);
        assertEquals(ModDriftDetector.DriftSeverity.NEW_MOD, newItem.severity());

        ModDriftItem missingItem = ModDriftDetector.compare(pristineRef, null);
        assertEquals(ModDriftDetector.DriftSeverity.MISSING_MOD, missingItem.severity());
    }

    @Test
    void detectDriftAcrossInstallRoot() throws Exception {
        Path installRoot = temp.resolve("Starsector");
        Path modsDir = installRoot.resolve("mods");
        Path mod1 = modsDir.resolve("mod1");
        Path mod2 = modsDir.resolve("mod2");
        Files.createDirectories(mod1);
        Files.createDirectories(mod2);

        Files.writeString(mod1.resolve("mod_info.json"), "{\"id\": \"mod1\", \"name\": \"Mod 1\", \"version\": \"1.0\"}");
        Files.writeString(mod1.resolve("mod1.csv"), "a,b\n1,2\n");

        Files.writeString(mod2.resolve("mod_info.json"), "{\"id\": \"mod2\", \"name\": \"Mod 2\", \"version\": \"1.0\"}");

        ModContentSignature ref1 = ModContentSignature.compute(mod1);
        ModContentSignature ref2 = ModContentSignature.compute(mod2);

        // Modify mod1
        Files.writeString(mod1.resolve("mod1.csv"), "a,b\n1,3\n");

        DriftReport report = ModDriftDetector.detectDrift(
                installRoot,
                Map.of("mod1", ref1, "mod2", ref2),
                "CHECKPOINT",
                "checkpoint-123"
        );

        assertEquals(DriftReport.FORMAT, report.format());
        assertEquals(2, report.summary().totalMods());
        assertEquals(1, report.summary().pristineCount());
        assertEquals(1, report.summary().sameVersionDriftCount());
        assertTrue(report.summary().hasDrift());
    }
}

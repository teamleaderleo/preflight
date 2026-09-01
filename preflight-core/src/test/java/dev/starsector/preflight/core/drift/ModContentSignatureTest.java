package dev.starsector.preflight.core.drift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModContentSignatureTest {
    @TempDir
    Path temp;

    @Test
    void computesDeterministicSignatureIgnoringMtimeAndLogs() throws Exception {
        Path modDir = temp.resolve("test_mod");
        Files.createDirectories(modDir.resolve("data/weapons"));
        Files.createDirectories(modDir.resolve("jars"));

        Files.writeString(modDir.resolve("mod_info.json"), """
                {
                    "id": "test_mod",
                    "name": "Test Mod",
                    "version": "1.0.0"
                }
                """);
        Files.writeString(modDir.resolve("data/weapons/weapons.csv"), "id,damage\nlaser,100\n");
        Files.writeString(modDir.resolve("jars/test.jar"), "dummy jar bytes");
        // Runtime log and OS noise files
        Files.writeString(modDir.resolve("debug.log"), "2026-08-18 log line");
        Files.writeString(modDir.resolve("debug.log.1"), "rotated log");
        Files.writeString(modDir.resolve("debug.log.lck"), "lock");
        Files.writeString(modDir.resolve(".DS_Store"), "apple desktop metadata");
        Files.writeString(modDir.resolve("Thumbs.db"), "windows thumbnails");

        ModContentSignature sig1 = ModContentSignature.compute(modDir);
        assertEquals("test_mod", sig1.modId());
        assertEquals("Test Mod", sig1.declaredName());
        assertEquals("1.0.0", sig1.declaredVersion());
        assertEquals("test_mod", sig1.directoryName());
        assertNotNull(sig1.contentSha256());
        assertNotNull(sig1.modInfoSha256());
        assertEquals(1, sig1.jarSignatures().size());
        assertEquals("jars/test.jar", sig1.jarSignatures().get(0).relativePath());
        assertTrue(sig1.criticalFileSignatures().containsKey("mod_info.json"));
        assertTrue(sig1.criticalFileSignatures().containsKey("data/weapons/weapons.csv"));
        assertTrue(sig1.criticalFileSignatures().containsKey("jars/test.jar"));
        assertFalse(sig1.criticalFileSignatures().containsKey("debug.log"));

        // Touch mtime of all files
        Files.setLastModifiedTime(modDir.resolve("data/weapons/weapons.csv"), FileTime.from(Instant.ofEpochSecond(1000)));
        ModContentSignature sig2 = ModContentSignature.compute(modDir);
        assertEquals(sig1.contentSha256(), sig2.contentSha256());

        // Modify content of CSV -> signature must change
        Files.writeString(modDir.resolve("data/weapons/weapons.csv"), "id,damage\nlaser,200\n");
        ModContentSignature sig3 = ModContentSignature.compute(modDir);
        assertNotEquals(sig1.contentSha256(), sig3.contentSha256());
    }

    @Test
    void rejectsNonDirectory() {
        Path file = temp.resolve("not_a_dir.txt");
        assertThrows(IOException.class, () -> ModContentSignature.compute(file));
    }
}

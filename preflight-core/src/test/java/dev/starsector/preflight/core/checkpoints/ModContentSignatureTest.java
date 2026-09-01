package dev.starsector.preflight.core.checkpoints;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModContentSignatureTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Deterministic mod content hashing ignores mtime changes")
    void deterministicContentHashingIgnoresMtime() throws Exception {
        Path modDir = Files.createDirectories(tempDir.resolve("my_mod"));
        Files.writeString(modDir.resolve("mod_info.json"), "{\"id\":\"my_mod\",\"name\":\"My Mod\",\"version\":\"1.0.0\"}");
        Path data = Files.createDirectories(modDir.resolve("data"));
        Files.writeString(data.resolve("weapons.csv"), "id,name\nlaser,Laser\n");

        ModContentSignature sig1 = ModContentSignature.compute(modDir);
        assertNotNull(sig1.contentSha256());

        // Alter mtime on all files to 1 year ago
        FileTime past = FileTime.from(Instant.now().minusSeconds(86400 * 365));
        try (var stream = Files.walk(modDir)) {
            for (Path p : stream.toList()) {
                Files.setLastModifiedTime(p, past);
            }
        }

        ModContentSignature sig2 = ModContentSignature.compute(modDir);
        assertEquals(sig1.contentSha256(), sig2.contentSha256());
        assertEquals(sig1.fileCount(), sig2.fileCount());
        assertEquals(sig1.totalBytes(), sig2.totalBytes());
    }

    @Test
    @DisplayName("Modifying file content immediately alters contentSha256")
    void detectsContentModifications() throws Exception {
        Path modDir = Files.createDirectories(tempDir.resolve("mod_edit"));
        Files.writeString(modDir.resolve("mod_info.json"), "{\"id\":\"mod_edit\",\"name\":\"Mod Edit\",\"version\":\"1.0.0\"}");
        Path data = Files.createDirectories(modDir.resolve("data"));
        Path csv = data.resolve("weapons.csv");
        Files.writeString(csv, "id,damage\nweapon1,100\n");

        ModContentSignature sig1 = ModContentSignature.compute(modDir);

        // Edit weapon damage
        Files.writeString(csv, "id,damage\nweapon1,150\n");
        ModContentSignature sig2 = ModContentSignature.compute(modDir);

        assertNotEquals(sig1.contentSha256(), sig2.contentSha256());
        assertEquals(sig1.modId(), sig2.modId());
    }

    @Test
    @DisplayName("Excludes transient logs (*.log, *.log.lck) and OS metadata")
    void ignoresTransientLogsAndOsMetadata() throws Exception {
        Path modDir = Files.createDirectories(tempDir.resolve("clean_mod"));
        Files.writeString(modDir.resolve("mod_info.json"), "{\"id\":\"clean_mod\",\"name\":\"Clean Mod\",\"version\":\"1.0\"}");
        Files.writeString(modDir.resolve("data.json"), "{\"clean\": true}");

        ModContentSignature cleanSig = ModContentSignature.compute(modDir);

        // Inject runtime logs and OS metadata
        Files.writeString(modDir.resolve("starsector.log"), "runtime log line");
        Files.writeString(modDir.resolve("app.log.1"), "rotated log line");
        Files.writeString(modDir.resolve("lock.log.lck"), "lock");
        Files.writeString(modDir.resolve(".DS_Store"), "macOS metadata");
        Files.writeString(modDir.resolve("Thumbs.db"), "Windows thumbnail");

        ModContentSignature afterLogsSig = ModContentSignature.compute(modDir);
        assertEquals(cleanSig.contentSha256(), afterLogsSig.contentSha256());
        assertEquals(cleanSig.fileCount(), afterLogsSig.fileCount());
        assertEquals(cleanSig.totalBytes(), afterLogsSig.totalBytes());
    }

    @Test
    @DisplayName("Inspects JAR bytecode and class entries")
    void inspectsJarArchives() throws Exception {
        Path modDir = Files.createDirectories(tempDir.resolve("jar_mod"));
        Files.writeString(modDir.resolve("mod_info.json"), "{\"id\":\"jar_mod\",\"name\":\"Jar Mod\",\"version\":\"1.0\"}");
        Path jars = Files.createDirectories(modDir.resolve("jars"));
        Path jarFile = jars.resolve("plugin.jar");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry("com/example/Plugin.class");
            zos.putNextEntry(entry);
            zos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            zos.closeEntry();
        }
        Files.write(jarFile, baos.toByteArray());

        ModContentSignature sig = ModContentSignature.compute(modDir);
        assertEquals(1, sig.jarSignatures().size());
        assertEquals("jars/plugin.jar", sig.jarSignatures().get(0).relativePath());
        assertEquals(1, sig.jarSignatures().get(0).classFileCount());
        assertNotNull(sig.jarSignatures().get(0).bytecodeDigest());
    }
}

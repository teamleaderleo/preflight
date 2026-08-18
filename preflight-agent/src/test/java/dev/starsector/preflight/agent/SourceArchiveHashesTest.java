package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceArchiveHashesTest {
    @TempDir
    Path temporary;

    @AfterEach
    void reset() {
        SourceArchiveHashes.beginSession();
    }

    @Test
    void persistentHashIsVerifiedAndChangedArchiveIsRehashed() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("cache"));
        Path archive = temporary.resolve("source.jar");
        Files.writeString(archive, "first archive contents");

        SourceArchiveHashes.beginSession();
        SourceArchiveHashes.configure(cache);
        assertEquals(sha256(archive), SourceArchiveHashes.sha256(archive).sha256());
        assertEquals(1L, SourceArchiveHashes.telemetry().get("hashes"));
        SourceArchiveHashes.flushJournalForTest();

        SourceArchiveHashes.beginSession();
        SourceArchiveHashes.configure(cache);
        assertEquals(sha256(archive), SourceArchiveHashes.sha256(archive).sha256());
        assertEquals(1L, SourceArchiveHashes.telemetry().get("journalHits"));
        assertEquals(1L, SourceArchiveHashes.telemetry().get("hashes"));

        Files.writeString(archive, "different archive contents with another size");
        SourceArchiveHashes.beginSession();
        SourceArchiveHashes.configure(cache);
        assertEquals(sha256(archive), SourceArchiveHashes.sha256(archive).sha256());
        assertEquals(0L, SourceArchiveHashes.telemetry().get("journalHits"));
        assertEquals(1L, SourceArchiveHashes.telemetry().get("hashes"));
    }

    @Test
    void sameSizeArchiveWithRestoredTimestampDoesNotReusePersistentHash() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("cache"));
        Path archive = temporary.resolve("source.jar");
        Files.writeString(archive, "original archive");
        FileTime modified = Files.getLastModifiedTime(archive);

        SourceArchiveHashes.beginSession();
        SourceArchiveHashes.configure(cache);
        String originalHash = SourceArchiveHashes.sha256(archive).sha256();
        SourceArchiveHashes.flushJournalForTest();

        Files.writeString(archive, "modified archive");
        Files.setLastModifiedTime(archive, modified);
        String modifiedHash = sha256(archive);

        SourceArchiveHashes.beginSession();
        SourceArchiveHashes.configure(cache);
        assertEquals(modifiedHash, SourceArchiveHashes.sha256(archive).sha256());
        assertTrue(!originalHash.equals(modifiedHash));
        assertEquals(0L, SourceArchiveHashes.telemetry().get("journalHits"));
        assertEquals(1L, SourceArchiveHashes.telemetry().get("hashes"));
    }

    @Test
    void malformedJournalFallsBackToContentHashing() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("cache"));
        Path archive = temporary.resolve("source.jar");
        Files.writeString(archive, "archive contents");
        Files.write(cache.resolve("adapter-source-hashes-v1.bin"), new byte[] {1, 2, 3});

        SourceArchiveHashes.beginSession();
        SourceArchiveHashes.configure(cache);
        SourceArchiveHashes.Result result = SourceArchiveHashes.sha256(archive);

        assertTrue(result.successful());
        assertEquals(1L, SourceArchiveHashes.telemetry().get("journalLoadFailures"));
        assertEquals(1L, SourceArchiveHashes.telemetry().get("hashes"));
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}

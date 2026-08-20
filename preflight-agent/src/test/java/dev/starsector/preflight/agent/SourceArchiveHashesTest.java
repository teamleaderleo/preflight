package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void firstGenerationAlwaysHashesBeforePersistentJournalCanMatch() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("cache"));
        Path archive = temporary.resolve("source.jar");
        Files.writeString(archive, "first archive contents");

        Object firstLoader = new Object();
        SourceArchiveHashes.beginSession();
        SourceArchiveHashes.configure(cache);
        assertEquals(sha256(archive), SourceArchiveHashes.sha256(archive, firstLoader).sha256());
        assertEquals(1L, SourceArchiveHashes.telemetry().get("hashes"));
        SourceArchiveHashes.flushJournalForTest();

        Object secondLoader = new Object();
        SourceArchiveHashes.beginSession();
        SourceArchiveHashes.configure(cache);
        assertEquals(sha256(archive), SourceArchiveHashes.sha256(archive, secondLoader).sha256());
        assertEquals(1L, SourceArchiveHashes.telemetry().get("journalHits"));
        assertEquals(1L, SourceArchiveHashes.telemetry().get("hashes"));
        assertEquals(1L, SourceArchiveHashes.telemetry().get("distinctGenerations"));
    }

    @Test
    void unchangedArchiveHashesOncePerLoaderGeneration() throws Exception {
        Path archive = temporary.resolve("source.jar");
        Files.writeString(archive, "archive contents");
        Object firstLoader = new Object();

        String expected = sha256(archive);
        assertEquals(expected, SourceArchiveHashes.sha256(archive, firstLoader).sha256());
        assertEquals(expected, SourceArchiveHashes.sha256(archive, firstLoader).sha256());
        assertEquals(2L, SourceArchiveHashes.telemetry().get("calls"));
        assertEquals(1L, SourceArchiveHashes.telemetry().get("hashes"));
        assertEquals(1L, SourceArchiveHashes.telemetry().get("sessionHits"));
        assertEquals(1L, SourceArchiveHashes.telemetry().get("distinctGenerations"));

        Object secondLoader = new Object();
        assertEquals(expected, SourceArchiveHashes.sha256(archive, secondLoader).sha256());
        assertEquals(2L, SourceArchiveHashes.telemetry().get("hashes"));
        assertEquals(2L, SourceArchiveHashes.telemetry().get("distinctGenerations"));
    }

    @Test
    void sameSizeRestoredTimestampIsRecheckedByANewGeneration() throws Exception {
        Path archive = temporary.resolve("source.jar");
        Files.writeString(archive, "AAAA");
        FileTime originalTime = Files.getLastModifiedTime(archive);
        Object firstLoader = new Object();

        String first = SourceArchiveHashes.sha256(archive, firstLoader).sha256();
        Files.writeString(archive, "BBBB");
        Files.setLastModifiedTime(archive, originalTime);
        String changed = sha256(archive);
        assertNotEquals(first, changed);

        Object secondLoader = new Object();
        assertEquals(changed, SourceArchiveHashes.sha256(archive, secondLoader).sha256());
        assertEquals(2L, SourceArchiveHashes.telemetry().get("hashes"));
        assertEquals(2L, SourceArchiveHashes.telemetry().get("distinctGenerations"));
    }

    @Test
    void sameLoaderGenerationTreatsInPlaceRestoredMetadataAsActiveLaunchMutation() throws Exception {
        Path archive = temporary.resolve("source.jar");
        Files.writeString(archive, "AAAA");
        FileTime originalTime = Files.getLastModifiedTime(archive);
        Object loader = new Object();

        String established = SourceArchiveHashes.sha256(archive, loader).sha256();
        Files.writeString(archive, "BBBB");
        Files.setLastModifiedTime(archive, originalTime);

        // A live defining loader owns one immutable code generation. Deliberate same-user mutation
        // that restores every observed identity field during that generation requires a restart;
        // the next generation hashes the current bytes before it can authorize a target.
        assertEquals(established, SourceArchiveHashes.sha256(archive, loader).sha256());
        assertEquals(1L, SourceArchiveHashes.telemetry().get("hashes"));
        assertEquals(1L, SourceArchiveHashes.telemetry().get("sessionHits"));
    }

    @Test
    void malformedJournalFallsBackToContentHashing() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("cache"));
        Path archive = temporary.resolve("source.jar");
        Files.writeString(archive, "archive contents");
        Files.write(cache.resolve("adapter-source-hashes-v1.bin"), new byte[] {1, 2, 3});

        SourceArchiveHashes.beginSession();
        SourceArchiveHashes.configure(cache);
        SourceArchiveHashes.Result result = SourceArchiveHashes.sha256(archive, new Object());

        assertTrue(result.successful());
        assertEquals(1L, SourceArchiveHashes.telemetry().get("journalLoadFailures"));
        assertEquals(1L, SourceArchiveHashes.telemetry().get("hashes"));
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}

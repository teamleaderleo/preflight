package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationLeaseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void refusesASecondOwnerAndNamesTheActiveOperation() throws Exception {
        PreflightHome home = new PreflightHome(temporaryDirectory, List.of());
        OperationLease.Acquisition first = OperationLease.acquire(home, "preparing", Path.of("game"));
        try (OperationLease ignored = first.lease()) {
            OperationLease.BusyException busy = assertThrows(
                    OperationLease.BusyException.class,
                    () -> OperationLease.acquire(home, "launching", Path.of("game")));
            assertTrue(busy.getMessage().contains("already preparing"));
            assertNotNull(OperationLease.readOwner(home));
        }
        assertNull(OperationLease.readOwner(home));
    }

    @Test
    void reportsAndReplacesTheRecordLeftByAnInterruptedOwner() throws Exception {
        PreflightHome home = new PreflightHome(temporaryDirectory, List.of());
        OperationLease.Acquisition first = OperationLease.acquire(home, "preparing", Path.of("old-game"));
        OperationLease.Owner old = OperationLease.readOwner(home);
        assertNotNull(old);

        // Simulate process death: the OS lock disappears while its descriptive record remains.
        first.lease().close();
        Files.createDirectories(home.state());
        Files.writeString(home.state().resolve("operation.json"), """
                {"format":"preflight-operation-v1","operation":"preparing","pid":987654321,
                 "startedAt":"2026-08-07T00:00:00Z","installRoot":"old-game","token":"old-token"}
                """);
        Path staleTemporary = home.cache().resolve("blobs/value.tmp-987654321-42");
        Files.createDirectories(staleTemporary.getParent());
        Files.writeString(staleTemporary, "partial");
        Path unrelated = home.cache().resolve("blobs/value.tmp-123-42");
        Files.writeString(unrelated, "keep");

        OperationLease.Acquisition second = OperationLease.acquire(home, "launching", Path.of("new-game"));
        try (OperationLease ignored = second.lease()) {
            assertNotNull(second.recovered());
            assertEquals("preparing", second.recovered().operation());
            assertEquals(987654321L, second.recovered().pid());
            assertEquals(1, second.recoveredTemporaryFiles());
            assertTrue(Files.notExists(staleTemporary));
            assertTrue(Files.isRegularFile(unrelated));
            assertEquals("launching", OperationLease.readOwner(home).operation());
        }
    }

    @Test
    void unreadableOldRecordNeverBlocksRecovery() throws Exception {
        PreflightHome home = new PreflightHome(temporaryDirectory, List.of());
        Files.createDirectories(home.state());
        Files.writeString(home.state().resolve("operation.json"), "half-written");

        OperationLease.Acquisition acquired = OperationLease.acquire(home, "preparing", null);
        try (OperationLease ignored = acquired.lease()) {
            assertNull(acquired.recovered());
            assertEquals("preparing", OperationLease.readOwner(home).operation());
        }
    }

    @Test
    void acquireRefusesRemoveAllOperationWhenRootIsSymlink() throws Exception {
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(outside);
        Path sensitiveFile = outside.resolve("keep.txt");
        Files.writeString(sensitiveFile, "important data");

        Path symlinkedHomeRoot = temporaryDirectory.resolve("symlink-home");
        Files.createSymbolicLink(symlinkedHomeRoot, outside);

        PreflightHome home = new PreflightHome(symlinkedHomeRoot, List.of());
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> OperationLease.acquire(home, OperationLease.REMOVE_ALL_OPERATION, null));
        assertTrue(error.getMessage().contains("symlink or alias"), error.getMessage());
        assertTrue(Files.isRegularFile(sensitiveFile));
    }
}

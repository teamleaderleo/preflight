package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Adversarial regression for replacing the pathname of an already-locked lease file. */
final class OperationLeaseGenerationReplacementTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void replacementOfLockedPublicEntryCannotCreateASecondLease() throws Exception {
        PreflightHome home = new PreflightHome(temporaryDirectory.resolve("home"), List.of());
        OperationLease.Acquisition first =
                OperationLease.acquire(home, "preparing", temporaryDirectory.resolve("game"));

        try (OperationLease ignored = first.lease()) {
            Path publicLock = home.state().resolve("operation.lock");
            Path displacedLock = home.state().resolve("operation.lock.displaced");
            try {
                Files.move(publicLock, displacedLock);
            } catch (IOException renameUnavailable) {
                assumeTrue(false,
                        "This filesystem does not permit renaming an open locked file: "
                                + renameUnavailable.getMessage());
            }

            Files.write(
                    publicLock,
                    new byte[0],
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);

            assertThrows(
                    OperationLease.BusyException.class,
                    () -> {
                        OperationLease.Acquisition second = OperationLease.acquire(
                                home, "launching", temporaryDirectory.resolve("game"));
                        try (OperationLease ignoredSecond = second.lease()) {
                            // Returning normally demonstrates split ownership: assertThrows must fail.
                        }
                    });
        }
    }
}

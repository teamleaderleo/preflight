package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;

/** Explicit test-only process seams for interruption and disk-full recovery checks. */
final class PreparationFaultInjection {
    static final String ENABLED_PROPERTY = "preflight.test.failureInjection";
    static final String PAUSE_MARKER_PROPERTY = "preflight.test.prepare.pauseAfterLease";
    static final String RESOURCE_VALIDATION_PAUSE_MARKER_PROPERTY =
            "preflight.test.prepare.pauseBeforeResourceValidation";
    static final String ENOSPC_TARGET_PROPERTY = "preflight.test.prepare.enospcTarget";
    private static final Duration PAUSE_LIMIT = Duration.ofSeconds(30);

    private PreparationFaultInjection() {
    }

    static void afterLeaseAcquired() throws IOException {
        pauseAtMarker(PAUSE_MARKER_PROPERTY, "post-lease preparation pause");
    }

    static void beforeResourceValidation() throws IOException {
        pauseAtMarker(RESOURCE_VALIDATION_PAUSE_MARKER_PROPERTY, "resource-index validation pause");
    }

    static void beforeAtomicMove(Path target) throws IOException {
        if (!enabled()) return;
        String configured = System.getProperty(ENOSPC_TARGET_PROPERTY, "").trim();
        if (configured.isEmpty()) return;
        Path failureTarget = Path.of(configured).toAbsolutePath().normalize();
        if (failureTarget.equals(target.toAbsolutePath().normalize())) {
            throw new FileSystemException(
                    target.toString(), null, "No space left on device (injected process-boundary test)");
        }
    }

    private static void pauseAtMarker(String property, String description) throws IOException {
        if (!enabled()) return;
        String configured = System.getProperty(property, "").trim();
        if (configured.isEmpty()) return;

        Path marker = Path.of(configured).toAbsolutePath().normalize();
        Path parent = marker.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(
                marker,
                Long.toString(ProcessHandle.current().pid()),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);

        Path release = marker.resolveSibling(marker.getFileName() + ".release");
        Instant deadline = Instant.now().plus(PAUSE_LIMIT);
        while (!Files.exists(release)) {
            if (Instant.now().isAfter(deadline)) {
                throw new IOException("Timed out at the injected " + description);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted at the injected " + description, interrupted);
            }
        }
    }

    private static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }
}

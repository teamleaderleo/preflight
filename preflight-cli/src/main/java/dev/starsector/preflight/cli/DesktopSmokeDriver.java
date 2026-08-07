package dev.starsector.preflight.cli;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Platform adapter boundary used by the driver-neutral desktop smoke runner.
 *
 * <p>Calls run on a deadline-controlled daemon thread. Implementations must stop synthesizing input
 * when interrupted; subprocess-backed implementations must also terminate their child process.
 */
interface DesktopSmokeDriver {
    Descriptor descriptor() throws Exception;

    void attach(ProcessTarget target) throws Exception;

    ActionResult execute(Map<String, Object> step, Path runDirectory) throws Exception;

    Observation observe() throws Exception;

    record Descriptor(
            String id,
            String version,
            String platform,
            Set<String> capabilities,
            List<String> diagnostics) {
    }

    record ProcessTarget(long pid, Instant startedAt) {
    }

    record ActionResult(String detail, List<Artifact> artifacts) {
        static ActionResult completed(String detail) {
            return new ActionResult(detail, List.of());
        }
    }

    record Artifact(String kind, Path path) {
    }

    record Observation(String detail) {
    }

    /** An unavailable permission or capability is a skip, not a product failure. */
    final class UnavailableException extends Exception {
        UnavailableException(String message) {
            super(message);
        }

        UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

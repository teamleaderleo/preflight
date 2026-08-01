package dev.starsector.preflight.agent;

import java.nio.file.Path;

/**
 * Where the in-flight recording sidecar lives.
 *
 * <p>Public because the agent writes the sidecar and the CLI reconciles it after the process is
 * gone, and a naming rule that the two disagree on would silently leave the fuller recording behind.
 *
 * @see RecordingFlusher
 */
public final class RecordingFlusherPaths {
    /** Appended to the recording's stem. */
    public static final String SIDECAR_SUFFIX = ".partial.jfr";

    private RecordingFlusherPaths() {
    }

    /** The sidecar beside {@code destination}, e.g. {@code startup.jfr -> startup.partial.jfr}. */
    public static Path sidecarFor(Path destination) {
        String name = destination.getFileName().toString();
        String stem = name.endsWith(".jfr") ? name.substring(0, name.length() - 4) : name;
        return destination.resolveSibling(stem + SIDECAR_SUFFIX);
    }
}

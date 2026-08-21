package dev.starsector.preflight.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Bounded JSON admission for small runtime-owned identity files. */
final class BoundedRuntimeJson {
    private BoundedRuntimeJson() {
    }

    static Map<String, Object> readObject(
            Path source,
            long maximumBytes,
            String label) throws IOException {
        return readObject(source, maximumBytes, label, ignored -> {}, input -> input);
    }

    static Map<String, Object> readObject(
            Path source,
            long maximumBytes,
            String label,
            BeforeOpenHook beforeOpen,
            InputDecorator decorator) throws IOException {
        if (maximumBytes < 0 || maximumBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Runtime JSON byte limit is invalid: " + maximumBytes);
        }
        if (beforeOpen == null || decorator == null) {
            throw new IllegalArgumentException("Runtime JSON read hooks are required");
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a regular file: " + source);
        }
        if (Files.size(source) > maximumBytes) {
            throw new IOException(label + " exceeds " + maximumBytes + " bytes: " + source);
        }
        beforeOpen.run(source);
        try (InputStream raw = Files.newInputStream(
                source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            InputStream input = decorator.decorate(raw);
            if (input == null) {
                throw new IOException(label + " input decorator returned no stream: " + source);
            }
            return BoundedEvidenceJson.readObject(
                    input, maximumBytes, source.toString(), label);
        }
    }

    @FunctionalInterface
    interface BeforeOpenHook {
        void run(Path source) throws IOException;
    }

    @FunctionalInterface
    interface InputDecorator {
        InputStream decorate(InputStream input) throws IOException;
    }
}

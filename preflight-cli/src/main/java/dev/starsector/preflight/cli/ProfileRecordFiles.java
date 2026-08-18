package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Filename and staging authority for Preflight-owned named-profile records. */
final class ProfileRecordFiles {
    private static final Pattern RECORD_FILE = Pattern.compile("[0-9a-f]{64}\\.json");
    private static final String STAGING_PREFIX = ".preflight-profile";

    private ProfileRecordFiles() {
    }

    /**
     * Returns only pathnames that can be authoritative named-profile records.
     *
     * <p>Known staging names are deliberately silent: an interrupted writer may leave one behind,
     * and read-only profile enumeration must neither adopt it nor turn it into a persistent warning.
     * Other JSON files are diagnosed because they look like attempted profile records while lacking
     * the name-derived canonical pathname.
     */
    static Scan scan(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return new Scan(List.of(), List.of());
        }
        List<Path> records = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (isKnownStagingName(name)) {
                    continue;
                }
                if (RECORD_FILE.matcher(name).matches()) {
                    records.add(file);
                    continue;
                }
                if (name.endsWith(".json")) {
                    diagnostics.add("Ignored " + name + ": not a canonical named-profile filename");
                }
            }
        }
        return new Scan(List.copyOf(records), List.copyOf(diagnostics));
    }

    static void requireRegularRecord(Path file) throws IOException {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!RECORD_FILE.matcher(name).matches()
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Named profile not found: " + file);
        }
    }

    static void requireNameMatchesFilename(Path file, String profileName) throws IOException {
        String expected = canonicalFileName(profileName);
        String actual = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!expected.equals(actual)) {
            throw new IOException(
                    "Named profile filename " + actual + " does not match its embedded profile name");
        }
    }

    /**
     * Creates a sibling staging file outside the authoritative record namespace.
     *
     * <p>The PID marker intentionally matches {@link OperationLease}'s interrupted-write cleanup.
     */
    static Path createStagingFile(Path parent, String purpose) throws IOException {
        long pid = ProcessHandle.current().pid();
        return Files.createTempFile(
                parent,
                STAGING_PREFIX + "-" + purpose + ".tmp-" + pid + "-",
                "");
    }

    static String canonicalFileName(String profileName) {
        return Hashes.sha256(profileName.getBytes(StandardCharsets.UTF_8)) + ".json";
    }

    private static boolean isKnownStagingName(String name) {
        return name.startsWith(STAGING_PREFIX);
    }

    record Scan(List<Path> records, List<String> diagnostics) {
    }
}

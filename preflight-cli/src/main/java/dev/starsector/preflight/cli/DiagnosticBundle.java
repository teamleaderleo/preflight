package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a small, disclosed support bundle from an explicit evidence-file allowlist. */
final class DiagnosticBundle {
    static final String FORMAT = "starsector-preflight-diagnostics-v1";
    static final int DEFAULT_RUNS = 3;
    static final int DEFAULT_BENCHMARKS = 2;
    static final int MAX_SESSIONS_PER_KIND = 20;
    static final int MAX_FILE_BYTES = 512 * 1024;
    static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024;
    private static final long ZIP_ENTRY_TIME_MILLIS = 946_684_800_000L;

    private static final Set<String> RUN_FILES = Set.of(
            "adapter-analysis.json",
            "adapter-health.json",
            "adapter.json",
            "comparison-result.json",
            "main-menu-ready.json",
            "menu-flushed.json",
            "menu.json",
            "operator-gameplay-result.json",
            "profile.json",
            "run.json",
            "runtime-process.json",
            "runtime-state.json",
            "runtime-frame-report.json",
            "runtime-adapter-health.json",
            "smoke-evidence.json",
            "summary.json");
    private static final Set<String> BENCHMARK_FILES = Set.of(
            "benchmark-summary.json",
            "identity.json",
            "launch-settings.json",
            "prepare.json",
            "profile-baseline.json",
            "profile-settled.json",
            "results.jsonl",
            "session-config.json");
    private static final byte[] DISCLOSURE = ("""
            Preflight diagnostics bundle
            ============================

            This ZIP contains only bounded, textual Preflight metadata selected by an explicit
            filename allowlist from the newest launch-run and benchmark evidence sessions.

            Included when present:
            - run outcome, runtime and launch-option metadata
            - enabled-mod/resource metadata (names, counts, sizes and hashes; never asset contents)
            - adapter activation, health, counters and bounded timing summaries
            - desktop-smoke process/state identity and sealed outcome metadata
            - benchmark identity, settings and result metadata

            Always excluded:
            - acceleration caches and prepared texture/audio/bytecode payloads
            - Starsector files, mod files, saves and decoded assets
            - console/wrapper/game logs, crash dumps and environment dumps
            - JFR recordings, screenshots, audio and unknown filenames
            - symbolic links and files larger than 512 KiB

            Absolute paths below the current user's home directory are replaced with <home> in
            exported text. Other metadata, including enabled mod IDs and platform/runtime details,
            remains visible because it is useful for compatibility diagnosis. Inspect the ZIP
            before sharing it if that information is sensitive to you.

            manifest.json lists every included or skipped source and the enforced limits.
            """).getBytes(StandardCharsets.UTF_8);

    private DiagnosticBundle() {
    }

    static Result export(
            PreflightHome home,
            EvidenceRetention.Inventory inventory,
            Path output,
            int runCount,
            int benchmarkCount,
            boolean overwrite) throws IOException {
        if (runCount < 0 || benchmarkCount < 0) {
            throw new IllegalArgumentException("Diagnostic evidence counts must be nonnegative");
        }
        if (runCount > MAX_SESSIONS_PER_KIND || benchmarkCount > MAX_SESSIONS_PER_KIND) {
            throw new IllegalArgumentException("Diagnostic evidence counts cannot exceed "
                    + MAX_SESSIONS_PER_KIND + " per category");
        }
        Path requested = output.toAbsolutePath().normalize();
        Path requestedParent = requested.getParent();
        Path fileName = requested.getFileName();
        if (requestedParent == null || fileName == null) {
            throw new IOException("Diagnostic bundle output has no parent or filename: " + requested);
        }
        if (!fileName.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IOException("Diagnostic bundle output must end in .zip: " + requested);
        }
        Files.createDirectories(requestedParent);
        Path parent = requestedParent.toRealPath();
        if (!Files.isDirectory(parent)) {
            throw new IOException("Diagnostic bundle output parent is not a directory: " + parent);
        }
        Path destination = parent.resolve(fileName).normalize();
        if (inside(destination, realOrAbsolute(home.runs()))
                || inside(destination, realOrAbsolute(home.benchmarks()))) {
            throw new IOException("Diagnostic bundle output cannot be inside live evidence: "
                    + destination);
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(destination)
                    || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Diagnostic bundle output is not a regular file: "
                        + destination);
            }
            if (!overwrite) {
                throw new IOException("Diagnostic bundle output already exists; add --overwrite: "
                        + destination);
            }
        }

        List<Included> included = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<BundleFile> files = new ArrayList<>();
        files.add(new BundleFile("README.txt", DISCLOSURE));
        int[] remaining = {MAX_CONTENT_BYTES - DISCLOSURE.length};

        List<EvidenceRetention.Session> runs = newest(inventory.runs(), runCount);
        List<EvidenceRetention.Session> benchmarks = newest(inventory.benchmarks(), benchmarkCount);
        collect("run", runs, RUN_FILES, home, files, included, skipped, remaining);
        collect("benchmark", benchmarks, BENCHMARK_FILES, home, files, included, skipped, remaining);

        byte[] manifest = manifest(
                runs, benchmarks, runCount, benchmarkCount, included, skipped);
        files.add(new BundleFile("manifest.json", manifest));

        String leaf = destination.getFileName().toString();
        Path temporary = Files.createTempFile(parent, "." + leaf + ".", ".tmp");
        boolean moved = false;
        try {
            writeZip(temporary, files);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
        return new Result(
                destination,
                Files.size(destination),
                Hashes.sha256(destination),
                files.size(),
                runs.size(),
                benchmarks.size(),
                List.copyOf(included),
                List.copyOf(skipped));
    }

    private static void collect(
            String kind,
            List<EvidenceRetention.Session> sessions,
            Set<String> allowlist,
            PreflightHome home,
            List<BundleFile> files,
            List<Included> included,
            List<Skipped> skipped,
            int[] remaining) throws IOException {
        for (int index = 0; index < sessions.size(); index++) {
            EvidenceRetention.Session session = sessions.get(index);
            for (String name : allowlist.stream().sorted().toList()) {
                Path source = session.path().resolve(name);
                String entry = kind + "s/" + (index + 1) + "/" + name;
                ReadResult result;
                try {
                    result = read(source, home, remaining[0]);
                } catch (IOException unavailable) {
                    skipped.add(new Skipped(entry, "could not be read while exporting"));
                    continue;
                }
                if (!result.present()) {
                    continue;
                }
                if (result.skippedReason() != null) {
                    skipped.add(new Skipped(entry, result.skippedReason()));
                    continue;
                }
                byte[] bytes = result.bytes();
                remaining[0] -= bytes.length;
                files.add(new BundleFile(entry, bytes));
                included.add(new Included(entry, bytes.length, Hashes.sha256(bytes)));
            }
        }
    }

    private static ReadResult read(Path source, PreflightHome home, int remaining) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return ReadResult.absent();
        }
        if (Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            return ReadResult.skipped("not a regular non-symbolic-link file");
        }
        BasicFileAttributes before = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() > MAX_FILE_BYTES) {
            return ReadResult.skipped("exceeds the 512 KiB per-file limit");
        }
        if (remaining <= 0) {
            return ReadResult.skipped("bundle content limit reached");
        }
        byte[] raw = boundedRead(source, Math.min(MAX_FILE_BYTES, remaining));
        if (raw == null) {
            return ReadResult.skipped(remaining < MAX_FILE_BYTES
                    ? "would exceed the 5 MiB bundle content limit"
                    : "grew beyond the 512 KiB per-file limit while reading");
        }
        BasicFileAttributes after = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || after.isSymbolicLink()) {
            return ReadResult.skipped("changed while the bundle was being created");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException binary) {
            return ReadResult.skipped("is not valid UTF-8 text");
        }
        return ReadResult.included(redact(text, home).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] boundedRead(Path source, int limit) throws IOException {
        try (InputStream input = Files.newInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 32 * 1024))) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total = Math.addExact(total, read);
                if (total > limit) return null;
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String redact(String text, PreflightHome home) {
        String userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize().toString();
        String configuredHome = home.root().getParent() == null
                ? userHome
                : home.root().getParent().toString();
        String redacted = replacePath(text, userHome);
        if (!configuredHome.equals(userHome)) {
            redacted = replacePath(redacted, configuredHome);
        }
        return redacted;
    }

    private static String replacePath(String text, String path) {
        if (path.isBlank()) return text;
        String result = text.replace(path, "<home>");
        result = result.replace(path.replace("\\", "\\\\"), "<home>");
        result = result.replace(path.replace('\\', '/'), "<home>");
        return result;
    }

    private static byte[] manifest(
            List<EvidenceRetention.Session> runs,
            List<EvidenceRetention.Session> benchmarks,
            int maximumRuns,
            int maximumBenchmarks,
            List<Included> included,
            List<Skipped> skipped) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", FORMAT);
        value.put("createdAt", Instant.now());
        value.put("platform", Platform.current().name().toLowerCase(Locale.ROOT));
        value.put("engineVersion", engineVersion());
        value.put("selectedRuns", sessionViews(runs));
        value.put("selectedBenchmarks", sessionViews(benchmarks));
        value.put("limits", Map.of(
                "maximumRuns", maximumRuns,
                "maximumBenchmarks", maximumBenchmarks,
                "maximumFileBytes", MAX_FILE_BYTES,
                "maximumContentBytes", MAX_CONTENT_BYTES));
        value.put("redactions", List.of("current user home -> <home>"));
        value.put("included", included.stream().map(Included::view).toList());
        value.put("skipped", skipped.stream().map(Skipped::view).toList());
        value.put("excludedCategories", List.of(
                "acceleration caches", "game and mod files", "saves", "logs and crash dumps",
                "JFR recordings", "screenshots and audio", "unknown filenames", "symbolic links"));
        return (Json.object(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static List<Map<String, Object>> sessionViews(List<EvidenceRetention.Session> sessions) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int index = 0; index < sessions.size(); index++) {
            EvidenceRetention.Session session = sessions.get(index);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("rank", index + 1);
            value.put("modifiedMillis", session.modifiedMillis());
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static String engineVersion() {
        String version = PreflightCli.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private static List<EvidenceRetention.Session> newest(
            List<EvidenceRetention.Session> sessions, int count) {
        return List.copyOf(sessions.subList(0, Math.min(count, sessions.size())));
    }

    private static boolean inside(Path candidate, Path root) {
        return candidate.startsWith(root.toAbsolutePath().normalize());
    }

    private static Path realOrAbsolute(Path path) throws IOException {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                ? path.toRealPath()
                : path.toAbsolutePath().normalize();
    }

    private static void writeZip(Path output, List<BundleFile> files) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (BundleFile file : files) {
                ZipEntry entry = new ZipEntry(file.entry());
                entry.setTime(ZIP_ENTRY_TIME_MILLIS);
                zip.putNextEntry(entry);
                zip.write(file.bytes());
                zip.closeEntry();
            }
        }
    }

    record Result(
            Path output,
            long bytes,
            String sha256,
            int files,
            int runs,
            int benchmarks,
            List<Included> included,
            List<Skipped> skipped) {
    }

    record Included(String entry, long bytes, String sha256) {
        Map<String, Object> view() {
            return Map.of("entry", entry, "bytes", bytes, "sha256", sha256);
        }
    }

    record Skipped(String entry, String reason) {
        Map<String, Object> view() {
            return Map.of("entry", entry, "reason", reason);
        }
    }

    private record BundleFile(String entry, byte[] bytes) {
    }

    private record ReadResult(boolean present, byte[] bytes, String skippedReason) {
        static ReadResult absent() {
            return new ReadResult(false, null, null);
        }

        static ReadResult skipped(String reason) {
            return new ReadResult(true, null, reason);
        }

        static ReadResult included(byte[] bytes) {
            return new ReadResult(true, bytes, null);
        }
    }
}

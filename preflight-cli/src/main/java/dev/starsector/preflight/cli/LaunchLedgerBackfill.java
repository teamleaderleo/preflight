package dev.starsector.preflight.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Imports old run metadata once and recovers interrupted launches whenever history is read.
 *
 * <p>The ledger began recording the day it shipped, so without this a player who has been using
 * Preflight for months sees a total of zero on the release that introduces the total. Their history
 * is not missing -- every run directory holds {@code run.json} with the same fields the ledger
 * wants -- it simply predates anything reading it.
 *
 * <p>The historical import runs once, guarded by a marker beside the ledger, and skips any run
 * already present so that a marker lost to a half-finished upgrade cannot double a total. Entirely
 * best-effort: a run directory that will not parse is skipped, and a backfill that cannot run at
 * all leaves the ledger exactly as it was.
 *
 * <p>The recurring pass reads only bounded metadata and heartbeat files. It is what makes a wrapper
 * killed after the first historical import recoverable without counting a wrapper that is alive.
 */
final class LaunchLedgerBackfill {
    private static final long MAX_RUN_METADATA_BYTES = 1024L * 1024L;

    private LaunchLedgerBackfill() {
    }

    static Path marker(PreflightHome home) {
        return LaunchLedger.path(home).resolveSibling("backfilled-from-runs");
    }

    /**
     * @return how many past launches were imported, or 0 when there was nothing to do
     */
    static int runOnce(PreflightHome home) {
        try {
            return LaunchLedger.withExclusiveLock(home, () -> {
                Set<String> known = knownLaunches(home);
                int recovered = recoverHeartbeats(home, known);
                Path marker = LaunchLedger.historyDirectory(home, true)
                        .resolve("backfilled-from-runs");
                LaunchLedger.requireRegularFileIfPresent(marker, "Launch-history backfill marker");
                if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    return recovered;
                }
                List<LaunchLedger.Entry> imported = collectHistorical(home, known);
                LaunchLedger.appendAllUnlocked(home, imported);
                Files.writeString(
                        marker,
                        "Imported " + imported.size() + " launches from run directories at "
                                + Instant.now() + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                return recovered + imported.size();
            });
        } catch (IOException | RuntimeException bestEffort) {
            return 0;
        }
    }

    private static Set<String> knownLaunches(PreflightHome home) throws IOException {
        Set<String> known = new HashSet<>();
        for (LaunchLedger.Entry entry : LaunchLedger.readUnlocked(home)) {
            if (entry.launchId() != null) known.add(entry.launchId());
        }
        return known;
    }

    /**
     * Recovers launches whose wrapper disappeared before it could append its ledger row.
     *
     * <p>This runs every time history is read. The historical import below is intentionally once,
     * while an interrupted launch can happen tomorrow. A heartbeat belongs to a run only when its
     * random launch id and exact process identity agree with {@code run.json}; a reused PID or a
     * copied file therefore cannot turn an active or unrelated process into playtime.
     */
    private static int recoverHeartbeats(PreflightHome home, Set<String> known) throws IOException {
        Path runs = home.runs();
        if (!Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS)) return 0;
        int recovered = 0;
        try (var children = Files.list(runs)) {
            for (Path directory : children
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .toList()) {
                LaunchHeartbeat.Record heartbeat = LaunchHeartbeat.read(directory);
                if (heartbeat == null) continue;
                if (known.contains(heartbeat.launchId())) {
                    LaunchHeartbeat.complete(directory, heartbeat.launchId());
                    continue;
                }
                Map<String, Object> values = metadata(directory);
                if (!owns(values, heartbeat) || heartbeat.ownerAlive()) continue;
                LaunchLedger.Entry entry = recoveredEntry(directory, values, heartbeat);
                if (entry == null || !LaunchLedger.fitsEncodedEntryLimit(entry)) continue;
                LaunchLedger.appendUnlocked(home, entry);
                known.add(entry.launchId());
                recovered++;
                LaunchHeartbeat.complete(directory, entry.launchId());
            }
        }
        return recovered;
    }

    private static boolean owns(Map<String, Object> values, LaunchHeartbeat.Record heartbeat) {
        if (values == null) return false;
        Long wrapperPid = integer(values.get("wrapperPid"));
        Instant wrapperStartedAt = instant(values.get("wrapperStartedAt"));
        Instant started = instant(values.get("started"));
        return heartbeat.launchId().equals(values.get("launchId"))
                && wrapperPid != null
                && wrapperPid == heartbeat.ownerPid()
                && heartbeat.ownerStartedAt().equals(wrapperStartedAt)
                && heartbeat.started().equals(started);
    }

    private static LaunchLedger.Entry recoveredEntry(
            Path directory,
            Map<String, Object> values,
            LaunchHeartbeat.Record heartbeat) {
        Instant ended = instant(values.get("ended"));
        String outcome = values.get("outcome") instanceof String value ? value : null;
        Long elapsedMillis;
        if (ended == null || outcome == null || "RUNNING".equals(outcome)) {
            outcome = "INTERRUPTED";
            elapsedMillis = heartbeat.elapsedMillis();
        } else {
            elapsedMillis = boundedElapsed(values.get("elapsedMillis"));
            if (elapsedMillis == null) {
                long wallMillis = Duration.between(heartbeat.started(), ended).toMillis();
                if (wallMillis < 0) return null;
                elapsedMillis = wallMillis;
            }
        }
        Object lifecycle = values.get("lifecycleEvidence");
        boolean fatal = lifecycle instanceof Map<?, ?> evidence
                && Boolean.TRUE.equals(evidence.get("fatalDetected"));
        return new LaunchLedger.Entry(
                heartbeat.launchId(),
                heartbeat.started(),
                elapsedMillis,
                outcome,
                values.get("exitCode") instanceof Number code ? code.intValue() : null,
                fatal,
                values.get("optimizationPreset") instanceof String preset ? preset : null,
                strings(values.get("disabledOptimizationDomains")),
                directory.getFileName().toString(),
                values.get("textureProfileFingerprint") instanceof String profile
                        ? profile : heartbeat.profileFingerprint());
    }

    /** Past launches not already in the ledger, oldest first. */
    private static List<LaunchLedger.Entry> collectHistorical(
            PreflightHome home,
            Set<String> known) throws IOException {
        Path runs = home.runs();
        if (!Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<LaunchLedger.Entry> found = new ArrayList<>();
        try (var children = Files.list(runs)) {
            for (Path directory : children
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .toList()) {
                String name = directory.getFileName().toString();
                // A valid heartbeat is owned by the recurring recovery pass. If it is still active,
                // the launch is not history; if it is malformed or mismatched, fail closed instead
                // of guessing a duration from it here.
                if (LaunchHeartbeat.read(directory) != null) continue;
                LaunchLedger.Entry entry = readHistorical(directory, name);
                if (entry != null
                        && LaunchLedger.fitsEncodedEntryLimit(entry)
                        && !known.contains(entry.launchId())) {
                    found.add(entry);
                    known.add(entry.launchId());
                }
            }
        }
        found.sort(Comparator.comparing(LaunchLedger.Entry::started));
        return found;
    }

    /** One run directory's metadata as a ledger row, or null when it is not usable as one. */
    private static LaunchLedger.Entry readHistorical(Path directory, String name) {
        Map<String, Object> values = metadata(directory);
        if (values == null) return null;
        Instant started = instant(values.get("started"));
        if (started == null) {
            return null;
        }
        Instant ended = instant(values.get("ended"));
        Long elapsedMillis = null;
        String outcome = values.get("outcome") instanceof String o ? o : null;
        if (ended != null) {
            long wallMillis = Duration.between(started, ended).toMillis();
            if (wallMillis < 0 || wallMillis > Duration.ofDays(365).toMillis()) return null;
            elapsedMillis = wallMillis;
        }
        Object lifecycle = values.get("lifecycleEvidence");
        boolean fatal = lifecycle instanceof Map<?, ?> evidence
                && Boolean.TRUE.equals(evidence.get("fatalDetected"));
        return new LaunchLedger.Entry(
                values.get("launchId") instanceof String launchId && !launchId.isBlank()
                        ? launchId : LaunchIdentity.imported(started, name),
                started,
                elapsedMillis,
                outcome,
                values.get("exitCode") instanceof Number code ? code.intValue() : null,
                fatal,
                values.get("optimizationPreset") instanceof String preset ? preset : null,
                strings(values.get("disabledOptimizationDomains")),
                name,
                values.get("textureProfileFingerprint") instanceof String profile ? profile : null);
    }

    static Map<String, Object> metadata(Path directory) {
        Path path = directory.resolve("run.json");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (Files.size(path) > MAX_RUN_METADATA_BYTES) return null;
            try (InputStream input = Files.newInputStream(
                    path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                return metadata(input, path.toString());
            }
        } catch (IOException unreadable) {
            return null;
        }
    }

    static Map<String, Object> metadata(InputStream input, String sourceLabel) {
        try {
            return BoundedEvidenceJson.readObject(
                    input, MAX_RUN_METADATA_BYTES, sourceLabel, "Launch run metadata");
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static Long integer(Object value) {
        if (!(value instanceof Number number)
                || number.doubleValue() != number.longValue()
                || number.longValue() <= 0) return null;
        return number.longValue();
    }

    private static Long boundedElapsed(Object value) {
        if (!(value instanceof Number number)
                || number.doubleValue() != number.longValue()
                || number.longValue() < 0
                || number.longValue() > Duration.ofDays(365).toMillis()) return null;
        return number.longValue();
    }

    private static Instant instant(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException notAnInstant) {
            return null;
        }
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof String text) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }
}

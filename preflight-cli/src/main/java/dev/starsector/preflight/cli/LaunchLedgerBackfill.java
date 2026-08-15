package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the launches that already happened into the ledger, once.
 *
 * <p>The ledger began recording the day it shipped, so without this a player who has been using
 * Preflight for months sees a total of zero on the release that introduces the total. Their history
 * is not missing -- every run directory holds {@code run.json} with the same fields the ledger
 * wants -- it simply predates anything reading it.
 *
 * <p>Runs once, guarded by a marker beside the ledger, and skips any run already present so that a
 * marker lost to a half-finished upgrade cannot double a total. Entirely best-effort: a run
 * directory that will not parse is skipped, and a backfill that cannot run at all leaves the ledger
 * exactly as it was.
 *
 * <p>Deliberately not on the launch path. This walks every run directory, which is cheap once and
 * pointless before every game.
 */
final class LaunchLedgerBackfill {
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
            if (Files.exists(marker(home), LinkOption.NOFOLLOW_LINKS)) {
                return 0;
            }
            List<LaunchLedger.Entry> imported = collect(home);
            for (LaunchLedger.Entry entry : imported) {
                if (LaunchLedger.record(home, entry) != null) {
                    // Writing failed; leave the marker off so a later run can finish the job.
                    return 0;
                }
            }
            Files.writeString(
                    marker(home),
                    "Imported " + imported.size() + " launches from run directories at "
                            + Instant.now() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            return imported.size();
        } catch (IOException | RuntimeException bestEffort) {
            return 0;
        }
    }

    /** Past launches not already in the ledger, oldest first. */
    private static List<LaunchLedger.Entry> collect(PreflightHome home) throws IOException {
        Path runs = home.runs();
        if (!Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Set<String> known = new HashSet<>();
        for (LaunchLedger.Entry entry : LaunchLedger.read(home)) {
            if (entry.runDirectory() != null) {
                known.add(entry.runDirectory());
            }
        }
        List<LaunchLedger.Entry> found = new ArrayList<>();
        try (var children = Files.list(runs)) {
            for (Path directory : children.filter(Files::isDirectory).toList()) {
                String name = directory.getFileName().toString();
                if (known.contains(name)) {
                    continue;
                }
                LaunchLedger.Entry entry = read(directory, name);
                if (entry != null) {
                    found.add(entry);
                }
            }
        }
        found.sort(Comparator.comparing(LaunchLedger.Entry::started));
        return found;
    }

    /** One run directory's metadata as a ledger row, or null when it is not usable as one. */
    private static LaunchLedger.Entry read(Path directory, String name) {
        Path metadata = directory.resolve("run.json");
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Map<String, Object> values;
        try {
            values = StrictJson.object(Files.readString(metadata, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        Instant started = instant(values.get("started"));
        if (started == null) {
            return null;
        }
        Instant ended = instant(values.get("ended"));
        Object lifecycle = values.get("lifecycleEvidence");
        boolean fatal = lifecycle instanceof Map<?, ?> evidence
                && Boolean.TRUE.equals(evidence.get("fatalDetected"));
        return new LaunchLedger.Entry(
                started,
                ended == null ? null : Duration.between(started, ended).toMillis(),
                values.get("outcome") instanceof String outcome ? outcome : null,
                values.get("exitCode") instanceof Number code ? code.intValue() : null,
                fatal,
                values.get("optimizationPreset") instanceof String preset ? preset : null,
                strings(values.get("disabledOptimizationDomains")),
                name,
                values.get("textureProfileFingerprint") instanceof String profile ? profile : null);
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

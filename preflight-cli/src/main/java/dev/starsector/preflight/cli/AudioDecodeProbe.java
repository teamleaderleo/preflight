package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/**
 * Answers the one question that decides whether prepared audio is worth building: does Starsector
 * open every declared sound effect while it loads, or only when something first plays one?
 *
 * <p>The audio census sized that answer's consequences — 1,803 declared effects expanding to
 * 1,172.6 MB of PCM — without establishing its premise. If the game decodes all of them at startup,
 * that is the largest unclaimed cost measured in this repository. If it decodes them on first play,
 * prepared audio moves work from moments nobody is timing into a cache nobody needed, and the
 * milestone should not be built at all.</p>
 *
 * <h2>What this can and cannot conclude</h2>
 *
 * <p>A file read is not a decode, and this does not pretend otherwise. What the recording supports
 * with no inference at all is the negative: <em>a file the process never opened cannot have been
 * decoded</em>. So a run in which most declared effects are never read settles the question.</p>
 *
 * <p>The positive direction is weaker and is labelled as such. Every declared effect being opened
 * during load, in a burst, off a shared stack, is consistent with eager decoding and awkward to
 * explain any other way — but it is still evidence about reads, and the verdict says so rather than
 * rounding it up to proof.</p>
 *
 * <h2>Why the ordinary startup recording cannot answer it</h2>
 *
 * <p>{@link dev.starsector.preflight.agent.PreflightAgent} enables {@code jdk.FileRead} with a
 * one-millisecond threshold, which is the right setting for finding what cost time and the wrong one
 * for finding what was opened: a warm page-cache read of a small file finishes well inside it. A
 * recording made without {@code --trace-all-file-reads} is therefore refused rather than analysed,
 * because its silence is indistinguishable from the answer.</p>
 */
final class AudioDecodeProbe {
    /** Above this share of declared effects opened, the run read essentially all of them. */
    private static final double EAGER_FRACTION = 0.90;
    /** Below this, the great majority were never opened and so were never decoded. */
    private static final double LAZY_FRACTION = 0.10;
    /**
     * A first-open window this small, relative to the whole session, is a bulk load rather than
     * playback. Deliberately generous: the observed load put 1,278 files inside 1.5 seconds of a 360
     * second session, which is 0.4%, and anything an order of magnitude looser is still nothing like
     * sounds being opened as they are played.
     */
    private static final double BURST_SHARE_OF_RUN = 0.05;
    /**
     * A burst needs enough files to be one. Without this, a session that opened a single effect would
     * have a zero-length window and read as a perfect bulk load.
     */
    private static final int BURST_MINIMUM_FILES = 20;
    private static final int TOP_READERS = 8;
    /** Enough never-opened paths to see what they have in common, without listing hundreds. */
    private static final int NEVER_OPENED_SAMPLE = 20;

    private AudioDecodeProbe() {
    }

    enum Verdict {
        /** Nearly every declared effect was opened during the run. */
        EAGER,
        /** The great majority were never opened, so they were not decoded. */
        LAZY,
        /** Neither pattern; the report carries the numbers rather than a conclusion. */
        INCONCLUSIVE,
        /** The recording cannot answer the question and was not interpreted. */
        UNUSABLE
    }

    /**
     * @param verdict what the run showed
     * @param detail one sentence stating the finding and its limits, safe to quote
     * @param report the full machine-readable result
     */
    record Result(Verdict verdict, String detail, Map<String, Object> report) {
    }

    /**
     * Per-kind tally of what the census declared against what the run opened.
     *
     * <p>The byte columns are the point of the exercise. Counting files says whether loading is eager;
     * only the PCM behind the files that were actually opened says whether that is worth caching.</p>
     */
    private record Tally(
            String kind,
            int declared,
            int read,
            long openedDecodedBytes,
            long neverOpenedDecodedBytes,
            List<Long> firstReadMillis,
            List<String> neverOpenedSample) {
        double fraction() {
            return declared == 0 ? 0 : (double) read / declared;
        }
    }

    static Result run(Path recording, Path installRoot) throws IOException {
        AudioCensus.Result census = AudioCensus.scan(installRoot);
        ResourceIndexBuilder.BuildResult built = ResourceIndexBuilder.build(installRoot);

        // Winning absolute path -> logical path, so a JFR path is an exact map hit rather than a
        // suffix search across two thousand candidates for every read event in the recording. A
        // shadowed provider must not count: a mod can open its own losing copy directly, but that
        // does not establish that the file selected by ordinary resource resolution was opened.
        Map<String, String> byAbsolutePath = new LinkedHashMap<>();
        ResourceIndex index = built.index();
        for (Map.Entry<String, List<ResourceIndex.Provider>> entry : index.entries().entrySet()) {
            if (!isAudio(entry.getKey())) {
                continue;
            }
            List<ResourceIndex.Provider> providers = entry.getValue();
            ResourceIndex.Provider winner = providers.get(providers.size() - 1);
            try {
                byAbsolutePath.put(key(index.resolveExisting(winner)), entry.getKey());
            } catch (IOException | RuntimeException ignored) {
                // A path that cannot be resolved cannot be matched against a read either.
            }
        }

        Map<String, AudioCensus.Sound> soundByLogicalPath = new LinkedHashMap<>();
        for (AudioCensus.Sound sound : census.sounds()) {
            soundByLogicalPath.put(sound.logicalPath(), sound);
        }

        Map<String, Long> firstReadNanos = new LinkedHashMap<>();
        Map<String, Long> readsByCaller = new TreeMap<>();
        long audioReadEvents = 0;
        long totalReadEvents = 0;
        Instant runStart = null;
        Instant runEnd = null;
        Boolean exhaustive = null;

        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String name = event.getEventType().getName();
                Instant start = event.getStartTime();
                runStart = runStart == null || start.isBefore(runStart) ? start : runStart;
                runEnd = runEnd == null || start.isAfter(runEnd) ? start : runEnd;

                if ("preflight.AgentStarted".equals(name)) {
                    exhaustive = event.hasField("exhaustiveFileReads")
                            && event.getBoolean("exhaustiveFileReads");
                    continue;
                }
                if (!"jdk.FileRead".equals(name)) {
                    continue;
                }
                totalReadEvents++;
                String path = event.hasField("path") ? event.getString("path") : null;
                if (path == null) {
                    continue;
                }
                String logical = byAbsolutePath.get(key(Path.of(path)));
                if (logical == null) {
                    continue;
                }
                audioReadEvents++;
                firstReadNanos.merge(logical, nanosSinceEpoch(start), Math::min);
                readsByCaller.merge(caller(event), 1L, Long::sum);
            }
        }

        if (exhaustive == null || !exhaustive) {
            return unusable(recording, exhaustive != null, census);
        }

        long zero = runStart == null ? 0 : nanosSinceEpoch(runStart);
        List<Tally> tallies = new ArrayList<>();
        for (AudioCensus.Kind kind : AudioCensus.Kind.values()) {
            int declared = 0;
            int read = 0;
            long openedBytes = 0;
            long neverOpenedBytes = 0;
            List<Long> offsets = new ArrayList<>();
            List<String> neverOpened = new ArrayList<>();
            for (Map.Entry<String, AudioCensus.Sound> entry : soundByLogicalPath.entrySet()) {
                AudioCensus.Sound sound = entry.getValue();
                if (sound.kind() != kind) {
                    continue;
                }
                declared++;
                Long at = firstReadNanos.get(entry.getKey());
                if (at != null) {
                    read++;
                    openedBytes += sound.decodedBytes();
                    offsets.add((at - zero) / 1_000_000L);
                } else {
                    neverOpenedBytes += sound.decodedBytes();
                    if (neverOpened.size() < NEVER_OPENED_SAMPLE) {
                        neverOpened.add(entry.getKey());
                    }
                }
            }
            offsets.sort(Comparator.naturalOrder());
            tallies.add(new Tally(kind.name().toLowerCase(Locale.ROOT), declared, read,
                    openedBytes, neverOpenedBytes, offsets, neverOpened));
        }

        Tally effects = tallies.stream()
                .filter(tally -> tally.kind().equals("effect"))
                .findFirst()
                .orElseThrow();
        long runSpanMillis = runStart == null || runEnd == null
                ? 0
                : Duration.between(runStart, runEnd).toMillis();
        Verdict verdict;
        String detail;
        if (effects.declared() == 0) {
            verdict = Verdict.INCONCLUSIVE;
            detail = "The profile declares no sound effects, so there was nothing to observe.";
        } else if (effects.read() == 0) {
            verdict = Verdict.LAZY;
            detail = "The run opened none of the " + effects.declared() + " declared effects. They"
                    + " cannot have been decoded, so the game does not build them at load.";
        } else if (isBurst(effects, runSpanMillis)) {
            verdict = Verdict.EAGER;
            detail = "The run opened " + effects.read() + " of " + effects.declared()
                    + " declared effects, and opened them inside "
                    + String.format(Locale.ROOT, "%.1f", burstMillis(effects) / 1000.0)
                    + " s of a "
                    + String.format(Locale.ROOT, "%.1f", runSpanMillis / 1000.0)
                    + " s session. Loading spread across a session is what on-demand looks like; a"
                    + " burst is what bulk loading looks like. Reads are not decodes, but a file that"
                    + " is never opened is never decoded"
                    + (effects.fraction() >= EAGER_FRACTION
                            ? "."
                            : ", and this covers only part of what the profile declares — the rest may"
                                    + " load in a later phase this session never reached.");
        } else if (effects.fraction() <= LAZY_FRACTION) {
            verdict = Verdict.LAZY;
            detail = "The run never opened " + (effects.declared() - effects.read()) + " of "
                    + effects.declared() + " declared effects, and opened the rest spread across the"
                    + " session rather than in a burst. The game does not build them all at load.";
        } else {
            verdict = Verdict.INCONCLUSIVE;
            detail = "The run opened " + effects.read() + " of " + effects.declared()
                    + " declared effects, spread across the session rather than concentrated at load."
                    + " That matches neither bulk loading nor purely on-demand loading, so the numbers"
                    + " are reported without a conclusion drawn from them.";
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportFormat", "starsector-preflight-audio-decode-probe-v1");
        report.put("recording", recording.toAbsolutePath().normalize().toString());
        report.put("installRoot", installRoot.toAbsolutePath().normalize().toString());
        report.put("verdict", verdict.name());
        report.put("detail", detail);
        report.put("exhaustiveFileReads", true);
        report.put("runSpanMillis", runStart == null || runEnd == null
                ? 0
                : Duration.between(runStart, runEnd).toMillis());
        report.put("fileReadEvents", totalReadEvents);
        report.put("audioFileReadEvents", audioReadEvents);
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (Tally tally : tallies) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", tally.kind());
            entry.put("declared", tally.declared());
            entry.put("opened", tally.read());
            entry.put("neverOpened", tally.declared() - tally.read());
            entry.put("openedDecodedBytes", tally.openedDecodedBytes());
            entry.put("neverOpenedDecodedBytes", tally.neverOpenedDecodedBytes());
            entry.put("firstOpenPercentilesMillis", percentiles(tally.firstReadMillis()));
            entry.put("neverOpenedSample", tally.neverOpenedSample());
            kinds.add(entry);
        }
        report.put("byKind", kinds);
        report.put("topReaders", readsByCaller.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(TOP_READERS)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("frame", entry.getKey());
                    row.put("reads", entry.getValue());
                    return row;
                })
                .toList());
        report.put("censusDiagnostics", census.report().get("diagnostics"));
        return new Result(verdict, detail, report);
    }

    private static Result unusable(Path recording, boolean markerPresent, AudioCensus.Result census) {
        String detail = markerPresent
                ? "This recording was made without --trace-all-file-reads, so file reads under a"
                        + " millisecond were dropped. Most sound files read from a warm page cache"
                        + " finish inside that, so an absent read here would mean nothing. Re-run"
                        + " with the flag."
                : "This recording carries no Preflight agent marker, so there is no way to tell"
                        + " whether file reads were filtered. Re-run through `preflight run"
                        + " --trace-all-file-reads`.";
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportFormat", "starsector-preflight-audio-decode-probe-v1");
        report.put("recording", recording.toAbsolutePath().normalize().toString());
        report.put("verdict", Verdict.UNUSABLE.name());
        report.put("detail", detail);
        report.put("exhaustiveFileReads", false);
        report.put("declaredEffects", census.sounds().stream()
                .filter(sound -> sound.kind() == AudioCensus.Kind.EFFECT)
                .count());
        return new Result(Verdict.UNUSABLE, detail, report);
    }

    /**
     * Whether the effects that were opened were opened all at once.
     *
     * <p>This is the signal the first version of this class lacked, and the first real run is what
     * exposed the gap. That run opened 62% of declared effects — between an eager threshold of 90% and
     * a lazy one of 10%, so it reported {@code INCONCLUSIVE} about data that is not remotely
     * ambiguous: every one of those 1,278 files was opened inside 1.5 seconds, 23 seconds into a six
     * minute session, by a single caller. The missing 38% turned out to be campaign sounds in a
     * session that never left the menus.</p>
     *
     * <p>Counting files was always a proxy. "Lazy" means <em>at the time of use</em>, so when the
     * opens happen is the direct evidence and how many happen is not. A burst answers the question the
     * fraction only gestures at.</p>
     */
    private static boolean isBurst(Tally effects, long runSpanMillis) {
        if (effects.read() < BURST_MINIMUM_FILES || runSpanMillis <= 0) {
            return false;
        }
        return (double) burstMillis(effects) / runSpanMillis <= BURST_SHARE_OF_RUN;
    }

    /** The window between the first and last first-open, in milliseconds. */
    private static long burstMillis(Tally effects) {
        List<Long> offsets = effects.firstReadMillis();
        return offsets.isEmpty() ? 0 : offsets.get(offsets.size() - 1) - offsets.get(0);
    }

    /** p0/p50/p90/p100 of first-open times, in milliseconds from the first event in the recording. */
    private static Map<String, Object> percentiles(List<Long> sorted) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (sorted.isEmpty()) {
            return values;
        }
        values.put("p0", sorted.get(0));
        values.put("p50", sorted.get(sorted.size() / 2));
        values.put("p90", sorted.get(Math.min(sorted.size() - 1, (sorted.size() * 9) / 10)));
        values.put("p100", sorted.get(sorted.size() - 1));
        return values;
    }

    /**
     * The first frame outside the JDK and Preflight — the code that actually asked for the file.
     * Knowing that all the audio reads share one caller is most of what separates "the game loaded
     * these" from "something walked the directory".
     */
    private static String caller(RecordedEvent event) {
        if (event.getStackTrace() == null) {
            return "<no stack>";
        }
        for (RecordedFrame frame : event.getStackTrace().getFrames()) {
            if (frame.getMethod() == null || frame.getMethod().getType() == null) {
                continue;
            }
            String type = frame.getMethod().getType().getName();
            if (type.startsWith("java.") || type.startsWith("jdk.") || type.startsWith("sun.")
                    || type.startsWith("dev.starsector.preflight.")) {
                continue;
            }
            return type + "." + frame.getMethod().getName();
        }
        return "<jdk only>";
    }

    private static boolean isAudio(String logicalPath) {
        return logicalPath.endsWith(".ogg") || logicalPath.endsWith(".wav")
                || logicalPath.endsWith(".mp3") || logicalPath.endsWith(".aif")
                || logicalPath.endsWith(".aiff");
    }

    /**
     * Lower-cased canonical path.
     *
     * <p>Symlinks have to be resolved on both sides or nothing matches. Flight Recorder reports the
     * path the JVM opened, which the OS has already resolved, while the resource index reports the
     * path Preflight walked to. On macOS those differ for anything under {@code /var} or
     * {@code /tmp}, and an install reached through a symlinked directory would differ too — in every
     * such case each file would look unopened and the probe would report a confident, wrong
     * {@code LAZY}. {@code toRealPath} is only available for a file that exists, so a path that has
     * since gone is normalized instead and simply may not match.</p>
     *
     * <p>Matching is case-insensitive because the two filesystems this runs on most are.</p>
     */
    private static String key(Path path) {
        Path resolved;
        try {
            resolved = path.toRealPath();
        } catch (IOException | RuntimeException ignored) {
            resolved = path.toAbsolutePath().normalize();
        }
        return resolved.toString().toLowerCase(Locale.ROOT);
    }

    private static long nanosSinceEpoch(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }
}

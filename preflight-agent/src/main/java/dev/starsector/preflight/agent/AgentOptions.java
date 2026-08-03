package dev.starsector.preflight.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

record AgentOptions(
        Path destination,
        String settings,
        AdapterMode adapterMode,
        Path adapterReport,
        Path adapterTargets,
        Path textureCacheDirectory,
        Path textureManifest,
        Path textureIndex,
        TextureAdapterMode textureAdapterMode,
        boolean exhaustiveFileReads,
        RecordingMode recordingMode,
        Duration flushInterval,
        boolean startupPhaseProbe,
        boolean ruleTokenCache,
        boolean resourceProbeCache,
        boolean loadJsonMemo,
        Path variantJsonCache,
        Path weaponJsonCache,
        Path projectileJsonCache,
        Path hullJsonCache,
        Path rulesCsvCache,
        Path ruleCommandClassCache,
        List<String> candidatePrefixes) {
    /**
     * Long enough that a startup which finishes inside it pays nothing, short enough that a session
     * ended by force-quit loses at most this much. A flush writes the recording so far -- single-digit
     * megabytes -- from a minimum-priority daemon thread.
     */
    static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofSeconds(60);

    private static final List<String> DEFAULT_CANDIDATE_PREFIXES = List.of(
            "com/fs/starfarer/",
            "com/fs/graphics/");

    AgentOptions {
        candidatePrefixes = List.copyOf(candidatePrefixes);
    }

    static AgentOptions parse(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw != null && !raw.isBlank()) {
            for (String token : raw.split(",")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0 || separator == trimmed.length() - 1) {
                    throw new IllegalArgumentException("Invalid agent option: " + trimmed);
                }
                String key = trimmed.substring(0, separator).trim();
                String prior = values.putIfAbsent(key, trimmed.substring(separator + 1).trim());
                if (prior != null) {
                    throw new IllegalArgumentException("Duplicate agent option: " + key);
                }
            }
        }

        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-');
        Path destination = decodedPath(values, "dest64");
        if (destination == null) {
            destination = Path.of(values.getOrDefault("dest", "preflight-startup-" + timestamp + ".jfr"));
        }
        String settings = values.getOrDefault("settings", "profile");
        AdapterMode adapterMode = AdapterMode.parse(values.get("adapter"));
        Path adapterReport = decodedPath(values, "adapterReport64");
        if (adapterReport == null) {
            adapterReport = destination.resolveSibling("adapter.json");
        }
        Path adapterTargets = decodedPath(values, "targets64");
        Path textureCacheDirectory = decodedPath(values, "textureCache64");
        Path textureManifest = decodedPath(values, "textureManifest64");
        Path textureIndex = decodedPath(values, "textureIndex64");
        TextureAdapterMode textureAdapterMode = TextureAdapterMode.parse(values.get("textureMode"));
        // The default recording drops file reads under a millisecond, which is most of them once the
        // page cache is warm. A question of the form "was this file opened at all" cannot be answered
        // from a threshold, so it gets its own opt-in.
        boolean exhaustiveFileReads = "all".equalsIgnoreCase(values.get("fileReads"));
        // Measuring the recorder against itself put a number on it: stack-traced class loads and
        // file reads plus 10ms execution sampling cost about 24% of startup on the reviewed
        // installation, which is more than the texture cache saves. Someone who wants the caches
        // and not the profile can turn the recording off; it stays on by default, because every
        // analysis command in this repository reads what it produces. The middle setting keeps the
        // sampling and drops the stack-traced per-event records, so a profile can ask where the
        // time goes without the measurement landing on class loading hardest.
        RecordingMode recordingMode = RecordingMode.parse(values.get("record"));
        // A recording with a destination is a zero-byte file until the JVM exits, and this project's
        // own recordings show the exit dump losing the tail even on a clean exit. The flusher writes
        // a sidecar as the run goes, so a force-quit or a crash still leaves something to read.
        Duration flushInterval = flushInterval(values.get("flush"));
        boolean startupPhaseProbe = "on".equalsIgnoreCase(values.get("startupPhases"));
        // No path and no artifact: the memo lives and dies with the process, so a plain
        // on/off is the whole of its configuration.
        boolean ruleTokenCache = "on".equalsIgnoreCase(values.get("ruleTokens"));
        // Same shape: an in-process memo of which directories exist, with nothing on disk
        // to point at and nothing to invalidate between launches.
        boolean resourceProbeCache = "on".equalsIgnoreCase(values.get("resourceProbes"));
        boolean loadJsonMemo = "on".equalsIgnoreCase(values.get("loadJsonMemo"));
        Path variantJsonCache = decodedPath(values, "variantJsonCache64");
        Path weaponJsonCache = decodedPath(values, "weaponJsonCache64");
        Path projectileJsonCache = decodedPath(values, "projectileJsonCache64");
        Path hullJsonCache = decodedPath(values, "hullJsonCache64");
        Path rulesCsvCache = decodedPath(values, "rulesCsvCache64");
        Path ruleCommandClassCache = decodedPath(values, "ruleCommandCache64");
        return new AgentOptions(
                destination,
                settings,
                adapterMode,
                adapterReport,
                adapterTargets,
                textureCacheDirectory,
                textureManifest,
                textureIndex,
                textureAdapterMode,
                exhaustiveFileReads,
                recordingMode,
                flushInterval,
                startupPhaseProbe,
                ruleTokenCache,
                resourceProbeCache,
                loadJsonMemo,
                variantJsonCache,
                weaponJsonCache,
                projectileJsonCache,
                hullJsonCache,
                rulesCsvCache,
                ruleCommandClassCache,
                DEFAULT_CANDIDATE_PREFIXES);
    }

    /** {@code flush=<seconds>}; {@code 0} turns sidecar flushing off. */
    private static Duration flushInterval(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_FLUSH_INTERVAL;
        }
        long seconds;
        try {
            seconds = Long.parseLong(raw.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid flush interval: " + raw, error);
        }
        if (seconds < 0) {
            throw new IllegalArgumentException("Flush interval cannot be negative: " + raw);
        }
        return Duration.ofSeconds(seconds);
    }


    private static Path decodedPath(Map<String, String> values, String key) {
        String encoded = values.get(key);
        if (encoded == null) {
            return null;
        }
        try {
            return Path.of(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid base64 path for " + key, error);
        }
    }
}

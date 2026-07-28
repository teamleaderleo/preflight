package dev.starsector.preflight.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
        List<String> candidatePrefixes) {
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
                DEFAULT_CANDIDATE_PREFIXES);
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

package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Privacy-preserving projection shared by every diagnostic evidence source. */
final class SupportEvidenceProjection {
    static final String FORMAT = "starsector-preflight-support-evidence-v1";
    static final int MAX_RECORDS = 2_048;
    static final int MAX_FIELDS_PER_RECORD = 512;
    static final int MAX_STRING_CHARS = 512;
    static final int MAX_FIELD_PATH_CHARS = 256;
    private static final int MAX_SOURCE_DEPTH = 12;
    private static final int MAX_ARRAY_ITEMS = 256;

    /**
     * Exact field-name vocabulary that support bundles may carry. Every non-numeric path segment
     * must appear here, including container segments. Deliberately omit path/file/root/directory,
     * command-line, log, stack, environment, token, receipt, key, URL, and free-form content names.
     */
    private static final Set<String> ALLOWED_SEGMENTS = Set.of(
            "active", "adapter", "adapterHealth", "adapters", "architecture", "available",
            "averageFps", "battleSize", "benchmark", "benchmarks", "bytes", "cache", "cacheHit",
            "cacheHits", "cacheMiss", "cacheMisses", "channels", "classCount", "configured",
            "containedFailure", "containedFailures", "count", "counts", "cpu", "createdAt",
            "current", "decline", "declined", "declineCount", "delta", "disabledOptimizationDomains",
            "domain", "domains", "durationMs", "enabled", "enabledMods", "engineVersion", "entries",
            "entryCount", "failure", "failures", "failureCount", "finishedAt", "format", "fps",
            "frame", "frames", "fullscreen", "gameVersion", "height", "hit", "hits", "identity",
            "identitySha256", "id", "improvementPercent", "kind", "launch", "launches", "maximum",
            "measurementOnly", "memoryMiB", "metrics", "minimum", "miss", "misses", "modCount",
            "modId", "modIds", "mods", "name", "observations", "optimized", "optimizationPreset",
            "phase", "platform", "preparedAudio", "preparedTextures", "preset", "process",
            "processToMainMenuMs", "profile", "profileFingerprint", "protocolVersion", "providerCount",
            "providers", "reason", "recommended", "resolution", "result", "results", "runtime",
            "seconds", "sha256", "size", "sound", "startedAt", "state", "status", "success",
            "successful", "summary", "timing", "timings", "total", "transformedClassCount",
            "transformedClasses", "uiScale", "version", "width", "antialiasing",
            "antialiasingSamples", "pixelConversions", "productVersion", "ready", "sampleCount",
            "samples", "selected", "storage", "totalBytes", "type", "value", "values");

    private SupportEvidenceProjection() {
    }

    static byte[] project(String sourceName, String text) {
        if (sourceName == null || sourceName.isBlank() || sourceName.contains("/") || sourceName.contains("\\")) {
            throw new IllegalArgumentException("Support evidence source must be a filename");
        }
        List<Map<String, Object>> sourceRecords = parseRecords(sourceName, text);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> source : sourceRecords) {
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            flatten(source, new ArrayList<>(), fields, 0);
            records.add(Map.of("fields", new LinkedHashMap<>(fields)));
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("format", FORMAT);
        envelope.put("source", sourceName);
        envelope.put("records", List.copyOf(records));
        return (Json.object(envelope) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    private static List<Map<String, Object>> parseRecords(String sourceName, String text) {
        if (sourceName.endsWith(".json")) {
            return List.of(StrictJson.object(text));
        }
        if (!sourceName.endsWith(".jsonl")) {
            throw new IllegalArgumentException("Unsupported support evidence source: " + sourceName);
        }
        List<Map<String, Object>> records = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].isBlank()) continue;
            if (records.size() >= MAX_RECORDS) {
                throw new IllegalArgumentException("Support evidence exceeds " + MAX_RECORDS + " records");
            }
            try {
                records.add(StrictJson.object(lines[index]));
            } catch (IllegalArgumentException malformed) {
                throw new IllegalArgumentException(
                        "Invalid JSON object in " + sourceName + " line " + (index + 1), malformed);
            }
        }
        return List.copyOf(records);
    }

    @SuppressWarnings("unchecked")
    private static void flatten(
            Object value,
            List<String> path,
            LinkedHashMap<String, Object> fields,
            int depth) {
        if (depth > MAX_SOURCE_DEPTH || fields.size() >= MAX_FIELDS_PER_RECORD) return;
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = (Map<String, Object>) raw;
            map.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        if (fields.size() >= MAX_FIELDS_PER_RECORD || !allowedSegment(entry.getKey())) return;
                        path.add(entry.getKey());
                        flatten(entry.getValue(), path, fields, depth + 1);
                        path.remove(path.size() - 1);
                    });
            return;
        }
        if (value instanceof List<?> list) {
            int limit = Math.min(list.size(), MAX_ARRAY_ITEMS);
            for (int index = 0; index < limit && fields.size() < MAX_FIELDS_PER_RECORD; index++) {
                path.add(Integer.toString(index));
                flatten(list.get(index), path, fields, depth + 1);
                path.remove(path.size() - 1);
            }
            return;
        }
        if (path.isEmpty() || !safeScalar(value)) return;
        String fieldPath = String.join(".", path);
        if (fieldPath.length() > MAX_FIELD_PATH_CHARS) return;
        fields.put(fieldPath, value);
    }

    private static boolean allowedSegment(String segment) {
        return segment != null && ALLOWED_SEGMENTS.contains(segment);
    }

    private static boolean safeScalar(Object value) {
        if (value == null || value instanceof Boolean) return true;
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            return Double.isFinite(numeric) && Math.abs(numeric) <= 9_007_199_254_740_991d;
        }
        if (!(value instanceof String text)) return false;
        return text.length() <= MAX_STRING_CHARS
                && text.chars().noneMatch(character -> character < 0x20 || character == 0x7f)
                && !looksLikeAbsolutePath(text);
    }

    private static boolean looksLikeAbsolutePath(String text) {
        String value = text.trim();
        if (value.startsWith("<home>")) return false;
        if (value.startsWith("/") || value.startsWith("\\\\") || value.startsWith("file:")) return true;
        return value.length() >= 3
                && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':'
                && (value.charAt(2) == '\\' || value.charAt(2) == '/');
    }
}

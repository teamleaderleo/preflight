package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strictly validates and seals a platform driver's desktop smoke evidence. */
final class DesktopSmokeEvidence {
    static final String FORMAT = "starsector-preflight-smoke-evidence-v1";
    static final String DRIVER_RESULT_FORMAT = "starsector-preflight-smoke-driver-result-v1";

    private static final long MAX_RESULT_BYTES = 1024 * 1024;
    private static final long MAX_ARTIFACT_BYTES = 256L * 1024 * 1024;
    private static final long MAX_TOTAL_ARTIFACT_BYTES = 512L * 1024 * 1024;
    private static final int MAX_ARTIFACTS = 100;
    private static final int MAX_DIAGNOSTICS = 100;
    private static final Set<String> ROOT_FIELDS = Set.of(
            "format", "status", "startedAt", "completedAt", "driver", "runDirectory",
            "steps", "diagnostics");
    private static final Set<String> DRIVER_FIELDS = Set.of(
            "id", "version", "platform", "capabilities");
    private static final Set<String> STEP_FIELDS = Set.of(
            "id", "status", "startedAt", "completedAt", "detail", "artifacts");
    private static final Set<String> ARTIFACT_FIELDS = Set.of("kind", "path");
    private static final Set<String> STATUSES = Set.of("passed", "failed", "skipped");
    private static final Set<String> PLATFORMS = Set.of("mac", "windows", "linux", "other");
    private static final Set<String> ARTIFACT_KINDS = Set.of(
            "screenshot", "audio-window", "log-tail", "adapter-health", "frame-report");

    private DesktopSmokeEvidence() {
    }

    static Map<String, Object> collect(
            DesktopSmokeScenario scenario, Path driverResult, Path destination) throws IOException {
        Map<String, Object> root = readResult(driverResult);
        exactFields(root, ROOT_FIELDS, "driver result");
        requireString(root, "format", DRIVER_RESULT_FORMAT);
        String status = member(requireString(root, "status"), STATUSES, "status");
        Instant startedAt = instant(root, "startedAt");
        Instant completedAt = instant(root, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt precedes startedAt");
        }

        Driver driver = driver(object(root, "driver"));
        Path runDirectory = directory(requireString(root, "runDirectory"));
        Path requestedOutput = destination.toAbsolutePath().normalize();
        Path outputParent = requestedOutput.getParent() == null
                ? null
                : requestedOutput.getParent().toRealPath();
        if (!runDirectory.equals(outputParent)) {
            throw new IllegalArgumentException("Smoke evidence must be written directly in the run directory");
        }
        if (!"smoke-evidence.json".equals(requestedOutput.getFileName().toString())) {
            throw new IllegalArgumentException("Smoke evidence filename must be smoke-evidence.json");
        }
        Path output = runDirectory.resolve(requestedOutput.getFileName());
        if (Files.isSymbolicLink(output)) {
            throw new IllegalArgumentException("Smoke evidence destination must not be a symbolic link");
        }

        List<String> diagnostics = boundedStrings(
                array(root, "diagnostics"), "diagnostics", MAX_DIAGNOSTICS, 2_000);
        List<Object> rawSteps = array(root, "steps");
        if (rawSteps.size() > scenario.stepIds().size()) {
            throw new IllegalArgumentException("Driver result contains more steps than the scenario");
        }

        boolean capabilitiesComplete = driver.capabilities().containsAll(scenario.requiredCapabilities());
        if (!capabilitiesComplete && !"skipped".equals(status)) {
            throw new IllegalArgumentException("A driver missing required capabilities must report skipped");
        }

        Collector collector = new Collector(runDirectory);
        List<Map<String, Object>> steps = new ArrayList<>();
        Instant cursor = startedAt;
        for (int index = 0; index < rawSteps.size(); index++) {
            Map<String, Object> raw = castObject(rawSteps.get(index), "steps[" + index + "]");
            exactFields(raw, STEP_FIELDS, "steps[" + index + "]");
            String expectedId = scenario.stepIds().get(index);
            String actualId = requireString(raw, "id");
            if (!expectedId.equals(actualId)) {
                throw new IllegalArgumentException(
                        "Driver result step order differs from the scenario at " + expectedId);
            }
            String stepStatus = member(
                    requireString(raw, "status"), STATUSES, "steps[" + index + "].status");
            Instant stepStarted = instant(raw, "startedAt");
            Instant stepCompleted = instant(raw, "completedAt");
            if (stepStarted.isBefore(cursor)
                    || stepCompleted.isBefore(stepStarted)
                    || stepCompleted.isAfter(completedAt)) {
                throw new IllegalArgumentException("Driver result step timestamps are out of order");
            }
            if (index + 1 < rawSteps.size() && !"passed".equals(stepStatus)) {
                throw new IllegalArgumentException("Only the final reported step may fail or skip");
            }
            String detail = boundedString(raw, "detail", 2_000);
            List<Map<String, Object>> artifacts = collector.collect(
                    array(raw, "artifacts"), "steps[" + index + "].artifacts");
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", actualId);
            step.put("status", stepStatus);
            step.put("startedAt", stepStarted);
            step.put("completedAt", stepCompleted);
            step.put("detail", detail);
            step.put("artifacts", artifacts);
            steps.add(step);
            cursor = stepCompleted;
        }
        validateOutcome(status, scenario.stepIds().size(), steps);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", FORMAT);
        result.put("scenario", scenario.name());
        result.put("status", status);
        result.put("startedAt", startedAt);
        result.put("completedAt", completedAt);
        result.put("driver", driver.view());
        result.put("runDirectory", runDirectory);
        result.put("steps", steps);
        result.put("artifacts", collector.all());
        result.put("diagnostics", diagnostics);
        atomicWrite(output, Json.object(result) + System.lineSeparator());
        return result;
    }

    static Map<String, Object> readResult(Path source) throws IOException {
        return readResult(source, ignored -> {});
    }

    static Map<String, Object> readResult(Path source, BeforeOpenHook beforeOpen) throws IOException {
        if (beforeOpen == null) {
            throw new IllegalArgumentException("Desktop smoke pre-open hook is required");
        }
        Path absolute = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Desktop smoke driver result is not a regular file: " + absolute);
        }
        if (Files.size(absolute) > MAX_RESULT_BYTES) {
            throw new IOException("Desktop smoke driver result exceeds " + MAX_RESULT_BYTES + " bytes");
        }
        beforeOpen.run(absolute);
        try (InputStream input = Files.newInputStream(
                absolute, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return readResult(input, absolute.toString());
        }
    }

    static Map<String, Object> readResult(InputStream input, String sourceLabel) throws IOException {
        return BoundedEvidenceJson.readObject(
                input, MAX_RESULT_BYTES, sourceLabel, "Desktop smoke driver result");
    }

    static long artifactHashLimit(long totalBytes) throws IOException {
        if (totalBytes < 0 || totalBytes > MAX_TOTAL_ARTIFACT_BYTES) {
            throw new IOException("Smoke artifact total is outside the admitted range");
        }
        return Math.min(MAX_ARTIFACT_BYTES, MAX_TOTAL_ARTIFACT_BYTES - totalBytes);
    }

    static String hashArtifact(InputStream input, long maximumBytes, String sourceLabel)
            throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Smoke artifact input is required");
        }
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("Smoke artifact byte limit must be nonnegative");
        }
        return Hashes.sha256(new BoundedArtifactInputStream(input, maximumBytes, sourceLabel));
    }

    @FunctionalInterface
    interface BeforeOpenHook {
        void run(Path path) throws IOException;
    }

    private static Driver driver(Map<String, Object> raw) {
        exactFields(raw, DRIVER_FIELDS, "driver");
        String id = identifier(requireString(raw, "id"), "driver.id");
        String version = boundedString(raw, "version", 100);
        if (version.isBlank()) {
            throw new IllegalArgumentException("driver.version must not be blank");
        }
        String platform = member(requireString(raw, "platform"), PLATFORMS, "driver.platform");
        List<String> rawCapabilities = boundedStrings(
                array(raw, "capabilities"), "driver.capabilities", 32, 64);
        Set<String> capabilities = new LinkedHashSet<>();
        for (String capability : rawCapabilities) {
            identifier(capability, "driver capability");
            if (!capabilities.add(capability)) {
                throw new IllegalArgumentException("Duplicate driver capability: " + capability);
            }
        }
        return new Driver(id, version, platform, List.copyOf(capabilities));
    }

    private static void validateOutcome(
            String status, int scenarioSteps, List<Map<String, Object>> steps) {
        if ("passed".equals(status)) {
            if (steps.size() != scenarioSteps
                    || steps.stream().anyMatch(step -> !"passed".equals(step.get("status")))) {
                throw new IllegalArgumentException("A passed result requires every scenario step to pass");
            }
            return;
        }
        if (steps.isEmpty()) {
            if (!"skipped".equals(status)) {
                throw new IllegalArgumentException("A failed result must identify the failed step");
            }
            return;
        }
        String finalStatus = steps.get(steps.size() - 1).get("status").toString();
        if (!status.equals(finalStatus)) {
            throw new IllegalArgumentException("The final step status must match the overall result");
        }
    }

    private static Path directory(String value) throws IOException {
        Path declared = Path.of(value);
        if (!declared.isAbsolute()) {
            throw new IllegalArgumentException("runDirectory must be absolute");
        }
        if (!Files.isDirectory(declared, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("runDirectory is not a directory: " + declared);
        }
        return declared.toRealPath();
    }

    private static void atomicWrite(Path destination, String value) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName()
                + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static void exactFields(Map<String, Object> value, Set<String> fields, String context) {
        for (String field : value.keySet()) {
            if (!fields.contains(field)) {
                throw new IllegalArgumentException(context + " contains unknown field: " + field);
            }
        }
        for (String field : fields) {
            if (!value.containsKey(field)) {
                throw new IllegalArgumentException(context + " is missing field: " + field);
            }
        }
    }

    private static String requireString(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return text;
    }

    private static void requireString(Map<String, Object> value, String field, String expected) {
        if (!expected.equals(requireString(value, field))) {
            throw new IllegalArgumentException(field + " must be " + expected);
        }
    }

    private static String boundedString(Map<String, Object> value, String field, int maximum) {
        String text = requireString(value, field);
        if (text.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
        return text;
    }

    private static Instant instant(Map<String, Object> value, String field) {
        try {
            return Instant.parse(requireString(value, field));
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(field + " must be an RFC 3339 instant", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> value, String field) {
        return castObject(value.get(field), field);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castObject(Object raw, String context) {
        if (!(raw instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(context + " must be an object");
        }
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof List<?>)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return (List<Object>) raw;
    }

    private static List<String> boundedStrings(
            List<Object> raw, String context, int maximumItems, int maximumLength) {
        if (raw.size() > maximumItems) {
            throw new IllegalArgumentException(context + " exceeds " + maximumItems + " items");
        }
        List<String> result = new ArrayList<>();
        for (Object value : raw) {
            if (!(value instanceof String text) || text.length() > maximumLength) {
                throw new IllegalArgumentException(
                        context + " must contain strings no longer than " + maximumLength);
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static String identifier(String value, String context) {
        if (!value.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException(
                    context + " must match [a-z0-9][a-z0-9-]{0,63}");
        }
        return value;
    }

    private static String member(String value, Set<String> allowed, String context) {
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(context + " is unsupported: " + value);
        }
        return value;
    }

    private record Driver(String id, String version, String platform, List<String> capabilities) {
        Map<String, Object> view() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", id);
            value.put("version", version);
            value.put("platform", platform);
            value.put("capabilities", capabilities);
            return value;
        }
    }

    private static final class BoundedArtifactInputStream extends InputStream {
        private final InputStream delegate;
        private final long maximumBytes;
        private final String sourceLabel;
        private long remaining;

        private BoundedArtifactInputStream(InputStream delegate, long maximumBytes, String sourceLabel) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
            this.sourceLabel = sourceLabel == null ? "smoke artifact" : sourceLabel;
            this.remaining = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                int extra = delegate.read();
                if (extra < 0) return -1;
                throw exceeded();
            }
            int value = delegate.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (bytes == null) throw new NullPointerException("bytes");
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return 0;
            if (remaining == 0) {
                int extra = delegate.read();
                if (extra < 0) return -1;
                throw exceeded();
            }
            int allowed = (int) Math.min((long) length, remaining);
            int read = delegate.read(bytes, offset, allowed);
            if (read > 0) remaining -= read;
            return read;
        }

        private IOException exceeded() {
            return new IOException(
                    "Smoke artifact read exceeds " + maximumBytes + " bytes: " + sourceLabel);
        }
    }

    private static final class Collector {
        private final Path runDirectory;
        private final Map<Path, Map<String, Object>> artifacts = new LinkedHashMap<>();
        private long totalBytes;

        private Collector(Path runDirectory) {
            this.runDirectory = runDirectory;
        }

        private List<Map<String, Object>> collect(List<Object> raw, String context) throws IOException {
            if (raw.size() > MAX_ARTIFACTS || artifacts.size() + raw.size() > MAX_ARTIFACTS) {
                throw new IllegalArgumentException("Smoke evidence exceeds " + MAX_ARTIFACTS + " artifacts");
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (int index = 0; index < raw.size(); index++) {
                Map<String, Object> declaration = castObject(raw.get(index), context + "[" + index + "]");
                exactFields(declaration, ARTIFACT_FIELDS, context + "[" + index + "]");
                String kind = member(
                        requireString(declaration, "kind"), ARTIFACT_KINDS, context + ".kind");
                Path declared = Path.of(requireString(declaration, "path"));
                Path candidate = declared.isAbsolute() ? declared.normalize() : runDirectory.resolve(declared).normalize();
                if (!candidate.startsWith(runDirectory)
                        || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Smoke artifact is not a regular file inside the run directory: "
                            + candidate);
                }
                Path real = candidate.toRealPath();
                if (!real.startsWith(runDirectory)) {
                    throw new IOException("Smoke artifact resolves outside the run directory: " + candidate);
                }
                if (artifacts.containsKey(real)) {
                    throw new IllegalArgumentException("Smoke artifact is declared more than once: " + real);
                }
                BasicFileAttributes before = Files.readAttributes(
                        real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (before.size() > MAX_ARTIFACT_BYTES) {
                    throw new IOException("Smoke artifact exceeds " + MAX_ARTIFACT_BYTES + " bytes: " + real);
                }
                long hashLimit = artifactHashLimit(totalBytes);
                if (before.size() > hashLimit) {
                    throw new IOException(
                            "Smoke artifacts exceed " + MAX_TOTAL_ARTIFACT_BYTES + " bytes in total");
                }
                String sha256;
                try (InputStream input = Files.newInputStream(
                        real, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    sha256 = hashArtifact(input, hashLimit, real.toString());
                }
                BasicFileAttributes after = Files.readAttributes(
                        real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (before.size() != after.size()
                        || !before.lastModifiedTime().equals(after.lastModifiedTime())
                        || (before.fileKey() != null && !before.fileKey().equals(after.fileKey()))) {
                    throw new IOException("Smoke artifact changed while it was being sealed: " + real);
                }
                totalBytes = Math.addExact(totalBytes, after.size());
                if (totalBytes > MAX_TOTAL_ARTIFACT_BYTES) {
                    throw new IOException(
                            "Smoke artifacts exceed " + MAX_TOTAL_ARTIFACT_BYTES + " bytes in total");
                }
                Map<String, Object> artifact = new LinkedHashMap<>();
                artifact.put("kind", kind);
                artifact.put("path", real);
                artifact.put("bytes", after.size());
                artifact.put("sha256", sha256);
                Map<String, Object> sealed = Collections.unmodifiableMap(artifact);
                artifacts.put(real, sealed);
                result.add(sealed);
            }
            return List.copyOf(result);
        }

        private List<Map<String, Object>> all() {
            return List.copyOf(artifacts.values());
        }
    }
}

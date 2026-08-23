package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Driver-neutral semantic scenario for desktop smoke automation. */
final class DesktopSmokeScenario {
    static final String FORMAT = "starsector-preflight-smoke-v1";
    private static final int MAX_FILE_CHARS = 256 * 1024;
    private static final int MAX_STEPS = 100;
    private static final Set<String> ROOT_FIELDS =
            Set.of("format", "name", "timeoutSeconds", "launch", "steps");
    private static final Set<String> LAUNCH_FIELDS =
            Set.of("preset", "textureStorage", "profile");
    private static final Set<String> COMMON_STEP_FIELDS = Set.of("id", "kind");
    private static final Set<String> STATES = Set.of(
            "main-menu-ready", "main-menu-interactive", "campaign-ready",
            "refit-ready", "simulation-ready", "combat-ready");
    private static final Set<String> TARGETS = Set.of(
            "main-menu.continue",
            "main-menu.load-game",
            "load-game.first-save",
            "load-game.load",
            "campaign.refit",
            "refit.run-simulation",
            "simulation.default",
            "simulation.engage",
            "simulation.exit",
            "refit.exit",
            "main-menu.quit");
    private static final Set<String> ARTIFACTS = Set.of(
            "screenshot", "audio-window", "log-tail", "adapter-health", "frame-report");

    private final String name;
    private final int timeoutSeconds;
    private final Launch launch;
    private final List<Step> steps;
    private final Set<String> requiredCapabilities;

    private DesktopSmokeScenario(String name, int timeoutSeconds, Launch launch, List<Step> steps) {
        this.name = name;
        this.timeoutSeconds = timeoutSeconds;
        this.launch = launch;
        this.steps = List.copyOf(steps);
        this.requiredCapabilities = capabilities(steps);
    }

    static DesktopSmokeScenario read(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IOException("Desktop smoke scenario is not a regular file: " + absolute);
        }
        long size = Files.size(absolute);
        if (size > MAX_FILE_CHARS) {
            throw new IOException("Desktop smoke scenario exceeds " + MAX_FILE_CHARS + " bytes");
        }
        return parse(Files.readString(absolute, StandardCharsets.UTF_8));
    }

    static DesktopSmokeScenario parse(String json) {
        Map<String, Object> root = StrictJson.object(json);
        exactFields(root, ROOT_FIELDS, "scenario");
        requireString(root, "format", FORMAT);
        String name = identifier(requireString(root, "name"), "scenario name");
        int timeout = integer(root, "timeoutSeconds", 10, 3_600);
        Launch launch = launch(object(root, "launch"));
        List<Object> rawSteps = array(root, "steps");
        if (rawSteps.isEmpty() || rawSteps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("steps must contain 1.." + MAX_STEPS + " entries");
        }
        List<Step> steps = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < rawSteps.size(); index++) {
            if (!(rawSteps.get(index) instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("steps[" + index + "] must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> step = (Map<String, Object>) raw;
            Step parsed = step(step, index);
            if (!ids.add(parsed.id())) {
                throw new IllegalArgumentException("Duplicate step id: " + parsed.id());
            }
            steps.add(parsed);
        }
        return new DesktopSmokeScenario(name, timeout, launch, steps);
    }

    Map<String, Object> view() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", FORMAT);
        value.put("name", name);
        value.put("timeoutSeconds", timeoutSeconds);
        value.put("launch", launch.view());
        value.put("requiredCapabilities", requiredCapabilities);
        value.put("steps", steps.stream().map(Step::view).toList());
        return value;
    }

    String name() {
        return name;
    }

    Set<String> requiredCapabilities() {
        return requiredCapabilities;
    }

    boolean usesOnlyRuntimeState() {
        return requiredCapabilities.equals(Set.of("process-control", "semantic-state"))
                && steps.stream().allMatch(step -> "wait-state".equals(step.kind()));
    }

    List<String> stepIds() {
        return steps.stream().map(Step::id).toList();
    }

    int timeoutSeconds() {
        return timeoutSeconds;
    }

    String launchPreset() {
        return launch.preset();
    }

    String launchProfile() {
        return launch.profile();
    }

    String textureStorage() {
        return launch.textureStorage();
    }

    Map<String, Object> benchmarkIdentity() {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("format", FORMAT);
        identity.put("timeoutSeconds", timeoutSeconds);
        identity.put("textureStorage", launch.textureStorage());
        identity.put("profile", launch.profile());
        identity.put("steps", stepViews());
        return identity;
    }

    List<Map<String, Object>> stepViews() {
        return steps.stream().map(Step::view).toList();
    }

    private static Launch launch(Map<String, Object> value) {
        exactFields(value, LAUNCH_FIELDS, "launch");
        String preset = requireString(value, "preset");
        if (!Set.of("fast", "measurement-only").contains(preset)) {
            throw new IllegalArgumentException(
                    "launch.preset must be fast or measurement-only");
        }
        String storage = requireString(value, "textureStorage");
        if (!Set.of("balanced", "fastest", "minimal").contains(storage)) {
            throw new IllegalArgumentException(
                    "launch.textureStorage must be balanced, fastest, or minimal");
        }
        String profile = optionalString(value, "profile");
        if (profile != null && (profile.isBlank() || profile.length() > 100)) {
            throw new IllegalArgumentException("launch.profile must be 1..100 characters");
        }
        return new Launch(preset, storage, profile);
    }

    private static Step step(Map<String, Object> value, int index) {
        String context = "steps[" + index + "]";
        String id = identifier(requireString(value, "id"), context + ".id");
        String kind = requireString(value, "kind");
        return switch (kind) {
            case "wait-state" -> {
                exactFields(value, with("state", "timeoutSeconds"), context);
                String state = member(requireString(value, "state"), STATES, context + ".state");
                int timeout = integer(value, "timeoutSeconds", 1, 600);
                yield new Step(id, kind, Map.of("state", state, "timeoutSeconds", timeout));
            }
            case "activate-window", "quit" -> {
                exactFields(value, COMMON_STEP_FIELDS, context);
                yield new Step(id, kind, Map.of());
            }
            case "click" -> {
                exactFields(value, with("target"), context);
                String target = member(requireString(value, "target"), TARGETS, context + ".target");
                yield new Step(id, kind, Map.of("target", target));
            }
            case "press-key" -> {
                exactFields(value, with("key"), context);
                yield new Step(id, kind, Map.of("key", key(requireString(value, "key"), context)));
            }
            case "hold-key" -> {
                exactFields(value, with("key", "durationMillis"), context);
                String key = key(requireString(value, "key"), context);
                int duration = integer(value, "durationMillis", 50, 30_000);
                yield new Step(id, kind, Map.of("key", key, "durationMillis", duration));
            }
            case "capture" -> {
                exactFields(value, with("artifacts"), context);
                List<String> artifacts = stringArray(value, "artifacts");
                if (artifacts.isEmpty()) {
                    throw new IllegalArgumentException(context + ".artifacts must not be empty");
                }
                Set<String> distinct = new LinkedHashSet<>();
                for (String artifact : artifacts) {
                    member(artifact, ARTIFACTS, context + ".artifacts");
                    if (!distinct.add(artifact)) {
                        throw new IllegalArgumentException("Duplicate artifact: " + artifact);
                    }
                }
                yield new Step(id, kind, Map.of("artifacts", List.copyOf(distinct)));
            }
            default -> throw new IllegalArgumentException(context + ".kind is unsupported: " + kind);
        };
    }

    private static Set<String> capabilities(List<Step> steps) {
        Set<String> capabilities = new LinkedHashSet<>();
        capabilities.add("process-control");
        for (Step step : steps) {
            switch (step.kind()) {
                case "wait-state" -> capabilities.add("semantic-state");
                case "activate-window", "click", "press-key", "hold-key" ->
                        capabilities.add("window-control");
                case "capture" -> {
                    @SuppressWarnings("unchecked")
                    List<String> artifacts = (List<String>) step.values().get("artifacts");
                    for (String artifact : artifacts) {
                        switch (artifact) {
                            case "screenshot" -> capabilities.add("screen-capture");
                            case "audio-window" -> capabilities.add("audio-capture");
                            default -> capabilities.add("evidence-read");
                        }
                    }
                }
                default -> {
                    // quit is process control, which is always required.
                }
            }
        }
        return java.util.Collections.unmodifiableSet(capabilities);
    }

    private static String key(String value, String context) {
        if (!value.matches("[A-Za-z0-9_-]{1,24}")) {
            throw new IllegalArgumentException(context + ".key is invalid: " + value);
        }
        return value;
    }

    private static String identifier(String value, String context) {
        if (!value.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException(context + " must match [a-z0-9][a-z0-9-]{0,63}");
        }
        return value;
    }

    private static String member(String value, Set<String> allowed, String context) {
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(context + " is unsupported: " + value);
        }
        return value;
    }

    private static Set<String> with(String... fields) {
        Set<String> allowed = new LinkedHashSet<>(COMMON_STEP_FIELDS);
        allowed.addAll(List.of(fields));
        return Set.copyOf(allowed);
    }

    private static void exactFields(Map<String, Object> value, Set<String> allowed, String context) {
        for (String key : value.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(context + " contains unknown field: " + key);
            }
        }
        for (String key : allowed) {
            if (!value.containsKey(key)) {
                throw new IllegalArgumentException(context + " is missing field: " + key);
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
        String actual = requireString(value, field);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(field + " must be " + expected);
        }
    }

    private static String optionalString(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) return null;
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException(field + " must be a string or null");
        }
        return text;
    }

    private static int integer(Map<String, Object> value, String field, int minimum, int maximum) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        long parsed = number.longValue();
        if (number.doubleValue() != parsed || parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(
                    field + " must be an integer in " + minimum + ".." + maximum);
        }
        return (int) parsed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(field + " must be an object");
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

    private static List<String> stringArray(Map<String, Object> value, String field) {
        List<Object> raw = array(value, field);
        List<String> strings = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof String text)) {
                throw new IllegalArgumentException(field + " must contain only strings");
            }
            strings.add(text);
        }
        return List.copyOf(strings);
    }

    private record Launch(String preset, String textureStorage, String profile) {
        Map<String, Object> view() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("preset", preset);
            value.put("textureStorage", textureStorage);
            value.put("profile", profile);
            return value;
        }
    }

    private record Step(String id, String kind, Map<String, Object> values) {
        Map<String, Object> view() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", id);
            value.put("kind", kind);
            value.putAll(values);
            return value;
        }
    }
}

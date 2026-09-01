package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Selects the reviewed Linux startup collector without editing Starsector's launcher. */
final class LinuxStartupGcPolicy {
    static final String DISABLE_ENVIRONMENT = "PREFLIGHT_DISABLE_LINUX_G1_POLICY";
    static final String DISABLE_SHENANDOAH = "-XX:-UseShenandoahGC";
    static final String ENABLE_G1 = "-XX:+UseG1GC";
    static final String DEFER_HEAP_COMMIT = "-XX:-AlwaysPreTouch";
    static final String MODE_PROPERTY = "-Dpreflight.linux.gcPolicy=reviewed-g1";
    private static final int MAX_INPUT_BYTES = 1024 * 1024;

    private LinuxStartupGcPolicy() {
    }

    static Resolution resolve(
            Platform platform,
            LaunchTarget target,
            OptimizationPreset preset,
            Map<String, String> environment) {
        if (preset != OptimizationPreset.RECOMMENDED) {
            return Resolution.inactive("the selected optimization preset preserves the launcher collector");
        }
        if (disabled(environment.get(DISABLE_ENVIRONMENT))) {
            return Resolution.inactive("disabled by " + DISABLE_ENVIRONMENT);
        }
        if (explicitCollector(environment.get("_JAVA_OPTIONS"))
                || explicitCollector(environment.get("JAVA_TOOL_OPTIONS"))) {
            return Resolution.inactive("the environment already selects a garbage collector");
        }
        if (platform != Platform.LINUX) {
            return Resolution.inactive("not Linux");
        }

        Path launcher = target.launcher().toAbsolutePath().normalize();
        Path release = target.installRoot().resolve("jre_linux/release").toAbsolutePath().normalize();
        try {
            if (!reviewedLauncher(boundedRegularText(launcher))) {
                return new Resolution(false,
                        "launcher does not match the reviewed Linux Shenandoah policy", launcher, release);
            }
            if (!reviewedRuntime(boundedRegularText(release))) {
                return new Resolution(false,
                        "bundled runtime is not the reviewed x86-64 Zulu 17.0.10 Linux build",
                        launcher, release);
            }
            return new Resolution(true,
                    "reviewed Linux x86-64 Zulu launcher/runtime policy matched", launcher, release);
        } catch (IOException | RuntimeException problem) {
            return new Resolution(false,
                    "collector policy probe failed: " + message(problem), launcher, release);
        }
    }

    static String appendOptions(String existing, Resolution resolution) {
        if (!resolution.active()) return existing;
        String result = existing == null ? "" : existing.trim();
        result = appendUnlessPresent(result, DISABLE_SHENANDOAH);
        result = appendUnlessPresent(result, ENABLE_G1);
        result = appendUnlessPresent(result, DEFER_HEAP_COMMIT);
        result = appendUnlessPresent(result, MODE_PROPERTY);
        return result;
    }

    private static boolean reviewedLauncher(String text) {
        return containsAll(text,
                "./jre_linux/bin/java",
                "-XX:+AlwaysPreTouch",
                "-XX:+UseShenandoahGC",
                "-XX:ShenandoahGCMode=iu",
                "-XX:ShenandoahGCHeuristics=compact",
                "-XX:ShenandoahGuaranteedGCInterval=0");
    }

    private static boolean reviewedRuntime(String text) {
        return containsAll(text,
                "IMPLEMENTOR=\"Azul Systems, Inc.\"",
                "JAVA_RUNTIME_VERSION=\"17.0.10+7-LTS\"",
                "OS_ARCH=\"x86_64\"",
                "OS_NAME=\"Linux\"");
    }

    private static String boundedRegularText(Path path) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a regular file: " + path);
        }
        long size = Files.size(path);
        if (size > MAX_INPUT_BYTES) {
            throw new IOException("refusing oversized policy input " + path + " (" + size + " bytes)");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static boolean explicitCollector(String options) {
        if (options == null || options.isBlank()) return false;
        for (String token : options.trim().split("\\s+")) {
            if (token.startsWith("-XX:+Use") && token.endsWith("GC")) return true;
            if (token.startsWith("-XX:-Use") && token.endsWith("GC")) return true;
        }
        return false;
    }

    private static boolean disabled(String value) {
        if (value == null) return false;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    private static boolean containsAll(String text, String... needles) {
        for (String needle : needles) {
            if (!text.contains(needle)) return false;
        }
        return true;
    }

    private static String appendUnlessPresent(String existing, String option) {
        if ((" " + existing + " ").contains(" " + option + " ")) return existing;
        return existing.isBlank() ? option : existing + " " + option;
    }

    private static String message(Throwable problem) {
        String detail = problem.getMessage();
        return problem.getClass().getSimpleName()
                + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    record Resolution(boolean active, String reason, Path launcher, Path runtimeRelease) {
        static Resolution inactive(String reason) {
            return new Resolution(false, reason, null, null);
        }

        Map<String, Object> toReportValues() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("active", active);
            values.put("reason", reason);
            values.put("launcher", launcher);
            values.put("runtimeRelease", runtimeRelease);
            values.put("javaOptions", active
                    ? List.of(DISABLE_SHENANDOAH, ENABLE_G1, DEFER_HEAP_COMMIT, MODE_PROPERTY)
                    : List.of());
            values.put("disableEnvironment", DISABLE_ENVIRONMENT);
            return values;
        }
    }
}

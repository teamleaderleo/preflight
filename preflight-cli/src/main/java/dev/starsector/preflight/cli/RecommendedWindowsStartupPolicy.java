package dev.starsector.preflight.cli;

/** Enables accepted, exact-gated Windows startup plans for the recommended preset. */
final class RecommendedWindowsStartupPolicy {
    static final String KALEIDOSCOPE_PREFETCH_PROPERTY =
            "preflight.texture.windowsKaleidoscopePrefetch";

    private RecommendedWindowsStartupPolicy() {
    }

    static String appendOptions(
            String existing, Platform platform, OptimizationPreset preset) {
        if (platform != Platform.WINDOWS || preset != OptimizationPreset.RECOMMENDED) {
            return existing;
        }
        String result = existing == null ? "" : existing.trim();
        if (containsProperty(result, KALEIDOSCOPE_PREFETCH_PROPERTY)) {
            return result;
        }
        String option = "-D" + KALEIDOSCOPE_PREFETCH_PROPERTY + "=true";
        return result.isBlank() ? option : result + " " + option;
    }

    private static boolean containsProperty(String options, String property) {
        if (options.isBlank()) return false;
        String prefix = "-D" + property;
        for (String token : options.split("\\s+")) {
            if (token.equals(prefix) || token.startsWith(prefix + "=")) return true;
        }
        return false;
    }
}

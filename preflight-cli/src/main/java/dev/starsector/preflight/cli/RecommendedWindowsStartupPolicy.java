package dev.starsector.preflight.cli;

/** Enables accepted, exact-gated Windows startup plans for the recommended preset. */
final class RecommendedWindowsStartupPolicy {
    static final String KALEIDOSCOPE_PREFETCH_PROPERTY =
            "preflight.texture.windowsKaleidoscopePrefetch";
    static final String PREPARED_RESOURCES_PROPERTY = "preflight.texture.windowsPreparedResources";
    static final String PRESTART_PROPERTY = "preflight.texture.windowsPreparedPrestart";
    static final String UNIT_MEMO_PROPERTY = "preflight.janino.unitMemo";
    static final String STAGING_PROPERTY = "preflight.texture.preparedStaging";
    static final String FACTION_PRIORITY_PROPERTY = "preflight.startup.windowsFactionPriorityCache";

    private RecommendedWindowsStartupPolicy() {
    }

    static String appendOptions(
            String existing, Platform platform, OptimizationPreset preset) {
        return appendOptions(existing, platform, preset, false);
    }

    static String appendOptions(String existing, Platform platform, OptimizationPreset preset,
            boolean validatedPreparedAudio) {
        if (platform != Platform.WINDOWS || preset != OptimizationPreset.RECOMMENDED) {
            return existing;
        }
        String result = existing == null ? "" : existing.trim();
        result = appendDefault(result, KALEIDOSCOPE_PREFETCH_PROPERTY);
        if (validatedPreparedAudio) {
            // Earlier admission only earned promotion with the Windows audio dependency removed.
            // Runtime admission still checks exact identities, one worker and main ownership.
            if (allowsTrue(result, PREPARED_RESOURCES_PROPERTY) && allowsTrue(result, PRESTART_PROPERTY)) {
                result = appendDefault(result, PREPARED_RESOURCES_PROPERTY);
                result = appendDefault(result, PRESTART_PROPERTY);
                // Staging earned promotion only with repeated live compilation removed.
                if (allowsTrue(result, UNIT_MEMO_PROPERTY) && allowsTrue(result, STAGING_PROPERTY)) {
                    result = appendDefault(result, UNIT_MEMO_PROPERTY);
                    result = appendDefault(result, STAGING_PROPERTY);
                }
            }
            result = appendDefault(result, FACTION_PRIORITY_PROPERTY);
        }
        return result;
    }

    private static String appendDefault(String result, String property) {
        if (containsProperty(result, property)) return result;
        String option = "-D" + property + "=true";
        return result.isBlank() ? option : result + " " + option;
    }

    private static boolean allowsTrue(String options, String property) {
        String prefix = "-D" + property;
        for (String token : options.split("\\s+")) {
            if (token.equals(prefix)) return false;
            if (token.startsWith(prefix + "=") && !Boolean.parseBoolean(token.substring(prefix.length() + 1))) {
                return false;
            }
        }
        return true;
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

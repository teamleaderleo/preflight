package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports whether this machine can start Starsector without its launcher, and with what settings.
 *
 * <p>Exists so the benchmark harness can decide between an unattended protocol and a clicked one
 * before it launches anything, rather than discovering mid-campaign that nothing pressed the button.
 * Always exits zero and always emits a document: "not available, and here is why" is the answer to
 * the question, not a failure to answer it.
 */
final class LaunchSettingsCommand {
    private LaunchSettingsCommand() {
    }

    static int execute(String[] args, int offset) {
        for (int i = offset; i < args.length; i++) {
            if (!"--json".equals(args[i])) {
                System.err.println("Unknown option: " + args[i]);
                return 2;
            }
        }
        DirectLaunchSettings.Availability availability =
                DirectLaunchSettings.preferencesReadable()
                        ? DirectLaunchSettings.resolve(DirectLaunchSettings.installedPreferences())
                        : DirectLaunchSettings.Availability.unavailable(
                                "The game's launcher preferences (" + DirectLaunchSettings.PREFERENCES_NODE
                                        + ") do not exist yet. Start the game once through its own"
                                        + " launcher first.");
        System.out.println(Json.object(describe(availability)));
        return 0;
    }

    static Map<String, Object> describe(DirectLaunchSettings.Availability availability) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("directLaunchAvailable", availability.available());
        document.put("reason", availability.reason());
        document.put("settings",
                availability.settings() == null ? null : availability.settings().toReportValues());
        return document;
    }
}

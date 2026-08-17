package dev.starsector.preflight.cli;

import java.io.PrintStream;
import java.util.Locale;

/** Human view of the typed metadata result. Rendering is deliberately separate from evaluation. */
final class ModCompatibilityPresenter {
    private ModCompatibilityPresenter() {}

    static void print(ModCompatibilityPrecheck.Result result, PrintStream out) {
        if (result.findings().isEmpty()) {
            return;
        }
        out.println("Mod metadata readiness:");
        for (ModCompatibilityPrecheck.Finding finding : result.findings()) {
            out.printf(
                    Locale.ROOT,
                    "  %-7s %-38s %s%n",
                    finding.severity().name().toLowerCase(Locale.ROOT),
                    finding.reason().code(),
                    finding.summary());
        }
        ModCompatibilityPrecheck.ProfileChange change = result.suggestedProfileChange();
        if (change != null) {
            out.println("  reviewed profile change available; current profile remains unchanged:");
            out.println("    before: " + change.before());
            out.println("    enable: " + change.enable());
            out.println("    after:  " + change.after());
            out.println("    applying this change requires an explicit profile/settings action; dependency chains stay untouched.");
        }
        out.println("  launch remains available; this metadata pass does not change the profile or installed mods.");
    }
}

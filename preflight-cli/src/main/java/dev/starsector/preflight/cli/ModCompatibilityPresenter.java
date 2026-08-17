package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ModCompatibilityReadiness.Finding;
import dev.starsector.preflight.core.ModCompatibilityReadiness.ProfileChange;
import dev.starsector.preflight.core.ModCompatibilityReadiness.Result;
import dev.starsector.preflight.core.ModCompatibilityReadiness.Severity;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/** Human view of metadata readiness; informational evidence stays available in the typed result. */
final class ModCompatibilityPresenter {
    private ModCompatibilityPresenter() {
    }

    static void print(Result result, PrintStream out) {
        List<Finding> visible = result.findings().stream()
                .filter(finding -> finding.severity() != Severity.INFO)
                .toList();
        ProfileChange change = result.suggestedProfileChange();
        if (visible.isEmpty() && change == null) {
            return;
        }
        out.println("Mod metadata readiness:");
        for (Finding finding : visible) {
            out.printf(
                    Locale.ROOT,
                    "  %-7s %-38s %s%n",
                    finding.severity().name().toLowerCase(Locale.ROOT),
                    finding.reason().code(),
                    finding.summary());
        }
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

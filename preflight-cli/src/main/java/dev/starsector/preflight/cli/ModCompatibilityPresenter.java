package dev.starsector.preflight.cli;

import java.io.PrintStream;
import java.util.Locale;

/** Human view of the typed metadata result. Rendering is deliberately separate from evaluation. */
final class ModCompatibilityPresenter {
    private ModCompatibilityPresenter() {}

    static void print(ModCompatibilityPrecheck.Result result, PrintStream out) {
        out.println("Mod metadata readiness:");
        if (result.findings().isEmpty()) {
            out.println("  clear  no metadata-decidable compatibility problems found");
            return;
        }
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
            out.println("  reviewed profile change available; no profile file was changed:");
            out.println("    before: " + change.before());
            out.println("    enable: " + change.enable());
            out.println("    after:  " + change.after());
            out.println("    apply only through an explicit profile/settings review; dependency chains stay untouched.");
        }
        out.println("  launch remains available; metadata findings are advisory to the launch path.");
    }
}

package dev.starsector.preflight.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Reports what Preflight is storing and which prepared profile the current install matches. */
final class CacheCommand {
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withZone(ZoneId.systemDefault());

    private CacheCommand() {
    }

    static int execute(String[] args, int from) throws Exception {
        for (int index = from; index < args.length; index++) {
            String argument = args[index];
            if ("--help".equals(argument) || "-h".equals(argument)) {
                PreflightCli.commandUsage("cache", System.out);
                return 0;
            }
            System.err.println("preflight cache: unknown option: " + argument);
            return 2;
        }
        return report(PreflightHome.current(), currentFingerprint(), System.out);
    }

    static int report(PreflightHome home, String currentFingerprint, PrintStream out)
            throws Exception {
        CacheFootprint.Report footprint = CacheFootprint.measure(home);
        if (!footprint.present()) {
            out.println("Preflight is storing nothing: " + home.root() + " does not exist.");
            return 0;
        }

        out.printf(Locale.ROOT, "Preflight storage: %s%n", home.root());
        out.printf(Locale.ROOT, "  %s across %,d files%n%n",
                CacheFootprint.humanBytes(footprint.whole().bytes()), footprint.whole().files());

        for (CacheFootprint.Entry entry : footprint.entries()) {
            if (entry.usage().files() == 0) {
                continue;
            }
            out.printf(Locale.ROOT, "  %-32s %10s  %7d files  %s%n",
                    entry.path(),
                    CacheFootprint.humanBytes(entry.usage().bytes()),
                    entry.usage().files(),
                    entry.description());
        }
        if (footprint.uncategorizedBytes() > 0) {
            out.printf(Locale.ROOT, "  %-32s %10s%n",
                    "(elsewhere under the root)",
                    CacheFootprint.humanBytes(footprint.uncategorizedBytes()));
        }

        reportProfiles(footprint.profiles(), currentFingerprint, out);
        reportIntegrations(home, out);

        out.println();
        out.println("The Starsector installation is never written to. Everything Preflight");
        out.println("produces is under the root above, plus the launcher integration listed");
        out.println("here, so `preflight uninstall` removes all of it and nothing needs");
        out.println("restoring. Deleting the cache costs one slower launch, not correctness.");
        return 0;
    }

    private static void reportProfiles(
            List<CacheFootprint.Profile> profiles, String currentFingerprint, PrintStream out) {
        out.println();
        if (profiles.isEmpty()) {
            out.println("No prepared profiles. Run `preflight prepare` to build one.");
            return;
        }
        out.printf(Locale.ROOT, "Prepared profiles (%d):%n", profiles.size());
        boolean matched = false;
        for (CacheFootprint.Profile profile : profiles) {
            boolean current = profile.fingerprint().equals(currentFingerprint);
            matched = matched || current;
            out.printf(Locale.ROOT, "  %s%s  %9s  %s%n",
                    profile.fingerprint().substring(0, 16),
                    current ? "  <- current install" : "                    ",
                    CacheFootprint.humanBytes(profile.bytes()),
                    DAY.format(Instant.ofEpochMilli(profile.lastModifiedMillis())));
        }
        if (currentFingerprint == null) {
            out.println();
            out.println("  Could not read the current install's profile, so none is marked current.");
        } else if (!matched) {
            out.println();
            out.println("  None of these match the current install. The next launch prepares a new");
            out.println("  one; these stay, so switching back to an earlier mod set finds its");
            out.println("  artifacts still here.");
        }
    }

    private static void reportIntegrations(PreflightHome home, PrintStream out) {
        out.println();
        out.println("Launcher integration:");
        if (home.integrations().isEmpty()) {
            out.println("  none on this operating system; Preflight runs as `java -jar preflight.jar`.");
            return;
        }
        for (PreflightHome.Integration integration : home.integrations()) {
            out.printf(Locale.ROOT, "  %-28s %-9s %s%n",
                    integration.label(),
                    integration.present() ? "installed" : "absent",
                    integration.path());
        }
    }

    /**
     * The fingerprint of the profile the install currently resolves to, or null if unreadable.
     *
     * <p>This is the same builder a launch uses to decide which artifacts apply, so what this marks
     * "current" is what the next launch will actually reach for. A missing install or an
     * inconsistent mod set is reported as "unknown" rather than guessed at.
     */
    private static String currentFingerprint() {
        try {
            DiscoveryResult discovery = StarsectorDiscovery.discover(
                    Platform.current(),
                    Path.of(System.getProperty("user.home")),
                    Path.of(System.getProperty("user.dir")),
                    System.getenv(), null, null);
            LaunchTarget target = discovery.selected();
            return target == null
                    ? null
                    : ResourceIndexBuilder.build(target.installRoot()).index().profileFingerprint();
        } catch (Exception unreadable) {
            return null;
        }
    }
}

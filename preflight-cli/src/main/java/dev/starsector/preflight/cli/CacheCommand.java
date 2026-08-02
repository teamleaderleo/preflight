package dev.starsector.preflight.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reports what Preflight is storing and which prepared profile the current install matches. */
final class CacheCommand {
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withZone(ZoneId.systemDefault());

    private CacheCommand() {
    }

    static int execute(String[] args, int from) throws Exception {
        boolean prune = false;
        boolean confirmed = false;
        for (int index = from; index < args.length; index++) {
            switch (args[index]) {
                case "prune" -> prune = true;
                case "--yes" -> confirmed = true;
                case "--help", "-h" -> {
                    PreflightCli.commandUsage("cache", System.out);
                    return 0;
                }
                default -> {
                    System.err.println("preflight cache: unknown option: " + args[index]);
                    return 2;
                }
            }
        }
        PreflightHome home = PreflightHome.current();
        if (prune) {
            return prune(home, confirmed, System.out);
        }
        return report(home, currentFingerprint(), System.out);
    }

    /**
     * Removes every profile except the one the current install resolves to.
     *
     * <p>Refuses to plan anything if the current profile cannot be identified. Pruning "everything
     * except the current one" when the current one is unknown would delete the entire cache, which
     * is a legitimate thing to want and is spelled {@code preflight uninstall --purge}, not this.
     */
    static int prune(PreflightHome home, boolean confirmed, PrintStream out) throws Exception {
        String current = currentFingerprint();
        if (current == null) {
            System.err.println("Cannot identify the current install's profile, so there is nothing");
            System.err.println("safe to keep. Run `preflight doctor` to see why the install could");
            System.err.println("not be read, or `preflight uninstall --purge` to remove everything.");
            return 3;
        }

        Set<String> keepIdentities = liveSpecStoreIdentities(home, current);
        CachePrune.Plan plan = CachePrune.plan(home, Set.of(current), keepIdentities);

        if (!plan.safe()) {
            System.err.println("Refusing to prune:");
            for (String refusal : plan.refusals()) {
                System.err.println("  " + refusal);
            }
            System.err.println();
            System.err.println("Blobs are shared between profiles, so a manifest that cannot be read");
            System.err.println("leaves the set of blobs still in use unknown. Nothing was removed.");
            return 3;
        }

        if (plan.removals().isEmpty()) {
            out.printf(Locale.ROOT, "Nothing to prune. Profile %s is the only one held.%n",
                    current.substring(0, 16));
            return 0;
        }

        long blobRemovals = plan.removals().stream()
                .filter(removal -> "unreferenced blob".equals(removal.reason()))
                .count();
        out.printf(Locale.ROOT, "Keeping profile %s (the current install).%n%n",
                current.substring(0, 16));
        out.printf(Locale.ROOT, "%s %,d files, freeing %s:%n",
                confirmed ? "Removing" : "Would remove",
                plan.removals().size(),
                CacheFootprint.humanBytes(plan.bytes()));
        out.printf(Locale.ROOT, "  %,d unreferenced texture blobs (%,d stay, still referenced)%n",
                blobRemovals, plan.reachableBlobs());
        for (CachePrune.Removal removal : plan.removals()) {
            if (!"unreferenced blob".equals(removal.reason())) {
                out.printf(Locale.ROOT, "  %-28s %9s  %s%n",
                        removal.reason(),
                        CacheFootprint.humanBytes(removal.bytes()),
                        removal.path().getFileName());
            }
        }

        if (!confirmed) {
            out.println();
            out.println("Nothing was removed. Re-run with --yes to do it.");
            return 0;
        }

        long freed = CachePrune.apply(plan);
        out.println();
        out.printf(Locale.ROOT, "Freed %s.%n", CacheFootprint.humanBytes(freed));
        out.println("The kept profile is untouched, so the next launch is still a warm one.");
        return 0;
    }

    /**
     * The spec-store identities the surviving profile resolves to.
     *
     * <p>Spec-store artifacts are keyed by a per-corpus dependency identity rather than by the
     * profile fingerprint, so the only way to know which are live is to build them. An install that
     * cannot produce them yields an empty set, which leaves every spec-store artifact in place --
     * keeping a stale 28 MB is the right failure here, not guessing.
     */
    private static Set<String> liveSpecStoreIdentities(PreflightHome home, String fingerprint) {
        Path index = home.cache().resolve("resource-indexes").resolve(fingerprint + ".spfi");
        if (!Files.isRegularFile(index)) {
            return Set.of();
        }
        try {
            DiscoveryResult discovery = StarsectorDiscovery.discover(
                    Platform.current(),
                    Path.of(System.getProperty("user.home")),
                    Path.of(System.getProperty("user.dir")),
                    System.getenv(), null, null);
            LaunchTarget target = discovery.selected();
            if (target == null) {
                return Set.of();
            }
            try (ProfileIdentityContext context =
                         ProfileIdentityContext.open(target.installRoot(), index)) {
                return Set.of(
                        VariantJsonProfileIdentityBuilder.build(context).identitySha256(),
                        WeaponJsonProfileIdentityBuilder.build(context).identitySha256(),
                        ProjectileJsonProfileIdentityBuilder.build(context).identitySha256(),
                        HullJsonProfileIdentityBuilder.build(context).identitySha256(),
                        RulesCsvProfileIdentityBuilder.build(context).identitySha256(),
                        RuleCommandClassProfileIdentityBuilder.build(context).identitySha256());
            }
        } catch (Exception unreadable) {
            return Set.of();
        }
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

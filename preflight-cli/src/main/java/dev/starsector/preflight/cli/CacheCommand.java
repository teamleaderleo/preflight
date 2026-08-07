package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.ResourceIndexIO;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        boolean json = false;
        boolean keepNamed = false;
        Path game = null;
        Path launcher = null;
        for (int index = from; index < args.length; index++) {
            switch (args[index]) {
                case "prune" -> prune = true;
                case "--yes" -> confirmed = true;
                case "--json" -> json = true;
                case "--keep-named" -> keepNamed = true;
                case "--game" -> game = Path.of(requireValue(args, ++index, "--game"));
                case "--launcher" -> launcher = Path.of(requireValue(args, ++index, "--launcher"));
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
            return prune(
                    home,
                    game,
                    launcher,
                    confirmed,
                    keepNamed,
                    json,
                    System.out);
        }
        if (confirmed || keepNamed) {
            System.err.println("preflight cache: --yes and --keep-named require prune");
            return 2;
        }
        if (json) {
            return reportJson(home, currentFingerprint(game, launcher), System.out);
        }
        return report(home, currentFingerprint(game, launcher), System.out);
    }

    /**
     * Removes every profile except the one the current install resolves to.
     *
     * <p>Refuses to plan anything if the current profile cannot be identified. Pruning "everything
     * except the current one" when the current one is unknown would delete the entire cache, which
     * is a legitimate thing to want and is spelled {@code preflight uninstall --purge}, not this.
     */
    static int prune(PreflightHome home, boolean confirmed, PrintStream out) throws Exception {
        return prune(home, confirmed, false, false, out);
    }

    static int prune(
            PreflightHome home,
            boolean confirmed,
            boolean keepNamed,
            boolean json,
            PrintStream out) throws Exception {
        return prune(home, null, null, confirmed, keepNamed, json, out);
    }

    private static int prune(
            PreflightHome home,
            Path game,
            Path launcher,
            boolean confirmed,
            boolean keepNamed,
            boolean json,
            PrintStream out) throws Exception {
        if (!confirmed) {
            return pruneOwned(home, currentFingerprint(game, launcher), false, keepNamed, json, out);
        }
        OperationLease.Acquisition ownership = OperationLease.acquire(home, "cleaning-cache", null);
        try (OperationLease ignored = ownership.lease()) {
            return pruneOwned(home, currentFingerprint(game, launcher), true, keepNamed, json, out);
        }
    }

    private static int pruneOwned(
            PreflightHome home,
            String current,
            boolean confirmed,
            boolean keepNamed,
            boolean json,
            PrintStream out) throws Exception {
        if (current == null) {
            System.err.println("Cannot identify the current install's profile, so there is nothing");
            System.err.println("safe to keep. Run `preflight doctor` to see why the install could");
            System.err.println("not be read, or `preflight uninstall --purge` to remove everything.");
            return 3;
        }

        Set<String> survivors = new LinkedHashSet<>();
        survivors.add(current);
        if (keepNamed) {
            ProfileCommand.RetainedFingerprints named = ProfileCommand.retainedFingerprints(home);
            if (!named.diagnostics().isEmpty()) {
                if (json) {
                    emitPruneJson(
                            current, survivors, null, false, false, named.diagnostics(), out);
                } else {
                    System.err.println("Refusing to prune while a named profile is unreadable:");
                    for (String diagnostic : named.diagnostics()) {
                        System.err.println("  " + diagnostic);
                    }
                }
                return 3;
            }
            survivors.addAll(named.fingerprints());
        }
        // Per-corpus and compiler identities cannot be reconstructed for an inactive named mod set
        // without activating it. Retain those small/shared stores rather than guess.
        Set<String> keepIdentities = survivors.size() == 1
                ? liveSpecStoreIdentities(home, current)
                : Set.of();
        Set<String> keepJaninoContexts = survivors.size() == 1
                ? liveJaninoContexts(home, current)
                : Set.of();
        CachePrune.Plan plan = CachePrune.plan(
                home, survivors, keepIdentities, keepJaninoContexts);

        if (!plan.safe()) {
            if (json) {
                emitPruneJson(current, survivors, plan, false, false, plan.refusals(), out);
                return 3;
            }
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
            if (json) {
                emitPruneJson(current, survivors, plan, true, confirmed, List.of(), out);
                return 0;
            }
            out.printf(Locale.ROOT, "Nothing to prune. Keeping current profile %s%s.%n",
                    current.substring(0, 16),
                    survivors.size() == 1
                            ? ""
                            : " plus " + (survivors.size() - 1) + " named profile(s)");
            return 0;
        }

        if (json) {
            if (confirmed) {
                CachePrune.apply(plan);
            }
            emitPruneJson(current, survivors, plan, true, confirmed, List.of(), out);
            return 0;
        }

        long blobRemovals = plan.removals().stream()
                .filter(removal -> "unreferenced blob".equals(removal.reason()))
                .count();
        long audioBlobRemovals = plan.removals().stream()
                .filter(removal -> "unreferenced prepared-audio blob".equals(removal.reason()))
                .count();
        long redundantBytecode = plan.removals().stream()
                .filter(removal -> "redundant generated-bytecode bundle".equals(removal.reason()))
                .count();
        long staleBytecode = plan.removals().stream()
                .filter(removal -> removal.reason().startsWith("stale generated-bytecode context "))
                .count();
        out.printf(Locale.ROOT, "Keeping profile %s (the current install)%s.%n%n",
                current.substring(0, 16),
                survivors.size() == 1 ? "" : " and " + (survivors.size() - 1) + " named profile(s)");
        out.printf(Locale.ROOT, "%s %,d files, freeing %s:%n",
                confirmed ? "Removing" : "Would remove",
                plan.removals().size(),
                CacheFootprint.humanBytes(plan.bytes()));
        out.printf(Locale.ROOT, "  %,d unreferenced texture blobs (%,d stay, still referenced)%n",
                blobRemovals, plan.reachableBlobs());
        if (audioBlobRemovals > 0 || plan.reachableAudioBlobs() > 0) {
            out.printf(Locale.ROOT,
                    "  %,d unreferenced prepared-audio blobs (%,d stay, still referenced)%n",
                    audioBlobRemovals,
                    plan.reachableAudioBlobs());
        }
        if (redundantBytecode > 0 || staleBytecode > 0) {
            out.printf(Locale.ROOT,
                    "  %,d redundant and %,d stale-context generated-bytecode files%n",
                    redundantBytecode, staleBytecode);
        }
        for (CachePrune.Removal removal : plan.removals()) {
            if (!"unreferenced blob".equals(removal.reason())
                    && !"redundant generated-bytecode bundle".equals(removal.reason())
                    && !"unreferenced prepared-audio blob".equals(removal.reason())
                    && !removal.reason().startsWith("stale generated-bytecode context ")) {
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

    static void emitPruneJson(
            String current,
            Set<String> survivors,
            CachePrune.Plan plan,
            boolean safe,
            boolean applied,
            List<String> refusals,
            PrintStream out) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("format", "starsector-preflight-cache-prune-v1");
        report.put("safe", safe);
        report.put("applied", applied);
        report.put("currentProfileFingerprint", current);
        report.put("survivingProfileFingerprints", survivors);
        report.put("bytes", plan == null ? 0 : plan.bytes());
        report.put("files", plan == null ? 0 : plan.removals().size());
        report.put("reachableTextureBlobs", plan == null ? 0 : plan.reachableBlobs());
        report.put("reachablePreparedAudioBlobs", plan == null ? 0 : plan.reachableAudioBlobs());
        report.put("refusals", refusals);
        Map<String, long[]> reasonTotals = new LinkedHashMap<>();
        if (plan != null) {
            for (CachePrune.Removal removal : plan.removals()) {
                long[] totals = reasonTotals.computeIfAbsent(removal.reason(), ignored -> new long[2]);
                totals[0] = Math.addExact(totals[0], removal.bytes());
                totals[1]++;
            }
        }
        report.put("groups", reasonTotals.entrySet().stream().map(entry -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("reason", entry.getKey());
            value.put("bytes", entry.getValue()[0]);
            value.put("files", entry.getValue()[1]);
            return value;
        }).toList());
        int sampleLimit = 100;
        report.put("removals", plan == null ? List.of() : plan.removals().stream().limit(sampleLimit).map(removal -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("path", removal.path());
            value.put("bytes", removal.bytes());
            value.put("reason", removal.reason());
            return value;
        }).toList());
        report.put("removalsTruncated", plan != null && plan.removals().size() > sampleLimit);
        out.println(Json.object(report));
    }

    /** Exact compiler context reachable from the current install, or empty to retain them all. */
    private static Set<String> liveJaninoContexts(PreflightHome home, String fingerprint) {
        Path index = ResourceIndexIO.directory(home.cache()).resolve(fingerprint + ".spfi");
        if (!Files.isRegularFile(index)) return Set.of();
        try {
            DiscoveryResult discovery = StarsectorDiscovery.discover(
                    Platform.current(),
                    Path.of(System.getProperty("user.home")),
                    Path.of(System.getProperty("user.dir")),
                    System.getenv(), null, null);
            LaunchTarget target = discovery.selected();
            if (target == null) return Set.of();
            try (ProfileIdentityContext context =
                         ProfileIdentityContext.open(target.installRoot(), index)) {
                var archives = JaninoProfileIdentityBuilder.discoverOrderedArchives(context);
                String launchContract = RunCommand.janinoLaunchContract(context, target);
                return Set.of(JaninoProfileIdentityBuilder.build(
                        context, archives, launchContract).context().keySha256());
            }
        } catch (Exception unreadable) {
            return Set.of();
        }
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
        Path index = ResourceIndexIO.directory(home.cache()).resolve(fingerprint + ".spfi");
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

    /** Stable machine-readable storage/profile snapshot for the desktop host and other tools. */
    static int reportJson(PreflightHome home, String currentFingerprint, PrintStream out)
            throws Exception {
        CacheFootprint.Report footprint = CacheFootprint.measure(home);
        List<Map<String, Object>> categories = footprint.entries().stream().map(entry -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("path", entry.path());
            value.put("group", entry.group());
            value.put("description", entry.description());
            value.put("bytes", entry.usage().bytes());
            value.put("files", entry.usage().files());
            return value;
        }).toList();
        Map<String, long[]> groupTotals = new LinkedHashMap<>();
        for (CacheFootprint.Entry entry : footprint.entries()) {
            long[] totals = groupTotals.computeIfAbsent(entry.group(), ignored -> new long[2]);
            totals[0] = Math.addExact(totals[0], entry.usage().bytes());
            totals[1] = Math.addExact(totals[1], entry.usage().files());
        }
        List<Map<String, Object>> groups = groupTotals.entrySet().stream().map(entry -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", entry.getKey());
            value.put("bytes", entry.getValue()[0]);
            value.put("files", entry.getValue()[1]);
            return value;
        }).toList();
        List<Map<String, Object>> profiles = footprint.profiles().stream().map(profile -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("fingerprint", profile.fingerprint());
            value.put("current", profile.fingerprint().equals(currentFingerprint));
            value.put("bytes", profile.bytes());
            value.put("indexBytes", profile.indexBytes());
            value.put("manifestBytes", profile.manifestBytes());
            value.put("lastModifiedMillis", profile.lastModifiedMillis());
            return value;
        }).toList();
        List<Map<String, Object>> integrations = home.reportedIntegrations().stream().map(integration -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", integration.id().name());
            value.put("label", integration.label());
            value.put("path", integration.path());
            value.put("present", integration.present());
            return value;
        }).toList();

        Map<String, Object> total = new LinkedHashMap<>();
        total.put("bytes", footprint.whole().bytes());
        total.put("files", footprint.whole().files());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("format", "starsector-preflight-cache-v1");
        report.put("root", footprint.root());
        report.put("present", footprint.present());
        report.put("total", total);
        report.put("groups", groups);
        report.put("categories", categories);
        report.put("uncategorizedBytes", footprint.uncategorizedBytes());
        report.put("currentProfileFingerprint", currentFingerprint);
        report.put("profiles", profiles);
        report.put("integrations", integrations);
        out.println(Json.object(report));
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
        if (home.reportedIntegrations().isEmpty()) {
            out.println("  none on this operating system; Preflight runs as `java -jar preflight.jar`.");
            return;
        }
        for (PreflightHome.Integration integration : home.reportedIntegrations()) {
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
        return currentFingerprint(null, null);
    }

    private static String currentFingerprint(Path game, Path launcher) {
        try {
            DiscoveryResult discovery = StarsectorDiscovery.discover(
                    Platform.current(),
                    Path.of(System.getProperty("user.home")),
                    Path.of(System.getProperty("user.dir")),
                    System.getenv(), game, launcher);
            LaunchTarget target = discovery.selected();
            return target == null
                    ? null
                    : ResourceIndexBuilder.build(target.installRoot()).index().profileFingerprint();
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}

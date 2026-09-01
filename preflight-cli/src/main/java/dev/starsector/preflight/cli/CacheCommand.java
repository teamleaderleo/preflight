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
        boolean health = false;
        boolean inspect = false;
        boolean repair = false;
        boolean confirmed = false;
        boolean json = false;
        boolean keepNamed = false;
        boolean discardableOnly = false;
        Path game = null;
        Path launcher = null;
        String expectedProfile = null;
        for (int index = from; index < args.length; index++) {
            switch (args[index]) {
                case "prune" -> prune = true;
                case "health" -> health = true;
                case "inspect" -> inspect = true;
                case "repair" -> repair = true;
                case "--yes" -> confirmed = true;
                case "--json" -> json = true;
                case "--keep-named" -> keepNamed = true;
                case "--discardable-only" -> discardableOnly = true;
                case "--game" -> game = Path.of(requireValue(args, ++index, "--game"));
                case "--launcher" -> launcher = Path.of(requireValue(args, ++index, "--launcher"));
                case "--expected-profile" -> expectedProfile =
                        requireValue(args, ++index, "--expected-profile");
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
        int actions = (prune ? 1 : 0) + (health ? 1 : 0) + (inspect ? 1 : 0) + (repair ? 1 : 0);
        if (expectedProfile != null && !expectedProfile.matches("[0-9a-f]{64}")) {
            System.err.println("preflight cache repair: --expected-profile must be a lowercase SHA-256 fingerprint");
            return 2;
        }
        if (actions > 1) {
            System.err.println("preflight cache: choose only one of prune, health, inspect, or repair");
            return 2;
        }
        if (prune) {
            if (expectedProfile != null) {
                System.err.println("preflight cache prune: --expected-profile isn't valid");
                return 2;
            }
            if (discardableOnly) {
                if (keepNamed || game != null || launcher != null) {
                    System.err.println("preflight cache prune: --discardable-only doesn't use --keep-named, --game, or --launcher");
                    return 2;
                }
                return pruneDiscardable(home, confirmed, json, System.out);
            }
            return prune(
                    home,
                    game,
                    launcher,
                    confirmed,
                    keepNamed,
                    json,
                    System.out);
        }
        if (discardableOnly) {
            System.err.println("preflight cache: --discardable-only requires prune");
            return 2;
        }
        boolean fullIdentity = requiresFullIdentity(inspect, health, repair);
        CurrentProfile currentProfile = fullIdentity
                ? currentProfile(game, launcher)
                : currentFingerprintOnly(game, launcher);
        String current = currentProfile.fingerprint();
        if (inspect) {
            if (confirmed || keepNamed || expectedProfile != null) {
                System.err.println("preflight cache inspect: --yes, --keep-named, and --expected-profile aren't valid");
                return 2;
            }
            System.out.println(Json.object(inspect(home, currentProfile)));
            return 0;
        }
        if (health) {
            if (confirmed || keepNamed || expectedProfile != null) {
                System.err.println("preflight cache health: --yes, --keep-named, and --expected-profile aren't valid");
                return 2;
            }
            return health(home, currentProfile, json, System.out);
        }
        if (repair) {
            if (keepNamed) {
                System.err.println("preflight cache repair: --keep-named isn't valid");
                return 2;
            }
            return repair(home, game, launcher, currentProfile, expectedProfile, confirmed, json, System.out);
        }
        if (confirmed || keepNamed || expectedProfile != null) {
            System.err.println("preflight cache: --yes, --keep-named, and --expected-profile require repair or prune");
            return 2;
        }
        if (json) {
            return reportJson(home, current, System.out);
        }
        return report(home, current, System.out);
    }

    static int health(
            PreflightHome home, String currentFingerprint, boolean json, PrintStream out) {
        return health(home, currentFingerprint, null, json, out);
    }

    static int health(
            PreflightHome home,
            String currentFingerprint,
            String identityDiagnostic,
            boolean json,
            PrintStream out) {
        return health(home, new CurrentProfile(
                currentFingerprint, identityDiagnostic, null, null), json, out);
    }

    private static int health(
            PreflightHome home,
            CurrentProfile currentProfile,
            boolean json,
            PrintStream out) {
        CacheHealth.Report report = CacheHealth.inspect(
                home,
                currentProfile.fingerprint(),
                currentProfile.diagnostic(),
                currentProfile.audioBuild(),
                currentProfile.audioDecoder());
        if (json) {
            out.println(Json.object(CacheHealth.json(report)));
        } else if ("ready".equals(report.status())) {
            out.println("Prepared metadata for the current profile is structurally valid.");
        } else if ("cold".equals(report.status())) {
            out.println("The current profile has no prepared data yet.");
        } else if ("unknown".equals(report.status())) {
            out.println("The current profile could not be identified; nothing was changed.");
        } else if ("unsafe".equals(report.status())) {
            out.println("The cache boundary could not be verified; nothing will be changed.");
            for (CacheHealth.Issue issue : report.issues()) {
                out.println("  " + issue.summary());
            }
        } else {
            out.println("Prepared data for the current profile needs repair:");
            for (CacheHealth.Issue issue : report.issues()) {
                out.println("  " + issue.summary());
            }
        }
        return "unknown".equals(report.status()) || "unsafe".equals(report.status()) ? 3 : 0;
    }

    private static int repair(
            PreflightHome home,
            Path game,
            Path launcher,
            CurrentProfile inspectedProfile,
            String expectedFingerprint,
            boolean confirmed,
            boolean json,
            PrintStream out) throws Exception {
        CacheHealth.Repair repair;
        if (confirmed) {
            OperationLease.Acquisition ownership = OperationLease.acquire(
                    home, "repairing-current-cache", null);
            try (OperationLease ignored = ownership.lease()) {
                CurrentProfile ownedProfile = currentProfile(game, launcher);
                String ownedFingerprint = ownedProfile.fingerprint();
                if (expectedFingerprint != null
                        && !expectedFingerprint.equals(ownedFingerprint)) {
                    repair = new CacheHealth.Repair(false, false, "profile-changed",
                            ownedFingerprint, 0, 0, List.of());
                } else {
                    repair = CacheHealth.repair(
                            home,
                            ownedFingerprint,
                            true,
                            ownedProfile.audioBuild(),
                            ownedProfile.audioDecoder());
                }
            }
        } else {
            repair = CacheHealth.repair(
                    home,
                    inspectedProfile.fingerprint(),
                    false,
                    inspectedProfile.audioBuild(),
                    inspectedProfile.audioDecoder());
        }
        if (json) {
            out.println(Json.object(CacheHealth.json(repair)));
        } else if (!repair.safe()) {
            out.println("The current profile could not be identified; nothing was changed.");
        } else if (repair.files() == 0) {
            out.println("Prepared data for the current profile doesn't need repair.");
        } else {
            out.printf(Locale.ROOT, "%s %,d profile-scoped artifact%s (%s).%n",
                    confirmed ? "Removed" : "Would remove",
                    repair.files(),
                    repair.files() == 1 ? "" : "s",
                    CacheFootprint.humanBytes(repair.bytes()));
            if (!confirmed) out.println("Nothing was removed. Re-run with --yes to repair it.");
        }
        return repair.safe() ? 0 : 3;
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

    static int pruneDiscardable(
            PreflightHome home, boolean confirmed, boolean json, PrintStream out) throws Exception {
        if (!confirmed) {
            return pruneDiscardableOwned(home, false, json, out);
        }
        OperationLease.Acquisition ownership = OperationLease.acquire(home, "cleaning-cache", null);
        try (OperationLease ignored = ownership.lease()) {
            return pruneDiscardableOwned(home, true, json, out);
        }
    }

    private static int pruneDiscardableOwned(
            PreflightHome home, boolean confirmed, boolean json, PrintStream out) throws Exception {
        CachePrune.Plan plan = CachePrune.planDiscardable(home);
        if (!plan.safe()) {
            if (json) {
                emitPruneJson(null, Set.of(), plan, false, false, plan.refusals(), out);
                return 3;
            }
            System.err.println("Preflight couldn't verify its cache boundary. Nothing was removed.");
            return 3;
        }
        if (confirmed) {
            CachePrune.apply(plan);
        }
        if (json) {
            emitPruneJson(null, Set.of(), plan, true, confirmed, List.of(), out);
        } else if (plan.removals().isEmpty()) {
            out.println("No replaced cache data to remove.");
        } else if (confirmed) {
            out.printf(Locale.ROOT, "Freed %s of replaced cache data.%n",
                    CacheFootprint.humanBytes(plan.bytes()));
        } else {
            out.printf(Locale.ROOT, "Would free %s of replaced cache data. Re-run with --yes to do it.%n",
                    CacheFootprint.humanBytes(plan.bytes()));
        }
        return 0;
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
            System.err.println("Shared cache artifacts are removed only from a complete survivor set.");
            System.err.println("An unreadable manifest or classpath index leaves reachability unknown.");
            System.err.println("Nothing was removed.");
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
        long classpathArchiveRemovals = plan.removals().stream()
                .filter(removal -> "unreferenced classpath archive index".equals(removal.reason()))
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
        if (classpathArchiveRemovals > 0 || plan.reachableClasspathArchives() > 0) {
            out.printf(Locale.ROOT,
                    "  %,d unreferenced classpath archive indexes (%,d stay, still referenced)%n",
                    classpathArchiveRemovals,
                    plan.reachableClasspathArchives());
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
                    && !"unreferenced classpath archive index".equals(removal.reason())
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
        report.put("reachableClasspathArchiveIndexes",
                plan == null ? 0 : plan.reachableClasspathArchives());
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
                String launchContract = LaunchCacheContexts.janinoLaunchContract(context, target);
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
            // Evidence directories repeat the same file names once per session, so the per-name
            // totals are what somebody can actually judge: not "runs is large" but "this one
            // document is here 315 times".
            for (CacheFootprint.Artifact artifact : entry.artifacts()) {
                out.printf(Locale.ROOT, "    %-42s %10s  %7d files%n",
                        artifact.name(),
                        CacheFootprint.humanBytes(artifact.bytes()),
                        artifact.files());
            }
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
        out.println(Json.object(reportJson(home, currentFingerprint)));
        return 0;
    }

    static Map<String, Object> reportJson(PreflightHome home, String currentFingerprint)
            throws Exception {
        CacheFootprint.Report footprint = CacheFootprint.measure(home);
        List<Map<String, Object>> categories = footprint.entries().stream().map(entry -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("path", entry.path());
            value.put("group", entry.group());
            value.put("description", entry.description());
            value.put("bytes", entry.usage().bytes());
            value.put("files", entry.usage().files());
            value.put("artifacts", entry.artifacts().stream().map(artifact -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", artifact.name());
                row.put("bytes", artifact.bytes());
                row.put("files", artifact.files());
                return row;
            }).toList());
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
        return report;
    }

    static Map<String, Object> inspect(PreflightHome home, CurrentProfile currentProfile)
            throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("format", "starsector-preflight-cache-inspection-v1");
        report.put("cache", reportJson(home, currentProfile.fingerprint()));
        report.put("health", CacheHealth.json(CacheHealth.inspect(
                home,
                currentProfile.fingerprint(),
                currentProfile.diagnostic(),
                currentProfile.audioBuild(),
                currentProfile.audioDecoder())));
        return report;
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
        return currentFingerprintOnly(game, launcher).fingerprint();
    }

    static boolean requiresFullIdentity(boolean inspect, boolean health, boolean repair) {
        return inspect || health || repair;
    }

    static CurrentProfile currentFingerprintOnly(Path game, Path launcher) {
        return currentProfile(game, launcher, ResourceIndexBuilder.DEFAULT_SCAN_WORKERS, false);
    }

    static CurrentProfile currentProfile(Path game, Path launcher) {
        return currentProfile(game, launcher, ResourceIndexBuilder.DEFAULT_SCAN_WORKERS, true);
    }

    /** Lets an interactive caller bound transient load without changing the resulting identity. */
    static CurrentProfile currentProfile(Path game, Path launcher, int scanWorkers) {
        return currentProfile(game, launcher, scanWorkers, true);
    }

    private static CurrentProfile currentProfile(
            Path game, Path launcher, int scanWorkers, boolean includeAudioIdentity) {
        try {
            DiscoveryResult discovery = StarsectorDiscovery.discover(
                    Platform.current(),
                    Path.of(System.getProperty("user.home")),
                    Path.of(System.getProperty("user.dir")),
                    System.getenv(), game, launcher);
            LaunchTarget target = discovery.selected();
            if (target == null) {
                String detail = discovery.diagnostics().stream()
                        .limit(4)
                        .map(CacheCommand::boundedDiagnostic)
                        .filter(value -> !value.isBlank())
                        .reduce((left, right) -> left + " " + right)
                        .orElse("No readable Starsector installation was found.");
                return new CurrentProfile(null, detail, null, null);
            }
            ResourceIndexBuilder.BuildResult resourceIndex =
                    ResourceIndexBuilder.build(target.installRoot(), scanWorkers);
            String fingerprint = resourceIndex.index().profileFingerprint();
            if (!includeAudioIdentity) {
                return new CurrentProfile(fingerprint, null, null, null);
            }
            try {
                List<Path> gameJars = PrepareAudioCommand.jars(target.installRoot());
                return new CurrentProfile(
                        fingerprint,
                        null,
                        PrepareAudioCommand.starsectorBuildIdentity(gameJars),
                        PrepareAudioCommand.decoderPolicyIdentity(gameJars));
            } catch (Exception audioIdentityUnreadable) {
                // Cache inventory and profile selection remain useful even if a future game layout
                // makes prepared-audio compatibility impossible to prove. CacheHealth reports that
                // uncertainty instead of calling the audio ready or deleting it.
                return new CurrentProfile(fingerprint, null, null, null);
            }
        } catch (Exception unreadable) {
            return new CurrentProfile(
                    null,
                    "The current mod setup couldn't be identified: "
                            + boundedDiagnostic(unreadable.getMessage() == null
                                    ? unreadable.getClass().getSimpleName()
                                    : unreadable.getMessage()),
                    null,
                    null);
        }
    }

    private static String boundedDiagnostic(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 1_000 ? normalized : normalized.substring(0, 997) + "...";
    }

    record CurrentProfile(
            String fingerprint,
            String diagnostic,
            String audioBuild,
            String audioDecoder) {
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}

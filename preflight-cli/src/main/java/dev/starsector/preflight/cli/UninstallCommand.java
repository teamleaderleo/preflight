package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Preview-first removal of Preflight-owned launch files or all Preflight-owned data. */
final class UninstallCommand {
    static final String FORMAT = "preflight-removal-v1";

    private UninstallCommand() {}

    static int execute(String[] args, int from) throws Exception {
        Scope scope = Scope.LAUNCHER;
        boolean confirmed = false;
        boolean json = false;
        for (int index = from; index < args.length; index++) {
            switch (args[index]) {
                case "--purge" -> scope = Scope.ALL_DATA;
                case "--yes" -> confirmed = true;
                case "--json" -> json = true;
                case "--scope" -> scope = Scope.parse(requireValue(args, ++index, "--scope"));
                case "--help", "-h" -> {
                    PreflightCli.commandUsage("uninstall", System.out);
                    return 0;
                }
                default -> {
                    System.err.println("preflight uninstall: unknown option: " + args[index]);
                    return 2;
                }
            }
        }
        return run(PreflightHome.current(), scope, confirmed, json, System.out);
    }

    static int run(PreflightHome home, boolean purge, boolean confirmed, PrintStream out)
            throws IOException {
        return run(home, purge ? Scope.ALL_DATA : Scope.LAUNCHER, confirmed, false, out);
    }

    static int run(PreflightHome home, Scope scope, boolean confirmed, boolean json, PrintStream out)
            throws IOException {
        Plan preview = plan(home, scope, false);
        if (!preview.safe()) {
            print(preview, json, out);
            return 1;
        }
        if (!confirmed) {
            print(preview, json, out);
            return 0;
        }
        if (preview.targets().isEmpty()) {
            Plan empty = new Plan(scope, true, true, 0, 0, List.of(), preview.refusals());
            print(empty, json, out);
            return 0;
        }

        Plan applied;
        OperationLease.Acquisition acquisition = acquire(home, scope);
        try (OperationLease ignored = acquisition.lease()) {
            Plan current = plan(home, scope, false);
            if (!current.safe()) {
                print(current, json, out);
                return 1;
            }
            if (scope == Scope.ALL_DATA) markRemoval(home);
            RemovalOutcome outcome = remove(current.targets(), scope, home);
            List<String> diagnostics = new ArrayList<>(current.refusals());
            diagnostics.addAll(outcome.failures());
            diagnostics.addAll(outcome.warnings());
            long bytes = outcome.removed().stream().mapToLong(Target::bytes).sum();
            long files = outcome.removed().stream().mapToLong(Target::files).sum();
            applied = new Plan(
                    scope,
                    outcome.failures().isEmpty(),
                    true,
                    bytes,
                    files,
                    List.copyOf(outcome.removed()),
                    List.copyOf(diagnostics));
        }

        if (scope == Scope.LAUNCHER) {
            home.recordInstalledIntegrations();
        }
        if (scope == Scope.ALL_DATA && applied.safe()) {
            try {
                deleteRecursively(home.state());
                if (Files.isDirectory(home.root(), LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(home.root());
                }
            } catch (IOException failure) {
                applied = new Plan(scope, false, true, applied.bytes(), applied.files(), applied.targets(),
                        List.of("Could not finish removing Preflight's operation state: " + failure.getMessage()));
            }
        }
        print(applied, json, out);
        return applied.safe() ? 0 : 1;
    }

    private static OperationLease.Acquisition acquire(PreflightHome home, Scope scope) throws IOException {
        return OperationLease.acquire(home,
                scope == Scope.ALL_DATA ? OperationLease.REMOVE_ALL_OPERATION : "remove-launcher",
                null);
    }

    private static void markRemoval(PreflightHome home) throws IOException {
        Files.writeString(OperationLease.removalMarker(home),
                "Preflight data removal is in progress. Re-run uninstall --purge --yes if interrupted.\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    static Plan plan(PreflightHome home, Scope scope, boolean applied) throws IOException {
        List<Target> targets = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        for (PreflightHome.Integration integration : home.integrations()) {
            Path path = integration.path().toAbsolutePath().normalize();
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                IntegrationMutation.Review review = IntegrationMutation.reviewForRemoval(integration);
                if (review.snapshot().present()) {
                    addTarget(targets, "launch-integration", integration.label(), path, review);
                }
            } catch (IOException refusal) {
                refusals.add(refusal.getMessage());
            }
        }
        Path installedEngine = home.installedJar().getParent();
        if (Files.exists(installedEngine, LinkOption.NOFOLLOW_LINKS)) {
            addTarget(targets, "installed-engine", "installed command engine", installedEngine, null);
        }
        boolean safe = true;
        if (scope == Scope.ALL_DATA) {
            Path root = home.root().toAbsolutePath().normalize();
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                    safe = false;
                    refusals.add("Preflight home directory is a symlink or alias (" + root + "). All-data removal is refused.");
                } else {
                    targets.removeIf(target -> target.path().startsWith(root));
                    addTarget(targets, "preflight-data", "Preflight data", root, null);
                }
            }
        }
        long bytes = targets.stream().mapToLong(Target::bytes).sum();
        long files = targets.stream().mapToLong(Target::files).sum();
        return new Plan(scope, safe, applied, bytes, files, List.copyOf(targets), List.copyOf(refusals));
    }

    private static void addTarget(
            List<Target> targets,
            String kind,
            String label,
            Path path,
            IntegrationMutation.Review integrationReview) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        targets.removeIf(existing -> existing.path().startsWith(normalized));
        if (targets.stream().anyMatch(existing -> normalized.startsWith(existing.path()))) return;
        CacheFootprint.Usage usage = strictUsage(normalized);
        targets.add(new Target(kind, label, normalized, usage.bytes(), usage.files(), integrationReview));
    }

    private static CacheFootprint.Usage strictUsage(Path path) throws IOException {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return new CacheFootprint.Usage(Files.size(path), 1);
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return new CacheFootprint.Usage(0, 0);
        }
        long[] totals = new long[2];
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    totals[0] = Math.addExact(totals[0], attributes.size());
                    totals[1] = Math.addExact(totals[1], 1);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                throw failure;
            }
        });
        return new CacheFootprint.Usage(totals[0], totals[1]);
    }

    private static RemovalOutcome remove(List<Target> targets, Scope scope, PreflightHome home) {
        List<Target> removed = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Target target : targets) {
            try {
                if (scope == Scope.ALL_DATA && target.path().equals(home.root())) {
                    removeRootContentsExceptState(home);
                    removed.add(target);
                } else if (target.integrationReview() != null) {
                    IntegrationMutation.Removal removal = IntegrationMutation.remove(target.integrationReview());
                    if (removal.removed()) {
                        removed.add(target);
                        try {
                            removal.cleanupCommitted();
                        } catch (IOException cleanupFailure) {
                            String warning = "Removed " + target.path()
                                    + " from its launcher pathname; preserved changed quarantine residue: "
                                    + cleanupFailure.getMessage();
                            warnings.add(warning);
                            System.err.println(warning);
                        }
                    }
                } else {
                    deleteRecursively(target.path());
                    removed.add(target);
                }
            } catch (IOException failure) {
                String message = "Could not remove " + target.path() + ": " + failure.getMessage();
                failures.add(message);
                System.err.println(message);
            }
        }
        return new RemovalOutcome(List.copyOf(removed), List.copyOf(failures), List.copyOf(warnings));
    }

    private static void removeRootContentsExceptState(PreflightHome home) throws IOException {
        Path root = home.root().toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (!child.equals(home.state())) deleteRecursively(child);
            }
        }
    }

    static void print(Plan plan, boolean json, PrintStream out) {
        if (json) {
            out.println(Json.object(plan.toJson()));
            return;
        }
        if (!plan.safe() && !plan.applied()) {
            out.println("Preview refused:");
            plan.refusals().forEach(refusal -> out.println("  " + refusal));
            return;
        }
        if (!plan.refusals().isEmpty()) {
            plan.refusals().forEach(out::println);
        }
        if (plan.targets().isEmpty()) {
            out.println(plan.applied()
                    ? "No owned paths were removed."
                    : "Preview: nothing to remove for the selected scope.");
            return;
        }
        out.println(plan.applied() ? "Removed:" : "Preview: Would remove:");
        for (Target target : plan.targets()) {
            out.printf(Locale.ROOT, "  %-24s %s%n", target.label(), target.path());
        }
        out.printf(Locale.ROOT, "%n%s across %,d files.%n", CacheFootprint.humanBytes(plan.bytes()), plan.files());
        out.println("Starsector, mods, saves, and game-owned settings are outside this plan.");
        if (!plan.applied()) {
            out.println("Preview only; nothing was removed. Re-run with --yes to apply this exact scope.");
        } else if (plan.safe()) {
            out.println("Done.");
        } else {
            out.println("Removal finished with one or more external integration generations preserved.");
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static IntegrationRemoval removeIntegration(PreflightHome.Integration integration) throws IOException {
        Path target = integration.path().toAbsolutePath().normalize();
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return new IntegrationRemoval(false, null);
        IntegrationMutation.Removal removal = IntegrationMutation.remove(IntegrationMutation.reviewForRemoval(integration));
        if (!removal.removed()) return new IntegrationRemoval(false, null);
        try {
            removal.cleanupCommitted();
            return new IntegrationRemoval(true, null);
        } catch (IOException cleanupFailure) {
            return new IntegrationRemoval(true,
                    "preserved changed quarantine residue after removal: " + cleanupFailure.getMessage());
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException("Missing value for " + option);
        return args[index];
    }

    enum Scope {
        LAUNCHER("launcher"), ALL_DATA("all-data");
        private final String value;
        Scope(String value) { this.value = value; }
        String value() { return value; }
        static Scope parse(String value) {
            for (Scope scope : values()) if (scope.value.equals(value)) return scope;
            throw new IllegalArgumentException("Removal scope must be launcher or all-data");
        }
    }

    record Target(
            String kind,
            String label,
            Path path,
            long bytes,
            long files,
            IntegrationMutation.Review integrationReview) {
        Map<String, Object> toJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", kind); value.put("label", label); value.put("path", path);
            value.put("bytes", bytes); value.put("files", files);
            return value;
        }
    }

    record IntegrationRemoval(boolean removed, String warning) {}

    private record RemovalOutcome(List<Target> removed, List<String> failures, List<String> warnings) {}

    record Plan(Scope scope, boolean safe, boolean applied, long bytes, long files,
                List<Target> targets, List<String> refusals) {
        Map<String, Object> toJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("format", FORMAT); value.put("scope", scope.value()); value.put("safe", safe);
            value.put("applied", applied); value.put("bytes", bytes); value.put("files", files);
            value.put("targets", targets.stream().map(Target::toJson).toList());
            value.put("refusals", refusals);
            value.put("preserves", List.of("Starsector installation", "mods", "saves", "game-owned settings"));
            return value;
        }
    }
}

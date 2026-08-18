package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
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
import java.util.Objects;

/** Preview-first removal of Preflight-owned launch files or all Preflight-owned data. */
final class UninstallCommand {
    static final String FORMAT = "preflight-removal-v1";
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static volatile RemovalTestHook removalTestHook = RemovalTestHook.NOOP;

    private UninstallCommand() {}

    enum RemovalEvent {
        AFTER_REVIEW,
        AFTER_QUARANTINE,
        BEFORE_DELETE
    }

    @FunctionalInterface
    interface RemovalTestHook {
        RemovalTestHook NOOP = (event, integration, path) -> {};
        void on(RemovalEvent event, PreflightHome.Integration integration, Path path) throws IOException;
    }

    static final class RemovalTestHookScope implements AutoCloseable {
        private final RemovalTestHook previous;
        private boolean closed;

        private RemovalTestHookScope(RemovalTestHook previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (!closed) {
                removalTestHook = previous;
                closed = true;
            }
        }
    }

    static RemovalTestHookScope installRemovalTestHook(RemovalTestHook hook) {
        RemovalTestHook previous = removalTestHook;
        removalTestHook = Objects.requireNonNull(hook);
        return new RemovalTestHookScope(previous);
    }

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
            List<String> refusals = new ArrayList<>(current.refusals());
            refusals.addAll(outcome.failures());
            long removedBytes = outcome.removed().stream().mapToLong(Target::bytes).sum();
            long removedFiles = outcome.removed().stream().mapToLong(Target::files).sum();
            applied = new Plan(
                    scope,
                    outcome.failures().isEmpty(),
                    true,
                    removedBytes,
                    removedFiles,
                    outcome.removed(),
                    List.copyOf(refusals));
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
            if (integration.present()) {
                if (integration.isOwned()) {
                    addTarget(targets, "launch-integration", integration.label(), integration.path());
                } else {
                    refusals.add("Existing " + integration.label() + " at " + integration.path()
                            + " is not proven Preflight-owned and is preserved untouched.");
                }
            }
        }
        Path installedEngine = home.installedJar().getParent();
        if (Files.exists(installedEngine, LinkOption.NOFOLLOW_LINKS)) {
            addTarget(targets, "installed-engine", "installed command engine", installedEngine);
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
                    addTarget(targets, "preflight-data", "Preflight data", root);
                }
            }
        }
        long bytes = targets.stream().mapToLong(Target::bytes).sum();
        long files = targets.stream().mapToLong(Target::files).sum();
        return new Plan(scope, safe, applied, bytes, files, List.copyOf(targets), List.copyOf(refusals));
    }

    private static void addTarget(List<Target> targets, String kind, String label, Path path)
            throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        targets.removeIf(existing -> existing.path().startsWith(normalized));
        if (targets.stream().anyMatch(existing -> normalized.startsWith(existing.path()))) return;
        CacheFootprint.Usage usage = strictUsage(normalized);
        targets.add(new Target(kind, label, normalized, usage.bytes(), usage.files()));
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
        for (Target target : targets) {
            try {
                if ("launch-integration".equals(target.kind())) {
                    PreflightHome.Integration integration = integrationAt(home, target.path());
                    if (integration == null) {
                        throw new IOException("No launcher integration authority matches this path");
                    }
                    removeIntegration(integration);
                } else if (scope == Scope.ALL_DATA && target.path().equals(home.root())) {
                    removeRootContentsExceptState(home);
                } else {
                    deleteRecursively(target.path());
                }
                removed.add(target);
            } catch (IOException failure) {
                String detail = "Could not remove " + target.label() + " at " + target.path()
                        + ": " + failure.getMessage();
                failures.add(detail);
                System.err.println(detail);
            }
        }
        return new RemovalOutcome(List.copyOf(removed), List.copyOf(failures));
    }

    private static PreflightHome.Integration integrationAt(PreflightHome home, Path path) {
        Path expected = path.toAbsolutePath().normalize();
        return home.integrations().stream()
                .filter(integration -> integration.path().toAbsolutePath().normalize().equals(expected))
                .findFirst()
                .orElse(null);
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
        if (!plan.safe()) {
            if (!plan.applied()) {
                out.println("Preview refused:");
                plan.refusals().forEach(refusal -> out.println("  " + refusal));
                return;
            }
            out.println("Removal incomplete:");
            if (!plan.targets().isEmpty()) {
                out.println("Removed before the refusal:");
                for (Target target : plan.targets()) {
                    out.printf(Locale.ROOT, "  %-24s %s%n", target.label(), target.path());
                }
            }
            plan.refusals().forEach(refusal -> out.println("  " + refusal));
            out.println("Starsector, mods, saves, and game-owned settings remain outside this operation.");
            return;
        }
        if (!plan.refusals().isEmpty()) {
            plan.refusals().forEach(out::println);
        }
        if (plan.targets().isEmpty()) {
            out.println(plan.applied()
                    ? "Nothing to remove for the selected scope."
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
        } else {
            out.println("Done.");
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

    static void removeIntegration(PreflightHome.Integration integration) throws IOException {
        Path target = integration.path().toAbsolutePath().normalize();
        IntegrationMutation.Snapshot reviewed = IntegrationMutation.Snapshot.capture(target);
        if (!reviewed.present()) return;
        if (!integration.isOwned()) {
            throw new IOException("Launcher integration is no longer proven Preflight-owned: " + target);
        }
        fireRemoval(RemovalEvent.AFTER_REVIEW, integration, target);

        Path quarantined = anchorIntegration(target);
        fireRemoval(RemovalEvent.AFTER_QUARANTINE, integration, quarantined);
        PreflightHome.Integration anchoredIntegration = relocated(integration, quarantined);
        boolean exactOwned;
        try {
            exactOwned = reviewed.sameAs(IntegrationMutation.Snapshot.capture(quarantined))
                    && anchoredIntegration.isOwned();
        } catch (IOException proofFailure) {
            try {
                restoreOrPreserve(quarantined, target);
            } catch (IOException restoreFailure) {
                proofFailure.addSuppressed(restoreFailure);
            }
            throw new IOException(
                    "Preserved launcher integration because its anchored generation could not be re-proved before deletion: "
                            + target + "; anchored generation at " + quarantined,
                    proofFailure);
        }
        if (!exactOwned) {
            restoreOrPreserve(quarantined, target);
            throw new IOException("Preserved launcher integration because its reviewed owned generation changed before deletion: "
                    + target + "; anchored generation at " + quarantined);
        }

        fireRemoval(RemovalEvent.BEFORE_DELETE, integration, quarantined);
        try {
            reviewed.deleteExact(quarantined);
        } catch (IOException failure) {
            throw new IOException("Preserved launcher removal residue after an exact-generation deletion refusal at "
                    + quarantined + ": " + failure.getMessage(), failure);
        }
    }

    private static Path anchorIntegration(Path target) throws IOException {
        String name = target.getFileName() == null ? "integration" : target.getFileName().toString();
        for (int attempt = 0; attempt < 16; attempt++) {
            Path quarantine = target.resolveSibling(name + ".preflight-remove-"
                    + ProcessHandle.current().pid() + "-" + System.nanoTime());
            try {
                ExclusiveMove.move(target, quarantine);
                return quarantine;
            } catch (FileAlreadyExistsException collision) {
                // Preserve the colliding sibling and choose another unique quarantine name.
            }
        }
        throw new IOException("Could not reserve launcher removal quarantine beside " + target);
    }

    private static void restoreOrPreserve(Path quarantine, Path target) throws IOException {
        if (!Files.exists(quarantine, NOFOLLOW) || Files.exists(target, NOFOLLOW)) return;
        try {
            ExclusiveMove.move(quarantine, target);
        } catch (FileAlreadyExistsException externalWon) {
            // A newer public generation wins; preserve both it and the quarantined generation.
        }
    }

    private static PreflightHome.Integration relocated(
            PreflightHome.Integration integration, Path path) {
        return new PreflightHome.Integration(
                integration.id(), integration.label(), path, integration.directory(), integration.legacy());
    }

    private static void fireRemoval(
            RemovalEvent event, PreflightHome.Integration integration, Path path) throws IOException {
        removalTestHook.on(event, integration, path);
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

    record Target(String kind, String label, Path path, long bytes, long files) {
        Map<String, Object> toJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", kind); value.put("label", label); value.put("path", path);
            value.put("bytes", bytes); value.put("files", files);
            return value;
        }
    }

    private record RemovalOutcome(List<Target> removed, List<String> failures) {}

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

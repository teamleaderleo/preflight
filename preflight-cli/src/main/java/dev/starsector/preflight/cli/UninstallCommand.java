package dev.starsector.preflight.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Removes everything Preflight put on this machine.
 *
 * <p>There is nothing to restore. Preflight never writes to the Starsector installation -- it reads
 * and hashes the game's files and puts every artifact it produces under its own home directory --
 * so uninstalling is deleting Preflight's own files, and the game is left exactly as it was found.
 *
 * <p>Two scopes, because they have different costs. Removing the launcher integration is free to
 * undo: run {@code preflight install} again. Removing the home directory discards prepared caches
 * and the run and benchmark evidence beside them; the caches rebuild themselves on the next launch,
 * but the evidence does not come back, which is why {@code --purge} is separate and why both scopes
 * refuse to act without {@code --yes}.
 */
final class UninstallCommand {
    private UninstallCommand() {
    }

    static int execute(String[] args, int from) throws Exception {
        boolean purge = false;
        boolean confirmed = false;
        for (int index = from; index < args.length; index++) {
            switch (args[index]) {
                case "--purge" -> purge = true;
                case "--yes" -> confirmed = true;
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
        return run(PreflightHome.current(), purge, confirmed, System.out);
    }

    static int run(PreflightHome home, boolean purge, boolean confirmed, PrintStream out)
            throws IOException {
        List<Target> targets = new ArrayList<>();
        for (PreflightHome.Integration integration : home.integrations()) {
            if (integration.present()) {
                targets.add(new Target(
                        integration.label(),
                        integration.path(),
                        CacheFootprint.usage(integration.directory()
                                ? integration.path() : integration.path().getParent())));
            }
        }
        boolean rootPresent = Files.isDirectory(home.root());
        if (purge && rootPresent) {
            targets.add(new Target(
                    "Preflight home", home.root(), CacheFootprint.usage(home.root())));
        }

        if (targets.isEmpty()) {
            out.println("Nothing to remove; Preflight is not installed on this machine.");
            if (!purge && rootPresent) {
                out.printf(Locale.ROOT,
                        "Its cache is still at %s (%s). Add --purge to remove that too.%n",
                        home.root(),
                        CacheFootprint.humanBytes(CacheFootprint.usage(home.root()).bytes()));
            }
            return 0;
        }

        out.println(confirmed ? "Removing:" : "Would remove:");
        for (Target target : targets) {
            out.printf(Locale.ROOT, "  %-20s %s%n", target.label(), target.path());
        }
        if (purge && rootPresent) {
            CacheFootprint.Usage usage = CacheFootprint.usage(home.root());
            out.printf(Locale.ROOT, "%nThat discards %s across %,d files, including prepared caches%n",
                    CacheFootprint.humanBytes(usage.bytes()), usage.files());
            out.println("and every run and benchmark record under it. Caches rebuild on the next");
            out.println("launch; the evidence does not.");
        }
        if (!purge && rootPresent) {
            out.printf(Locale.ROOT, "%nLeaving %s (%s). Add --purge to remove that too.%n",
                    home.root(),
                    CacheFootprint.humanBytes(CacheFootprint.usage(home.root()).bytes()));
        }

        if (!confirmed) {
            out.println();
            out.println("Nothing was removed. Re-run with --yes to do it.");
            return 0;
        }

        int failures = 0;
        for (Target target : targets) {
            try {
                deleteRecursively(target.path());
            } catch (IOException failure) {
                failures++;
                System.err.println("Could not remove " + target.path() + ": " + failure.getMessage());
            }
        }
        if (failures == 0) {
            out.println();
            out.println("Done. The Starsector installation was never modified, so nothing there");
            out.println("needs restoring.");
        }
        return failures == 0 ? 0 : 1;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void removeIntegration(PreflightHome.Integration integration) throws IOException {
        deleteRecursively(integration.path());
    }

    private record Target(String label, Path path, CacheFootprint.Usage usage) {
    }
}

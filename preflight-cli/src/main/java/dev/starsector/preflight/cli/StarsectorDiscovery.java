package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class StarsectorDiscovery {
    private static final Set<String> EXACT_LAUNCHER_NAMES = Set.of(
            "fr.sh", "fr.command", "fr.bat", "fr.cmd", "fr.exe",
            "fast-rendering.sh", "fast-rendering.command", "fast-rendering.bat", "fast-rendering.cmd",
            "starsector-fr.sh", "starsector-fr.command", "starsector-fr.bat", "starsector-fr.cmd",
            "starsector.sh", "starsector.command", "starsector.bat", "starsector.cmd", "starsector.exe", "starsector");

    private StarsectorDiscovery() {
    }

    static DiscoveryResult discover(
            Platform platform,
            Path home,
            Path currentDirectory,
            Map<String, String> environment,
            Path explicitGame,
            Path explicitLauncher) throws IOException {
        List<String> diagnostics = new ArrayList<>();
        Map<Path, LaunchTarget> targets = new LinkedHashMap<>();

        if (explicitLauncher != null) {
            Path launcher = explicitLauncher.toAbsolutePath().normalize();
            if (!Files.isRegularFile(launcher)) {
                throw new IOException("Explicit launcher does not exist or is not a file: " + launcher);
            }
            Path root = explicitGame == null ? launcher.getParent() : explicitGame.toAbsolutePath().normalize();
            LaunchTarget target = targetForLauncher(platform, root, launcher, 10_000, "--launcher");
            addTarget(targets, target);
        }

        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        addRoot(roots, explicitGame);
        if (explicitGame == null) {
            addRoot(roots, pathFromEnvironment(environment, "STARSECTOR_HOME"));
            addRoot(roots, pathFromEnvironment(environment, "STARSECTOR_DIR"));
            addImplicitWorkingDirectory(roots, currentDirectory, diagnostics);
            addStandardRoots(roots, platform, home, environment);
        }

        for (Path root : roots) {
            inspectRoot(platform, root, targets, diagnostics);
        }

        List<LaunchTarget> candidates = targets.values().stream()
                .sorted(Comparator.comparingInt(LaunchTarget::score).reversed()
                        .thenComparing(target -> target.launcher().toString()))
                .toList();
        LaunchTarget selected = candidates.isEmpty() ? null : candidates.get(0);
        if (selected == null) {
            diagnostics.add(nothingFound(platform, explicitGame));
            diagnostics.addAll(searchedLocations(roots));
        } else if (candidates.size() > 1 && candidates.get(1).score() == selected.score()) {
            diagnostics.add("Multiple launchers received the same score; selected the lexicographically first path. Use --launcher to override.");
        }
        return new DiscoveryResult(selected, candidates, List.copyOf(diagnostics));
    }

    /**
     * Why nothing was found, said in terms of what the caller actually did.
     *
     * <p>Advising {@code --game} to someone who just passed {@code --game} is the least useful thing
     * this can say. A path that does not exist and a folder that exists without an installation in it
     * need different fixes, so they are not given the same note. An explicit path naming a file is
     * accepted as a launcher upstream and rarely arrives here.
     */
    private static String nothingFound(Platform platform, Path explicitGame) {
        if (explicitGame == null) {
            return "No launcher found. Set STARSECTOR_HOME or use --game/--launcher.";
        }
        Path game = explicitGame.toAbsolutePath().normalize();
        if (!Files.exists(game)) {
            return "--game does not exist: " + game;
        }
        if (!Files.isDirectory(game)) {
            return "--game is not a directory: " + game
                    + ". Pass the folder holding the launcher, or name the launcher with --launcher.";
        }
        return "No Starsector launcher under " + game + ". Expected " + expectedLaunchers(platform)
                + " there, or a folder containing one. Use --launcher to name a launcher directly.";
    }

    /**
     * Where discovery looked, said once per place, so a failure can be checked rather than guessed at.
     *
     * <p>"Not found in the usual locations" answers nobody: the reason to ask is to learn whether
     * your own installation is somewhere unusual, and that needs the list. Each entry says whether
     * the directory was even there, because a path that does not exist and a path that exists
     * without a launcher in it are different problems.
     *
     * <p>Several standard roots differ only by case. On a case-insensitive filesystem they are one
     * directory and listing both prints the same place twice; on a case-sensitive one they are two
     * places and folding them would hide a path that really was searched. Identity therefore comes
     * from {@link Path#toRealPath} where the directory exists and the filesystem can answer, and
     * from the literal path where it does not.
     */
    private static List<String> searchedLocations(Set<Path> roots) {
        Set<Path> seen = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        for (Path root : roots) {
            Path normalized = root.toAbsolutePath().normalize();
            boolean present = Files.exists(normalized);
            if (!seen.add(canonicalIdentity(normalized))) {
                continue;
            }
            lines.add("Searched " + normalized + (present ? " (no launcher in it)" : " (not present)"));
        }
        return lines;
    }

    /** What an installation looks like here, so the note says what to go and check for. */
    private static String expectedLaunchers(Platform platform) {
        return switch (platform) {
            case WINDOWS -> "starsector.exe or starsector.bat";
            case LINUX -> "starsector.sh or starsector";
            case MAC -> "Starsector.app or starsector.command";
            case OTHER -> "a starsector launcher script";
        };
    }

    private static void inspectRoot(
            Platform platform,
            Path candidate,
            Map<Path, LaunchTarget> targets,
            List<String> diagnostics) {
        Path root = candidate.toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            return;
        }
        try {
            if (Files.isRegularFile(root)) {
                addTarget(targets, targetForLauncher(platform, root.getParent(), root, 500, "candidate file"));
                return;
            }
            if (isAppBundle(root)) {
                inspectAppBundle(platform, root, targets);
            }
            inspectTree(platform, root, targets, diagnostics);
            try (Stream<Path> children = Files.list(root)) {
                children.filter(Files::isDirectory)
                        .filter(StarsectorDiscovery::isAppBundle)
                        .forEach(app -> inspectAppBundle(platform, app, targets));
            }
        } catch (IOException error) {
            diagnostics.add("Could not inspect " + root + ": " + error.getMessage());
        }
    }

    private static void inspectTree(
            Platform platform,
            Path root,
            Map<Path, LaunchTarget> targets,
            List<String> diagnostics) throws IOException {
        Files.walkFileTree(root, Set.of(), 3, new SimpleFileVisitor<>() {
            private boolean reportedUnreadable;

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && looksLikeLauncher(path)) {
                    addTarget(
                            targets,
                            targetForLauncher(platform, root, path, 0, "discovered under " + root));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException error) {
                if (!reportedUnreadable) {
                    diagnostics.add("Skipped an unreadable discovery path under " + root + ": "
                            + path + ": " + error.getMessage());
                    reportedUnreadable = true;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void inspectAppBundle(Platform platform, Path app, Map<Path, LaunchTarget> targets) {
        Path macos = app.resolve("Contents").resolve("MacOS");
        if (!Files.isDirectory(macos)) {
            return;
        }
        try (Stream<Path> entries = Files.list(macos)) {
            entries.filter(Files::isRegularFile)
                    .filter(path -> Files.isExecutable(path) || looksLikeLauncher(path))
                    .forEach(path -> {
                        int bonus = app.getFileName().toString().toLowerCase(Locale.ROOT).contains("fast") ? 70 : 40;
                        addTarget(targets, targetForLauncher(platform, app, path, bonus, "macOS app bundle"));
                    });
        } catch (IOException ignored) {
            // A later explicit override remains available.
        }
    }

    private static LaunchTarget targetForLauncher(
            Platform platform,
            Path root,
            Path launcher,
            int baseScore,
            String source) {
        Path normalized = launcher.toAbsolutePath().normalize();
        String name = normalized.getFileName().toString().toLowerCase(Locale.ROOT);
        int score = baseScore;
        if (LaunchOwnership.isPortLauncherName(name) || name.equals("fr.sh") || name.equals("fr.command") || name.equals("fr.bat")
                || name.equals("fr.cmd") || name.equals("fr.exe")) {
            score += 130;
        }
        if (name.contains("fast") || name.contains("render")) {
            score += 100;
        }
        if (name.contains("starsector")) {
            score += 80;
        }
        if (Files.isExecutable(normalized)) {
            score += 10;
        }
        if (platform == Platform.MAC && (name.endsWith(".command") || isInsideAppBundle(normalized))) {
            score += 20;
        }
        if (platform == Platform.LINUX && (name.endsWith(".sh") || name.equals("starsector"))) {
            score += 20;
        }
        if (platform == Platform.WINDOWS
                && (name.endsWith(".exe") || name.endsWith(".bat") || name.endsWith(".cmd"))) {
            score += 20;
        }

        List<String> command;
        if (name.endsWith(".bat") || name.endsWith(".cmd")) {
            command = List.of("cmd.exe", "/d", "/s", "/c", "call", quoteWindowsCommand(normalized));
        } else if ((name.endsWith(".sh") || name.endsWith(".command")) && !Files.isExecutable(normalized)) {
            command = List.of("/bin/sh", normalized.toString());
        } else {
            command = List.of(normalized.toString());
        }
        Path workingDirectory = normalized.getParent();
        return new LaunchTarget(
                root.toAbsolutePath().normalize(),
                normalized,
                workingDirectory,
                command,
                launcherKind(name),
                score,
                source);
    }

    private static String quoteWindowsCommand(Path launcher) {
        return "\"" + launcher + "\"";
    }

    private static boolean looksLikeLauncher(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (EXACT_LAUNCHER_NAMES.contains(name)) {
            return true;
        }
        if (!(name.endsWith(".sh") || name.endsWith(".command") || name.endsWith(".bat")
                || name.endsWith(".cmd") || name.endsWith(".exe"))) {
            return false;
        }
        return name.contains("starsector") || name.contains("fast") || name.startsWith("fr.")
                || name.contains("render");
    }

    private static String launcherKind(String name) {
        if (name.endsWith(".bat") || name.endsWith(".cmd")) {
            return "windows-script";
        }
        if (name.endsWith(".sh") || name.endsWith(".command")) {
            return "shell-script";
        }
        if (name.endsWith(".exe")) {
            return "windows-executable";
        }
        return "executable";
    }

    private static boolean isAppBundle(Path path) {
        return Files.isDirectory(path)
                && path.getFileName() != null
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".app");
    }

    private static boolean isInsideAppBundle(Path path) {
        for (Path part : path) {
            if (part.toString().toLowerCase(Locale.ROOT).endsWith(".app")) {
                return true;
            }
        }
        return false;
    }

    private static void addTarget(Map<Path, LaunchTarget> targets, LaunchTarget target) {
        Path identity = canonicalIdentity(target.launcher());
        targets.merge(identity, target, (left, right) -> left.score() >= right.score() ? left : right);
    }

    private static Path canonicalIdentity(Path launcher) {
        try {
            return launcher.toRealPath();
        } catch (IOException ignored) {
            return launcher.toAbsolutePath().normalize();
        }
    }

    private static void addStandardRoots(
            Set<Path> roots,
            Platform platform,
            Path home,
            Map<String, String> environment) {
        if (home == null) {
            return;
        }
        switch (platform) {
            case MAC -> {
                roots.add(Path.of("/Applications/Starsector.app"));
                roots.add(Path.of("/Applications/starsector.app"));
                roots.add(home.resolve("Applications/Starsector.app"));
                roots.add(home.resolve("Applications/starsector.app"));
                roots.add(home.resolve("Games/Starsector.app"));
            }
            case LINUX -> {
                roots.add(home.resolve("starsector"));
                roots.add(home.resolve("Starsector"));
                roots.add(home.resolve("Games/starsector"));
                roots.add(home.resolve("Games/Starsector"));
                roots.add(home.resolve(".local/share/starsector"));
                roots.add(Path.of("/opt/starsector"));
            }
            case WINDOWS -> {
                addRoot(roots, child(environment.get("ProgramFiles"), "Starsector"));
                addRoot(roots, child(environment.get("ProgramFiles(x86)"), "Starsector"));
                addRoot(roots, child(environment.get("LOCALAPPDATA"), "Starsector"));
                roots.add(home.resolve("Games/Starsector"));
            }
            case OTHER -> {
            }
        }
    }

    private static Path child(String parent, String child) {
        return parent == null || parent.isBlank() ? null : Path.of(parent).resolve(child);
    }

    private static Path pathFromEnvironment(Map<String, String> environment, String name) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static void addRoot(Set<Path> roots, Path path) {
        if (path != null) {
            roots.add(path);
        }
    }

    /**
     * Keep CWD convenience deterministic. An arbitrary workspace is not itself a recursive search
     * root: inspect only exact launcher filenames beside the caller and a fixed set of likely
     * Starsector child locations. Explicit/environment/platform roots retain the deeper inspection.
     */
    private static void addImplicitWorkingDirectory(
            Set<Path> roots, Path currentDirectory, List<String> diagnostics) {
        if (currentDirectory == null) {
            return;
        }
        Path normalized = currentDirectory.toAbsolutePath().normalize();
        if (normalized.getParent() == null) {
            diagnostics.add("Skipped filesystem root as an implicit discovery directory: " + normalized);
            return;
        }
        EXACT_LAUNCHER_NAMES.stream()
                .sorted()
                .map(normalized::resolve)
                .filter(Files::isRegularFile)
                .forEach(roots::add);
        for (String relative : List.of(
                "Starsector",
                "starsector",
                "Starsector.app",
                "starsector.app",
                "Games/Starsector",
                "Games/starsector",
                "Games/Starsector.app",
                "Games/starsector.app")) {
            roots.add(normalized.resolve(relative));
        }
    }
}

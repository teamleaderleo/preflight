package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

record GameLayout(Path installRoot, Path modsDirectory, Path enabledModsFile, List<String> diagnostics) {
    /**
     * Canonical mutation boundary for enabled_mods.json.
     *
     * <p>Read-only discovery may follow ordinary platform links so the player can inspect an
     * installation. A confirmed profile switch first resolves the real installation and mods
     * directories, requires the real mods directory to stay inside that installation, and refuses
     * a symlink/non-file enabled_mods entry. The activation transaction then opens and pins this
     * canonical parent generation through commit.
     */
    GameLayout requireMutationSafe() throws IOException {
        Path realRoot = installRoot.toRealPath();
        Path realMods = modsDirectory.toRealPath();
        if (!realMods.startsWith(realRoot)) {
            throw new IOException("Refusing to modify enabled_mods.json outside the selected install: "
                    + modsDirectory);
        }
        if (!Files.isRegularFile(enabledModsFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to modify a symlink or non-file enabled_mods.json: "
                    + enabledModsFile);
        }
        Path realEnabled = enabledModsFile.toRealPath();
        if (!realEnabled.getParent().equals(realMods)) {
            throw new IOException("Refusing to modify enabled_mods.json outside the selected mods directory: "
                    + enabledModsFile);
        }
        return new GameLayout(realRoot, realMods, realEnabled, diagnostics);
    }

    static GameLayout locate(Path installRoot) throws IOException {
        Path root = installRoot.toAbsolutePath().normalize();
        List<String> diagnostics = new ArrayList<>();
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(root.resolve("mods"));
        candidates.add(root.resolve("starsector-core").resolve("mods"));
        candidates.add(root.resolve("Contents").resolve("Resources").resolve("Java").resolve("mods"));
        candidates.add(root.resolve("Contents").resolve("Resources").resolve("mods"));
        candidates.add(root.resolve("Contents").resolve("Java").resolve("mods"));

        for (Path mods : candidates) {
            Path enabled = mods.resolve("enabled_mods.json");
            if (Files.isRegularFile(enabled)) {
                return new GameLayout(root, mods, enabled, List.copyOf(diagnostics));
            }
        }

        if (Files.isDirectory(root)) {
            try (Stream<Path> matches = Files.find(
                    root,
                    7,
                    (path, attributes) -> attributes.isRegularFile()
                            && path.getFileName().toString().equalsIgnoreCase("enabled_mods.json")
                            && path.getParent() != null
                            && path.getParent().getFileName().toString().equalsIgnoreCase("mods"))) {
                List<Path> found = matches.sorted().limit(3).toList();
                if (!found.isEmpty()) {
                    if (found.size() > 1) {
                        diagnostics.add("Found multiple enabled_mods.json files; selected " + found.get(0));
                    }
                    Path enabled = found.get(0);
                    return new GameLayout(root, enabled.getParent(), enabled, List.copyOf(diagnostics));
                }
            }
        }

        throw new IOException("Could not locate mods/enabled_mods.json under " + root);
    }
}

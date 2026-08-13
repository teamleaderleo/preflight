package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Narrow launch-owner detection for optimizations that cannot compose across classloaders. */
record LaunchOwnership(Owner owner, List<String> evidence) {
    private static final long MAX_LAUNCHER_BYTES = 64 * 1024;
    private static final long MAX_VMPARAMS_BYTES = 256 * 1024;

    LaunchOwnership {
        evidence = List.copyOf(evidence);
    }

    static LaunchOwnership detect(LaunchTarget target) {
        List<String> evidence = new ArrayList<>();
        String name = target.launcher().getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals("fr.bat") || name.equals("fr.cmd") || name.equals("fr.sh")
                || name.equals("fr.command") || name.equals("fr.exe")
                || name.contains("fast-render") || name.contains("fastrender")) {
            evidence.add("launcher-name=" + name);
        }

        String launcher = boundedText(target.launcher(), MAX_LAUNCHER_BYTES);
        Path vmparams = target.launcher().resolveSibling("fr.vmparams");
        String parameters = boundedText(vmparams, MAX_VMPARAMS_BYTES);
        if (launcher.contains("@fr.vmparams") || launcher.contains("fr.jar")) {
            evidence.add("launcher-references-fast-rendering");
        }
        if (parameters.contains("fr.jar")) {
            evidence.add("fr.vmparams-classpath=fr.jar");
        }
        if (parameters.contains("java.system.class.loader=com.genir.renderer")) {
            evidence.add("fr.vmparams-custom-system-classloader");
        }
        Owner owner = evidence.isEmpty() ? Owner.STARSECTOR : Owner.FAST_RENDERING;
        return new LaunchOwnership(owner, evidence);
    }

    boolean fastRendering() {
        return owner == Owner.FAST_RENDERING;
    }

    private static String boundedText(Path path, long maximumBytes) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > maximumBytes) {
                return "";
            }
            return Files.readString(path, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    enum Owner {
        STARSECTOR,
        FAST_RENDERING
    }
}

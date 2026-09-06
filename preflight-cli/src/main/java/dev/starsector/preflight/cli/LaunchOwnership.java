package dev.starsector.preflight.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
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
        if (isPortLauncherName(name) || name.equals("fr.bat") || name.equals("fr.cmd") || name.equals("fr.sh")
                || name.equals("fr.command") || name.equals("fr.exe")
                || name.contains("fast-render") || name.contains("fastrender")) {
            evidence.add("launcher-name=" + name);
        }

        String launcher = boundedText(target.launcher(), MAX_LAUNCHER_BYTES);
        if (launcher.contains("@fr.vmparams") || launcher.contains("fr.jar")) {
            evidence.add("launcher-references-fast-rendering");
        }
        // A neighboring optional renderer is not evidence that the selected launcher uses it.
        // Only inspect its parameters once the launcher itself identifies that runtime.
        String parameters = evidence.isEmpty() ? ""
                : boundedText(target.launcher().resolveSibling("fr.vmparams"), MAX_VMPARAMS_BYTES);
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

    static boolean isPortLauncherName(String name) {
        return name.equals("starsector-fr.sh") || name.equals("starsector-fr.command")
                || name.equals("starsector-fr.bat") || name.equals("starsector-fr.cmd");
    }

    static String boundedText(Path path, long maximumBytes) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > maximumBytes) {
                return "";
            }
            try (InputStream input = Files.newInputStream(path)) {
                return boundedText(input, maximumBytes);
            }
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    static String boundedText(InputStream input, long maximumBytes) {
        try {
            int readLimit = Math.toIntExact(Math.addExact(maximumBytes, 1));
            byte[] bytes = input.readNBytes(readLimit);
            if (bytes.length > maximumBytes) {
                return "";
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .toLowerCase(Locale.ROOT);
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    enum Owner {
        STARSECTOR,
        FAST_RENDERING
    }
}

package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/** Opt-in experiment for the reviewed fixed-6-GiB Windows launcher; never edits its files. */
final class WindowsInitialHeapPolicy {
    static final String PROPERTY = "preflight.windows.initialHeapProbe";
    static final String BATCH_SHA = "c92ddf2855cd326cfa241d87c2aa75a034668d5866bdd5f5d8d4b67db3b3414d";
    static final String WRAPPER_SHA = "b93bcff1fb4b15d22167c66e75cc5c792e800c9c43266f67cdf92cdf0ac7919e";
    static final String JAVA_SHA = "9a2697956034fa9667c97644c0d2f8a3f7ab5e4cd3866951d56a73d9de4d2a9f";
    private static final Pattern HEAP_OPTION = Pattern.compile(
            "(?:^|\\s|[\"'])-(?:Xms\\S*|Xmx\\S*|XX:(?:InitialHeapSize|MinHeapSize|MaxHeapSize)=\\S*)");

    private WindowsInitialHeapPolicy() { }

    static Resolution resolve(Platform platform, LaunchTarget target, OptimizationPreset preset,
            Map<String, String> environment) {
        return resolve(platform, target, preset, environment, Boolean.getBoolean(PROPERTY), Hashes::sha256);
    }

    static Resolution resolve(Platform platform, LaunchTarget target, OptimizationPreset preset,
            Map<String, String> environment, boolean requested, FileHash hash) {
        if (!requested) return new Resolution(false, false, "not requested");
        if (platform != Platform.WINDOWS || preset != OptimizationPreset.RECOMMENDED) {
            return new Resolution(true, false, "requires Windows Recommended");
        }
        for (String key : new String[] {"_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"}) {
            if (HEAP_OPTION.matcher(environment.getOrDefault(key, "")).find()) {
                return new Resolution(true, false, "explicit heap option in " + key);
            }
        }
        try {
            Path root = target.installRoot().toRealPath();
            Path batch = root.resolve("starsector-core/starsector.bat");
            Path java = root.resolve("jre/bin/java.exe");
            Path launcher = target.launcher().toRealPath();
            boolean direct = launcher.equals(batch.toRealPath());
            if (!direct && (!launcher.equals(root.resolve("Play-Starsector-VM.cmd").toRealPath())
                    || Files.size(launcher) > 16 * 1024 || !WRAPPER_SHA.equals(hash.read(launcher)))) {
                return new Resolution(true, false, "reviewed Windows wrapper identity mismatch");
            }
            // The exact reviewed batch fixes both initial and maximum heap at 6144 MiB.
            if (!Files.isRegularFile(batch, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(java, LinkOption.NOFOLLOW_LINKS)
                    || !batch.toRealPath().startsWith(root) || !java.toRealPath().startsWith(root)
                    || Files.size(batch) > 64 * 1024 || Files.size(java) > 1024 * 1024
                    || !BATCH_SHA.equals(hash.read(batch)) || !JAVA_SHA.equals(hash.read(java))) {
                return new Resolution(true, false, "reviewed Windows launcher/runtime identity mismatch");
            }
            return new Resolution(true, true, "reviewed Windows heap: initial 2048 MiB, maximum unchanged at 6144 MiB");
        } catch (IOException | RuntimeException failure) {
            return new Resolution(true, false, "launcher/runtime identity probe failed");
        }
    }

    static String appendOptions(String existing, Resolution resolution) {
        if (!resolution.active()) return existing;
        String prefix = existing == null ? "" : existing.trim();
        return (prefix.isEmpty() ? "" : prefix + " ") + "-Xms2048m";
    }

    @FunctionalInterface
    interface FileHash { String read(Path path) throws IOException; }

    record Resolution(boolean requested, boolean active, String reason) {
        Map<String, Object> toReportValues() {
            return Map.of("requested", requested, "active", active, "reason", reason,
                    "initialHeapMiB", active ? 2048 : 0, "preservesMaximum", true);
        }
    }
}

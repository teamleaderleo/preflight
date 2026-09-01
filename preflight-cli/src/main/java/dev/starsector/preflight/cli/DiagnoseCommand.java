package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * CLI command and desktop bridge handler for crash diagnosis and recovery actions.
 */
final class DiagnoseCommand {

    private DiagnoseCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        if (offset < args.length && ("--help".equals(args[offset]) || "-h".equals(args[offset]))) {
            PreflightCli.commandUsage("diagnose", System.out);
            return 0;
        }

        DiagnoseOptions options = DiagnoseOptions.parse(args, offset);
        PreflightHome home = PreflightHome.current();
        LaunchTarget target = discover(options.game(), null);
        Path installRoot = target != null ? target.installRoot() : null;

        Path runDir = options.runDirectory();
        if (runDir == null && home != null && installRoot != null) {
            runDir = findLatestRunDirectory(home.runs(), installRoot);
        }

        CrashDiagnosis diagnosis = runDiagnosis(installRoot, runDir);

        if (options.json()) {
            System.out.println(diagnosis.toJson());
        } else {
            renderHumanOutput(diagnosis, System.out);
        }

        return 0;
    }

    static int recover(String[] args, int offset) throws Exception {
        if (offset < args.length && ("--help".equals(args[offset]) || "-h".equals(args[offset]))) {
            PreflightCli.commandUsage("recover", System.out);
            return 0;
        }

        RecoverOptions options = RecoverOptions.parse(args, offset);
        PreflightHome home = PreflightHome.current();
        LaunchTarget target = discover(options.game(), null);
        Path installRoot = target != null ? target.installRoot() : null;

        if (installRoot == null) {
            throw new IOException("Could not discover Starsector installation directory");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        if (options.modId() != null) {
            params.put("modId", options.modId());
        }
        if (options.memoryMb() != null) {
            params.put("targetHeapMiB", options.memoryMb());
            params.put("heapMiB", options.memoryMb());
        }

        RecoveryActionEngine.ExecutionResult result = RecoveryActionEngine.execute(
                home,
                installRoot,
                options.action(),
                params,
                options.confirmed()
        );

        if (options.json()) {
            System.out.println(result.toJson());
        } else {
            System.out.println(result.summary());
            if (result.details() != null) {
                System.out.println("  " + result.details());
            }
            if (result.backupPath() != null) {
                System.out.println("  Backup: " + result.backupPath());
            }
        }

        return result.success() ? 0 : 1;
    }

    static int executeDesktop(String[] args, int offset) throws Exception {
        DiagnoseOptions options = DiagnoseOptions.parse(args, offset);
        PreflightHome home = PreflightHome.current();
        LaunchTarget target = discover(options.game(), null);
        Path installRoot = target != null ? target.installRoot() : null;

        Path runDir = options.runDirectory();
        if (runDir == null && home != null && installRoot != null) {
            runDir = findLatestRunDirectory(home.runs(), installRoot);
        }

        CrashDiagnosis diagnosis = runDiagnosis(installRoot, runDir);
        System.out.println(diagnosis.toJson());
        return 0;
    }

    static int executeRecoverDesktop(String[] args, int offset) throws Exception {
        RecoverOptions options = RecoverOptions.parse(args, offset);
        PreflightHome home = PreflightHome.current();
        LaunchTarget target = discover(options.game(), null);
        Path installRoot = target != null ? target.installRoot() : null;

        if (installRoot == null) {
            throw new IOException("Could not discover Starsector installation directory");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        if (options.modId() != null) {
            params.put("modId", options.modId());
        }
        if (options.memoryMb() != null) {
            params.put("targetHeapMiB", options.memoryMb());
            params.put("heapMiB", options.memoryMb());
        }

        RecoveryActionEngine.ExecutionResult result = RecoveryActionEngine.execute(
                home,
                installRoot,
                options.action(),
                params,
                options.confirmed()
        );

        System.out.println(result.toJson());
        return result.success() ? 0 : 1;
    }

    static CrashDiagnosis runDiagnosis(Path installRoot, Path runDirectory) {
        List<String> logLines = new ArrayList<>();
        int exitCode = 1;
        Integer launcherExitCode = null;

        // 1. Read run.json if present
        if (runDirectory != null && Files.isDirectory(runDirectory)) {
            Path runJson = runDirectory.resolve("run.json");
            if (Files.isRegularFile(runJson, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    String json = Files.readString(runJson, StandardCharsets.UTF_8);
                    Object ec = StrictJson.object(json).get("exitCode");
                    if (ec instanceof Number n) {
                        exitCode = n.intValue();
                    }
                    Object lec = StrictJson.object(json).get("launcherExitCode");
                    if (lec instanceof Number n) {
                        launcherExitCode = n.intValue();
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // 2. Discover log candidates
        List<Path> logCandidates = LogTailer.discoverLogCandidates(installRoot, runDirectory);
        long remainingScanBudget = LogTailer.MAX_SCAN_BYTES;

        for (Path candidate : logCandidates) {
            if (remainingScanBudget <= 0) break;
            LogTailer.TailResult tail = LogTailer.tailFile(candidate, remainingScanBudget, LogTailer.MAX_MEMORY_BYTES);
            remainingScanBudget -= tail.totalBytesScanned();
            if (!tail.lines().isEmpty()) {
                logLines.addAll(tail.lines());
            }
        }

        return CrashClassifier.classify(installRoot, runDirectory, exitCode, launcherExitCode, logLines);
    }

    private static Path findLatestRunDirectory(Path runsDirectory, Path installRoot) {
        if (runsDirectory == null || !Files.isDirectory(runsDirectory)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(runsDirectory)) {
            return stream.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(dir -> runMatchesInstall(dir, installRoot))
                    .max(Comparator.comparing(DiagnoseCommand::lastModifiedSafe))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean runMatchesInstall(Path runDir, Path installRoot) {
        if (installRoot == null) return true;
        Path runJson = runDir.resolve("run.json");
        if (!Files.isRegularFile(runJson, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            String json = Files.readString(runJson, StandardCharsets.UTF_8);
            Object root = StrictJson.object(json).get("installRoot");
            if (root instanceof String s && !s.isBlank()) {
                return Files.isSameFile(Path.of(s), installRoot);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static Instant lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private static LaunchTarget discover(Path game, Path launcher) {
        try {
            DiscoveryResult discovery = StarsectorDiscovery.discover(
                    Platform.current(),
                    Path.of(System.getProperty("user.home")),
                    Path.of(System.getProperty("user.dir")),
                    System.getenv(),
                    game,
                    launcher);
            return discovery.selected();
        } catch (Exception e) {
            return null;
        }
    }

    private static void renderHumanOutput(CrashDiagnosis d, PrintStream out) {
        out.println("================================================================================");
        out.println("PREFLIGHT LAUNCH CRASH DIAGNOSIS");
        out.println("================================================================================");
        out.printf(Locale.ROOT, "Category:    %s (Confidence: %s)%n", d.rootCauseCategory(), d.confidence());
        out.printf(Locale.ROOT, "Title:       %s%n", d.summaryTitle());
        out.printf(Locale.ROOT, "Exit Code:   %d%n", d.exitCode());
        if (d.offendingMod() != null) {
            out.printf(Locale.ROOT, "Culprit Mod: %s (%s v%s)%n",
                    d.offendingMod().name(), d.offendingMod().id(), d.offendingMod().version());
            out.printf(Locale.ROOT, "Location:    %s.%s(line %d)%n",
                    d.offendingMod().crashingClass(), d.offendingMod().crashingMethod(), d.offendingMod().lineNumber());
        }
        if (d.missingDependency() != null) {
            out.printf(Locale.ROOT, "Missing Mod: %s (required by %s)%n",
                    d.missingDependency().missingModId(), d.missingDependency().dependentModId());
        }
        out.println();
        out.println("Summary:");
        out.println("  " + d.summaryDescription());
        out.println();
        if (!d.recoveryActions().isEmpty()) {
            out.println("Recommended Recovery Actions:");
            for (CrashDiagnosis.RecoveryAction action : d.recoveryActions()) {
                String rec = action.recommended() ? "[RECOMMENDED] " : "";
                out.printf(Locale.ROOT, "  * %s%s - %s%n", rec, action.label(), action.description());
            }
            out.println();
        }
        if (!d.logSnippetLines().isEmpty()) {
            out.println("Log Snippet:");
            for (String line : d.logSnippetLines()) {
                out.println("  | " + line);
            }
        }
        out.println("================================================================================");
    }

    private record DiagnoseOptions(Path game, Path runDirectory, boolean json) {
        static DiagnoseOptions parse(String[] args, int offset) {
            Path game = null;
            Path run = null;
            boolean json = false;

            for (int i = offset; i < args.length; i++) {
                String arg = args[i];
                if ("--game".equals(arg) && i + 1 < args.length) {
                    game = Path.of(args[++i]);
                } else if ("--run".equals(arg) && i + 1 < args.length) {
                    run = Path.of(args[++i]);
                } else if ("--run-dir".equals(arg) && i + 1 < args.length) {
                    run = Path.of(args[++i]);
                } else if ("--json".equals(arg)) {
                    json = true;
                } else {
                    throw new IllegalArgumentException("Unknown diagnose option: " + arg);
                }
            }
            return new DiagnoseOptions(game, run, json);
        }
    }

    private record RecoverOptions(
            String action,
            Path game,
            String modId,
            Integer memoryMb,
            boolean confirmed,
            boolean json
    ) {
        static RecoverOptions parse(String[] args, int offset) {
            String action = null;
            Path game = null;
            String modId = null;
            Integer memoryMb = null;
            boolean confirmed = false;
            boolean json = false;

            for (int i = offset; i < args.length; i++) {
                String arg = args[i];
                if ("--action".equals(arg) && i + 1 < args.length) {
                    action = args[++i];
                } else if ("--game".equals(arg) && i + 1 < args.length) {
                    game = Path.of(args[++i]);
                } else if ("--mod-id".equals(arg) && i + 1 < args.length) {
                    modId = args[++i];
                } else if ("--memory-mb".equals(arg) && i + 1 < args.length) {
                    memoryMb = Integer.parseInt(args[++i]);
                } else if ("--heap-mib".equals(arg) && i + 1 < args.length) {
                    memoryMb = Integer.parseInt(args[++i]);
                } else if ("--yes".equals(arg) || "-y".equals(arg)) {
                    confirmed = true;
                } else if ("--json".equals(arg)) {
                    json = true;
                } else {
                    throw new IllegalArgumentException("Unknown recover option: " + arg);
                }
            }

            if (action == null || action.isBlank()) {
                throw new IllegalArgumentException("recover requires --action <action-id>");
            }

            return new RecoverOptions(action, game, modId, memoryMb, confirmed, json);
        }
    }
}

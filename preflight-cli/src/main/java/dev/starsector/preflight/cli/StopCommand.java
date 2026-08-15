package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Stops a Starsector process that Preflight started.
 *
 * <p>Every harness here stops the game from an {@code EXIT} trap, which covers success, failure,
 * timeout and Ctrl-C. Nothing covered a launch that outlived its wrapper: {@code preflight run}
 * stays attached until the game exits and has no auto-stop, so a launch driven by hand, or one
 * whose wrapper was killed, leaves a ~4 GB JVM and a GPU context alive. Every measurement taken
 * after that is against a machine that is still busy.
 *
 * <p>The game is a grandchild of the wrapper, so killing the wrapper's own children never reaches
 * it. This command goes to the recorded PID instead, and refuses to signal one it cannot prove is
 * the same process: {@link RuntimeProcessIdentity} requires the live start instant to equal the
 * recorded one, so a reused PID is reported rather than killed.
 */
final class StopCommand {
    private static final String FORMAT = "starsector-preflight-stop-v1";
    private static final int DEFAULT_TIMEOUT_SECONDS = 20;

    private StopCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        boolean json = false;
        boolean force = false;
        boolean dryRun = false;
        Long requestedPid = null;
        int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        for (int index = offset; index < args.length; index++) {
            String argument = args[index];
            switch (argument) {
                case "--json" -> json = true;
                case "--force" -> force = true;
                case "--dry-run" -> dryRun = true;
                case "--pid" -> {
                    if (requestedPid != null) {
                        throw new IllegalArgumentException("--pid was supplied more than once");
                    }
                    requestedPid = positivePid(requireValue(args, ++index, argument), argument);
                }
                case "--timeout-seconds" -> timeoutSeconds =
                        positive(requireValue(args, ++index, argument), argument);
                default -> throw new IllegalArgumentException("Unknown option: " + argument);
            }
        }

        List<RuntimeProcessIdentity> records = selectPid(
                recordedRuns(PreflightHome.current().runs()), requestedPid);
        List<Map<String, Object>> stopped = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (RuntimeProcessIdentity record : records) {
            Map<String, Object> inspection = record.inspect();
            if (!Boolean.TRUE.equals(inspection.get("attachable"))) {
                if (!"runtime record is stopped".equals(inspection.get("reason"))) {
                    skipped.add(outcome(record, "skipped", String.valueOf(inspection.get("reason"))));
                }
                continue;
            }
            if (dryRun) {
                stopped.add(outcome(record, "would-stop", null));
                continue;
            }
            stopped.add(stop(record, timeoutSeconds, force));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("format", FORMAT);
        report.put("inspected", records.size());
        report.put("stopped", stopped);
        report.put("skipped", skipped);
        PrintStream output = System.out;
        if (json) {
            output.println(Json.object(report));
        } else {
            print(output, stopped, skipped, dryRun);
        }
        boolean unresolved = stopped.stream()
                .anyMatch(entry -> "still-running".equals(entry.get("result")));
        return unresolved ? 1 : 0;
    }

    /**
     * The game's own runtime record, newest first.
     *
     * <p>Only runs Preflight itself launched are considered. A Starsector the player started from
     * the game's launcher has no record here and is deliberately left alone -- this command exists
     * to clean up after Preflight, not to close a window someone is using.
     */
    static List<RuntimeProcessIdentity> recordedRuns(Path runs) throws IOException {
        if (!Files.isDirectory(runs)) return List.of();
        List<RuntimeProcessIdentity> records = new ArrayList<>();
        try (Stream<Path> directories = Files.list(runs)) {
            List<Path> sorted = directories
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
            for (Path directory : sorted) {
                Path file = directory.resolve("runtime-process.json");
                if (!Files.isRegularFile(file)) continue;
                try {
                    records.add(RuntimeProcessIdentity.read(file));
                } catch (IOException | IllegalArgumentException unreadable) {
                    // A malformed or partial record from an interrupted run is not a reason to
                    // refuse to stop the others.
                }
            }
        }
        return records;
    }

    static List<RuntimeProcessIdentity> selectPid(
            List<RuntimeProcessIdentity> records, Long requestedPid) {
        if (requestedPid == null) return records;
        return records.stream().filter(record -> record.pid() == requestedPid).toList();
    }

    private static Map<String, Object> stop(
            RuntimeProcessIdentity record, int timeoutSeconds, boolean force) throws Exception {
        ProcessHandle process = ProcessHandle.of(record.pid()).orElse(null);
        if (process == null) return outcome(record, "already-exited", null);
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        // destroy() is SIGTERM on Unix, so the JVM runs its shutdown hooks and Preflight's own
        // run report is finished rather than truncated. Only escalate when asked.
        process.destroy();
        while (process.isAlive() && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        if (!process.isAlive()) {
            return outcome(record, "stopped", null);
        }
        if (!force) {
            return outcome(record, "still-running",
                    "still alive after " + timeoutSeconds + "s; re-run with --force to end it");
        }
        process.destroyForcibly();
        Instant forcedDeadline = Instant.now().plusSeconds(timeoutSeconds);
        while (process.isAlive() && Instant.now().isBefore(forcedDeadline)) {
            Thread.sleep(200);
        }
        return process.isAlive()
                ? outcome(record, "still-running", "did not exit after a forced stop")
                : outcome(record, "forced", null);
    }

    private static Map<String, Object> outcome(
            RuntimeProcessIdentity record, String result, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("pid", record.pid());
        entry.put("source", record.source().toString());
        entry.put("result", result);
        entry.put("reason", reason);
        return entry;
    }

    private static void print(
            PrintStream output,
            List<Map<String, Object>> stopped,
            List<Map<String, Object>> skipped,
            boolean dryRun) {
        if (stopped.isEmpty() && skipped.isEmpty()) {
            output.println("No Starsector process started by Preflight is running.");
            return;
        }
        for (Map<String, Object> entry : stopped) {
            String result = String.valueOf(entry.get("result"));
            String reason = entry.get("reason") == null ? "" : ". " + entry.get("reason");
            output.printf("%s PID %s%s%n", switch (result) {
                case "stopped" -> "Stopped";
                case "forced" -> "Force-stopped";
                case "would-stop" -> "Would stop";
                case "already-exited" -> "Already exited:";
                default -> "Could not stop";
            }, entry.get("pid"), reason);
        }
        for (Map<String, Object> entry : skipped) {
            output.printf("Left PID %s alone: %s%n", entry.get("pid"), entry.get("reason"));
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }

    private static int positive(String value, String option) {
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(option + " must be a whole number of seconds");
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(option + " must be greater than zero");
        }
        return parsed;
    }

    private static long positivePid(String value, String option) {
        long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(option + " must be a positive process ID");
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(option + " must be a positive process ID");
        }
        return parsed;
    }
}

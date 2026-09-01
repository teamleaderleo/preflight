package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.bisect.BisectStatus;
import dev.starsector.preflight.core.bisect.BisectVerdict;
import dev.starsector.preflight.core.bisect.ModBisectSession;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CLI command handler for Mod Bisect Assistant operations:
 * {@code preflight bisect <start|status|test|good|bad|skip|apply|reset>}.
 */
public final class BisectCommand {

    private BisectCommand() {}

    public static int execute(String[] args, int offset) throws Exception {
        if (offset >= args.length || "--help".equals(args[offset]) || "-h".equals(args[offset])) {
            PreflightCli.commandUsage("bisect", System.out);
            return offset >= args.length ? 2 : 0;
        }

        String operation = args[offset];
        Options options = Options.parse(args, offset + 1);
        LaunchTarget target = discover(options.game(), options.launcher());
        PreflightHome home = PreflightHome.current();

        return switch (operation) {
            case "start" -> start(home, target.installRoot(), options.modIds(), options.json(), System.out);
            case "status" -> status(home, target.installRoot(), options.json(), System.out);
            case "test" -> test(home, target.installRoot(), options.json(), System.out);
            case "good", "pass" -> recordVerdict(home, target.installRoot(), "good", options.json(), System.out);
            case "bad", "fail" -> recordVerdict(home, target.installRoot(), "bad", options.json(), System.out);
            case "skip" -> recordVerdict(home, target.installRoot(), "skip", options.json(), System.out);
            case "apply" -> apply(home, target.installRoot(), options.confirmed(), options.json(), System.out);
            case "reset" -> reset(home, target.installRoot(), options.json(), System.out);
            default -> throw new IllegalArgumentException("Expected: bisect <start|status|test|good|bad|skip|apply|reset> ...");
        };
    }

    public static int executeDesktop(String[] args, int offset) throws Exception {
        if (offset >= args.length) {
            throw new IllegalArgumentException("Expected desktop bisect command");
        }
        String operation = args[offset];
        Options options = Options.parse(args, offset + 1);
        LaunchTarget target = discover(options.game(), options.launcher());
        PreflightHome home = PreflightHome.current();

        return switch (operation) {
            case "start" -> start(home, target.installRoot(), options.modIds(), true, System.out);
            case "status" -> status(home, target.installRoot(), true, System.out);
            case "verdict" -> recordVerdict(home, target.installRoot(), options.verdict(), true, System.out);
            case "apply" -> apply(home, target.installRoot(), true, true, System.out);
            case "reset" -> reset(home, target.installRoot(), true, System.out);
            default -> throw new IllegalArgumentException("Unknown desktop bisect command: " + operation);
        };
    }

    static int start(
            PreflightHome home,
            Path installRoot,
            List<String> targetMods,
            boolean json,
            PrintStream out
    ) throws Exception {
        try (var lease = OperationLease.acquire(home, "starting-bisect", installRoot).lease()) {
            ModBisectSession session = ModBisectSession.start(installRoot, home.root(), targetMods);
            if (json) {
                out.println(session.toJson());
            } else {
                out.println("Mod bisect session started (" + session.suspectMods().size() + " suspect mods).");
                out.println("Step 1 of ~" + session.totalEstimatedSteps() + ": testing partition with "
                        + session.currentTestSubset().size() + " mods.");
                out.println("Run `preflight bisect good` or `preflight bisect bad` after test launch.");
            }
            return 0;
        }
    }

    static int status(
            PreflightHome home,
            Path installRoot,
            boolean json,
            PrintStream out
    ) throws Exception {
        Path sessionFile = ModBisectSession.resolveSessionFile(home.root());
        ModBisectSession session = ModBisectSession.load(sessionFile);

        if (session == null) {
            if (json) {
                Map<String, Object> inactive = new LinkedHashMap<>();
                inactive.put("format", ModBisectSession.FORMAT);
                inactive.put("active", false);
                inactive.put("state", BisectStatus.INACTIVE.name());
                out.println(Json.object(inactive));
            } else {
                out.println("No active mod bisect session.");
            }
            return 0;
        }

        if (json) {
            out.println(session.toJson());
        } else {
            out.println("Mod Bisect Session: [" + session.state() + "]");
            out.println("Step " + session.stepNumber() + " of ~" + session.totalEstimatedSteps());
            out.println("Suspects remaining: " + session.suspectMods().size());
            out.println("Active test partition (" + session.currentTestSubset().size() + " mods): " + session.currentTestSubset());
            if (session.candidateCulpritId() != null) {
                out.println("Isolated Culprit: " + session.candidateCulpritId());
            }
        }
        return 0;
    }

    static int test(
            PreflightHome home,
            Path installRoot,
            boolean json,
            PrintStream out
    ) throws Exception {
        Path sessionFile = ModBisectSession.resolveSessionFile(home.root());
        ModBisectSession session = ModBisectSession.load(sessionFile);
        if (session == null || session.state() != BisectStatus.TESTING) {
            throw new IllegalStateException("No active testing bisect session to test");
        }
        if (json) {
            out.println(session.toJson());
        } else {
            out.println("Current test partition ready with " + session.currentTestSubset().size() + " mods.");
        }
        return 0;
    }

    static int recordVerdict(
            PreflightHome home,
            Path installRoot,
            String verdictStr,
            boolean json,
            PrintStream out
    ) throws Exception {
        Path sessionFile = ModBisectSession.resolveSessionFile(home.root());
        ModBisectSession session = ModBisectSession.load(sessionFile);
        if (session == null) {
            throw new IllegalStateException("No active bisect session");
        }

        try (var lease = OperationLease.acquire(home, "advancing-bisect", installRoot).lease()) {
            ModBisectSession updated = session.recordVerdict(verdictStr, home.root());
            if (json) {
                out.println(updated.toJson());
            } else {
                out.println("Verdict recorded: " + verdictStr.toUpperCase(Locale.ROOT));
                if (updated.state() == BisectStatus.CULPRIT_FOUND) {
                    out.println("🎯 Culprit isolated: " + updated.candidateCulpritId());
                    out.println("Run `preflight bisect apply` to disable the culprit mod.");
                } else if (updated.state() == BisectStatus.COMPLETED) {
                    out.println("Bisect complete. No single culprit was found.");
                } else {
                    out.println("Step " + updated.stepNumber() + " of ~" + updated.totalEstimatedSteps()
                            + " (" + updated.suspectMods().size() + " suspects remaining)");
                }
            }
            return 0;
        }
    }

    static int apply(
            PreflightHome home,
            Path installRoot,
            boolean confirmed,
            boolean json,
            PrintStream out
    ) throws Exception {
        Path sessionFile = ModBisectSession.resolveSessionFile(home.root());
        ModBisectSession session = ModBisectSession.load(sessionFile);
        if (session == null || session.state() != BisectStatus.CULPRIT_FOUND) {
            throw new IllegalStateException("Cannot apply when culprit is not isolated");
        }

        if (!confirmed) {
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("format", "starsector-preflight-bisect-apply-v1");
            preview.put("candidateCulpritId", session.candidateCulpritId());
            preview.put("applied", false);
            if (json) {
                out.println(Json.object(preview));
            } else {
                out.println("Preview: will disable '" + session.candidateCulpritId() + "' and restore original mod list. Pass --yes to apply.");
            }
            return 0;
        }

        try (var lease = OperationLease.acquire(home, "applying-bisect-fix", installRoot).lease()) {
            session.apply(true, home.root());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("format", "starsector-preflight-bisect-apply-v1");
            result.put("candidateCulpritId", session.candidateCulpritId());
            result.put("applied", true);
            if (json) {
                out.println(Json.object(result));
            } else {
                out.println("Disabled culprit mod '" + session.candidateCulpritId() + "' and restored remaining mods.");
            }
            return 0;
        }
    }

    static int reset(
            PreflightHome home,
            Path installRoot,
            boolean json,
            PrintStream out
    ) throws Exception {
        Path sessionFile = ModBisectSession.resolveSessionFile(home.root());
        ModBisectSession session = ModBisectSession.load(sessionFile);
        if (session != null) {
            try (var lease = OperationLease.acquire(home, "resetting-bisect", installRoot).lease()) {
                session.reset(home.root());
            }
        }
        if (json) {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("format", "starsector-preflight-bisect-reset-v1");
            res.put("reset", true);
            out.println(Json.object(res));
        } else {
            out.println("Bisect session reset and original enabled mods restored.");
        }
        return 0;
    }

    private static LaunchTarget discover(Path game, Path launcher) throws IOException {
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                Platform.current(),
                Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                game,
                launcher);
        if (discovery.selected() == null) {
            throw new IOException("Could not discover Starsector. Use --game or --launcher.");
        }
        return discovery.selected();
    }

    private record Options(
            Path game,
            Path launcher,
            List<String> modIds,
            String verdict,
            boolean confirmed,
            boolean json
    ) {
        static Options parse(String[] args, int offset) {
            Path game = null;
            Path launcher = null;
            List<String> modIds = new ArrayList<>();
            String verdict = null;
            boolean confirmed = false;
            boolean json = false;

            for (int i = offset; i < args.length; i++) {
                switch (args[i]) {
                    case "--game" -> game = Path.of(requireValue(args, ++i, "--game"));
                    case "--launcher" -> launcher = Path.of(requireValue(args, ++i, "--launcher"));
                    case "--mod", "--suspect" -> modIds.add(requireValue(args, ++i, args[i - 1]));
                    case "--verdict" -> verdict = requireValue(args, ++i, "--verdict");
                    case "--yes", "--confirmed" -> confirmed = true;
                    case "--json" -> json = true;
                    default -> throw new IllegalArgumentException("Unknown bisect option: " + args[i]);
                }
            }

            return new Options(game, launcher, modIds, verdict, confirmed, json);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}

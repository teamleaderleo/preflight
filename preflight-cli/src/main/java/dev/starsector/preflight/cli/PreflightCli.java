package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ContentFingerprint;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

public final class PreflightCli {
    private PreflightCli() {
    }

    public static void main(String[] args) {
        Utf8Console.install();
        try {
            int status = run(args);
            if (status != 0) {
                System.exit(status);
            }
        } catch (Exception error) {
            String message = error.getMessage();
            System.err.println("preflight: " + (message == null || message.isBlank() ? error.toString() : message));
            hintForUnknownOption(args, message);
            if ("1".equals(System.getenv("PREFLIGHT_DEBUG"))) {
                error.printStackTrace();
            } else if (!(error instanceof IllegalArgumentException)) {
                System.err.println("preflight: set PREFLIGHT_DEBUG=1 for a full stack trace");
            }
            System.exit(1);
        }
    }

    static int run(String[] rawArgs) throws Exception {
        String[] args = Utf8Argv.decode(rawArgs);
        if (args.length == 0) {
            globalUsage(System.err);
            return 2;
        }
        if ("--help".equals(args[0]) || "-h".equals(args[0])) {
            globalUsage(System.out);
            return 0;
        }
        if ("help".equals(args[0])) {
            if (args.length == 1) {
                globalUsage(System.out);
                return 0;
            }
            if (args.length == 2 && USAGE.containsKey(args[1])) {
                commandUsage(args[1], System.out);
                return 0;
            }
            if (args.length == 2) {
                return unknownCommand(args[1]);
            }
            System.err.println("preflight: expected `preflight help [command]`");
            return 2;
        }
        if (args.length == 2
                && ("--help".equals(args[1]) || "-h".equals(args[1]))
                && USAGE.containsKey(args[0])) {
            commandUsage(args[0], System.out);
            return 0;
        }

        return switch (args[0]) {
            case "run" -> RunCommand.execute(CommandLine.parse(args, 1));
            case "stop" -> StopCommand.execute(args, 1);
            case "prepare" -> PrepareCommand.execute(args, 1);
            case "doctor" -> RunCommand.doctor(CommandLine.parse(args, 1));
            case "launch-settings" -> LaunchSettingsCommand.execute(args, 1);
            case "install" -> InstallCommand.execute(args, 1);
            case "uninstall" -> UninstallCommand.execute(args, 1);
            case "cache" -> CacheCommand.execute(args, 1);
            case "evidence" -> EvidenceCommand.execute(args, 1);
            case "profile" -> ProfileCommand.execute(args, 1);
            case "scan" -> ScanCommand.execute(ScanOptions.parse(args, 1));
            case "index" -> IndexCommand.execute(args, 1);
            case "texture" -> textureCommand(args);
            case "font" -> FontCommand.execute(args, 1);
            case "assets" -> AssetLabCommand.execute(args, 1);
            case "audio" -> audioCommand(args);
            case "lint" -> AssetLintCommand.execute(args, 1);
            case "classpath" -> ClasspathCommand.execute(args, 1);
            case "benchmark" -> BenchmarkCommand.execute(args, 1);
            case "analyze" -> AnalysisCommand.execute(args, 1);
            case "fingerprint" -> requirePathCommand(args, "fingerprint", PreflightCli::fingerprint);
            case "summarize" -> summarizeCommand(args);
            case "desktop" -> DesktopBridgeCommand.execute(args, 1);
            default -> unknownCommand(args[0]);
        };
    }

    private static int textureCommand(String[] args) throws Exception {
        if (args.length > 1 && "build".equals(args[1])) {
            return TextureBatchCommand.execute(args, 2);
        }
        if (args.length > 1 && "manifest".equals(args[1])) {
            return TextureManifestCommand.execute(args, 2);
        }
        return TextureCommand.execute(args, 1);
    }

    private static int audioCommand(String[] args) throws Exception {
        if (args.length > 1 && "jorbis-equivalence".equals(args[1])) {
            return InstalledJorbisEquivalenceCommand.execute(args, 2);
        }
        if (args.length > 1 && "sound-wrapper-observe".equals(args[1])) {
            return SoundWrapperObservationCommand.execute(args, 2);
        }
        if (args.length > 1 && "prepare".equals(args[1])) {
            return PrepareAudioCommand.execute(args, 2);
        }
        if (args.length > 1 && "census".equals(args[1])) {
            return AudioCensusCommand.execute(args, 2);
        }
        if (args.length > 1 && "decode-probe".equals(args[1])) {
            return AudioDecodeProbeCommand.execute(args, 2);
        }
        throw new IllegalArgumentException(
                "Expected: audio <census|decode-probe|jorbis-equivalence|sound-wrapper-observe> ...");
    }

    private static int requirePathCommand(String[] args, String name, PathCommand command) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected: " + name + " <path>");
        }
        return command.run(Path.of(args[1]));
    }

    private static int summarizeCommand(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Expected: summarize <recording.jfr> [--json <report.json>]");
        }
        return summarize(Path.of(args[1]), outputPath(args));
    }

    private static int fingerprint(Path path) throws IOException {
        System.out.println(ContentFingerprint.compute(path));
        return 0;
    }

    private static Path outputPath(String[] args) {
        if (args.length == 4 && "--json".equals(args[2])) {
            return Path.of(args[3]);
        }
        if (args.length == 2) {
            return null;
        }
        throw new IllegalArgumentException("Expected: summarize <recording.jfr> [--json <report.json>]");
    }

    static int summarize(Path recording, Path output) throws IOException {
        TraceAccumulator trace = new TraceAccumulator();
        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                trace.accept(file.readEvent());
            }
        }

        String json = trace.toJson(recording);
        if (output == null) {
            System.out.println(json);
        } else {
            Path absolute = output.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(absolute, json + System.lineSeparator());
            System.out.println(absolute);
        }
        return 0;
    }

    private static final Map<String, List<String>> USAGE = usageByCommand();

    private static Map<String, List<String>> usageByCommand() {
        Map<String, List<String>> usage = new LinkedHashMap<>();
        usage.put("run", List.of(
                "preflight run [--game <path>] [--launcher <path>] [--direct]"
                        + " [--optimization-preset recommended|conservative|off | --fast]"
                        + " [--no-record | --profile] [--dry-run] [-- <launcher args>]",
                "  Launches Starsector. Installed Preflight launchers use the recommended preset.",
                "  --direct skips the game's launcher and uses its saved display and sound settings.",
                "  --dry-run prints the resolved command and optimization plan without launching.",
                "  --profile records a diagnostic profile. --no-record is the normal fast path."));
        usage.put("prepare", List.of(
                "preflight prepare [--game <path>] [--launcher <path>] [--cache-dir <path>] [--report <path>] [--workers <count>] [--memory-mb <MiB>] [--texture-storage fastest|balanced] [--texture-scope full|learned] [--parallel-stages|--serial-stages] [--deep] [--verify-lookups] [--lookup-queries <count>] [--seed <long>] [--no-resource-index] [--no-classpath] [--no-textures]",
                "preflight prepare --plan [--json] [--game <path>] [--cache-dir <path>] [--workers <count>] [--texture-storage fastest|balanced] [--texture-scope full|learned]",
                "  balanced (default) uses exact lossless LZ4 except where compression saves under"
                        + " 23.1%; fastest stores every upload-ready pixel array raw.",
                "  --plan is read-only. Every real preparation checks its expected temporary"
                        + " requirement before writing, then checks live free space during the build."));
        usage.put("stop", List.of(
                "preflight stop [--pid <process-id>] [--dry-run] [--force] [--timeout-seconds <n>] [--json]",
                "  Stops a Starsector process Preflight started. `preflight run` stays attached"
                        + " until the game exits and never stops it, so a launch driven by hand"
                        + " can outlive its wrapper and hold several GB and a GPU context.",
                "  Only launches with a Preflight runtime record are considered, and a recorded PID"
                        + " is signalled only when its live start instant still matches, so a"
                        + " reused PID is reported instead of killed. A game the player started"
                        + " from Starsector's own launcher is left alone.",
                "  The default stop is a request the game's JVM can act on, so its shutdown hooks"
                        + " run and the run report is finished rather than truncated. --force ends"
                        + " a process that ignored it."));
        usage.put("doctor", List.of(
                "preflight doctor [--game <path>] [--launcher <path>] [--no-scan]",
                "    Reports discovery, then whether the next launch would be accelerated.",
                "    --no-scan omits the decoded-texture working-set summary; `scan` reports it in full."));
        usage.put("launch-settings", List.of(
                "preflight launch-settings [--game <path>] [--json]",
                "preflight launch-settings set [--game <path>] [--resolution WIDTHxHEIGHT]"
                        + " [--fullscreen true|false] [--sound true|false]"
                        + " [--antialiasing 0|2|4|8|12|16|24|32] [--ui-scale 1.00..3.00]"
                        + " [--battle-size <points>] [--memory-mb <MiB>]"
                        + " --confirm-settings-tools-closed [--json]",
                "  Reports and updates Starsector's own global launcher/gameplay preferences. Apply"
                        + " writes only the named keys, snapshots their previous values under Preflight's"
                        + " home first, and preserves unrelated gameplay settings. Battle size is"
                        + " checked against the selected installation's current settings.json bounds.",
                "  Apply is a quiescent boundary. Close Starsector, its launcher, settings editors,"
                        + " and mod managers; keep them closed until Apply finishes, then pass"
                        + " --confirm-settings-tools-closed.",
                "  Preflight's operation lease coordinates Preflight processes only. External"
                        + " programs can still change these global settings.",
                "  Heap memory follows the launcher that will actually run, including fr.vmparams."
                        + " Preflight refuses ambiguous layouts, keeps an exact file backup, and"
                        + " updates both -Xms and -Xmx when the launcher defines both.",
                "  The direct-launch availability fields are required by the startup benchmark: Starsector"
                        + " itself supports launchDirect/startRes/startFS/startSound, and Preflight"
                        + " refuses that unattended path when the game's saved inputs are incomplete."));
        usage.put("install", List.of(
                "preflight install [--game <path>] [--launcher <path>] [--prepare] [--texture-storage fastest|balanced] [--workers <count>] [--memory-mb <MiB>]",
                "  --prepare builds the exact current profile after installing the launcher.",
                "  balanced is the default. Worker and memory controls apply only to preparation;",
                "  without --prepare they are rejected rather than silently ignored."));
        usage.put("uninstall", List.of(
                "preflight uninstall [--scope launcher|all-data] [--json] [--yes]",
                "  Prints an exact removal plan and exits; --yes performs the removal.",
                "  launcher removes installed launch integrations and the installed command engine.",
                "  all-data (or the legacy --purge spelling) also removes ~/.starsector-preflight,",
                "  discarding prepared caches",
                "  and every run and benchmark record under it. The Starsector installation",
                "  is never modified by Preflight, so nothing there needs restoring."));
        usage.put("cache", List.of(
                "preflight cache [--game <path>] [--launcher <path>] [--json]",
                "  Reports total storage by category, the prepared profiles held, and which",
                "  one the current install matches. --json emits the stable desktop/tooling contract.",
                "preflight cache health [--game <path>] [--launcher <path>] [--json]",
                "  Checks the exact current profile's index, texture manifest/pack, and optional",
                "  prepared-audio manifest without reading game, mod, or save contents into output.",
                "preflight cache repair [--game <path>] [--launcher <path>] [--expected-profile <sha256>] [--json] [--yes]",
                "  Plans a profile-scoped repair. --yes removes only unreadable current-profile",
                "  metadata/packs; shared blobs remain available for preparation to reuse or quarantine.",
                "preflight cache prune [--keep-named] [--json] [--yes]",
                "  Removes every profile except the current one; --keep-named also preserves every",
                "  readable named profile. This includes profile texture packs,",
                "  texture and prepared-audio blobs no survivor references, stale Janino contexts,",
                "  and per-request bytecode bundles represented by the retained deduplicated pack.",
                "  Prints the plan and exits unless --yes; --json emits the stable plan contract."));
        usage.put("evidence", List.of(
                "preflight evidence [--json]",
                "  Reports launch-run and benchmark evidence separately from acceleration caches.",
                "preflight evidence export --output <bundle.zip> [--runs <count>] [--benchmarks <count>] [--overwrite] [--json]",
                "  Writes a bounded, disclosed ZIP from allowlisted text metadata only. Defaults to",
                "  the newest three runs and two benchmarks; caches, logs, crash dumps, recordings,",
                "  screenshots, game/mod assets, saves, symlinks, and unknown files are excluded.",
                "  Counts are capped at 20 each. Existing output is refused unless --overwrite.",
                "preflight evidence prune [--keep-runs <count>] [--keep-benchmarks <count>] [--json] [--yes]",
                "  Keeps the newest requested number in each selected category. An omitted category",
                "  is untouched. The plan is preview-only unless --yes; sessions that change while",
                "  the command is running are refused rather than deleting active evidence."));
        usage.put("profile", List.of(
                "preflight profile list [--game <path>] [--launcher <path>] [--json]",
                "preflight profile save <name> [--game <path>] [--launcher <path>] [--json]",
                "preflight profile update <name> [--game <path>] [--launcher <path>] [--expected-profile <sha256>] [--expected-replacement <sha256>] [--json] [--yes]",
                "preflight profile activate <name> [--game <path>] [--launcher <path>] [--json] [--yes]",
                "preflight profile duplicate <name> <new-name> [--game <path>] [--launcher <path>] [--expected-profile <sha256>] [--json] [--yes]",
                "preflight profile rename <name> <new-name> [--game <path>] [--launcher <path>] [--expected-profile <sha256>] [--json] [--yes]",
                "preflight profile delete <name> [--game <path>] [--launcher <path>] [--expected-profile <sha256>] [--json] [--yes]",
                "  Saves and restores ordered enabled-mod sets. Save creates a new canonical name and refuses",
                "  an existing one. Update previews the intentional replacement; applying it requires both",
                "  the exact existing-record token and the reviewed replacement token from that preview.",
                "  Activation prints the exact plan by default; --yes stages and replaces",
                "  mods/enabled_mods.json after backing it up. Duplicate, rename, and delete also preview by default.",
                "  Applying duplicate, rename, or delete requires the exact profile fingerprint from that preview;",
                "  delete keeps prepared data and writes a profile backup. Missing mods or a profile saved for",
                "  another installation are refused."));
        usage.put("scan", List.of(
                "preflight scan [--game <path>] [--launcher <path>] [--json <profile.json>] [--vram-budget <size>] [--max-texture-size <pixels>]",
                "  --vram-budget accepts bytes or a K/M/G suffix (e.g. 4G); adds a decoded-VRAM budget verdict",
                "  --max-texture-size <pixels> (e.g. 2048) projects what capping oversized textures would save"));
        usage.put("index", List.of(
                "preflight index build [--game <path>] [--launcher <path>] [--output <index.spfi>]",
                "preflight index inspect <index.spfi>",
                "preflight index query <index.spfi> <logical-path> [--all]",
                "preflight index validate <index.spfi>"));
        usage.put("texture", List.of(
                "preflight texture prepare <image> [--output <texture.spft>]",
                "preflight texture inspect <texture.spft>",
                "preflight texture verify <image> <texture.spft>",
                "preflight texture benchmark <image> <texture.spft> [--runs <count>]",
                "preflight texture build [--game <path> | --index <index.spfi>] [--cache-dir <path>] [--workers <count>] [--memory-mb <MiB>] [--texture-storage fastest|balanced]",
                "  balanced is the default; fastest trades substantially more disk space for minimum decode CPU",
                "preflight texture manifest inspect <manifest.spfm>",
                "preflight texture manifest query <manifest.spfm> <logical-path> [--cache-dir <path>]",
                "preflight texture manifest validate <manifest.spfm> [--cache-dir <path>]"));
        usage.put("font", List.of(
                "preflight font list-families",
                "preflight font generate (--ttf <font.ttf> | --logical sans-serif|serif|monospaced "
                        + "| --family <installed-family>) "
                        + "--size <px> --name <basename> --out-dir <dir> [--atlas-width <n>] [--padding <n>] "
                        + "[--charset-from <font.fnt> | --ascii | --latin1]",
                "preflight font generate-pack (--ttf <font.ttf> | --logical sans-serif|serif|monospaced "
                        + "| --family <installed-family>) --fonts-dir <graphics/fonts> --out-dir <mod-dir> "
                        + "[--scale <n>] [--atlas-width <n>] [--padding <n>] [--mod-id <id>] [--mod-name <name>] "
                        + "[--game-version <version>]"));
        usage.put("assets", List.of(
                "preflight assets shrink --max-texture-size <pixels> --out-dir <mod-dir> [--game <path>] "
                        + "[--launcher <path>] [--limit <n>] [--dry-run] [--force] [--mod-id <id>] "
                        + "[--mod-name <name>] [--game-version <version>]",
                "  writes capped copies of the enabled profile's oversized textures as a drop-in override mod;",
                "  size the cap first with `preflight scan --vram-budget <size> --max-texture-size <pixels>`",
                "preflight assets bake-blocks --out-dir <cache-dir> [--game <path>] [--launcher <path>] "
                        + "[--max-delta-e <deltaE>] [--limit <n>] [--mips] [--dry-run] [--force]",
                "  bakes the profile's art into a GPU-ready S3TC block cache, keeping only textures whose",
                "  measured loss stays under the gate (default 1.0, the just-noticeable threshold);",
                "  the cache is inert -- nothing reads it yet, so baking changes nothing about how the game loads",
                "preflight assets cache-conformance --cache-dir <cache-dir> --out <vector.bin> "
                        + "[--samples <n>] [--max-bytes <n>]",
                "  exports a sample of a baked cache as a conformance vector, so a real driver can check the",
                "  bytes preflight intends to upload; run it with probe-kits/gpu-capability/block-conformance-probe",
                "preflight assets contact-sheet --out <sheet.png> [--game <path>] [--launcher <path>] "
                        + "[--samples <n>] [--max-delta-e <deltaE>] [--panel <pixels>] [--columns <n>]",
                "  draws the profile's art beside its reconstruction, its error map and the baker's decision;",
                "  the decision is made from filenames, so this is how you check that what it calls a shader",
                "  map is one -- a question no fidelity number can answer. No GPU, display or game needed",
                "preflight assets compression-probe [--game <path>] [--launcher <path>] "
                        + "[--samples <n>] [--all-bc3]",
                "  measures block-compression error on profile art without writing a cache"));
        usage.put("lint", List.of(
                "preflight lint [--game <Starsector directory>] [--mod <mod id>] [--json] [--output <report.json>]",
                "preflight lint --path <mod directory> [--json] [--output <report.json>]"));
        usage.put("audio", List.of(
                "preflight audio census [--game <Starsector directory>] [--output <report.json>] [--csv <sounds.csv>]",
                "preflight audio jorbis-equivalence --jogg <jogg-0.0.7.jar> --jorbis <jorbis-0.0.15.jar> [--output <report.json>]",
                "preflight audio sound-wrapper-observe --game <Starsector directory> --jogg <jogg-0.0.7.jar> --jorbis <jorbis-0.0.15.jar> [--java <game-java>] [--output <report.json>]"));
        usage.put("classpath", List.of(
                "preflight classpath audit [--game <path>] [--launcher <path>] [--json <report.json>]",
                "preflight classpath index build [--game <path>] [--launcher <path>] [--cache-dir <path>]",
                "preflight classpath index inspect <profile.spfc>",
                "preflight classpath index query <profile.spfc> <entry-name> [--all] [--cache-dir <path>]",
                "preflight classpath index validate <profile.spfc> [--cache-dir <path>] [--deep]"));
        usage.put("benchmark", List.of(
                "preflight benchmark lookups [--resource-index <index.spfi>] [--classpath-index <profile.spfc>] [--queries <count>] [--seed <long>]",
                "preflight benchmark scenario --run-id <id> [--scenario-id <id>] --mode <mode> [--iteration <count>] [--profile-fingerprint <sha256>] --process-start <instant> --main-menu-ready <instant> --campaign-ready <instant> --first-combat-ready <instant> --exit-code <code> [--adapter-counter <name=value>] [--cache-counter <name=value>] [--disable-reason <reason>] [--output <benchmark.json>]",
                "preflight benchmark collect <run-directory> --scenario <scenario-result.json> [--output <collected-run.json>]",
                "preflight benchmark compare <scenario-result.json> <scenario-result.json>... [--output <comparison.json>]",
                "preflight benchmark compare-runs <collected-run.json> <collected-run.json>... [--output <campaign.json>]"));
        usage.put("analyze", List.of(
                "preflight analyze probe <adapter.json> <summary.json> [--json <adapter-analysis.json>]"));
        usage.put("fingerprint", List.of("preflight fingerprint <file-or-directory>"));
        usage.put("summarize", List.of("preflight summarize <recording.jfr> [--json <report.json>]"));
        return usage;
    }

    static void commandUsage(String command, java.io.PrintStream output) {
        output.println("Usage:");
        for (String line : USAGE.get(command)) {
            output.println("  " + line);
        }
    }

    private static int unknownCommand(String command) {
        System.err.println("preflight: unknown command `" + command + "`");
        String suggestion = closestCommand(command);
        if (suggestion != null) {
            System.err.println("Did you mean `" + suggestion + "`?");
        }
        System.err.println();
        globalUsage(System.err);
        return 2;
    }

    private static void globalUsage(java.io.PrintStream output) {
        output.println("Usage:");
        output.println("  preflight <command> [options]");
        output.println("  preflight help <command>");
        output.println();
        output.println("Commands:");
        // Widened to whatever the longest command actually is, so adding one that outgrows the
        // column cannot quietly leave a single ragged row in the first thing anyone sees.
        int width = USAGE.keySet().stream().mapToInt(String::length).max().orElse(12);
        for (String command : USAGE.keySet()) {
            output.printf("  %-" + width + "s  %s%n", command, commandSummary(command));
        }
        output.println();
        output.println("Run `preflight <command> --help` for detailed usage.");
        output.println("Set PREFLIGHT_DEBUG=1 to include stack traces for unexpected failures.");
    }

    private static String commandSummary(String command) {
        return switch (command) {
            case "run" -> "Launch Starsector with reviewed optimizations and a bounded run report.";
            case "prepare" -> "Build reusable artifacts for the current enabled profile.";
            case "stop" -> "Stop a Starsector process that Preflight started.";
            case "doctor" -> "Check installation discovery and launch readiness.";
            case "launch-settings" -> "Read or update the game settings used by ordinary and Preflight launches.";
            case "install" -> "Write the local Preflight launcher integration.";
            case "uninstall" -> "Remove the launcher integration, and with --purge the cache too.";
            case "cache" -> "Report what Preflight is storing and which profiles it holds.";
            case "evidence" -> "Report, export, and prune bounded diagnostic evidence.";
            case "profile" -> "Create, update, inspect, rename, delete, and safely activate named enabled-mod profiles.";
            case "scan" -> "Inspect the enabled profile and estimate decoded texture memory.";
            case "index" -> "Build, inspect, query, or validate a resource-provider index.";
            case "texture" -> "Prepare and inspect texture cache artifacts.";
            case "font" -> "List fonts or generate a drop-in bitmap-font pack.";
            case "assets" -> "Measure or generate opt-in asset overlays and block caches.";
            case "lint" -> "Report actionable asset problems without modifying mods.";
            case "audio" -> "Measure audio, prepare decoded PCM, probe decode timing, or run decoder evidence checks.";
            case "classpath" -> "Audit and index enabled mod JARs and classes.";
            case "benchmark" -> "Record, collect, and compare controlled startup runs.";
            case "analyze" -> "Join adapter probes with trace evidence.";
            case "fingerprint" -> "Hash a file or directory deterministically.";
            case "summarize" -> "Convert a startup JFR recording into bounded JSON.";
            default -> "";
        };
    }

    private static String closestCommand(String requested) {
        return Suggestions.closest(requested, USAGE.keySet());
    }

    /**
     * Turns {@code Unknown option: --gmae} into a pointer at {@code --game}.
     *
     * <p>Done here rather than in each parser because every command rejects options from its own
     * switch, and none of them holds a list of what it accepts. The usage text does hold one, it is
     * the same text {@code preflight help} prints, and it cannot drift from the documentation
     * without the documentation being wrong already.
     */
    private static void hintForUnknownOption(String[] rawArgs, String message) {
        if (message == null || !message.startsWith(UNKNOWN_OPTION)) {
            return;
        }
        String[] args = Utf8Argv.decode(rawArgs);
        if (args.length == 0) {
            return;
        }
        List<String> usage = USAGE.get(args[0]);
        if (usage == null) {
            return;
        }
        Set<String> documented = new LinkedHashSet<>();
        Matcher options = DOCUMENTED_OPTION.matcher(String.join(" ", usage));
        while (options.find()) {
            documented.add(options.group());
        }
        String suggestion = Suggestions.closest(message.substring(UNKNOWN_OPTION.length()).trim(), documented);
        if (suggestion != null) {
            System.err.println("Did you mean `" + suggestion + "`?");
        } else {
            System.err.println("Run `preflight help " + args[0] + "` for the options it accepts.");
        }
    }

    private static final String UNKNOWN_OPTION = "Unknown option: ";
    private static final Pattern DOCUMENTED_OPTION = Pattern.compile("--[a-z0-9][a-z0-9-]*");

    @FunctionalInterface
    private interface PathCommand {
        int run(Path path) throws Exception;
    }

    private static final class TraceAccumulator {
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private final IoTraceAttribution io = new IoTraceAttribution();
        private final ImageReadStackAttribution imageReadStacks = new ImageReadStackAttribution();
        private final StartupCodeAttribution code = new StartupCodeAttribution();
        private final StartupCpuAttribution cpu = new StartupCpuAttribution();
        private final JfrRuntimeIdentity runtimeIdentity = new JfrRuntimeIdentity();
        private Instant first;
        private Instant last;
        private long fileReadNanos;
        private long fileWriteNanos;
        private long compilationNanos;
        private long gcPauseNanos;
        private long parkNanos;
        private long sleepNanos;
        private long fileReadBytes;
        private long fileWriteBytes;

        void accept(RecordedEvent event) {
            String name = event.getEventType().getName();
            counts.merge(name, 1L, Long::sum);
            runtimeIdentity.record(event);
            Instant start = event.getStartTime();
            Instant end = event.getEndTime();
            first = first == null || start.isBefore(first) ? start : first;
            last = last == null || end.isAfter(last) ? end : last;

            long duration = event.getDuration().toNanos();
            switch (name) {
                case "jdk.FileRead" -> {
                    long bytes = longField(event, "bytesRead");
                    String path = stringField(event, "path");
                    fileReadNanos += duration;
                    fileReadBytes += bytes;
                    io.recordRead(path, bytes, duration);
                    imageReadStacks.record(event, path, bytes, duration);
                }
                case "jdk.FileWrite" -> {
                    long bytes = longField(event, "bytesWritten");
                    fileWriteNanos += duration;
                    fileWriteBytes += bytes;
                    io.recordWrite(stringField(event, "path"), bytes, duration);
                }
                case "jdk.Compilation" -> {
                    compilationNanos += duration;
                    code.recordCompilation(event, duration);
                }
                case "preflight.AgentStarted" -> {
                    // The directory the recorded process ran in, which is the only thing that gives
                    // its relative read paths a meaning. It can arrive after reads it applies to, so
                    // the attribution folds paths at report time rather than as they come in.
                    if (event.hasField("workingDirectory")) {
                        io.resolveAgainst(event.getString("workingDirectory"));
                    }
                }
                case "jdk.ClassDefine" -> code.recordClassDefine(event);
                case "jdk.ExecutionSample" -> cpu.record(event);
                case "jdk.GCPhasePause" -> gcPauseNanos += duration;
                case "jdk.ThreadPark" -> parkNanos += duration;
                case "jdk.ThreadSleep" -> sleepNanos += duration;
                default -> {
                }
            }
        }

        String toJson(Path source) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("source", source.toAbsolutePath().normalize().toString());
            values.put("traceStart", first);
            values.put("traceEnd", last);
            values.put("traceDurationMs", first == null || last == null ? 0 : Duration.between(first, last).toMillis());
            values.put("fileReadMs", nanosToMillis(fileReadNanos));
            values.put("fileReadBytes", fileReadBytes);
            values.put("fileWriteMs", nanosToMillis(fileWriteNanos));
            values.put("fileWriteBytes", fileWriteBytes);
            values.put("compilationMs", nanosToMillis(compilationNanos));
            values.put("gcPauseMs", nanosToMillis(gcPauseNanos));
            values.put("threadParkMs", nanosToMillis(parkNanos));
            values.put("threadSleepMs", nanosToMillis(sleepNanos));
            values.put("classLoadEvents", counts.getOrDefault("jdk.ClassLoad", 0L));
            values.put("classDefineEvents", counts.getOrDefault("jdk.ClassDefine", 0L));
            values.put("executionSamples", counts.getOrDefault("jdk.ExecutionSample", 0L));
            values.put("preflightAgentStartedEvents", counts.getOrDefault("preflight.AgentStarted", 0L));
            values.put("recordingRuntimeIdentity", runtimeIdentity.toMap());
            values.put("ioAttribution", io.toMap());
            values.put("imageReadStackAttribution", imageReadStacks.toMap());
            values.put("codeAttribution", code.toMap());
            values.put("cpuAttribution", cpu.toMap());
            values.put("eventTypeCounts", counts);
            return Json.object(values);
        }

        private static long longField(RecordedEvent event, String field) {
            return event.hasField(field) ? Math.max(0, event.getLong(field)) : 0L;
        }

        private static String stringField(RecordedEvent event, String field) {
            if (!event.hasField(field)) {
                return null;
            }
            try {
                return event.getString(field);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private static double nanosToMillis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }
}

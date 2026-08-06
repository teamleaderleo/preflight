package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ContentFingerprint;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

public final class PreflightCli {
    private PreflightCli() {
    }

    public static void main(String[] args) {
        try {
            int status = run(args);
            if (status != 0) {
                System.exit(status);
            }
        } catch (Exception error) {
            String message = error.getMessage();
            System.err.println("preflight: " + (message == null || message.isBlank() ? error.toString() : message));
            if ("1".equals(System.getenv("PREFLIGHT_DEBUG"))) {
                error.printStackTrace();
            } else if (!(error instanceof IllegalArgumentException)) {
                System.err.println("preflight: set PREFLIGHT_DEBUG=1 for a full stack trace");
            }
            System.exit(1);
        }
    }

    static int run(String[] args) throws Exception {
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
            case "prepare" -> PrepareCommand.execute(args, 1);
            case "doctor" -> RunCommand.doctor(CommandLine.parse(args, 1));
            case "launch-settings" -> LaunchSettingsCommand.execute(args, 1);
            case "install" -> InstallCommand.execute(CommandLine.parse(args, 1));
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
                "preflight run [--game <path>] [--launcher <path>] [--direct] [--fast] [--file-only-logs | --quiet-logs] [--trace-dir <path>] [--dry-run] [--no-summary] [--no-scan] [--adapter-probe | --adapter | --no-adapter] [--adapter-targets <path>] [--campaign-entity-index | --no-campaign-entity-index] [--startup-phase-probe] [--graphicslib-compact-replay] [--janino-bytecode-cache] [--graphicslib-insignia-cache] [--texture-auto [--texture-cache-dir <path>] | --texture-cache-dir <path> --texture-manifest <path> --texture-index <path>] [--texture-mode compatibility|prepared-pixels [--prepared-unpadded | --prepared-npot]] [--no-record | --profile] [--single-chunk-recording] [-- <launcher args>]",
                "    --direct starts Starsector through its own launchDirect path without showing the"
                        + " launcher. Resolution, fullscreen and sound come from the launcher's saved"
                        + " preferences; the run fails closed if those settings are unavailable or unsafe.",
                "    --fast enables every startup and gameplay cache that has passed its live gate,"
                        + " disables profiling and duplicate-console overhead, and leaves each exact adapter fail-closed."
                        + " Installed Preflight launchers use this preset by default.",
                "    --suppress-asset-progress-logs removes only the reviewed vanilla per-file"
                        + " weapon, projectile, hull, and variant INFO messages. Errors, warnings,"
                        + " and phase summaries remain; it is included by --fast and can be"
                        + " reversed by a later --full-asset-progress-logs.",
                "    --trust-validated-texture-index treats the complete configure-time provider"
                        + " validation as this launch's immutable source snapshot, avoiding one"
                        + " source filesystem round trip per texture. It is included by --fast"
                        + " and can be reversed by a later --recheck-texture-sources.",
                "    --file-only-logs keeps synchronous, crash-safe INFO writes in starsector.log"
                        + " but removes the duplicate console appender. It is included by --fast.",
                "    --quiet-logs keeps every INFO line in the rolling starsector.log, removes the"
                        + " duplicate console appender, and buffers file writes. It saves about 0.40s"
                        + " on the reviewed profile but a hard crash can lose the final 64 KiB, so it"
                        + " remains an explicit upgrade over --fast's unbuffered file-only mode.",
                "    --graphicslib-compact-replay replaces only the exact reviewed GraphicsLib"
                        + " 1.12.1 TextureData class with its measured compact normal-map request"
                        + " replay. Any class, archive, or classloader drift retains the mod's"
                        + " original bytes and is reported in adapter-health.json.",
                "    --janino-bytecode-cache reuses Janino's complete generated-class map only"
                        + " under the exact reviewed compiler, classpath, source graph, JVM, debug,"
                        + " parent-loader, and protection-domain policy. It is included by --fast"
                        + " after a clean cold/learn and warm/hit live gate.",
                "    --graphicslib-insignia-cache memoizes getFleetManager results only within one"
                        + " exact GraphicsLib 1.12.1 insignia UI render. It changes no render math"
                        + " and is included by --fast after a live combat pilot served repeated"
                        + " owners without an adapter failure.",
                "    --adapter also validates the exact refit simulator's merged opponent list"
                        + " against Starsector's loaded ship and fighter registries. Nonexistent"
                        + " mod entries are omitted from that simulator invocation and reported;"
                        + " registry uncertainty retains the original list. It also isolates"
                        + " vanilla's one-shot mod resource hint per loading thread, preventing"
                        + " another thread from making an existing resource appear missing. On"
                        + " exact MagicLib 1.5.6 it also answers paintjob unlock checks from the"
                        + " mod's authoritative set instead of copying and scanning a list.",
                "    --no-record runs the caches without recording a startup profile. The profile costs"
                        + " roughly a quarter of startup, so this is the mode to launch with when you"
                        + " want the speed and not the measurement; analysis commands need a recording.",
                "    --prepared-unpadded serves textures at their true size instead of padded up to a"
                        + " power of two, which both lets the bridge carry them and drops the padding"
                        + " entirely -- 1.86 GiB allocated and never sampled on the reviewed profile."
                        + " Needs a driver that accepts non-power-of-two uploads.",
                "    --prepared-npot is the conservative alternative: it carries the same textures while"
                        + " keeping the power-of-two padding, so it needs nothing of the driver. Use it"
                        + " to separate a broken conversion bypass from broken padding removal. Without"
                        + " one of these two the bridge declines every texture that needs padding, which"
                        + " on a normal profile is most of them, and the mode does almost nothing.",
                "    --profile records execution sampling and blocking only, dropping the stack-traced"
                        + " class loads and file reads that make up most of that cost. Use it to ask"
                        + " where startup time goes: the full recording lands hardest on class loading,"
                        + " so it cannot answer whether class loading is worth attacking.",
                "    --single-chunk-recording gives JFR 256 MiB for one timestamp-coherent chunk and"
                        + " disables periodic sidecar dumps, which themselves rotate chunks. Use it"
                        + " when the order and timing of startup events matters. It spends extra memory"
                        + " and gives up crash/force-quit sidecar recovery; --no-record is incompatible.",
                "    --campaign-entity-index enables the BaseLocation.getEntityById index. It"
                        + " requires --adapter, serves only snapshot-validated hits and misses, and"
                        + " fails open on any validation error. The exact mutation-tracked deployment"
                        + " icon cache and per-commodity event-mod memo use the same gameplay-cache"
                        + " switch; --fast enables all three."));
        usage.put("prepare", List.of(
                "preflight prepare [--game <path>] [--launcher <path>] [--cache-dir <path>] [--report <path>] [--workers <count>] [--memory-mb <MiB>] [--texture-storage fastest|balanced] [--deep] [--verify-lookups] [--lookup-queries <count>] [--seed <long>] [--no-resource-index] [--no-classpath] [--no-textures]",
                "  balanced (default) uses exact lossless LZ4 except where compression saves under"
                        + " 23.1%; fastest stores every upload-ready pixel array raw"));
        usage.put("doctor", List.of("preflight doctor [--game <path>] [--launcher <path>]"));
        usage.put("launch-settings", List.of(
                "preflight launch-settings [--json]",
                "  Reports whether Starsector can be started without showing its launcher, and with"
                        + " which resolution, fullscreen and sound settings. The game supports this"
                        + " itself through its launchDirect/startRes/startFS/startSound properties;"
                        + " the settings are read from the launcher's own preferences so an unattended"
                        + " launch matches a clicked one. Always exits zero -- unavailable is an"
                        + " answer, with a reason saying what to do about it."));
        usage.put("install", List.of("preflight install [--game <path>] [--launcher <path>]"));
        usage.put("uninstall", List.of(
                "preflight uninstall [--purge] [--yes]",
                "  Prints what it would remove and exits; --yes performs the removal.",
                "  --purge also removes ~/.starsector-preflight, discarding prepared caches",
                "  and every run and benchmark record under it. The Starsector installation",
                "  is never modified by Preflight, so nothing there needs restoring."));
        usage.put("cache", List.of(
                "preflight cache [--json]",
                "  Reports total storage by category, the prepared profiles held, and which",
                "  one the current install matches. --json emits the stable desktop/tooling contract.",
                "preflight cache prune [--keep-named] [--json] [--yes]",
                "  Removes every profile except the current one; --keep-named also preserves every",
                "  readable named profile. This includes profile texture packs,",
                "  texture and prepared-audio blobs no survivor references, stale Janino contexts,",
                "  and per-request bytecode bundles represented by the retained deduplicated pack.",
                "  Prints the plan and exits unless --yes; --json emits the stable plan contract."));
        usage.put("evidence", List.of(
                "preflight evidence [--json]",
                "  Reports launch-run and benchmark evidence separately from acceleration caches.",
                "preflight evidence prune [--keep-runs <count>] [--keep-benchmarks <count>] [--json] [--yes]",
                "  Keeps the newest requested number in each selected category. An omitted category",
                "  is untouched. The plan is preview-only unless --yes; sessions that change while",
                "  the command is running are refused rather than deleting active evidence."));
        usage.put("profile", List.of(
                "preflight profile list [--game <path>] [--launcher <path>] [--json]",
                "preflight profile save <name> [--game <path>] [--launcher <path>] [--json]",
                "preflight profile activate <name> [--game <path>] [--launcher <path>] [--json] [--yes]",
                "  Saves and restores ordered enabled-mod sets. Activation prints the exact plan",
                "  by default; --yes stages and replaces mods/enabled_mods.json after backing it up.",
                "  Missing mods or a profile saved for another installation are refused."));
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
        for (String command : USAGE.keySet()) {
            output.printf("  %-12s %s%n", command, commandSummary(command));
        }
        output.println();
        output.println("Run `preflight <command> --help` for detailed usage.");
        output.println("Set PREFLIGHT_DEBUG=1 to include stack traces for unexpected failures.");
    }

    private static String commandSummary(String command) {
        return switch (command) {
            case "run" -> "Launch Starsector with bounded profiling and optional adapters.";
            case "prepare" -> "Build reusable artifacts for the current enabled profile.";
            case "doctor" -> "Check installation discovery and launch readiness.";
            case "launch-settings" -> "Report whether the game can start without showing its launcher.";
            case "install" -> "Write the local Preflight launcher integration.";
            case "uninstall" -> "Remove the launcher integration, and with --purge the cache too.";
            case "cache" -> "Report what Preflight is storing and which profiles it holds.";
            case "profile" -> "Save, inspect, and safely activate named enabled-mod profiles.";
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
        String closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (String command : USAGE.keySet()) {
            int distance = editDistance(requested, command);
            if (distance < closestDistance) {
                closest = command;
                closestDistance = distance;
            }
        }
        int threshold = Math.max(1, Math.min(3, requested.length() / 2));
        return closestDistance <= threshold ? closest : null;
    }

    private static int editDistance(String left, String right) {
        int[] prior = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            prior[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = prior[j - 1]
                        + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(
                        Math.min(prior[j] + 1, current[j - 1] + 1),
                        substitution);
            }
            int[] swap = prior;
            prior = current;
            current = swap;
        }
        return prior[right.length()];
    }

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

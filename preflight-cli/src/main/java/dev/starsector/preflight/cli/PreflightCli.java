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
        if (args.length == 0
                || "help".equals(args[0])
                || "--help".equals(args[0])
                || "-h".equals(args[0])) {
            if (args.length >= 2 && "help".equals(args[0]) && USAGE.containsKey(args[1])) {
                commandUsage(args[1], System.out);
                return 0;
            }
            usage();
            return args.length == 0 ? 2 : 0;
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
            case "install" -> InstallCommand.execute(CommandLine.parse(args, 1));
            case "scan" -> ScanCommand.execute(ScanOptions.parse(args, 1));
            case "index" -> IndexCommand.execute(args, 1);
            case "texture" -> textureCommand(args);
            case "font" -> FontCommand.execute(args, 1);
            case "assets" -> AssetLabCommand.execute(args, 1);
            case "audio" -> audioCommand(args);
            case "classpath" -> ClasspathCommand.execute(args, 1);
            case "benchmark" -> BenchmarkCommand.execute(args, 1);
            case "analyze" -> AnalysisCommand.execute(args, 1);
            case "fingerprint" -> requirePathCommand(args, "fingerprint", PreflightCli::fingerprint);
            case "summarize" -> summarizeCommand(args);
            default -> {
                usage();
                yield 2;
            }
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
        throw new IllegalArgumentException(
                "Expected: audio <jorbis-equivalence|sound-wrapper-observe> ...");
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
                "preflight run [--game <path>] [--launcher <path>] [--trace-dir <path>] [--dry-run] [--no-summary] [--no-scan] [--adapter-probe | --adapter | --no-adapter] [--adapter-targets <path>] [--texture-auto [--texture-cache-dir <path>] | --texture-cache-dir <path> --texture-manifest <path> --texture-index <path>] [--texture-mode compatibility|prepared-pixels] [-- <launcher args>]"));
        usage.put("prepare", List.of(
                "preflight prepare [--game <path>] [--launcher <path>] [--cache-dir <path>] [--report <path>] [--workers <count>] [--memory-mb <MiB>] [--deep] [--verify-lookups] [--lookup-queries <count>] [--seed <long>] [--no-resource-index] [--no-classpath] [--no-textures]"));
        usage.put("doctor", List.of("preflight doctor [--game <path>] [--launcher <path>]"));
        usage.put("install", List.of("preflight install [--game <path>] [--launcher <path>]"));
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
                "preflight texture build [--game <path> | --index <index.spfi>] [--cache-dir <path>] [--workers <count>] [--memory-mb <MiB>]",
                "preflight texture manifest inspect <manifest.spfm>",
                "preflight texture manifest query <manifest.spfm> <logical-path> [--cache-dir <path>]",
                "preflight texture manifest validate <manifest.spfm> [--cache-dir <path>]"));
        usage.put("font", List.of(
                "preflight font generate (--ttf <font.ttf> | --logical sans-serif|serif|monospaced) "
                        + "--size <px> --name <basename> --out-dir <dir> [--atlas-width <n>] [--padding <n>] "
                        + "[--charset-from <font.fnt> | --ascii | --latin1]",
                "preflight font generate-pack --ttf <font.ttf> --fonts-dir <graphics/fonts> --out-dir <mod-dir> "
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
                "  bytes preflight intends to upload; run it with probe-kits/gpu-capability/block-conformance-probe"));
        usage.put("audio", List.of(
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

    private static void commandUsage(String command, java.io.PrintStream output) {
        output.println("Usage:");
        for (String line : USAGE.get(command)) {
            output.println("  " + line);
        }
    }

    private static void usage() {
        System.err.println("Usage:");
        for (List<String> lines : USAGE.values()) {
            for (String line : lines) {
                System.err.println("  " + line);
            }
        }
        System.err.println();
        System.err.println("Run `preflight <command> --help` for one command's usage.");
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

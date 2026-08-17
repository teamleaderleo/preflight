package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

final class RunCommand {
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);

    private RunCommand() {
    }

    static int execute(CommandLine options) throws Exception {
        Platform platform = Platform.current();
        Path home = Path.of(System.getProperty("user.home"));
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                platform,
                home,
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                options.game(),
                options.launcher());
        LaunchTarget target = discovery.selected();
        if (target == null) {
            printDiscovery(discovery);
            return 3;
        }
        ModCompatibilityPrecheck.Result modReadiness =
        ModCompatibilityPrecheck.inspect(target.installRoot(), target);
    ModCompatibilityPresenter.print(modReadiness, System.err);
        return executeSelected(options, discovery, target, platform);
    }

    private static int executeSelected(
            CommandLine options,
            DiscoveryResult discovery,
            LaunchTarget target,
            Platform platform) throws Exception {
        OperationLease.Acquisition operationOwnership = options.dryRun()
                ? null
                : OperationLease.acquire(PreflightHome.current(), "launching", target.installRoot());
        if (operationOwnership != null && operationOwnership.recovered() != null) {
            System.err.println("Preflight recovered ownership left by interrupted "
                    + operationOwnership.recovered().operation() + " process "
                    + operationOwnership.recovered().pid() + "; removed "
                    + operationOwnership.recoveredTemporaryFiles() + " incomplete temporary files.");
        }
        try (OperationLease ignored = operationOwnership == null ? null : operationOwnership.lease()) {
            return executeOwned(options, discovery, target, platform);
        }
    }

    private static int executeOwned(
            CommandLine options,
            DiscoveryResult discovery,
            LaunchTarget target,
            Platform platform) throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        CombatJvmSafeguard.Resolution combatJvmSafeguard =
                CombatJvmSafeguard.resolve(platform, target, System.getenv());
        LaunchOwnership ownership = LaunchOwnership.detect(target);
        boolean janinoCacheOwned = options.janinoBytecodeCache() && !ownership.fastRendering();
        if (options.janinoBytecodeCache() && ownership.fastRendering()) {
            System.out.println("Preflight left Janino compilation to Fast Rendering's custom "
                    + "system classloader (" + String.join(", ", ownership.evidence()) + ").");
        }
        LaunchCacheContexts.Result cacheContexts =
                LaunchCacheContexts.select(options, target, janinoCacheOwned);
        LaunchCacheContexts.Texture textureContext = cacheContexts.texture();
        LaunchCacheContexts.VariantJson variantJsonCache = cacheContexts.variantJson();
        LaunchCacheContexts.WeaponJson weaponJsonCache = cacheContexts.weaponJson();
        LaunchCacheContexts.ProjectileJson projectileJsonCache = cacheContexts.projectileJson();
        LaunchCacheContexts.HullJson hullJsonCache = cacheContexts.hullJson();
        LaunchCacheContexts.RulesCsv rulesCsvCache = cacheContexts.rulesCsv();
        LaunchCacheContexts.RuleCommand ruleCommandCache = cacheContexts.ruleCommand();
        LaunchCacheContexts.MergedRead mergedReadCache = cacheContexts.mergedRead();
        LaunchCacheContexts.Janino janinoBytecodeCache = cacheContexts.janino();
        DirectLaunchSettings directSettings = directLaunchSettings(options);

        Path runDirectory = options.traceDirectory() == null
                ? defaultRunDirectory(home, Instant.now(), UUID.randomUUID().toString().substring(0, 8))
                : options.traceDirectory().toAbsolutePath().normalize();
        Path recording = runDirectory.resolve("startup.jfr");
        Path report = runDirectory.resolve("summary.json");
        Path adapterReport = runDirectory.resolve("adapter.json");
        Path adapterHealth = runDirectory.resolve("adapter-health.json");
        Path adapterAnalysis = runDirectory.resolve("adapter-analysis.json");
        Path metadata = runDirectory.resolve("run.json");
        Path profile = runDirectory.resolve("profile.json");
        Path console = runDirectory.resolve("console.txt");
        Path logConfiguration = options.fileOnlyLogs()
                ? QuietLogConfiguration.path(runDirectory, options.quietLogs())
                : null;
        Path agentJar = SelfJar.locate();
        // The identity below is the installed JAR's; only the path the child JVM must read itself
        // is restated, and only when the system's encoding would otherwise destroy it.
        Path injectedAgentJar = AgentJarStaging.readableByTheChildJvm(agentJar);

        // Prepared audio is served only when the cache the bake wrote is present *and* the decoder
        // that baked it is still the decoder installed. A current, fully content-validated manifest
        // additionally enables path lookup; without one the original exact byte-hash lookup remains.
        LaunchCacheContexts.PreparedAudio preparedAudio = cacheContexts.preparedAudio();
        Path preparedAudioCache = preparedAudio == null ? null : preparedAudio.cacheRoot();
        String audioDecoderIdentity = preparedAudio == null ? null : preparedAudio.decoderIdentity();
        String javaToolOptions = AgentLaunchConfig.builder(injectedAgentJar, recording)
                .adapterMode(options.adapterMode())
                .adapterReport(adapterReport)
                .adapterTargets(options.adapterTargets())
                .textureCacheDirectory(textureContext == null ? null : textureContext.cacheDirectory())
                .textureManifest(textureContext == null ? null : textureContext.manifest())
                .textureIndex(textureContext == null ? null : textureContext.index())
                .textureAdapterMode(options.textureAdapterMode())
                .exhaustiveFileReads(options.exhaustiveFileReads())
                .recordingMode(options.recordingMode())
                .npotDirect(options.npotDirect())
                .unpadded(options.unpadded())
                .singleChunkRecording(options.singleChunkRecording())
                .campaignEntityIndex(options.campaignEntityIndex())
                .startupPhaseProbe(options.startupPhaseProbe())
                .variantJsonCache(variantJsonCache == null ? null : variantJsonCache.artifact())
                .weaponJsonCache(weaponJsonCache == null ? null : weaponJsonCache.artifact())
                .projectileJsonCache(projectileJsonCache == null ? null : projectileJsonCache.artifact())
                .hullJsonCache(hullJsonCache == null ? null : hullJsonCache.artifact())
                .rulesCsvCache(rulesCsvCache == null ? null : rulesCsvCache.artifact())
                .ruleTokenCache(options.ruleTokenCache())
                .ruleCommandClassCache(ruleCommandCache == null ? null : ruleCommandCache.artifact())
                .resourceProbeCache(options.resourceProbeCache())
                .loadJsonMemo(options.loadJsonMemo())
                .preparedAudioCache(preparedAudioCache)
                .audioDecoderIdentity(audioDecoderIdentity)
                .preparedAudioManifest(preparedAudio == null ? null : preparedAudio.manifest())
                .preparedAudioManifestIdentity(
                        preparedAudio == null ? null : preparedAudio.manifestIdentity())
                .mergedReadCache(mergedReadCache == null ? null : mergedReadCache.artifact())
                .quietLogs(options.quietLogs())
                .graphicsLibCompactReplay(options.graphicsLibCompactReplay())
                .janinoBytecodeCache(
                        janinoBytecodeCache == null ? null : janinoBytecodeCache.cacheRoot())
                .janinoBytecodeContext(
                        janinoBytecodeCache == null ? null : janinoBytecodeCache.contextToken())
                .graphicsLibInsigniaManagerCache(options.graphicsLibInsigniaManagerCache())
                .adapterPlanScope(options.adapterPlanScope())
                .build()
                .appendTo(System.getenv("JAVA_TOOL_OPTIONS"));
        if (directSettings != null) {
            javaToolOptions = appendJavaOptions(javaToolOptions, directSettings.javaOptions());
        }
        if (logConfiguration != null) {
            javaToolOptions = appendJavaOptions(
                    javaToolOptions,
                    List.of(QuietLogConfiguration.javaOption(logConfiguration)));
        }
        if (options.suppressAssetProgressLogs()) {
            javaToolOptions = appendJavaOptions(
                    javaToolOptions,
                    List.of("-Dpreflight.assetProgressLogs=off"));
        }
        if (options.trustValidatedTextureIndex()) {
            javaToolOptions = appendJavaOptions(
                    javaToolOptions,
                    List.of("-Dpreflight.texture.trustValidatedIndex=true"));
        }
        if (options.desktopSmoke()) {
            javaToolOptions = appendJavaOptions(
                    javaToolOptions,
                    List.of("-Dpreflight.desktopSmoke=true", "-Dpreflight.frameTimes=true"));
        }
        String javaOptions = CombatJvmSafeguard.appendOptions(
                System.getenv("_JAVA_OPTIONS"), combatJvmSafeguard);

        List<String> command = new ArrayList<>(target.command());
        command.addAll(options.forwardedArgs());
        printPlan(
                target,
                runDirectory,
                adapterReport,
                command,
                javaToolOptions,
                discovery,
                options,
                textureContext,
                directSettings,
                janinoBytecodeCache,
                combatJvmSafeguard,
                javaOptions);
        if (options.dryRun()) {
            return 0;
        }

        RunIdentity runIdentity = RunIdentity.capture(agentJar);
        String launchId = LaunchIdentity.fresh();
        Files.createDirectories(runDirectory);
        if (logConfiguration != null) {
            QuietLogConfiguration.write(logConfiguration, options.quietLogs());
        }
        // The census is a third full walk of the same 61,693 files -- 854ms on the reviewed profile
        // -- and nothing about the launch reads its output. It writes profile.json, which is a
        // report a human looks at afterwards. Leaving it here meant the game could not start until
        // a diagnostic had finished, so it runs beside the game instead and is collected when the
        // run is written up. A census that fails, or that is still running when the game exits,
        // costs the run its profile.json and nothing else.
        Future<Path> census = options.scan()
                ? censusInBackground(target.installRoot(), profile)
                : CompletableFuture.completedFuture(null);

        Instant started = Instant.now();
        // Wall clock labels the launch; the monotonic clock measures it. Instant.now() twice is a
        // subtraction of two readings that NTP or a user is free to move underneath us, and it can
        // come back short, long, or negative -- across a session long enough to be worth counting,
        // a correction landing mid-session is exactly when it would happen. nanoTime cannot move.
        long startedNanos = System.nanoTime();
        Long measuredElapsedMillis = null;
        Instant ended = null;
        Integer exitCode = null;
        Integer launcherExitCode = null;
        String outcome = "RUNNING";
        String executionFailure = null;
        StarsectorRunLogEvidence.Evidence lifecycleEvidence = null;
        ChildProcessOutput.Result childOutput = null;
        List<String> postprocessingFailures = new ArrayList<>();
        StarsectorRunLogEvidence.Snapshot logSnapshot = StarsectorRunLogEvidence.snapshot(target.installRoot());
        try {
            writeMetadata(
                    metadata, target, command, runIdentity, launchId, started, null, null, null, null, outcome, null,
                    null, options, directSettings, textureContext, adapterReport, adapterAnalysis, console, null,
                    postprocessingFailures, null, combatJvmSafeguard);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(target.workingDirectory().toFile());
            builder.environment().put("JAVA_TOOL_OPTIONS", javaToolOptions);
            if (javaOptions != null && !javaOptions.isBlank()) {
                builder.environment().put("_JAVA_OPTIONS", javaOptions);
            }
            builder.environment().put("PREFLIGHT_RUN_DIR", runDirectory.toString());

            try (LaunchHeartbeat ignored = LaunchHeartbeat.start(
                    runDirectory, launchId, started, startedNanos,
                    textureContext == null ? null : textureContext.profileFingerprint());
                    DesktopRunEvents desktopEvents = DesktopRunEvents.watch(
                    adapterReport.resolveSibling("runtime-process.json"),
                    System.getenv(),
                    System.err)) {
                childOutput = ChildProcessOutput.run(builder, console);
            }
            launcherExitCode = childOutput.exitCode();
            lifecycleEvidence = StarsectorRunLogEvidence.inspect(logSnapshot, childOutput);
            exitCode = StarsectorRunLogEvidence.effectiveExitCode(launcherExitCode, lifecycleEvidence);
            outcome = lifecycleEvidence.fatalDetected()
                    ? "FATAL_LOG_EVIDENCE"
                    : launcherExitCode == 0 ? "COMPLETED" : "LAUNCHER_EXIT_NONZERO";
            if (lifecycleEvidence.fatalDetected()) {
                System.err.println("Preflight detected fatal Starsector lifecycle evidence in logs or child console."
                        + " Launcher exit " + launcherExitCode + " is not a clean game exit.");
            }

            // Before anything reads the recording. The JVM's exit dump and the agent's sidecar are
            // two writes of the same run that race inside the process and cannot race out here.
            try {
                RecordingSidecar.Result reconciled = RecordingSidecar.reconcile(recording);
                if (reconciled != null && reconciled.promoted()) {
                    System.out.println("Preflight kept the in-flight recording ("
                            + reconciled.sidecarBytes() + " bytes); the exit dump held only "
                            + reconciled.recordingBytes()
                            + (reconciled.displaced() == null
                                    ? " bytes" : " bytes and is at " + reconciled.displaced()));
                }
            } catch (Exception error) {
                addPostprocessingFailure(postprocessingFailures, "recording-sidecar", error);
                System.err.println("Preflight could not reconcile the recording sidecar: " + message(error));
            }

            // A multi-chunk recording folds every later chunk's events back into the first chunk's
            // window, so a run that looks like it stopped early has not lost anything -- its
            // timestamps just cannot be read at face value. Say so, loudly, rather than letting the
            // next analysis quietly assume otherwise.
            if (Files.isRegularFile(recording)) {
                try {
                    RecordingCoverage.Result coverage = RecordingCoverage.inspect(recording);
                    if (!coverage.timestampsTrustworthy()) {
                        System.err.println("Preflight recording holds " + coverage.chunks()
                                + " chunks; its events claim a window of "
                                + coverage.eventSpan().toSeconds() + "s, which is not the run."
                                + " Timestamps across chunks are not comparable -- split it first:"
                                + " jfr disassemble --max-chunks 1 --output <dir> " + recording);
                        if (options.singleChunkRecording()) {
                            postprocessingFailures.add(
                                    "single-chunk-recording: expected one JFR chunk, found " + coverage.chunks());
                        }
                    } else if (options.singleChunkRecording()) {
                        System.out.println("Preflight recording is one chunk; timestamps are comparable across startup.");
                    }
                } catch (Exception error) {
                    addPostprocessingFailure(postprocessingFailures, "recording-coverage", error);
                    System.err.println("Preflight could not inspect recording coverage: " + message(error));
                }
            }

            if (options.summarize() && Files.isRegularFile(recording)) {
                try {
                    PreflightCli.summarize(recording, report);
                    System.out.println("Preflight report: " + report);
                } catch (Exception error) {
                    addPostprocessingFailure(postprocessingFailures, "summary", error);
                    System.err.println("Preflight summary skipped: " + message(error));
                }
            } else if (!Files.exists(recording)) {
                System.err.println("Preflight recording was not created. Run `doctor` and inspect the selected launcher.");
            }
            if (Files.isRegularFile(adapterReport)) {
                System.out.println("Preflight adapter report: " + adapterReport);
                try {
                    AdapterHealthReport.Result health = AdapterHealthReport.analyze(adapterReport, adapterHealth);
                    System.out.println("Preflight adapter health: " + health.status() + " — " + health.summary());
                    System.out.println("Preflight adapter health report: " + adapterHealth);
                } catch (Exception error) {
                    addPostprocessingFailure(postprocessingFailures, "adapter-health", error);
                    System.err.println("Preflight adapter health skipped: " + message(error));
                }
                Path startupPhases = adapterReport.resolveSibling("adapter-startup-phases.json");
                if (Files.isRegularFile(startupPhases)) {
                    System.out.println("Preflight startup phase report: " + startupPhases);
                }
                if (Files.isRegularFile(report)) {
                    try {
                        AdapterProbeAnalysis.analyze(adapterReport, report, adapterAnalysis);
                        System.out.println("Preflight adapter analysis: " + adapterAnalysis);
                    } catch (Exception error) {
                        addPostprocessingFailure(postprocessingFailures, "adapter-analysis", error);
                        System.err.println("Preflight adapter analysis skipped: " + message(error));
                    }
                }
            } else if (options.adapterMode() != dev.starsector.preflight.agent.AdapterMode.OFF) {
                System.err.println("Preflight adapter report was not created: " + adapterReport);
            }
            return exitCode;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            exitCode = 1;
            outcome = launcherExitCode == null ? "LAUNCH_FAILED" : "PREFLIGHT_FAILED";
            executionFailure = message(error);
            throw error;
        } catch (Exception error) {
            exitCode = 1;
            outcome = launcherExitCode == null ? "LAUNCH_FAILED" : "PREFLIGHT_FAILED";
            executionFailure = message(error);
            throw error;
        } finally {
            ended = Instant.now();
            measuredElapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            try {
                writeMetadata(
                        metadata, target, command, runIdentity, launchId, started, ended,
                        measuredElapsedMillis, exitCode, launcherExitCode, outcome,
                        lifecycleEvidence, collectCensus(census, postprocessingFailures),
                        options, directSettings, textureContext, adapterReport, adapterAnalysis,
                        console, childOutput, postprocessingFailures, executionFailure, combatJvmSafeguard);
            } catch (IOException error) {
                System.err.println("Preflight could not finalize run metadata: " + message(error));
            }
            // The run directory answers "what happened during this launch" and is worth a megabyte
            // for a few days. This is the part still worth keeping afterwards, at a couple of
            // hundred bytes, so retention does not have to choose between forgetting last month and
            // carrying last month's diagnostics.
            String ledgerProblem = LaunchLedger.record(PreflightHome.current(), new LaunchLedger.Entry(
                    launchId,
                    started,
                    measuredElapsedMillis,
                    outcome,
                    exitCode,
                    lifecycleEvidence != null && lifecycleEvidence.fatalDetected(),
                    options.optimizationPreset().optionValue(),
                    options.disabledOptimizationDomains().stream()
                            .map(OptimizationDomain::optionValue)
                            .sorted()
                            .toList(),
                    runDirectory.getFileName().toString(),
                    textureContext == null ? null : textureContext.profileFingerprint()));
            if (ledgerProblem != null) {
                System.err.println("Preflight could not record this launch in its history: "
                        + ledgerProblem);
            } else {
                LaunchHeartbeat.complete(runDirectory, launchId);
            }
        }
    }

    static Path defaultRunDirectory(Path home, Instant started, String nonce) {
        if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Run directory nonce must contain 1-64 safe characters");
        }
        return home.toAbsolutePath().normalize()
                .resolve(".starsector-preflight")
                .resolve("runs")
                .resolve(RUN_ID.format(started) + "-" + nonce);
    }

    static int doctor(CommandLine options) throws IOException {
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                Platform.current(),
                Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                options.game(),
                options.launcher());
        printDiscovery(discovery);
        if (discovery.selected() != null) {
            printLaunchReadiness(options);
            if (options.scan()) {
                printProfileVramSummary(discovery.selected());
            }
        }
        return discovery.selected() == null ? 3 : 0;
    }

    /**
     * Whether the next launch would be accelerated, and what is missing when it would not.
     *
     * <p>Doctor is the command someone runs after installing to find out whether this thing is
     * working. Discovery alone does not answer that: a found launcher with no prepared data
     * launches at vanilla speed, and nothing in the discovery block says so. Each line is a fact
     * with a fix beside it when it is not the wanted one.
     */
    private static void printLaunchReadiness(CommandLine options) {
        PreflightHome home = PreflightHome.current();
        CacheCommand.CurrentProfile profile = CacheCommand.currentProfile(options.game(), options.launcher());
        CacheHealth.Report health = CacheHealth.inspect(
                home,
                profile.fingerprint(),
                profile.diagnostic(),
                profile.audioBuild(),
                profile.audioDecoder());
        boolean prepared = "ready".equals(health.status());

        System.out.println();
        System.out.println("Launch readiness:");
        if (profile.fingerprint() == null) {
            System.out.println("  mod setup       unreadable  " + profile.diagnostic());
        } else {
            System.out.println("  mod setup       identified  profile " + profile.fingerprint().substring(0, 16));
        }
        System.out.println("  prepared data   " + switch (health.status()) {
            case "ready" -> "ready       this profile's artifacts are present and valid";
            case "cold" -> "none        run `preflight prepare` to build it";
            case "repair-needed" -> "damaged     run `preflight prepare` to rebuild it";
            case "unsafe" -> "unsafe      " + firstIssue(health);
            case "unknown" -> "unknown     " + firstIssue(health);
            default -> health.status() + "     " + firstIssue(health);
        });
        for (PreflightHome.Integration integration : home.reportedIntegrations()) {
            System.out.println("  " + pad(integration.label()) + (integration.present() ? "installed   " : "absent      ")
                    + integration.path());
        }
        System.out.println();
        System.out.println(prepared
                ? "Next launch is accelerated. `preflight run` uses the prepared artifacts above."
                : "Next launch runs at ordinary speed until preparation completes.");
        if (options.scan()) {
            System.out.println("Texture working-set scan follows; `--no-scan` skips it.");
        }
    }

    private static String firstIssue(CacheHealth.Report health) {
        return health.issues().isEmpty() ? "" : health.issues().get(0).summary();
    }

    private static String pad(String label) {
        String trimmed = label.length() > 15 ? label.substring(0, 15) : label;
        return trimmed + " ".repeat(16 - trimmed.length());
    }

    /**
     * Prints a compact decoded-texture (VRAM) working-set summary for the selected install. This is
     * a health-check view of {@link ProfileCensus}: the override-resolved decoded floor, the loudest
     * mods, and the grade/plan/cut chain that acts on it. Fail-soft — a scan problem must never fail
     * doctor.
     */
    private static void printProfileVramSummary(LaunchTarget target) {
        try {
            System.out.println();
            System.out.println("Preflight is scanning the enabled mod profile for decoded-texture (VRAM) cost...");
            Map<String, Object> values = ProfileCensus.scan(target.installRoot()).values();
            Map<String, Object> totals = asMap(values.get("totals"));
            Map<String, Object> workingSet = asMap(values.get("decodedWorkingSet"));
            List<?> enabled = values.get("enabledModIds") instanceof List<?> list ? list : List.of();
            long resident = asLong(workingSet.get("winnerResidentImageBytes"));
            long padding = asLong(workingSet.get("winnerPaddingImageBytes"));
            long decoded = asLong(workingSet.get("winnerDecodedImageBytes"));
            long unmeasured = asLong(workingSet.get("unmeasuredImageFiles"));

            System.out.println("Texture working set (enabled profile):");
            System.out.println("  enabled mods:   " + enabled.size());
            System.out.println("  image files:    " + asLong(totals.get("imageFiles"))
                    + (unmeasured > 0 ? " (" + unmeasured + " unmeasured)" : ""));
            System.out.println("  resident VRAM:  " + humanBytes(resident) + " override-resolved"
                    + "  (power-of-two padded, 4 bytes/px)");
            System.out.println("  of which pad:   " + humanBytes(padding)
                    + " allocated but never sampled");
            System.out.println("  decoded pixels: " + humanBytes(decoded) + " before padding/widening");
            List<?> largestDecoded = values.get("largestDecodedMods") instanceof List<?> list ? list : List.of();
            if (!largestDecoded.isEmpty()) {
                System.out.println("  largest decoded mods:");
                largestDecoded.stream().limit(3).forEach(entry -> {
                    Map<String, Object> mod = asMap(entry);
                    System.out.println("    " + humanBytes(asLong(mod.get("decodedImageBytes"))) + "  " + mod.get("id"));
                });
            }
            System.out.println("  grade it:       preflight scan --vram-budget <size>          (e.g. 4G)");
            System.out.println("  plan a cut:     preflight scan --vram-budget <size> --max-texture-size <pixels>");
            System.out.println("  take the cut:   preflight assets shrink --max-texture-size <pixels> --out-dir <mod-dir>");
        } catch (IOException | RuntimeException error) {
            System.err.println("Preflight profile scan skipped: " + message(error));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String humanBytes(long bytes) {
        if (bytes >= (1L << 30)) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / (double) (1L << 30));
        }
        if (bytes >= (1L << 20)) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1L << 20));
        }
        if (bytes >= (1L << 10)) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / (double) (1L << 10));
        }
        return bytes + " B";
    }

    private static void printPlan(
            LaunchTarget target,
            Path runDirectory,
            Path adapterReport,
            List<String> command,
            String javaToolOptions,
            DiscoveryResult discovery,
            CommandLine options,
            LaunchCacheContexts.Texture textureContext,
            DirectLaunchSettings directSettings,
            LaunchCacheContexts.Janino janinoBytecodeCache,
            CombatJvmSafeguard.Resolution combatJvmSafeguard,
            String javaOptions) {
        System.out.println("Preflight selected:");
        System.out.println("  install:  " + target.installRoot());
        System.out.println("  launcher: " + target.launcher());
        System.out.println("  kind:     " + target.kind());
        LaunchOwnership ownership = LaunchOwnership.detect(target);
        System.out.println("  runtime owner: " + ownership.owner()
                + (ownership.evidence().isEmpty() ? "" : " " + ownership.evidence()));
        System.out.println("  run data: " + runDirectory);
        System.out.println("  optimization preset: " + options.optimizationPreset().optionValue());
        System.out.println("  disabled optimization domains: "
                + options.disabledOptimizationDomains().stream()
                        .map(OptimizationDomain::optionValue)
                        .sorted()
                        .toList());
        System.out.println("  adapter:  " + options.adapterMode());
        System.out.println("  adapter plan scope: " + options.adapterPlanScope().optionValue());
        System.out.println("  recording: " + options.recordingMode()
                + (options.singleChunkRecording() ? " (single timestamp-coherent chunk)" : ""));
        System.out.println("  campaign entity index: " + options.campaignEntityIndex());
        System.out.println("  startup phase probe: " + options.startupPhaseProbe());
        System.out.println("  rule token cache: " + options.ruleTokenCache());
        System.out.println("  resource probe cache: " + options.resourceProbeCache());
        System.out.println("  loadJSON memo: " + options.loadJsonMemo());
        System.out.println("  rule command class cache: " + options.ruleCommandClassCache());
        System.out.println("  GraphicsLib compact replay: " + options.graphicsLibCompactReplay());
        System.out.println("  Janino bytecode cache: "
                + (janinoBytecodeCache != null ? "active"
                : options.janinoBytecodeCache() ? "suppressed or unavailable" : "off"));
        System.out.println("  GraphicsLib insignia manager cache: "
                + options.graphicsLibInsigniaManagerCache());
        System.out.println("  combat JVM safeguard: "
                + (combatJvmSafeguard.active() ? "active — " : "inactive — ")
                + combatJvmSafeguard.reason());
        System.out.println("  quiet logs: " + (options.quietLogs()
                ? QuietLogConfiguration.path(runDirectory)
                : "off"));
        System.out.println("  launch: " + (directSettings == null
                ? "launcher UI"
                : "direct " + directSettings.resolution()
                        + " fullscreen=" + directSettings.fullscreen()
                        + " sound=" + directSettings.sound()));
        System.out.println("  adapter report: " + adapterReport);
        if (options.adapterTargets() != null) {
            System.out.println("  adapter targets: " + options.adapterTargets().toAbsolutePath().normalize());
        }
        if (textureContext != null) {
            System.out.println("  texture mode: " + options.textureAdapterMode());
            System.out.println("  texture artifacts: " + (textureContext.automatic() ? "CURRENT_PROFILE_AUTO" : "EXPLICIT"));
            System.out.println("  texture cache: " + textureContext.cacheDirectory());
            System.out.println("  texture manifest: " + textureContext.manifest());
            System.out.println("  texture index: " + textureContext.index());
            if (textureContext.profileFingerprint() != null) {
                System.out.println("  texture profile: " + textureContext.profileFingerprint());
            }
        }
        System.out.println("  command:  " + renderCommand(command));
        System.out.println("  JAVA_TOOL_OPTIONS: " + javaToolOptions);
        if (javaOptions != null && !javaOptions.isBlank()) {
            System.out.println("  _JAVA_OPTIONS: " + javaOptions);
        }
        for (String diagnostic : discovery.diagnostics()) {
            System.out.println("  note: " + diagnostic);
        }
    }

    private static void printDiscovery(DiscoveryResult discovery) {
        System.out.println("Preflight doctor");
        if (discovery.selected() != null) {
            System.out.println("Selected: " + discovery.selected().launcher());
        }
        if (discovery.candidates().isEmpty()) {
            System.out.println("Candidates: none");
        }
        for (LaunchTarget target : discovery.candidates()) {
            System.out.println("Candidate score=" + target.score()
                    + " kind=" + target.kind()
                    + " path=" + target.launcher()
                    + " source=" + target.source());
        }
        for (String diagnostic : discovery.diagnostics()) {
            System.out.println("Note: " + diagnostic);
        }
    }

    private static String renderCommand(List<String> command) {
        return command.stream()
                .map(RunCommand::displayQuote)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private static String displayQuote(String value) {
        if (value.chars().anyMatch(Character::isWhitespace)) {
            return '"' + value.replace("\"", "\\\"") + '"';
        }
        return value;
    }

    /**
     * Walks the enabled profile beside the game rather than in front of it.
     *
     * <p>Daemon so that a census still running when the run ends cannot hold the process open, and
     * uncaught failure is reported through {@link #collectCensus} rather than thrown here, because
     * a missing report is never a reason to fail a launch that otherwise worked.
     */
    private static Future<Path> censusInBackground(Path installRoot, Path profile) {
        CompletableFuture<Path> result = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                ProfileCensus.Result census = ProfileCensus.scan(installRoot);
                Files.writeString(profile, census.toJson() + System.lineSeparator());
                result.complete(profile);
            } catch (Exception error) {
                result.completeExceptionally(error);
            }
        }, "preflight-profile-census");
        worker.setDaemon(true);
        worker.start();
        return result;
    }

    /** The written profile, or null with the reason recorded, once the game has finished with it. */
    private static Path collectCensus(Future<Path> census, List<String> failures) {
        try {
            Path written = census.get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (written != null) {
                System.out.println("Preflight profile: " + written);
            }
            return written;
        } catch (java.util.concurrent.ExecutionException failed) {
            System.err.println("Preflight profile scan skipped: " + message(failed.getCause()));
            failures.add("profile-census: " + message(failed.getCause()));
            return null;
        } catch (java.util.concurrent.TimeoutException | InterruptedException stopped) {
            if (stopped instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            census.cancel(true);
            failures.add("profile-census: did not finish");
            return null;
        }
    }

    private static void addPostprocessingFailure(List<String> failures, String stage, Exception error) {
        if (failures.size() < 16) {
            failures.add(stage + ": " + message(error));
        }
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private static void writeMetadata(
            Path path,
            LaunchTarget target,
            List<String> command,
            RunIdentity runIdentity,
            String launchId,
            Instant started,
            Instant ended,
            Long elapsedMillis,
            Integer exitCode,
            Integer launcherExitCode,
            String outcome,
            StarsectorRunLogEvidence.Evidence lifecycleEvidence,
            Path profile,
            CommandLine options,
            DirectLaunchSettings directSettings,
            LaunchCacheContexts.Texture textureContext,
            Path adapterReport,
            Path adapterAnalysis,
            Path console,
            ChildProcessOutput.Result childOutput,
            List<String> postprocessingFailures,
            String executionFailure,
            CombatJvmSafeguard.Resolution combatJvmSafeguard) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        ProcessHandle wrapper = ProcessHandle.current();
        values.put("launchId", launchId);
        values.put("wrapperPid", wrapper.pid());
        values.put("wrapperStartedAt", wrapper.info().startInstant().orElse(null));
        values.put("started", started);
        values.put("ended", ended);
        values.put("elapsedMillis", elapsedMillis);
        values.put("exitCode", exitCode);
        values.put("launcherExitCode", launcherExitCode);
        values.put("outcome", outcome);
        values.put("executionFailure", executionFailure);
        values.put("postprocessingFailures", List.copyOf(postprocessingFailures));
        values.put("lifecycleEvidence", lifecycleEvidence == null ? null : lifecycleEvidence.toMap());
        values.put("launcherConsole", console);
        values.put("launcherConsoleCapture", childOutput == null ? null : childOutput.toMap());
        values.put("platform", Platform.current());
        values.put("javaVersion", runIdentity.wrapperJavaVersion());
        values.put("runtimeIdentityScope", RunIdentity.SCOPE);
        values.put("preflightJar", runIdentity.preflightJar());
        values.put("preflightJarSha256", runIdentity.preflightJarSha256());
        values.put("wrapperRuntime", runIdentity.wrapperRuntime());
        values.put("installRoot", target.installRoot());
        values.put("launcher", target.launcher());
        values.put("launcherKind", target.kind());
        LaunchOwnership ownership = LaunchOwnership.detect(target);
        values.put("runtimeOwner", ownership.owner());
        values.put("runtimeOwnershipEvidence", ownership.evidence());
        values.put("command", renderCommand(command));
        values.put("profile", profile);
        values.put("recordingMode", options.recordingMode());
        values.put("singleChunkRecording", options.singleChunkRecording());
        values.put("recordingMaxChunkBytes", options.singleChunkRecording() ? 256L * 1024L * 1024L : null);
        values.put("recordingPeriodicFlush",
                options.recordingMode().records() && !options.singleChunkRecording());
        values.put("campaignEntityIndex", options.campaignEntityIndex());
        values.put("startupPhaseProbe", options.startupPhaseProbe());
        values.put("ruleTokenCache", options.ruleTokenCache());
        values.put("resourceProbeCache", options.resourceProbeCache());
        values.put("loadJsonMemo", options.loadJsonMemo());
        values.put("ruleCommandClassCache", options.ruleCommandClassCache());
        values.put("graphicsLibCompactReplay", options.graphicsLibCompactReplay());
        values.put("janinoBytecodeCache", options.janinoBytecodeCache());
        values.put("graphicsLibInsigniaManagerCache", options.graphicsLibInsigniaManagerCache());
        values.put("combatJvmSafeguard", combatJvmSafeguard.toReportValues());
        values.put("quietLogs", options.quietLogs());
        values.put("fileOnlyLogs", options.fileOnlyLogs());
        values.put("assetProgressLogsSuppressed", options.suppressAssetProgressLogs());
        values.put("trustedValidatedTextureIndex", options.trustValidatedTextureIndex());
        values.put("desktopSmoke", options.desktopSmoke());
        values.put("quietLogConfiguration", options.fileOnlyLogs()
                ? QuietLogConfiguration.path(path.getParent(), options.quietLogs())
                : null);
        values.put("directLaunch", options.directLaunch());
        values.put("directLaunchSettings", directSettings == null ? null : directSettings.toReportValues());
        values.put("optimizationPreset", options.optimizationPreset().optionValue());
        values.put("disabledOptimizationDomains", options.disabledOptimizationDomains().stream()
                .map(OptimizationDomain::optionValue)
                .sorted()
                .toList());
        values.put("adapterMode", options.adapterMode());
        values.put("adapterPlanScope", options.adapterPlanScope().optionValue());
        values.put("adapterReport", adapterReport);
        Path adapterHealth = adapterReport.resolveSibling("adapter-health.json");
        values.put("adapterHealthReport", Files.isRegularFile(adapterHealth) ? adapterHealth : null);
        values.put("adapterAnalysis", Files.isRegularFile(adapterAnalysis) ? adapterAnalysis : null);
        values.put("adapterTargets", options.adapterTargets());
        values.put("textureAdapterMode", options.textureAdapterMode());
        values.put("textureAuto", options.textureAuto());
        values.put("textureCacheDirectory", textureContext == null ? null : textureContext.cacheDirectory());
        values.put("textureManifest", textureContext == null ? null : textureContext.manifest());
        values.put("textureIndex", textureContext == null ? null : textureContext.index());
        values.put("textureProfileFingerprint", textureContext == null ? null : textureContext.profileFingerprint());
        values.put("textureManifestSha256", textureContext == null ? null : textureContext.manifestSha256());
        values.put("textureIndexSha256", textureContext == null ? null : textureContext.indexSha256());
        values.put("textureIndexCheckedProviders", textureContext == null ? null : textureContext.checkedProviders());
        values.put("textureCurrentIndexBuildMs", textureContext == null ? null : textureContext.indexBuildMillis());
        values.put("adapterKillSwitchProperty", "preflight.adapter.disabled");
        values.put("adapterKillSwitchEnvironment", "PREFLIGHT_DISABLE_ADAPTER");
        // Per-seam contract and signature reports are written when they carry a finding. Set either
        // of these to keep the routine ones too, which is what development wants and a player's
        // disk does not.
        values.put("fullEvidenceProperty", "preflight.evidence.full");
        values.put("fullEvidenceEnvironment", "PREFLIGHT_FULL_EVIDENCE");
        values.put("adapterPlanKillSwitchProperty", "preflight.adapter.disabledPlans");
        values.put("adapterPlanKillSwitchEnvironment", "PREFLIGHT_DISABLE_ADAPTER_PLANS");
        Files.writeString(path, Json.object(values) + System.lineSeparator());
    }

    private static DirectLaunchSettings directLaunchSettings(CommandLine options) {
        if (!options.directLaunch()) {
            return null;
        }
        DirectLaunchSettings.Availability availability = DirectLaunchSettings.preferencesReadable()
                ? DirectLaunchSettings.resolve(DirectLaunchSettings.installedPreferences())
                : DirectLaunchSettings.Availability.unavailable(
                        "The game's launcher preferences (" + DirectLaunchSettings.PREFERENCES_NODE
                                + ") are unavailable on this machine.");
        if (!availability.available()) {
            throw new IllegalArgumentException("--direct is unavailable: " + availability.reason());
        }
        return availability.settings();
    }

    static String appendJavaOptions(String existing, List<String> directOptions) {
        String options = String.join(" ", directOptions);
        return existing == null || existing.isBlank() ? options : existing + " " + options;
    }

}

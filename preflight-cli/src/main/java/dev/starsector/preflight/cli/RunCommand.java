package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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
        TextureLaunchContext textureContext = textureContext(options, target);
        SpecStoreCacheContexts specStoreCaches = specStoreCacheContexts(options, target, textureContext);
        VariantJsonCacheContext variantJsonCache = specStoreCaches.variantJson();
        WeaponJsonCacheContext weaponJsonCache = specStoreCaches.weaponJson();
        ProjectileJsonCacheContext projectileJsonCache = specStoreCaches.projectileJson();
        HullJsonCacheContext hullJsonCache = specStoreCaches.hullJson();
        RulesCsvCacheContext rulesCsvCache = specStoreCaches.rulesCsv();
        RuleCommandCacheContext ruleCommandCache = specStoreCaches.ruleCommand();
        DirectLaunchSettings directSettings = directLaunchSettings(options);

        Path runDirectory = options.traceDirectory() == null
                ? defaultRunDirectory(home, Instant.now(), UUID.randomUUID().toString().substring(0, 8))
                : options.traceDirectory().toAbsolutePath().normalize();
        Path recording = runDirectory.resolve("startup.jfr");
        Path report = runDirectory.resolve("summary.json");
        Path adapterReport = runDirectory.resolve("adapter.json");
        Path adapterAnalysis = runDirectory.resolve("adapter-analysis.json");
        Path metadata = runDirectory.resolve("run.json");
        Path profile = runDirectory.resolve("profile.json");
        Path console = runDirectory.resolve("console.txt");
        Path agentJar = SelfJar.locate();
        String javaToolOptions = AgentInjection.append(
                System.getenv("JAVA_TOOL_OPTIONS"),
                agentJar,
                recording,
                options.adapterMode(),
                adapterReport,
                options.adapterTargets(),
                textureContext == null ? null : textureContext.cacheDirectory(),
                textureContext == null ? null : textureContext.manifest(),
                textureContext == null ? null : textureContext.index(),
                options.textureAdapterMode(),
                options.exhaustiveFileReads(),
                options.recordingMode(),
                options.npotDirect(),
                options.unpadded(),
                options.singleChunkRecording(),
                options.campaignEntityIndex(),
                options.startupPhaseProbe(),
                variantJsonCache == null ? null : variantJsonCache.artifact(),
                weaponJsonCache == null ? null : weaponJsonCache.artifact(),
                projectileJsonCache == null ? null : projectileJsonCache.artifact(),
                hullJsonCache == null ? null : hullJsonCache.artifact(),
                rulesCsvCache == null ? null : rulesCsvCache.artifact(),
                options.ruleTokenCache(),
                ruleCommandCache == null ? null : ruleCommandCache.artifact(),
                options.resourceProbeCache());
        if (directSettings != null) {
            javaToolOptions = appendJavaOptions(javaToolOptions, directSettings.javaOptions());
        }

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
                directSettings);
        if (options.dryRun()) {
            return 0;
        }

        RunIdentity runIdentity = RunIdentity.capture(agentJar);
        Files.createDirectories(runDirectory);
        if (options.scan()) {
            try {
                System.out.println("Preflight is scanning the enabled mod profile...");
                ProfileCensus.Result census = ProfileCensus.scan(target.installRoot());
                Files.writeString(profile, census.toJson() + System.lineSeparator());
                System.out.println("Preflight profile: " + profile);
            } catch (Exception error) {
                System.err.println("Preflight profile scan skipped: " + message(error));
            }
        }

        Path recordedProfile = Files.isRegularFile(profile) ? profile : null;
        Instant started = Instant.now();
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
                    metadata, target, command, runIdentity, started, null, null, null, outcome, null,
                    recordedProfile, options, directSettings, textureContext, adapterReport, adapterAnalysis, console, null,
                    postprocessingFailures, null);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(target.workingDirectory().toFile());
            builder.environment().put("JAVA_TOOL_OPTIONS", javaToolOptions);
            builder.environment().put("PREFLIGHT_RUN_DIR", runDirectory.toString());

            childOutput = ChildProcessOutput.run(builder, console);
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
            try {
                writeMetadata(
                        metadata, target, command, runIdentity, started, ended, exitCode, launcherExitCode, outcome,
                        lifecycleEvidence, recordedProfile, options, directSettings, textureContext, adapterReport, adapterAnalysis,
                        console, childOutput, postprocessingFailures, executionFailure);
            } catch (IOException error) {
                System.err.println("Preflight could not finalize run metadata: " + message(error));
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
            printProfileVramSummary(discovery.selected());
        }
        return discovery.selected() == null ? 3 : 0;
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
            TextureLaunchContext textureContext,
            DirectLaunchSettings directSettings) {
        System.out.println("Preflight selected:");
        System.out.println("  install:  " + target.installRoot());
        System.out.println("  launcher: " + target.launcher());
        System.out.println("  kind:     " + target.kind());
        System.out.println("  run data: " + runDirectory);
        System.out.println("  adapter:  " + options.adapterMode());
        System.out.println("  recording: " + options.recordingMode()
                + (options.singleChunkRecording() ? " (single timestamp-coherent chunk)" : ""));
        System.out.println("  campaign entity index: " + options.campaignEntityIndex());
        System.out.println("  startup phase probe: " + options.startupPhaseProbe());
        System.out.println("  rule token cache: " + options.ruleTokenCache());
        System.out.println("  resource probe cache: " + options.resourceProbeCache());
        System.out.println("  rule command class cache: " + options.ruleCommandClassCache());
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
        for (String diagnostic : discovery.diagnostics()) {
            System.out.println("  note: " + diagnostic);
        }
    }

    private static void printDiscovery(DiscoveryResult discovery) {
        System.out.println("Starsector Preflight doctor");
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
            Instant started,
            Instant ended,
            Integer exitCode,
            Integer launcherExitCode,
            String outcome,
            StarsectorRunLogEvidence.Evidence lifecycleEvidence,
            Path profile,
            CommandLine options,
            DirectLaunchSettings directSettings,
            TextureLaunchContext textureContext,
            Path adapterReport,
            Path adapterAnalysis,
            Path console,
            ChildProcessOutput.Result childOutput,
            List<String> postprocessingFailures,
            String executionFailure) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("started", started);
        values.put("ended", ended);
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
        values.put("ruleCommandClassCache", options.ruleCommandClassCache());
        values.put("directLaunch", options.directLaunch());
        values.put("directLaunchSettings", directSettings == null ? null : directSettings.toReportValues());
        values.put("adapterMode", options.adapterMode());
        values.put("adapterReport", adapterReport);
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

    private static TextureLaunchContext textureContext(CommandLine options, LaunchTarget target) throws IOException {
        if (options.textureAuto()) {
            System.out.println("Preflight is matching prepared textures to the current installed profile...");
            CurrentTextureCache.Resolution resolved = CurrentTextureCache.resolve(
                    target.installRoot(), options.textureCacheDirectory());
            return new TextureLaunchContext(
                    resolved.cacheDirectory(),
                    resolved.manifest(),
                    resolved.index(),
                    true,
                    resolved.profileFingerprint(),
                    resolved.manifestSha256(),
                    resolved.indexSha256(),
                    resolved.checkedProviders(),
                    resolved.indexBuildMillis());
        }
        if (options.textureManifest() == null) {
            return null;
        }
        return new TextureLaunchContext(
                options.textureCacheDirectory().toAbsolutePath().normalize(),
                options.textureManifest().toAbsolutePath().normalize(),
                options.textureIndex().toAbsolutePath().normalize(),
                false,
                null,
                null,
                null,
                0,
                0);
    }

    /**
     * Selects every spec-store cache artifact from one pass over the launch profile.
     *
     * <p>These six identities used to be built independently, which meant six reads of the same
     * 8&nbsp;MB resource index, six hashes of the same game jar, and 12,797 separate {@code
     * toRealPath} resolutions -- 1,612ms in the launcher before the JVM started. Sharing one
     * {@link ProfileIdentityContext} removes the duplication without changing a single digest.
     *
     * <p>Each cache still fails independently: an identity that cannot be built leaves that one
     * context null and vanilla loading handles that corpus, exactly as before.
     */
    private static SpecStoreCacheContexts specStoreCacheContexts(
            CommandLine options,
            LaunchTarget target,
            TextureLaunchContext textures) {
        if (options.adapterMode() != dev.starsector.preflight.agent.AdapterMode.ENABLED
                || textures == null || !textures.automatic()) {
            return SpecStoreCacheContexts.none();
        }
        long opened = System.nanoTime();
        try (ProfileIdentityContext context =
                     ProfileIdentityContext.open(target.installRoot(), textures.index())) {
            System.out.printf(Locale.ROOT,
                    "Preflight read the launch profile in %.1fms (%d providers).%n",
                    (System.nanoTime() - opened) / 1_000_000.0,
                    context.resources().providerCount());
            return new SpecStoreCacheContexts(
                    variantJsonCacheContext(context, textures),
                    weaponJsonCacheContext(context, textures),
                    projectileJsonCacheContext(context, textures),
                    hullJsonCacheContext(context, textures),
                    rulesCsvCacheContext(context, textures),
                    options.ruleCommandClassCache()
                            ? ruleCommandCacheContext(context, textures)
                            : null);
        } catch (Exception error) {
            System.err.println("Preflight launch profile identity failed: " + message(error)
                    + "; vanilla loading remains active.");
            return SpecStoreCacheContexts.none();
        }
    }

    private static VariantJsonCacheContext variantJsonCacheContext(
            ProfileIdentityContext context, TextureLaunchContext textures) {
        long started = System.nanoTime();
        try {
            VariantJsonProfileIdentityBuilder.Result profile =
                    VariantJsonProfileIdentityBuilder.build(context);
            Path artifact = artifact(textures, "variant-json", profile.identitySha256(), ".spvj");
            report("variant JSON", profile.identitySha256(), started, artifact,
                    String.format(Locale.ROOT, "%d paths, %d providers",
                            profile.logicalPaths(), profile.providerCount()));
            return new VariantJsonCacheContext(artifact);
        } catch (Exception error) {
            declined("variant JSON", error);
            return null;
        }
    }

    private static WeaponJsonCacheContext weaponJsonCacheContext(
            ProfileIdentityContext context, TextureLaunchContext textures) {
        long started = System.nanoTime();
        try {
            WeaponJsonProfileIdentityBuilder.Result profile =
                    WeaponJsonProfileIdentityBuilder.build(context);
            Path artifact = artifact(textures, "weapon-json", profile.identitySha256(), ".spwj");
            report("weapon JSON", profile.identitySha256(), started, artifact,
                    String.format(Locale.ROOT, "%d paths, %d providers",
                            profile.logicalPaths(), profile.providerCount()));
            return new WeaponJsonCacheContext(artifact);
        } catch (Exception error) {
            declined("weapon JSON", error);
            return null;
        }
    }

    private static ProjectileJsonCacheContext projectileJsonCacheContext(
            ProfileIdentityContext context, TextureLaunchContext textures) {
        long started = System.nanoTime();
        try {
            ProjectileJsonProfileIdentityBuilder.Result profile =
                    ProjectileJsonProfileIdentityBuilder.build(context);
            Path artifact = artifact(textures, "projectile-json", profile.identitySha256(), ".sppj");
            report("projectile JSON", profile.identitySha256(), started, artifact,
                    String.format(Locale.ROOT, "%d paths, %d providers",
                            profile.logicalPaths(), profile.providerCount()));
            return new ProjectileJsonCacheContext(artifact);
        } catch (Exception error) {
            declined("projectile JSON", error);
            return null;
        }
    }

    private static HullJsonCacheContext hullJsonCacheContext(
            ProfileIdentityContext context, TextureLaunchContext textures) {
        long started = System.nanoTime();
        try {
            HullJsonProfileIdentityBuilder.Result profile =
                    HullJsonProfileIdentityBuilder.build(context);
            Path artifact = artifact(textures, "hull-json", profile.identitySha256(), ".sphj");
            report("hull JSON", profile.identitySha256(), started, artifact,
                    String.format(Locale.ROOT, "%d paths, %d providers",
                            profile.logicalPaths(), profile.providerCount()));
            return new HullJsonCacheContext(artifact);
        } catch (Exception error) {
            declined("hull JSON", error);
            return null;
        }
    }

    private static RulesCsvCacheContext rulesCsvCacheContext(
            ProfileIdentityContext context, TextureLaunchContext textures) {
        long started = System.nanoTime();
        try {
            RulesCsvProfileIdentityBuilder.Result profile =
                    RulesCsvProfileIdentityBuilder.build(context);
            Path artifact = artifact(textures, "rules-csv", profile.identitySha256(), ".sprc");
            report("rules CSV", profile.identitySha256(), started, artifact,
                    String.format(Locale.ROOT, "%d providers", profile.providerCount()));
            return new RulesCsvCacheContext(artifact);
        } catch (Exception error) {
            declined("rules CSV", error);
            return null;
        }
    }

    private static RuleCommandCacheContext ruleCommandCacheContext(
            ProfileIdentityContext context, TextureLaunchContext textures) {
        long started = System.nanoTime();
        try {
            RuleCommandClassProfileIdentityBuilder.Result profile =
                    RuleCommandClassProfileIdentityBuilder.build(context);
            Path artifact =
                    artifact(textures, "rule-command-classes", profile.identitySha256(), ".sprk");
            report("rule command class", profile.identitySha256(), started, artifact,
                    String.format(Locale.ROOT, "%d settings.json providers, %d jars, %.1f MB",
                            profile.settingsProviderCount(), profile.jarCount(),
                            profile.jarBytes() / 1_048_576.0));
            return new RuleCommandCacheContext(artifact);
        } catch (Exception error) {
            declined("rule command class", error);
            return null;
        }
    }

    private static Path artifact(
            TextureLaunchContext textures, String store, String identity, String extension) {
        return textures.cacheDirectory()
                .resolve("spec-store").resolve(store)
                .resolve(identity + extension)
                .toAbsolutePath().normalize();
    }

    private static void report(
            String label, String identity, long started, Path artifact, String detail) {
        System.out.printf(Locale.ROOT,
                "Preflight matched %s dependency profile %s in %.1fms (%s, %s).%n",
                label,
                identity,
                (System.nanoTime() - started) / 1_000_000.0,
                detail,
                Files.isRegularFile(artifact) ? "hit" : "learning run");
    }

    private static void declined(String label, Exception error) {
        System.err.println("Preflight " + label + " cache selection failed: " + message(error)
                + "; vanilla loading remains active.");
    }

    private record SpecStoreCacheContexts(
            VariantJsonCacheContext variantJson,
            WeaponJsonCacheContext weaponJson,
            ProjectileJsonCacheContext projectileJson,
            HullJsonCacheContext hullJson,
            RulesCsvCacheContext rulesCsv,
            RuleCommandCacheContext ruleCommand) {

        static SpecStoreCacheContexts none() {
            return new SpecStoreCacheContexts(null, null, null, null, null, null);
        }
    }

    private record TextureLaunchContext(
            Path cacheDirectory,
            Path manifest,
            Path index,
            boolean automatic,
            String profileFingerprint,
            String manifestSha256,
            String indexSha256,
            long checkedProviders,
            double indexBuildMillis) {
    }

    private record VariantJsonCacheContext(Path artifact) {
    }

    private record WeaponJsonCacheContext(Path artifact) {
    }

    private record ProjectileJsonCacheContext(Path artifact) {
    }

    private record HullJsonCacheContext(Path artifact) {
    }

    private record RulesCsvCacheContext(Path artifact) {
    }

    private record RuleCommandCacheContext(Path artifact) {
    }
}

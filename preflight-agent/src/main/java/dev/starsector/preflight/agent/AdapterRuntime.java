package dev.starsector.preflight.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs the optional probe transformer while keeping every unknown build unmodified. */
final class AdapterRuntime {
    static final String DISABLED_PLANS_PROPERTY = "preflight.adapter.disabledPlans";
    static final String DISABLED_PLANS_ENVIRONMENT = "PREFLIGHT_DISABLE_ADAPTER_PLANS";

    private AdapterRuntime() {
    }

    static Session start(AgentOptions options, Instrumentation instrumentation) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(instrumentation, "instrumentation");
        Set<String> disabledPlans = disabledPlans(System.getenv(), System.getProperties());
        AdapterPlanControl.configure(options.adapterPlanScope(), disabledPlans);
        SourceArchiveHashes.beginSession();
        AdapterTransformationCache.beginSession();
        TextureCompatibilityRuntime.beginSession();
        TextureAccessLearningRuntime.beginSession();
        TexturePreparedPixelRuntime.beginSession();
        TexturePaddingRuntime.beginSession();
        VariantJsonCacheRuntime.beginSession();
        WeaponJsonCacheRuntime.beginSession();
        ProjectileJsonCacheRuntime.beginSession();
        HullJsonCacheRuntime.beginSession();
        RulesCsvCacheRuntime.beginSession();
        RulesDuplicateIndexRuntime.beginSession();
        RuleTokenCacheRuntime.beginSession();
        RulesRegexCacheRuntime.beginSession();
        ResourcePriorityRuntime.beginSession();
        SaveDescriptorCompatibilityRuntime.beginSession();
        IndustryDemandSupplyMemoRuntime.beginSession();
        CodexLazyFleetMemberRuntime.beginSession();
        IndEvoSyntheticMarketRuntime.beginSession();
        RuleCommandClassCacheRuntime.beginSession();
        MergedReadCacheRuntime.beginSession();
        LoadingUtilsReaderRuntime.beginSession();
        LoadJsonMemoRuntime.reset();
        AudioStreamSourceErrorRuntime.beginSession();
        AudioResourceFallbackRuntime.beginSession();
        AudioMusicTransitionRuntime.beginSession();
        AiTweaksEngagementRangeRuntime.beginSession();
        AshLibVariantLookupRuntime.beginSession();
        GraphicsLibCompactReplayPlan.beginSession();
        JaninoBytecodeCacheRuntime.beginSession();
        GraphicsLibInsigniaManagerCacheRuntime.beginSession();
        GraphicsLibHotSettingsRuntime.reset();
        MagicLibPaintjobLoadRuntime.reset();
        MagicLibPaintjobCacheRuntime.beginSession();
        EntityLookupRuntime.beginSession();
        RadarRenderRuntime.beginSession();
        DeploymentIconCacheRuntime.beginSession();
        CommodityEventModMemoRuntime.beginSession();
        CampaignEntityMaintenanceRuntime.beginSession();
        FleetAiProfilerRuntime.beginSession();
        SimOpponentSafetyRuntime.beginSession();
        LogisticsNotificationsFuelRuntime.reset();
        MacMemoryWarningRuntime.beginSession();
        CombatRuntimeIntegrityRuntime.beginSession();
        FrameTimeRuntime.beginSession(Boolean.getBoolean("preflight.frameTimes"));
        boolean campaignTimes = Boolean.getBoolean("preflight.campaignTimes");
        CampaignCallTimeRuntime.beginSession(campaignTimes);
        CampaignEngineTimeRuntime.beginSession(campaignTimes);
        CampaignLocationEconomyTimeRuntime.beginSession(campaignTimes);
        CampaignMarketFleetTimeRuntime.beginSession(campaignTimes);
        StartupPhaseRuntime.beginSession(options.startupPhaseProbe()
                ? sibling(options.adapterReport(), "startup-phases.json") : null);
        StartupPhaseRuntime.enableMergedReadProbe(options.startupPhaseProbe());
        AdapterReport report = new AdapterReport(
                options.adapterMode(),
                options.adapterReport(),
                options.adapterTargets(),
                options.candidatePrefixes());
        RuntimeProcessReport runtimeProcess = RuntimeProcessReport.current(
                options.adapterReport().resolveSibling("runtime-process.json"));
        try {
            runtimeProcess.running();
        } catch (IOException error) {
            report.contained("Could not publish runtime process identity", error);
        }
        try {
            RuntimeSemanticState.beginSession(
                    options.adapterReport().resolveSibling("runtime-state.json"));
        } catch (IOException error) {
            report.contained("Could not publish runtime semantic state", error);
        }
        DesktopSmokeLiveReport desktopSmoke;
        try {
            desktopSmoke = DesktopSmokeLiveReport.start(options.adapterReport(), options.adapterMode());
        } catch (IOException error) {
            report.contained("Could not start desktop smoke live evidence", error);
            try {
                desktopSmoke = DesktopSmokeLiveReport.start(
                        options.adapterReport(), options.adapterMode(), false);
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        CodeLoaderSignatureReport codeLoaderReport = new CodeLoaderSignatureReport(
                sibling(options.adapterReport(), "code-loader-signatures.json"));
        AudioDecoderSignatureReport audioDecoderReport = new AudioDecoderSignatureReport(
                sibling(options.adapterReport(), "audio-decoder-signatures.json"));
        SoundLoaderContractReport soundLoaderReport = new SoundLoaderContractReport(
                sibling(options.adapterReport(), "sound-loader-contract.json"));
        BytecodeShapeReport textureLoaderReport = new BytecodeShapeReport(
                sibling(options.adapterReport(), "texture-loader-contract.json"));
        BytecodeShapeReport janinoLoaderReport = new BytecodeShapeReport(
                sibling(options.adapterReport(), "janino-loader-contract.json"),
                janinoTarget());
        Session session = new Session(
                runtimeProcess,
                report,
                codeLoaderReport,
                audioDecoderReport,
                soundLoaderReport,
                textureLoaderReport,
                janinoLoaderReport,
                desktopSmoke,
                options.adapterMode() != AdapterMode.OFF);
        if (options.adapterMode() == AdapterMode.OFF) {
            return session;
        }
        if (killSwitch(System.getenv(), System.getProperties())) {
            TextureCompatibilityRuntime.disable(TextureCompatibilityRuntime.DisableReason.KILL_SWITCH);
            report.killSwitch("Adapter kill switch is active; no transformer installed");
            return session;
        }

        if (options.adapterMode() == AdapterMode.ENABLED) {
            SourceArchiveHashes.configure(options.textureCacheDirectory());
            TextureCompatibilityRuntime.configure(
                    options.textureCacheDirectory(),
                    options.textureManifest(),
                    options.textureIndex());
            TextureAccessLearningRuntime.configure(
                    options.textureCacheDirectory(), options.textureProfile());
            VariantJsonCacheRuntime.configure(options.variantJsonCache());
            WeaponJsonCacheRuntime.configure(options.weaponJsonCache());
            ProjectileJsonCacheRuntime.configure(options.projectileJsonCache());
            HullJsonCacheRuntime.configure(options.hullJsonCache());
            RulesCsvCacheRuntime.configure(options.rulesCsvCache());
            RuleCommandClassCacheRuntime.configure(options.ruleCommandClassCache());
            MergedReadCacheRuntime.configure(options.mergedReadCache());
            MagicLibPaintjobCacheRuntime.configure(options.magicPaintjobCache());
            GraphicsLibCompactReplayPlan.configure(
                    options.graphicsLibCompactReplay(), options.textureCacheDirectory());
            JaninoBytecodeCacheRuntime.configure(
                    options.janinoBytecodeCache(), options.janinoBytecodeContext());
            GraphicsLibInsigniaManagerCacheRuntime.configure(
                    options.graphicsLibInsigniaManagerCache());
            if (options.ruleTokenCache()) {
                RuleTokenCacheRuntime.configure(options.rulesCsvCache());
            }
            ResourceProbeRuntime.enable(options.resourceProbeCache());
            LoadJsonMemoRuntime.enable(options.loadJsonMemo());
            if (options.preparedAudioCache() != null && options.audioDecoderIdentity() != null) {
                PreparedAudioRuntime.enable(true);
                PreparedAudioRuntime.configure(
                        options.preparedAudioCache(),
                        options.audioDecoderIdentity(),
                        options.preparedAudioManifest(),
                        options.preparedAudioManifestIdentity());
            }
        }

        AdapterTargetRegistry registry;
        try {
            registry = loadRegistry(options.adapterTargets(), report);
            if (options.adapterMode() == AdapterMode.ENABLED) {
                registry = registry.withTextureTarget(options.textureAdapterMode());
                report.diagnostic("Loaded the exact refit simulator opponent-safety target");
                report.diagnostic("Loaded the exact startup resource-priority index target");
                report.diagnostic("Loaded the exact save-descriptor compatibility memo target");
                report.diagnostic("Loaded the exact Codex industry demand/supply memo targets");
                report.diagnostic("Loaded the exact IndEvo synthetic-market safety targets");
                report.diagnostic("Loaded the exact campaign commodity event-mod memo target");
                report.diagnostic("Loaded the exact campaign entity-maintenance targets");
                report.diagnostic("Loaded the exact resource source-hint isolation target");
                report.diagnostic("Loaded the exact MagicLib unlocked-paintjob set target");
                report.diagnostic("Loaded the exact MagicLib optional-paintjob JSON shortcut");
                if (MagicLibPaintjobCacheRuntime.ready()) {
                    report.diagnostic("Loaded the exact MagicLib paintjob catalog target ("
                            + MagicLibPaintjobCacheRuntime.status() + ")");
                }
                report.diagnostic("Loaded the exact GraphicsLib hot-settings cache target");
                if (AudioStreamSourceErrorRuntime.disabled()) {
                    report.diagnostic("Skipped the streaming-audio OpenAL error-order target by diagnostic property");
                } else {
                    report.diagnostic("Loaded the exact streaming-audio OpenAL error-order target");
                }
                report.diagnostic("Loaded the exact sound classpath-root resource fallback target");
                report.diagnostic("Loaded the exact AI Tweaks per-selection range target");
                report.diagnostic("Loaded the exact AshLib callback-scoped variant index targets");
                if (FrameTimeRuntime.enabled()) {
                    registry = registry.withFrameTimeTarget();
                    report.diagnostic("Loaded the exact lightweight frame-time and campaign-state targets");
                }
                if (CampaignCallTimeRuntime.enabled()) {
                    registry = registry.withCampaignCallTimeTargets();
                }
                if (CampaignEngineTimeRuntime.enabled()) {
                    registry = registry.withCampaignEngineTimeTarget();
                }
                if (CampaignLocationEconomyTimeRuntime.enabled()) {
                    registry = registry.withCampaignLocationEconomyTimeTargets();
                }
                if (CampaignMarketFleetTimeRuntime.enabled()) {
                    registry = registry.withCampaignMarketFleetTimeTargets();
                }
                if (campaignTimes) {
                    report.diagnostic("Loaded the exact opt-in detailed campaign timing targets");
                }
                if (!options.startupPhaseProbe()
                        && (FrameTimeRuntime.enabled() || LoadJsonMemoRuntime.ready())) {
                    registry = registry.withFrameTimeStartupCompletionTarget();
                    report.diagnostic("Loaded the exact lightweight runtime startup-completion target");
                }
                if (RuntimeSemanticState.enabled()) {
                    registry = registry.withMainMenuInteractiveTarget();
                    report.diagnostic("Loaded the exact interactive main-menu state target");
                }
                if (options.startupPhaseProbe()) {
                    registry = registry.withStartupPhaseTarget();
                    report.diagnostic("Loaded the exact ResourceLoaderState and SpecStore startup-phase probe targets");
                }
                if (VariantJsonCacheRuntime.ready()) {
                    registry = registry.withVariantJsonCacheTarget();
                    report.diagnostic("Loaded the exact SpecStore variant JSON cache target ("
                            + VariantJsonCacheRuntime.status() + ")");
                } else {
                    // The variant target composes this disjoint rewrite whenever it is present.
                    registry = registry.withSpecStoreQuoteNormalizationTarget();
                    report.diagnostic("Loaded the exact SpecStore quote-normalization target");
                }
                if (WeaponJsonCacheRuntime.ready()) {
                    registry = registry.withWeaponJsonCacheTarget();
                    report.diagnostic("Loaded the exact weapon JSON cache target ("
                            + WeaponJsonCacheRuntime.status() + ")");
                }
                if (ProjectileJsonCacheRuntime.ready()) {
                    registry = registry.withProjectileJsonCacheTarget();
                    report.diagnostic("Loaded the exact projectile JSON cache target ("
                            + ProjectileJsonCacheRuntime.status() + ")");
                }
                if (HullJsonCacheRuntime.ready()) {
                    registry = registry.withHullJsonCacheTarget();
                    report.diagnostic("Loaded the exact hull JSON cache target ("
                            + HullJsonCacheRuntime.status() + ")");
                }
                if (RulesCsvCacheRuntime.ready()) {
                    registry = registry.withRulesCsvCacheTarget();
                    report.diagnostic("Loaded the exact rules CSV cache target ("
                            + RulesCsvCacheRuntime.status() + ")");
                }
                if (RulesDuplicateIndexRuntime.ready()) {
                    registry = registry.withRulesDuplicateIndexTarget();
                    report.diagnostic("Loaded the exact rules duplicate-index target");
                }
                registry = registry.withRulesRegexCacheTarget();
                report.diagnostic("Loaded the exact rules fixed-pattern cache target");
                if (RuleTokenCacheRuntime.ready()) {
                    registry = registry.withRuleTokenCacheTarget();
                    report.diagnostic("Loaded the exact rule-expression tokenizer memo target");
                }
                if (LoadJsonMemoRuntime.ready()) {
                    registry = registry.withLoadJsonMemoTarget();
                    report.diagnostic("Loaded the exact loadJSON memo target");
                }
                if (ResourceProbeRuntime.ready()) {
                    registry = registry.withResourceProbeCacheTarget();
                    report.diagnostic("Loaded the exact resource-resolver probe cache target");
                }
                if (PreparedAudioRuntime.ready()) {
                    registry = registry.withPreparedAudioTarget();
                    report.diagnostic("Loaded the exact Ogg Vorbis decoder target");
                }
                if (RuleCommandClassCacheRuntime.ready()) {
                    registry = registry.withRuleCommandClassCacheTarget();
                    report.diagnostic("Loaded the exact rule command class cache targets ("
                            + RuleCommandClassCacheRuntime.status() + ")");
                }
                if (MergedReadCacheRuntime.ready()) {
                    registry = registry.withMergedReadCacheTarget();
                    report.diagnostic("Loaded the exact merged-read cache target ("
                            + MergedReadCacheRuntime.status() + ")");
                }
                if (GraphicsLibCompactReplayPlan.ready()) {
                    registry = registry.withGraphicsLibCompactReplayTarget();
                    report.diagnostic("Loaded the exact GraphicsLib compact auto-generation replay target ("
                            + GraphicsLibCompactReplayPlan.status() + ")");
                } else if (options.graphicsLibCompactReplay()) {
                    report.diagnostic("GraphicsLib compact replay was requested but is unavailable ("
                            + GraphicsLibCompactReplayPlan.status() + ")");
                }
                if (JaninoBytecodeCacheRuntime.ready()) {
                    registry = registry.withJaninoBytecodeCacheTarget();
                    report.diagnostic("Loaded the exact Janino complete-map bytecode cache target ("
                            + JaninoBytecodeCacheRuntime.status() + ")");
                } else if (options.janinoBytecodeCache() != null) {
                    report.diagnostic("Janino bytecode cache was requested but is unavailable ("
                            + JaninoBytecodeCacheRuntime.status() + ")");
                }
                if (GraphicsLibInsigniaManagerCacheRuntime.ready()) {
                    registry = registry.withGraphicsLibInsigniaManagerCacheTarget();
                    report.diagnostic("Loaded the exact GraphicsLib insignia manager-cache target");
                }
                TexturePreparedPixelRuntime.select(options.textureAdapterMode());
                report.diagnostic("Loaded the compiled exact TextureLoader "
                        + options.textureAdapterMode().optionValue() + " target");
            }
            int beforeScope = registry.targets().size();
            registry = registry.forScope(options.adapterPlanScope());
            if (registry.targets().size() != beforeScope) {
                report.diagnostic("Adapter plan scope " + options.adapterPlanScope().optionValue()
                        + " omitted " + (beforeScope - registry.targets().size()) + " target(s)");
            }
            if (!disabledPlans.isEmpty()) {
                applyDisabledDiagnosticRuntimeGates(disabledPlans);
                int before = registry.targets().size();
                registry = registry.withoutPlans(disabledPlans);
                report.diagnostic("Plan filter disabled "
                        + (before - registry.targets().size())
                        + " direct target(s) and every matching composed rewrite: "
                        + String.join(",", disabledPlans));
            }
            if (options.adapterMode() == AdapterMode.ENABLED) {
                AdapterTransformationCache.configure(
                        options.textureCacheDirectory(), options, registry, System.getProperties());
                report.diagnostic("Adapter transformation cache: "
                        + AdapterTransformationCache.telemetry().get("status"));
            }
        } catch (IOException | RuntimeException error) {
            report.contained("Could not load adapter target registry", error);
            return session;
        }

        try {
            instrumentation.addTransformer(new AdapterProbeTransformer(
                    options.adapterMode(),
                    registry,
                    options.candidatePrefixes(),
                    report,
                    codeLoaderReport,
                    audioDecoderReport,
                    soundLoaderReport,
                    textureLoaderReport,
                    janinoLoaderReport), false);
            report.transformerInstalled(registry);
            if (registry.targets().isEmpty()) {
                report.diagnostic("No adapter targets are allowlisted; probe-only observation remains safe");
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            report.contained("Could not install adapter transformer", error);
        }
        return session;
    }

    static boolean killSwitch(Map<String, String> environment, Properties properties) {
        String property = properties.getProperty("preflight.adapter.disabled");
        String environmentValue = environment.get("PREFLIGHT_DISABLE_ADAPTER");
        return truthy(property) || truthy(environmentValue);
    }

    /**
     * Whether to keep the per-seam evidence documents a launch produced even when they found
     * nothing.
     *
     * <p>These four contract and signature reports are how an adapter seam is argued about: what
     * bytecode was observed, which identity matched, what the transform declined to do. They cost
     * around 400 KB of the roughly 940 KB a run directory holds, and nothing in the tree reads them
     * back -- they are read by a person, after something went wrong. A launch where every seam
     * matched writes the same document as the launch before it, so on a player's machine the
     * routine copies are pure accumulation.
     *
     * <p>So the routine ones are skipped and the ones carrying a finding are always written: a
     * containment diagnostic, a truncated record, or an identity the adapter did not recognize. A
     * player who hits a real problem still has the evidence from the launch that hit it, which is
     * the case these documents exist for and the one that cannot be reproduced afterwards.
     *
     * <p>Development sets this to keep the whole series, because a run that found nothing is itself
     * the evidence when the question is whether a change altered what a seam observes.
     */
    static boolean fullEvidence(Map<String, String> environment, Properties properties) {
        return truthy(properties.getProperty("preflight.evidence.full"))
                || truthy(environment.get("PREFLIGHT_FULL_EVIDENCE"));
    }

    private interface EvidenceWrite {
        void write() throws IOException;
    }

    private static void writeEvidence(
            String label, boolean keepRoutineEvidence, boolean routine, EvidenceWrite write) {
        if (routine && !keepRoutineEvidence) return;
        try {
            write.write();
        } catch (IOException error) {
            System.err.println(
                    "[Preflight] Failed to write " + label + " report: " + error.getMessage());
        }
    }

    static Set<String> disabledPlans(Properties properties) {
        return disabledPlans(Map.of(), properties);
    }

    static Set<String> disabledPlans(Map<String, String> environment, Properties properties) {
        Set<String> plans = new LinkedHashSet<>();
        for (String raw : List.of(
                environment.getOrDefault(DISABLED_PLANS_ENVIRONMENT, ""),
                properties.getProperty(DISABLED_PLANS_PROPERTY, ""))) {
            for (String token : raw.split(",")) {
                String plan = token.trim();
                if (!plan.isEmpty()) plans.add(plan);
            }
        }
        return Set.copyOf(plans);
    }

    /** Keeps composed diagnostic probes off when their standalone registry target is filtered. */
    static void applyDisabledDiagnosticRuntimeGates(Set<String> disabledPlans) {
        if (disabledPlans.contains(CampaignCallTimeRuntime.PLAN_ID)) {
            CampaignCallTimeRuntime.beginSession(false);
        }
        if (disabledPlans.contains(CampaignEngineTimeRuntime.PLAN_ID)) {
            CampaignEngineTimeRuntime.beginSession(false);
        }
        if (disabledPlans.contains(CampaignLocationEconomyTimeRuntime.PLAN_ID)) {
            CampaignLocationEconomyTimeRuntime.beginSession(false);
        }
        if (disabledPlans.contains(CampaignMarketFleetTimeRuntime.PLAN_ID)) {
            CampaignMarketFleetTimeRuntime.beginSession(false);
        }
    }

    private static BytecodeShapeReport.CaptureTarget janinoTarget() {
        return new BytecodeShapeReport.CaptureTarget(
                "installed-janino-complete-map-shape-v1",
                "org/codehaus/janino/JavaSourceClassLoader",
                "6b0eea7994ab4c314f1bc7cdefaa99b66897d500c2cad6fd2d97cd08b134c4b8",
                "STARSECTOR_CORE",
                "janino.jar",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app",
                List.of(
                        new BytecodeShapeReport.MethodKey(
                                "generateBytecodes", "(Ljava/lang/String;)Ljava/util/Map;"),
                        new BytecodeShapeReport.MethodKey(
                                "defineBytecode", "(Ljava/lang/String;[B)Ljava/lang/Class;"),
                        new BytecodeShapeReport.MethodKey(
                                "findClass", "(Ljava/lang/String;)Ljava/lang/Class;")));
    }

    private static AdapterTargetRegistry loadRegistry(Path path, AdapterReport report) throws IOException {
        if (path == null) {
            return AdapterTargetRegistry.empty();
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IOException("Adapter target file does not exist: " + absolute);
        }
        AdapterTargetRegistry registry = AdapterTargetRegistry.load(absolute);
        report.diagnostic("Loaded " + registry.targets().size() + " adapter target(s) from " + absolute);
        return registry;
    }

    private static Path sibling(Path adapterReport, String suffix) {
        Path absolute = adapterReport.toAbsolutePath().normalize();
        String name = absolute.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return absolute.resolveSibling(stem + "-" + suffix);
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on", "enabled" -> true;
            default -> false;
        };
    }

    static final class Session implements AutoCloseable {
        private final RuntimeProcessReport runtimeProcess;
        private final AdapterReport report;
        private final CodeLoaderSignatureReport codeLoaderReport;
        private final AudioDecoderSignatureReport audioDecoderReport;
        private final SoundLoaderContractReport soundLoaderReport;
        private final BytecodeShapeReport textureLoaderReport;
        private final BytecodeShapeReport janinoLoaderReport;
        private final DesktopSmokeLiveReport desktopSmoke;
        private final boolean writeReport;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Session(
                RuntimeProcessReport runtimeProcess,
                AdapterReport report,
                CodeLoaderSignatureReport codeLoaderReport,
                AudioDecoderSignatureReport audioDecoderReport,
                SoundLoaderContractReport soundLoaderReport,
                BytecodeShapeReport textureLoaderReport,
                BytecodeShapeReport janinoLoaderReport,
                DesktopSmokeLiveReport desktopSmoke,
                boolean writeReport) {
            this.runtimeProcess = runtimeProcess;
            this.report = report;
            this.codeLoaderReport = codeLoaderReport;
            this.audioDecoderReport = audioDecoderReport;
            this.soundLoaderReport = soundLoaderReport;
            this.textureLoaderReport = textureLoaderReport;
            this.janinoLoaderReport = janinoLoaderReport;
            this.desktopSmoke = desktopSmoke;
            this.writeReport = writeReport;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                runtimeProcess.stopped();
            } catch (IOException error) {
                System.err.println("[Preflight] Failed to finalize runtime process identity: "
                        + error.getMessage());
            }
            RuntimeSemanticState.stopped();
            desktopSmoke.close();
            if (!writeReport) return;
            try {
                MergedReadCacheRuntime.complete();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable error) {
                System.err.println("[Preflight] Failed to publish lazy read cache: "
                        + error.getMessage());
            }
            try {
                TextureAccessLearningRuntime.complete();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable error) {
                System.err.println("[Preflight] Failed to publish texture access order: "
                        + error.getMessage());
            }
            try {
                JaninoBytecodeCacheRuntime.complete();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable error) {
                System.err.println("[Preflight] Failed to publish Janino bytecode pack: "
                        + error.getMessage());
            }
            try {
                AdapterTransformationCache.complete();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable error) {
                System.err.println("[Preflight] Failed to publish adapter transformation cache: "
                        + error.getMessage());
            }
            try {
                report.write();
            } catch (IOException error) {
                System.err.println("[Preflight] Failed to write adapter report: " + error.getMessage());
            }
            boolean keepRoutineEvidence = fullEvidence(System.getenv(), System.getProperties());
            writeEvidence("code-loader signature", keepRoutineEvidence,
                    codeLoaderReport.routine(), codeLoaderReport::write);
            writeEvidence("audio-decoder signature", keepRoutineEvidence,
                    audioDecoderReport.routine(), audioDecoderReport::write);
            writeEvidence("sound-loader contract", keepRoutineEvidence,
                    soundLoaderReport.routine(), soundLoaderReport::write);
            writeEvidence("texture-loader contract", keepRoutineEvidence,
                    textureLoaderReport.routine(), textureLoaderReport::write);
            writeEvidence("Janino-loader contract", keepRoutineEvidence,
                    janinoLoaderReport.routine(), janinoLoaderReport::write);
        }
    }
}

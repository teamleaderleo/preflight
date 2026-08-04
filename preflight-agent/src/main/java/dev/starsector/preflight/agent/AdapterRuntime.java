package dev.starsector.preflight.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs the optional probe transformer while keeping every unknown build unmodified. */
final class AdapterRuntime {
    private AdapterRuntime() {
    }

    static Session start(AgentOptions options, Instrumentation instrumentation) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(instrumentation, "instrumentation");
        TextureCompatibilityRuntime.beginSession();
        TexturePreparedPixelRuntime.beginSession();
        TexturePaddingRuntime.beginSession();
        VariantJsonCacheRuntime.beginSession();
        WeaponJsonCacheRuntime.beginSession();
        ProjectileJsonCacheRuntime.beginSession();
        HullJsonCacheRuntime.beginSession();
        RulesCsvCacheRuntime.beginSession();
        RulesDuplicateIndexRuntime.beginSession();
        RuleTokenCacheRuntime.beginSession();
        RuleCommandClassCacheRuntime.beginSession();
        MergedReadCacheRuntime.beginSession();
        GraphicsLibCompactReplayPlan.beginSession();
        JaninoBytecodeCacheRuntime.beginSession();
        GraphicsLibInsigniaManagerCacheRuntime.beginSession();
        EntityLookupRuntime.beginSession();
        DeploymentIconCacheRuntime.beginSession();
        SimOpponentSafetyRuntime.beginSession();
        StartupPhaseRuntime.beginSession(options.startupPhaseProbe()
                ? sibling(options.adapterReport(), "startup-phases.json") : null);
        StartupPhaseRuntime.enableMergedReadProbe(options.startupPhaseProbe());
        AdapterReport report = new AdapterReport(
                options.adapterMode(),
                options.adapterReport(),
                options.adapterTargets(),
                options.candidatePrefixes());
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
                report,
                codeLoaderReport,
                audioDecoderReport,
                soundLoaderReport,
                textureLoaderReport,
                janinoLoaderReport,
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
            TextureCompatibilityRuntime.configure(
                    options.textureCacheDirectory(),
                    options.textureManifest(),
                    options.textureIndex());
            VariantJsonCacheRuntime.configure(options.variantJsonCache());
            WeaponJsonCacheRuntime.configure(options.weaponJsonCache());
            ProjectileJsonCacheRuntime.configure(options.projectileJsonCache());
            HullJsonCacheRuntime.configure(options.hullJsonCache());
            RulesCsvCacheRuntime.configure(options.rulesCsvCache());
            RuleCommandClassCacheRuntime.configure(options.ruleCommandClassCache());
            MergedReadCacheRuntime.configure(options.mergedReadCache());
            GraphicsLibCompactReplayPlan.configure(options.graphicsLibCompactReplay());
            JaninoBytecodeCacheRuntime.configure(
                    options.janinoBytecodeCache(), options.janinoBytecodeContext());
            GraphicsLibInsigniaManagerCacheRuntime.configure(
                    options.graphicsLibInsigniaManagerCache());
            if (options.ruleTokenCache()) {
                RuleTokenCacheRuntime.enable();
            }
            ResourceProbeRuntime.enable(options.resourceProbeCache());
            LoadJsonMemoRuntime.enable(options.loadJsonMemo());
            if (options.preparedAudioCache() != null && options.audioDecoderIdentity() != null) {
                PreparedAudioRuntime.enable(true);
                PreparedAudioRuntime.configure(
                        options.preparedAudioCache(), options.audioDecoderIdentity());
            }
        }

        AdapterTargetRegistry registry;
        try {
            registry = loadRegistry(options.adapterTargets(), report);
            if (options.adapterMode() == AdapterMode.ENABLED) {
                registry = registry.withTextureTarget(options.textureAdapterMode());
                report.diagnostic("Loaded the exact refit simulator opponent-safety target");
                report.diagnostic("Loaded the exact resource source-hint isolation target");
                report.diagnostic("Loaded the exact MagicLib unlocked-paintjob set target");
                if (options.startupPhaseProbe()) {
                    registry = registry.withStartupPhaseTarget();
                    report.diagnostic("Loaded the exact ResourceLoaderState and SpecStore startup-phase probe targets");
                }
                if (VariantJsonCacheRuntime.ready()) {
                    registry = registry.withVariantJsonCacheTarget();
                    report.diagnostic("Loaded the exact SpecStore variant JSON cache target ("
                            + VariantJsonCacheRuntime.status() + ")");
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
            report.transformerInstalled(registry.targets().size());
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
        private final AdapterReport report;
        private final CodeLoaderSignatureReport codeLoaderReport;
        private final AudioDecoderSignatureReport audioDecoderReport;
        private final SoundLoaderContractReport soundLoaderReport;
        private final BytecodeShapeReport textureLoaderReport;
        private final BytecodeShapeReport janinoLoaderReport;
        private final boolean writeReport;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Session(
                AdapterReport report,
                CodeLoaderSignatureReport codeLoaderReport,
                AudioDecoderSignatureReport audioDecoderReport,
                SoundLoaderContractReport soundLoaderReport,
                BytecodeShapeReport textureLoaderReport,
                BytecodeShapeReport janinoLoaderReport,
                boolean writeReport) {
            this.report = report;
            this.codeLoaderReport = codeLoaderReport;
            this.audioDecoderReport = audioDecoderReport;
            this.soundLoaderReport = soundLoaderReport;
            this.textureLoaderReport = textureLoaderReport;
            this.janinoLoaderReport = janinoLoaderReport;
            this.writeReport = writeReport;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true) || !writeReport) {
                return;
            }
            try {
                report.write();
            } catch (IOException error) {
                System.err.println("[Preflight] Failed to write adapter report: " + error.getMessage());
            }
            try {
                codeLoaderReport.write();
            } catch (IOException error) {
                System.err.println("[Preflight] Failed to write code-loader signature report: " + error.getMessage());
            }
            try {
                audioDecoderReport.write();
            } catch (IOException error) {
                System.err.println("[Preflight] Failed to write audio-decoder signature report: " + error.getMessage());
            }
            try {
                soundLoaderReport.write();
            } catch (IOException error) {
                System.err.println("[Preflight] Failed to write sound-loader contract report: " + error.getMessage());
            }
            try {
                textureLoaderReport.write();
            } catch (IOException error) {
                System.err.println("[Preflight] Failed to write texture-loader contract report: " + error.getMessage());
            }
            try {
                janinoLoaderReport.write();
            } catch (IOException error) {
                System.err.println("[Preflight] Failed to write Janino-loader contract report: " + error.getMessage());
            }
        }
    }
}

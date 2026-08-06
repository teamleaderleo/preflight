package dev.starsector.preflight.cli;

import dev.starsector.preflight.agent.AdapterMode;
import dev.starsector.preflight.agent.AdapterPlanScope;
import dev.starsector.preflight.agent.RecordingMode;
import dev.starsector.preflight.agent.TextureAdapterMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

record CommandLine(
        Path game,
        Path launcher,
        Path traceDirectory,
        boolean dryRun,
        boolean summarize,
        boolean scan,
        OptimizationPreset optimizationPreset,
        AdapterPlanScope adapterPlanScope,
        AdapterMode adapterMode,
        Path adapterTargets,
        Path textureCacheDirectory,
        Path textureManifest,
        Path textureIndex,
        boolean textureAuto,
        TextureAdapterMode textureAdapterMode,
        boolean exhaustiveFileReads,
        RecordingMode recordingMode,
        boolean singleChunkRecording,
        boolean npotDirect,
        boolean unpadded,
        boolean campaignEntityIndex,
        boolean startupPhaseProbe,
        boolean ruleTokenCache,
        boolean resourceProbeCache,
        boolean preparedAudio,
        boolean loadJsonMemo,
        boolean ruleCommandClassCache,
        boolean graphicsLibCompactReplay,
        boolean janinoBytecodeCache,
        boolean graphicsLibInsigniaManagerCache,
        boolean directLaunch,
        boolean fileOnlyLogs,
        boolean quietLogs,
        boolean suppressAssetProgressLogs,
        boolean trustValidatedTextureIndex,
        List<String> forwardedArgs) {
    static CommandLine parse(String[] args, int offset) {
        Path game = null;
        Path launcher = null;
        Path traceDirectory = null;
        boolean dryRun = false;
        boolean summarize = true;
        boolean scan = true;
        OptimizationPreset optimizationPreset = OptimizationPreset.CUSTOM;
        AdapterPlanScope adapterPlanScope = AdapterPlanScope.FULL;
        boolean exhaustiveFileReads = false;
        RecordingMode recordingMode = RecordingMode.FULL;
        boolean singleChunkRecording = false;
        boolean npotDirect = false;
        boolean unpadded = false;
        boolean campaignEntityIndex = false;
        boolean startupPhaseProbe = false;
        boolean ruleTokenCache = false;
        boolean resourceProbeCache = false;
        boolean preparedAudio = false;
        boolean loadJsonMemo = false;
        boolean ruleCommandClassCache = false;
        boolean graphicsLibCompactReplay = false;
        boolean janinoBytecodeCache = false;
        boolean graphicsLibInsigniaManagerCache = false;
        boolean directLaunch = false;
        boolean fileOnlyLogs = false;
        boolean quietLogs = false;
        boolean suppressAssetProgressLogs = false;
        boolean trustValidatedTextureIndex = false;
        AdapterMode adapterMode = AdapterMode.OFF;
        boolean adapterModeSpecified = false;
        Path adapterTargets = null;
        Path textureCacheDirectory = null;
        Path textureManifest = null;
        Path textureIndex = null;
        boolean textureAuto = false;
        TextureAdapterMode textureAdapterMode = TextureAdapterMode.COMPATIBILITY;
        boolean textureModeSpecified = false;
        List<String> forwarded = new ArrayList<>();
        for (int i = offset; i < args.length; i++) {
            String arg = args[i];
            if ("--fast".equals(arg) || "--optimization-preset".equals(arg)) {
                optimizationPreset = "--fast".equals(arg)
                        ? OptimizationPreset.RECOMMENDED
                        : OptimizationPreset.parse(requireValue(args, ++i, arg));
                PresetConfiguration preset = PresetConfiguration.forPreset(optimizationPreset);
                summarize = preset.summarize();
                scan = preset.scan();
                adapterMode = preset.adapterMode();
                adapterPlanScope = preset.adapterPlanScope();
                textureAuto = preset.textureAuto();
                textureAdapterMode = preset.textureAdapterMode();
                textureModeSpecified = preset.textureAuto();
                exhaustiveFileReads = preset.exhaustiveFileReads();
                recordingMode = preset.recordingMode();
                singleChunkRecording = preset.singleChunkRecording();
                npotDirect = preset.npotDirect();
                unpadded = preset.unpadded();
                campaignEntityIndex = preset.campaignEntityIndex();
                startupPhaseProbe = preset.startupPhaseProbe();
                ruleTokenCache = preset.ruleTokenCache();
                resourceProbeCache = preset.resourceProbeCache();
                preparedAudio = preset.preparedAudio();
                loadJsonMemo = preset.loadJsonMemo();
                ruleCommandClassCache = preset.ruleCommandClassCache();
                graphicsLibCompactReplay = preset.graphicsLibCompactReplay();
                janinoBytecodeCache = preset.janinoBytecodeCache();
                graphicsLibInsigniaManagerCache = preset.graphicsLibInsigniaManagerCache();
                fileOnlyLogs = preset.fileOnlyLogs();
                quietLogs = preset.quietLogs();
                suppressAssetProgressLogs = preset.suppressAssetProgressLogs();
                trustValidatedTextureIndex = preset.trustValidatedTextureIndex();
                continue;
            }
            switch (arg) {
                case "--game" -> game = Path.of(requireValue(args, ++i, arg));
                case "--launcher" -> launcher = Path.of(requireValue(args, ++i, arg));
                case "--trace-dir" -> traceDirectory = Path.of(requireValue(args, ++i, arg));
                case "--dry-run" -> dryRun = true;
                case "--no-summary" -> summarize = false;
                case "--no-scan" -> scan = false;
                case "--adapter-probe" -> {
                    adapterMode = chooseAdapterMode(adapterMode, adapterModeSpecified, AdapterMode.PROBE);
                    adapterModeSpecified = true;
                }
                case "--adapter" -> {
                    adapterMode = chooseAdapterMode(adapterMode, adapterModeSpecified, AdapterMode.ENABLED);
                    adapterModeSpecified = true;
                }
                case "--no-adapter" -> {
                    adapterMode = chooseAdapterMode(adapterMode, adapterModeSpecified, AdapterMode.OFF);
                    adapterModeSpecified = true;
                }
                case "--adapter-targets" -> adapterTargets = Path.of(requireValue(args, ++i, arg));
                case "--texture-cache-dir" -> textureCacheDirectory = Path.of(requireValue(args, ++i, arg));
                case "--texture-manifest" -> textureManifest = Path.of(requireValue(args, ++i, arg));
                case "--texture-index" -> textureIndex = Path.of(requireValue(args, ++i, arg));
                case "--texture-auto" -> textureAuto = true;
                case "--trace-all-file-reads" -> exhaustiveFileReads = true;
                case "--no-record" -> recordingMode = RecordingMode.OFF;
                case "--profile" -> recordingMode = RecordingMode.SAMPLE;
                case "--single-chunk-recording" -> singleChunkRecording = true;
                case "--prepared-npot" -> npotDirect = true;
                case "--prepared-unpadded" -> unpadded = true;
                case "--campaign-entity-index" -> campaignEntityIndex = true;
                case "--no-campaign-entity-index" -> campaignEntityIndex = false;
                case "--startup-phase-probe" -> startupPhaseProbe = true;
                case "--rule-token-cache" -> ruleTokenCache = true;
                case "--resource-probe-cache" -> resourceProbeCache = true;
                case "--prepared-audio" -> preparedAudio = true;
                case "--loadjson-memo" -> loadJsonMemo = true;
                case "--rule-command-cache" -> ruleCommandClassCache = true;
                case "--graphicslib-compact-replay" -> graphicsLibCompactReplay = true;
                case "--janino-bytecode-cache" -> janinoBytecodeCache = true;
                case "--graphicslib-insignia-cache" -> graphicsLibInsigniaManagerCache = true;
                case "--direct" -> directLaunch = true;
                case "--file-only-logs" -> fileOnlyLogs = true;
                case "--quiet-logs" -> {
                    fileOnlyLogs = true;
                    quietLogs = true;
                }
                case "--suppress-asset-progress-logs" -> suppressAssetProgressLogs = true;
                case "--full-asset-progress-logs" -> suppressAssetProgressLogs = false;
                case "--trust-validated-texture-index" -> trustValidatedTextureIndex = true;
                case "--recheck-texture-sources" -> trustValidatedTextureIndex = false;
                case "--texture-mode" -> {
                    textureAdapterMode = TextureAdapterMode.valueOf(
                            requireValue(args, ++i, arg).trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
                    textureModeSpecified = true;
                }
                case "--" -> {
                    for (int j = i + 1; j < args.length; j++) {
                        forwarded.add(args[j]);
                    }
                    i = args.length;
                }
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        if (adapterTargets != null && adapterMode == AdapterMode.OFF) {
            throw new IllegalArgumentException("--adapter-targets requires --adapter-probe or --adapter");
        }
        int textureArtifacts = (textureManifest == null ? 0 : 1) + (textureIndex == null ? 0 : 1);
        boolean manualTextureContext = textureCacheDirectory != null && textureArtifacts == 2;
        if (!textureAuto && (textureCacheDirectory != null || textureArtifacts != 0) && !manualTextureContext) {
            throw new IllegalArgumentException(
                    "--texture-cache-dir, --texture-manifest, and --texture-index must be supplied together");
        }
        if (textureAuto && textureArtifacts != 0) {
            throw new IllegalArgumentException(
                    "--texture-auto resolves the manifest and index; do not supply either artifact path");
        }
        if ((manualTextureContext || textureAuto) && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("Texture adapter options require --adapter");
        }
        if (textureModeSpecified && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("--texture-mode requires --adapter");
        }
        if (textureModeSpecified && !manualTextureContext && !textureAuto) {
            throw new IllegalArgumentException("--texture-mode requires the complete texture cache context");
        }
        // Only the prepared-pixel bridge reads this, and silently accepting it elsewhere would
        // produce a launch that looks configured for non-power-of-two and is not -- which is
        // indistinguishable from the bridge simply declining, the failure this flag exists to fix.
        if ((npotDirect || unpadded) && textureAdapterMode != TextureAdapterMode.PREPARED_PIXELS) {
            throw new IllegalArgumentException(
                    "--prepared-npot and --prepared-unpadded require --texture-mode prepared-pixels");
        }
        // Both carry non-power-of-two textures, and they disagree about how. Together they build a
        // coherent-direct carrier sized for the padded allocation and then supply the true-size
        // buffer -- a shrunken allocation handed a padded buffer, or its inverse, which is the
        // documented insufficient-original-buffer failure.
        if (npotDirect && unpadded) {
            throw new IllegalArgumentException(
                    "--prepared-npot and --prepared-unpadded are alternatives; pass only one");
        }
        if (singleChunkRecording && !recordingMode.records()) {
            throw new IllegalArgumentException("--single-chunk-recording cannot be used with --no-record");
        }
        if (campaignEntityIndex && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("--campaign-entity-index requires --adapter");
        }
        if (loadJsonMemo && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("--loadjson-memo requires --adapter");
        }
        if (preparedAudio && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("--prepared-audio requires --adapter");
        }
        if (resourceProbeCache && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException(
                    "--resource-probe-cache requires --adapter");
        }
        if (ruleTokenCache && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException(
                    "--rule-token-cache requires --adapter");
        }
        if (ruleCommandClassCache && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("--rule-command-cache requires --adapter");
        }
        if (graphicsLibCompactReplay && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("--graphicslib-compact-replay requires --adapter");
        }
        if (janinoBytecodeCache && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("--janino-bytecode-cache requires --adapter");
        }
        if (graphicsLibInsigniaManagerCache && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("--graphicslib-insignia-cache requires --adapter");
        }
        if (startupPhaseProbe && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("--startup-phase-probe requires --adapter");
        }
        if (adapterPlanScope == AdapterPlanScope.PORTABLE_STARTUP
                && (campaignEntityIndex
                        || graphicsLibCompactReplay
                        || graphicsLibInsigniaManagerCache)) {
            throw new IllegalArgumentException(
                    "Gameplay and mod-specific options require the recommended preset or a custom launch");
        }
        // --texture-auto and --texture-mode are independent: auto resolves which manifest and
        // index to use, the mode decides which TextureLoader target reads them. Both modes are
        // configured from the same TextureCompatibilityRuntime.configure call and the same SPFT
        // blobs, so there is nothing for auto to resolve differently. The restriction that used
        // to sit here predated prepared-pixels being able to prove its contract against the
        // installed class, and kept the mode unreachable from the only ergonomic way to launch.
        return new CommandLine(
                game,
                launcher,
                traceDirectory,
                dryRun,
                summarize,
                scan,
                optimizationPreset,
                adapterPlanScope,
                adapterMode,
                adapterTargets,
                textureCacheDirectory,
                textureManifest,
                textureIndex,
                textureAuto,
                textureAdapterMode,
                exhaustiveFileReads,
                recordingMode,
                singleChunkRecording,
                npotDirect,
                unpadded,
                campaignEntityIndex,
                startupPhaseProbe,
                ruleTokenCache,
                resourceProbeCache,
                preparedAudio,
                loadJsonMemo,
                ruleCommandClassCache,
                graphicsLibCompactReplay,
                janinoBytecodeCache,
                graphicsLibInsigniaManagerCache,
                directLaunch,
                fileOnlyLogs,
                quietLogs,
                suppressAssetProgressLogs,
                trustValidatedTextureIndex,
                List.copyOf(forwarded));
    }

    private record PresetConfiguration(
            boolean summarize,
            boolean scan,
            AdapterMode adapterMode,
            AdapterPlanScope adapterPlanScope,
            boolean textureAuto,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean singleChunkRecording,
            boolean npotDirect,
            boolean unpadded,
            boolean campaignEntityIndex,
            boolean startupPhaseProbe,
            boolean ruleTokenCache,
            boolean resourceProbeCache,
            boolean preparedAudio,
            boolean loadJsonMemo,
            boolean ruleCommandClassCache,
            boolean graphicsLibCompactReplay,
            boolean janinoBytecodeCache,
            boolean graphicsLibInsigniaManagerCache,
            boolean fileOnlyLogs,
            boolean quietLogs,
            boolean suppressAssetProgressLogs,
            boolean trustValidatedTextureIndex) {
        static PresetConfiguration forPreset(OptimizationPreset preset) {
            return switch (preset) {
                case CUSTOM -> new PresetConfiguration(
                        true, true, AdapterMode.OFF, preset.planScope(),
                        false, TextureAdapterMode.COMPATIBILITY, false, RecordingMode.FULL,
                        false, false, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false, false);
                case RECOMMENDED -> new PresetConfiguration(
                        true, true, AdapterMode.ENABLED, preset.planScope(),
                        true, TextureAdapterMode.PREPARED_PIXELS, false, RecordingMode.OFF,
                        false, false, true, true, false, true, false, true, true,
                        true, true, true, true, true, false, true, true);
                case CONSERVATIVE -> new PresetConfiguration(
                        true, true, AdapterMode.ENABLED, preset.planScope(),
                        true, TextureAdapterMode.PREPARED_PIXELS, false, RecordingMode.OFF,
                        false, true, false, false, false, true, false, true, true,
                        true, false, true, false, true, false, true, true);
                case OFF -> new PresetConfiguration(
                        false, false, AdapterMode.OFF, preset.planScope(),
                        false, TextureAdapterMode.COMPATIBILITY, false, RecordingMode.OFF,
                        false, false, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false, false);
            };
        }
    }

    private static AdapterMode chooseAdapterMode(
            AdapterMode current,
            boolean alreadySpecified,
            AdapterMode requested) {
        if (alreadySpecified && current != requested) {
            throw new IllegalArgumentException("Conflicting adapter mode options");
        }
        return requested;
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}

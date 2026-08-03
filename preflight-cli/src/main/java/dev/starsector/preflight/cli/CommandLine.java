package dev.starsector.preflight.cli;

import dev.starsector.preflight.agent.AdapterMode;
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
        boolean directLaunch,
        boolean quietLogs,
        boolean disableHeapPretouch,
        List<String> forwardedArgs) {
    static CommandLine parse(String[] args, int offset) {
        Path game = null;
        Path launcher = null;
        Path traceDirectory = null;
        boolean dryRun = false;
        boolean summarize = true;
        boolean scan = true;
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
        boolean directLaunch = false;
        boolean quietLogs = false;
        boolean disableHeapPretouch = false;
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
                case "--startup-phase-probe" -> startupPhaseProbe = true;
                case "--rule-token-cache" -> ruleTokenCache = true;
                case "--resource-probe-cache" -> resourceProbeCache = true;
                case "--prepared-audio" -> preparedAudio = true;
                case "--loadjson-memo" -> loadJsonMemo = true;
                case "--rule-command-cache" -> ruleCommandClassCache = true;
                case "--graphicslib-compact-replay" -> graphicsLibCompactReplay = true;
                case "--direct" -> directLaunch = true;
                case "--quiet-logs" -> quietLogs = true;
                case "--no-heap-pretouch" -> disableHeapPretouch = true;
                // One flag for "everything that has landed and is safe to turn on". The individual
                // flags stay, because a campaign that isolates one of them needs to name it -- but
                // nobody running the game should have to remember seven of them in the right order.
                case "--fast" -> {
                    adapterMode = AdapterMode.ENABLED;
                    textureAuto = true;
                    textureAdapterMode = TextureAdapterMode.PREPARED_PIXELS;
                    textureModeSpecified = true;
                    npotDirect = true;
                    ruleTokenCache = true;
                    ruleCommandClassCache = true;
                    resourceProbeCache = true;
                    preparedAudio = true;
                    loadJsonMemo = true;
                    recordingMode = RecordingMode.OFF;
                }
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
        if (startupPhaseProbe && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("--startup-phase-probe requires --adapter");
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
                directLaunch,
                quietLogs,
                disableHeapPretouch,
                List.copyOf(forwarded));
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

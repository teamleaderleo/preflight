package dev.starsector.preflight.cli;

import dev.starsector.preflight.agent.AdapterMode;
import dev.starsector.preflight.agent.AdapterPlanScope;
import dev.starsector.preflight.agent.RecordingMode;
import dev.starsector.preflight.agent.TextureAdapterMode;
import java.nio.file.Path;
import java.util.Objects;

/** Named immutable configuration for the injected Preflight agent. */
final class AgentLaunchConfig {
    private final Path agentJar;
    private final Path destination;
    private final AdapterMode adapterMode;
    private final Path adapterReport;
    private final Path adapterTargets;
    private final Path textureCacheDirectory;
    private final Path textureManifest;
    private final Path textureIndex;
    private final TextureAdapterMode textureAdapterMode;
    private final boolean exhaustiveFileReads;
    private final RecordingMode recordingMode;
    private final boolean npotDirect;
    private final boolean unpadded;
    private final boolean singleChunkRecording;
    private final boolean campaignEntityIndex;
    private final boolean startupPhaseProbe;
    private final Path variantJsonCache;
    private final Path weaponJsonCache;
    private final Path projectileJsonCache;
    private final Path hullJsonCache;
    private final Path rulesCsvCache;
    private final boolean ruleTokenCache;
    private final Path ruleCommandClassCache;
    private final boolean resourceProbeCache;
    private final boolean loadJsonMemo;
    private final Path preparedAudioCache;
    private final String audioDecoderIdentity;
    private final Path preparedAudioManifest;
    private final String preparedAudioManifestIdentity;
    private final Path mergedReadCache;
    private final boolean quietLogs;
    private final boolean graphicsLibCompactReplay;
    private final Path janinoBytecodeCache;
    private final String janinoBytecodeContext;
    private final boolean graphicsLibInsigniaManagerCache;
    private final AdapterPlanScope adapterPlanScope;

    private AgentLaunchConfig(Builder builder) {
        agentJar = builder.agentJar;
        destination = builder.destination;
        adapterMode = builder.adapterMode;
        adapterReport = builder.adapterReport;
        adapterTargets = builder.adapterTargets;
        textureCacheDirectory = builder.textureCacheDirectory;
        textureManifest = builder.textureManifest;
        textureIndex = builder.textureIndex;
        textureAdapterMode = builder.textureAdapterMode;
        exhaustiveFileReads = builder.exhaustiveFileReads;
        recordingMode = builder.recordingMode;
        npotDirect = builder.npotDirect;
        unpadded = builder.unpadded;
        singleChunkRecording = builder.singleChunkRecording;
        campaignEntityIndex = builder.campaignEntityIndex;
        startupPhaseProbe = builder.startupPhaseProbe;
        variantJsonCache = builder.variantJsonCache;
        weaponJsonCache = builder.weaponJsonCache;
        projectileJsonCache = builder.projectileJsonCache;
        hullJsonCache = builder.hullJsonCache;
        rulesCsvCache = builder.rulesCsvCache;
        ruleTokenCache = builder.ruleTokenCache;
        ruleCommandClassCache = builder.ruleCommandClassCache;
        resourceProbeCache = builder.resourceProbeCache;
        loadJsonMemo = builder.loadJsonMemo;
        preparedAudioCache = builder.preparedAudioCache;
        audioDecoderIdentity = builder.audioDecoderIdentity;
        preparedAudioManifest = builder.preparedAudioManifest;
        preparedAudioManifestIdentity = builder.preparedAudioManifestIdentity;
        mergedReadCache = builder.mergedReadCache;
        quietLogs = builder.quietLogs;
        graphicsLibCompactReplay = builder.graphicsLibCompactReplay;
        janinoBytecodeCache = builder.janinoBytecodeCache;
        janinoBytecodeContext = builder.janinoBytecodeContext;
        graphicsLibInsigniaManagerCache = builder.graphicsLibInsigniaManagerCache;
        adapterPlanScope = builder.adapterPlanScope;
    }

    static Builder builder(Path agentJar, Path destination) {
        return new Builder(agentJar, destination);
    }

    String appendTo(String existing) {
        return AgentInjection.append(
                existing,
                agentJar,
                destination,
                adapterMode,
                adapterReport,
                adapterTargets,
                textureCacheDirectory,
                textureManifest,
                textureIndex,
                textureAdapterMode,
                exhaustiveFileReads,
                recordingMode,
                npotDirect,
                unpadded,
                singleChunkRecording,
                campaignEntityIndex,
                startupPhaseProbe,
                variantJsonCache,
                weaponJsonCache,
                projectileJsonCache,
                hullJsonCache,
                rulesCsvCache,
                ruleTokenCache,
                ruleCommandClassCache,
                resourceProbeCache,
                loadJsonMemo,
                preparedAudioCache,
                audioDecoderIdentity,
                preparedAudioManifest,
                preparedAudioManifestIdentity,
                mergedReadCache,
                quietLogs,
                graphicsLibCompactReplay,
                janinoBytecodeCache,
                janinoBytecodeContext,
                graphicsLibInsigniaManagerCache,
                adapterPlanScope);
    }

    static final class Builder {
        private final Path agentJar;
        private final Path destination;
        private AdapterMode adapterMode = AdapterMode.OFF;
        private Path adapterReport;
        private Path adapterTargets;
        private Path textureCacheDirectory;
        private Path textureManifest;
        private Path textureIndex;
        private TextureAdapterMode textureAdapterMode = TextureAdapterMode.COMPATIBILITY;
        private boolean exhaustiveFileReads;
        private RecordingMode recordingMode = RecordingMode.FULL;
        private boolean npotDirect;
        private boolean unpadded;
        private boolean singleChunkRecording;
        private boolean campaignEntityIndex;
        private boolean startupPhaseProbe;
        private Path variantJsonCache;
        private Path weaponJsonCache;
        private Path projectileJsonCache;
        private Path hullJsonCache;
        private Path rulesCsvCache;
        private boolean ruleTokenCache;
        private Path ruleCommandClassCache;
        private boolean resourceProbeCache;
        private boolean loadJsonMemo;
        private Path preparedAudioCache;
        private String audioDecoderIdentity;
        private Path preparedAudioManifest;
        private String preparedAudioManifestIdentity;
        private Path mergedReadCache;
        private boolean quietLogs;
        private boolean graphicsLibCompactReplay;
        private Path janinoBytecodeCache;
        private String janinoBytecodeContext;
        private boolean graphicsLibInsigniaManagerCache;
        private AdapterPlanScope adapterPlanScope = AdapterPlanScope.FULL;

        private Builder(Path agentJar, Path destination) {
            this.agentJar = Objects.requireNonNull(agentJar, "agentJar");
            this.destination = Objects.requireNonNull(destination, "destination");
        }

        Builder adapterMode(AdapterMode value) { adapterMode = Objects.requireNonNull(value); return this; }
        Builder adapterReport(Path value) { adapterReport = value; return this; }
        Builder adapterTargets(Path value) { adapterTargets = value; return this; }
        Builder textureCacheDirectory(Path value) { textureCacheDirectory = value; return this; }
        Builder textureManifest(Path value) { textureManifest = value; return this; }
        Builder textureIndex(Path value) { textureIndex = value; return this; }
        Builder textureAdapterMode(TextureAdapterMode value) { textureAdapterMode = Objects.requireNonNull(value); return this; }
        Builder exhaustiveFileReads(boolean value) { exhaustiveFileReads = value; return this; }
        Builder recordingMode(RecordingMode value) { recordingMode = Objects.requireNonNull(value); return this; }
        Builder npotDirect(boolean value) { npotDirect = value; return this; }
        Builder unpadded(boolean value) { unpadded = value; return this; }
        Builder singleChunkRecording(boolean value) { singleChunkRecording = value; return this; }
        Builder campaignEntityIndex(boolean value) { campaignEntityIndex = value; return this; }
        Builder startupPhaseProbe(boolean value) { startupPhaseProbe = value; return this; }
        Builder variantJsonCache(Path value) { variantJsonCache = value; return this; }
        Builder weaponJsonCache(Path value) { weaponJsonCache = value; return this; }
        Builder projectileJsonCache(Path value) { projectileJsonCache = value; return this; }
        Builder hullJsonCache(Path value) { hullJsonCache = value; return this; }
        Builder rulesCsvCache(Path value) { rulesCsvCache = value; return this; }
        Builder ruleTokenCache(boolean value) { ruleTokenCache = value; return this; }
        Builder ruleCommandClassCache(Path value) { ruleCommandClassCache = value; return this; }
        Builder resourceProbeCache(boolean value) { resourceProbeCache = value; return this; }
        Builder loadJsonMemo(boolean value) { loadJsonMemo = value; return this; }
        Builder preparedAudioCache(Path value) { preparedAudioCache = value; return this; }
        Builder audioDecoderIdentity(String value) { audioDecoderIdentity = value; return this; }
        Builder preparedAudioManifest(Path value) { preparedAudioManifest = value; return this; }
        Builder preparedAudioManifestIdentity(String value) { preparedAudioManifestIdentity = value; return this; }
        Builder mergedReadCache(Path value) { mergedReadCache = value; return this; }
        Builder quietLogs(boolean value) { quietLogs = value; return this; }
        Builder graphicsLibCompactReplay(boolean value) { graphicsLibCompactReplay = value; return this; }
        Builder janinoBytecodeCache(Path value) { janinoBytecodeCache = value; return this; }
        Builder janinoBytecodeContext(String value) { janinoBytecodeContext = value; return this; }
        Builder graphicsLibInsigniaManagerCache(boolean value) { graphicsLibInsigniaManagerCache = value; return this; }
        Builder adapterPlanScope(AdapterPlanScope value) { adapterPlanScope = Objects.requireNonNull(value); return this; }

        AgentLaunchConfig build() {
            validate();
            return new AgentLaunchConfig(this);
        }

        private void validate() {
            if (adapterPlanScope == null) {
                throw new IllegalArgumentException("Adapter plan scope is required");
            }
            if ((janinoBytecodeCache == null) != (janinoBytecodeContext == null)) {
                throw new IllegalArgumentException(
                        "Janino bytecode cache and compilation context must be supplied together");
            }
            requireEnabledAdapter(
                    janinoBytecodeCache != null,
                    "Janino bytecode cache requires the enabled adapter");
            requireEnabledAdapter(
                    mergedReadCache != null,
                    "Merged read cache requires the enabled adapter");
            if ((preparedAudioCache == null) != (audioDecoderIdentity == null)) {
                throw new IllegalArgumentException(
                        "Prepared audio cache and decoder identity must be supplied together");
            }
            requireEnabledAdapter(
                    preparedAudioCache != null,
                    "Prepared audio requires the enabled adapter");
            if ((preparedAudioManifest == null) != (preparedAudioManifestIdentity == null)) {
                throw new IllegalArgumentException(
                        "Prepared audio manifest and identity must be supplied together");
            }
            if (preparedAudioManifest != null && preparedAudioCache == null) {
                throw new IllegalArgumentException("Prepared audio manifest requires the audio cache");
            }
            requireEnabledAdapter(loadJsonMemo, "loadJSON memo requires the enabled adapter");
            requireEnabledAdapter(resourceProbeCache, "Resource probe cache requires the enabled adapter");
            requireEnabledAdapter(
                    ruleCommandClassCache != null,
                    "Rule command class cache requires the enabled adapter");
            requireEnabledAdapter(ruleTokenCache, "Rule token cache requires the enabled adapter");
            if (singleChunkRecording && !recordingMode.records()) {
                throw new IllegalArgumentException(
                        "Single-chunk recording requires recording to be enabled");
            }
            requireEnabledAdapter(
                    campaignEntityIndex,
                    "Campaign entity index requires the enabled adapter");
            requireEnabledAdapter(
                    startupPhaseProbe,
                    "Startup phase probe requires the enabled adapter");
            requireEnabledAdapter(
                    variantJsonCache != null,
                    "Variant JSON cache requires the enabled adapter");
            requireEnabledAdapter(
                    weaponJsonCache != null,
                    "Weapon JSON cache requires the enabled adapter");
            requireEnabledAdapter(
                    projectileJsonCache != null,
                    "Projectile JSON cache requires the enabled adapter");
            requireEnabledAdapter(
                    hullJsonCache != null,
                    "Hull JSON cache requires the enabled adapter");
            requireEnabledAdapter(
                    rulesCsvCache != null,
                    "Rules CSV cache requires the enabled adapter");
            requireEnabledAdapter(
                    graphicsLibCompactReplay,
                    "GraphicsLib compact replay requires the enabled adapter");
            requireEnabledAdapter(
                    graphicsLibInsigniaManagerCache,
                    "GraphicsLib insignia cache requires the enabled adapter");
        }

        private void requireEnabledAdapter(boolean required, String message) {
            if (required && adapterMode != AdapterMode.ENABLED) {
                throw new IllegalArgumentException(message);
            }
        }
    }
}

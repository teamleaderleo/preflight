package dev.starsector.preflight.cli;

import dev.starsector.preflight.agent.CommodityEventModMemoRuntime;
import dev.starsector.preflight.agent.DeploymentIconCacheRuntime;
import dev.starsector.preflight.agent.EntityLookupRuntime;
import dev.starsector.preflight.agent.RecordingMode;
import dev.starsector.preflight.agent.TexturePaddingRuntime;
import dev.starsector.preflight.agent.TexturePreparedPixelRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

final class AgentInjection {
    private AgentInjection() {
    }

    static String append(String existing, AgentLaunchConfig config) {
        String current = existing == null ? "" : existing.trim();
        String lower = current.toLowerCase(Locale.ROOT);
        if (lower.contains("-javaagent:") && lower.contains("preflight")) {
            throw new IllegalArgumentException("JAVA_TOOL_OPTIONS already contains a Preflight javaagent");
        }

        StringBuilder arguments = new StringBuilder("dest64=")
                .append(encodedPath(config.destination()))
                .append(",adapter=")
                .append(config.adapterMode().optionValue())
                .append(",planScope=")
                .append(config.adapterPlanScope().optionValue())
                .append(",textureMode=")
                .append(config.textureAdapterMode().optionValue());
        appendPath(arguments, "adapterReport64", config.adapterReport());
        appendPath(arguments, "targets64", config.adapterTargets());
        appendPath(arguments, "textureCache64", config.textureCacheDirectory());
        appendPath(arguments, "textureManifest64", config.textureManifest());
        appendPath(arguments, "textureIndex64", config.textureIndex());
        appendPath(arguments, "variantJsonCache64", config.variantJsonCache());
        appendPath(arguments, "weaponJsonCache64", config.weaponJsonCache());
        appendPath(arguments, "projectileJsonCache64", config.projectileJsonCache());
        appendPath(arguments, "hullJsonCache64", config.hullJsonCache());
        appendPath(arguments, "rulesCsvCache64", config.rulesCsvCache());
        appendPath(arguments, "ruleCommandCache64", config.ruleCommandClassCache());
        appendPath(arguments, "mergedReadCache64", config.mergedReadCache());
        appendPath(arguments, "janinoBytecodeCache64", config.janinoBytecodeCache());
        if (config.janinoBytecodeContext() != null) {
            arguments.append(",janinoBytecodeContext=").append(config.janinoBytecodeContext());
        }
        if (config.exhaustiveFileReads()) {
            arguments.append(",fileReads=all");
        }
        if (config.recordingMode() != RecordingMode.FULL) {
            arguments.append(",record=").append(config.recordingMode().optionValue());
        }
        if (config.singleChunkRecording()) {
            // Recording.dump rotates the active chunk. A periodic sidecar is valuable for ordinary
            // sessions but defeats a mode whose entire purpose is one timestamp-coherent chunk.
            arguments.append(",flush=0");
        }
        if (config.startupPhaseProbe()) {
            arguments.append(",startupPhases=on");
        }
        if (config.ruleTokenCache()) {
            arguments.append(",ruleTokens=on");
        }
        if (config.resourceProbeCache()) {
            arguments.append(",resourceProbes=on");
        }
        if (config.loadJsonMemo()) {
            arguments.append(",loadJsonMemo=on");
        }
        if (config.quietLogs()) {
            arguments.append(",quietLogs=on");
        }
        if (config.graphicsLibCompactReplay()) {
            arguments.append(",graphicsLibCompactReplay=on");
        }
        if (config.graphicsLibInsigniaManagerCache()) {
            arguments.append(",graphicsLibInsigniaManagerCache=on");
        }
        if (config.preparedAudioCache() != null) {
            appendPath(arguments, "preparedAudioCache64", config.preparedAudioCache());
            arguments.append(",audioDecoder=").append(config.audioDecoderIdentity());
            if (config.preparedAudioManifest() != null) {
                appendPath(arguments, "preparedAudioManifest64", config.preparedAudioManifest());
                arguments.append(",preparedAudioManifestIdentity=")
                        .append(config.preparedAudioManifestIdentity());
            }
        }
        String option = "-javaagent:"
                + quoteJvmOptionValue(config.agentJar().toAbsolutePath().normalize().toString())
                + "="
                + arguments;
        // A system property rather than an agent option because the runtime reads it on every
        // texture through Boolean.getBoolean, which is a property read by construction.
        if (config.npotDirect()) {
            option = option + " -D" + TexturePreparedPixelRuntime.COHERENT_DIRECT_PROPERTY + "=true";
        }
        if (config.unpadded()) {
            option = option + " -D" + TexturePaddingRuntime.UNPADDED_PROPERTY + "=true";
        }
        if (config.singleChunkRecording()) {
            // JFR's ordinary 12 MiB chunk rotation is the source of the cross-chunk timestamp
            // folding observed on the reviewed Zulu 17 runtime. maxchunksize cannot exceed the
            // recorder memory size, so these are one policy and must move together.
            option = option + " -XX:FlightRecorderOptions=memorysize=256m,maxchunksize=256m";
        }
        if (config.campaignEntityIndex()) {
            option = option + " -D" + EntityLookupRuntime.ENABLED_PROPERTY + "=true"
                    + " -D" + DeploymentIconCacheRuntime.ENABLED_PROPERTY + "=true"
                    + " -D" + CommodityEventModMemoRuntime.ENABLED_PROPERTY + "=true";
        }
        return current.isEmpty() ? option : current + " " + option;
    }

    private static void appendPath(StringBuilder arguments, String key, Path value) {
        if (value != null) {
            arguments.append(',').append(key).append('=').append(encodedPath(value));
        }
    }

    private static String encodedPath(Path value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String quoteJvmOptionValue(String value) {
        if (value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Unsupported character in javaagent path: " + value);
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            return '"' + value + '"';
        }
        return value;
    }
}

package dev.starsector.preflight.cli;

import dev.starsector.preflight.agent.AdapterMode;
import dev.starsector.preflight.agent.EntityLookupRuntime;
import dev.starsector.preflight.agent.RecordingMode;
import dev.starsector.preflight.agent.TexturePaddingRuntime;
import dev.starsector.preflight.agent.TexturePreparedPixelRuntime;
import dev.starsector.preflight.agent.TextureAdapterMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

final class AgentInjection {
    private AgentInjection() {
    }

    static String append(String existing, Path agentJar, Path destination) {
        return append(
                existing,
                agentJar,
                destination,
                AdapterMode.OFF,
                null,
                null,
                null,
                null,
                null,
                TextureAdapterMode.COMPATIBILITY);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets) {
        return append(
                existing,
                agentJar,
                destination,
                adapterMode,
                adapterReport,
                adapterTargets,
                null,
                null,
                null,
                TextureAdapterMode.COMPATIBILITY);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex) {
        return append(
                existing,
                agentJar,
                destination,
                adapterMode,
                adapterReport,
                adapterTargets,
                textureCacheDirectory,
                textureManifest,
                textureIndex,
                TextureAdapterMode.COMPATIBILITY);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode) {
        return append(existing, agentJar, destination, adapterMode, adapterReport, adapterTargets,
                textureCacheDirectory, textureManifest, textureIndex, textureAdapterMode, false,
                RecordingMode.FULL, false, false, false, false);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean npotDirect,
            boolean unpadded) {
        return append(
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
                false,
                false);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean npotDirect,
            boolean unpadded,
            boolean singleChunkRecording) {
        return append(
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
                false);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean npotDirect,
            boolean unpadded,
            boolean singleChunkRecording,
            boolean campaignEntityIndex) {
        return append(existing, agentJar, destination, adapterMode, adapterReport, adapterTargets,
                textureCacheDirectory, textureManifest, textureIndex, textureAdapterMode,
                exhaustiveFileReads, recordingMode, npotDirect, unpadded, singleChunkRecording,
                campaignEntityIndex, false);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean npotDirect,
            boolean unpadded,
            boolean singleChunkRecording,
            boolean campaignEntityIndex,
            boolean startupPhaseProbe) {
        return append(existing, agentJar, destination, adapterMode, adapterReport, adapterTargets,
                textureCacheDirectory, textureManifest, textureIndex, textureAdapterMode,
                exhaustiveFileReads, recordingMode, npotDirect, unpadded, singleChunkRecording,
                campaignEntityIndex, startupPhaseProbe, null, null);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean npotDirect,
            boolean unpadded,
            boolean singleChunkRecording,
            boolean campaignEntityIndex,
            boolean startupPhaseProbe,
            Path variantJsonCache) {
        return append(existing, agentJar, destination, adapterMode, adapterReport, adapterTargets,
                textureCacheDirectory, textureManifest, textureIndex, textureAdapterMode,
                exhaustiveFileReads, recordingMode, npotDirect, unpadded, singleChunkRecording,
                campaignEntityIndex, startupPhaseProbe, variantJsonCache, null);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean npotDirect,
            boolean unpadded,
            boolean singleChunkRecording,
            boolean campaignEntityIndex,
            boolean startupPhaseProbe,
            Path variantJsonCache,
            Path weaponJsonCache) {
        return append(existing, agentJar, destination, adapterMode, adapterReport, adapterTargets,
                textureCacheDirectory, textureManifest, textureIndex, textureAdapterMode,
                exhaustiveFileReads, recordingMode, npotDirect, unpadded, singleChunkRecording,
                campaignEntityIndex, startupPhaseProbe, variantJsonCache, weaponJsonCache, null);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean npotDirect,
            boolean unpadded,
            boolean singleChunkRecording,
            boolean campaignEntityIndex,
            boolean startupPhaseProbe,
            Path variantJsonCache,
            Path weaponJsonCache,
            Path projectileJsonCache) {
        return append(existing, agentJar, destination, adapterMode, adapterReport, adapterTargets,
                textureCacheDirectory, textureManifest, textureIndex, textureAdapterMode,
                exhaustiveFileReads, recordingMode, npotDirect, unpadded, singleChunkRecording,
                campaignEntityIndex, startupPhaseProbe, variantJsonCache, weaponJsonCache,
                projectileJsonCache, null);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean npotDirect,
            boolean unpadded,
            boolean singleChunkRecording,
            boolean campaignEntityIndex,
            boolean startupPhaseProbe,
            Path variantJsonCache,
            Path weaponJsonCache,
            Path projectileJsonCache,
            Path hullJsonCache) {
        return append(existing, agentJar, destination, adapterMode, adapterReport, adapterTargets,
                textureCacheDirectory, textureManifest, textureIndex, textureAdapterMode,
                exhaustiveFileReads, recordingMode, npotDirect, unpadded, singleChunkRecording,
                campaignEntityIndex, startupPhaseProbe, variantJsonCache, weaponJsonCache,
                projectileJsonCache, hullJsonCache, null);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean npotDirect,
            boolean unpadded,
            boolean singleChunkRecording,
            boolean campaignEntityIndex,
            boolean startupPhaseProbe,
            Path variantJsonCache,
            Path weaponJsonCache,
            Path projectileJsonCache,
            Path hullJsonCache,
            Path rulesCsvCache) {
        return append(existing, agentJar, destination, adapterMode, adapterReport, adapterTargets,
                textureCacheDirectory, textureManifest, textureIndex, textureAdapterMode,
                exhaustiveFileReads, recordingMode, npotDirect, unpadded, singleChunkRecording,
                campaignEntityIndex, startupPhaseProbe, variantJsonCache, weaponJsonCache,
                projectileJsonCache, hullJsonCache, rulesCsvCache, false);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean npotDirect,
            boolean unpadded,
            boolean singleChunkRecording,
            boolean campaignEntityIndex,
            boolean startupPhaseProbe,
            Path variantJsonCache,
            Path weaponJsonCache,
            Path projectileJsonCache,
            Path hullJsonCache,
            Path rulesCsvCache,
            boolean ruleTokenCache) {
        return append(existing, agentJar, destination, adapterMode, adapterReport, adapterTargets,
                textureCacheDirectory, textureManifest, textureIndex, textureAdapterMode,
                exhaustiveFileReads, recordingMode, npotDirect, unpadded, singleChunkRecording,
                campaignEntityIndex, startupPhaseProbe, variantJsonCache, weaponJsonCache,
                projectileJsonCache, hullJsonCache, rulesCsvCache, ruleTokenCache, null);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean npotDirect,
            boolean unpadded,
            boolean singleChunkRecording,
            boolean campaignEntityIndex,
            boolean startupPhaseProbe,
            Path variantJsonCache,
            Path weaponJsonCache,
            Path projectileJsonCache,
            Path hullJsonCache,
            Path rulesCsvCache,
            boolean ruleTokenCache,
            Path ruleCommandClassCache) {
        return append(existing, agentJar, destination, adapterMode, adapterReport, adapterTargets,
                textureCacheDirectory, textureManifest, textureIndex, textureAdapterMode,
                exhaustiveFileReads, recordingMode, npotDirect, unpadded, singleChunkRecording,
                campaignEntityIndex, startupPhaseProbe, variantJsonCache, weaponJsonCache,
                projectileJsonCache, hullJsonCache, rulesCsvCache, ruleTokenCache,
                ruleCommandClassCache, false);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean npotDirect,
            boolean unpadded,
            boolean singleChunkRecording,
            boolean campaignEntityIndex,
            boolean startupPhaseProbe,
            Path variantJsonCache,
            Path weaponJsonCache,
            Path projectileJsonCache,
            Path hullJsonCache,
            Path rulesCsvCache,
            boolean ruleTokenCache,
            Path ruleCommandClassCache,
            boolean resourceProbeCache) {
        return append(existing, agentJar, destination, adapterMode, adapterReport, adapterTargets,
                textureCacheDirectory, textureManifest, textureIndex, textureAdapterMode,
                exhaustiveFileReads, recordingMode, npotDirect, unpadded, singleChunkRecording,
                campaignEntityIndex, startupPhaseProbe, variantJsonCache, weaponJsonCache,
                projectileJsonCache, hullJsonCache, rulesCsvCache, ruleTokenCache,
                ruleCommandClassCache, resourceProbeCache, false);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean npotDirect,
            boolean unpadded,
            boolean singleChunkRecording,
            boolean campaignEntityIndex,
            boolean startupPhaseProbe,
            Path variantJsonCache,
            Path weaponJsonCache,
            Path projectileJsonCache,
            Path hullJsonCache,
            Path rulesCsvCache,
            boolean ruleTokenCache,
            Path ruleCommandClassCache,
            boolean resourceProbeCache,
            boolean loadJsonMemo) {
        return append(existing, agentJar, destination, adapterMode, adapterReport, adapterTargets,
                textureCacheDirectory, textureManifest, textureIndex, textureAdapterMode,
                exhaustiveFileReads, recordingMode, npotDirect, unpadded, singleChunkRecording,
                campaignEntityIndex, startupPhaseProbe, variantJsonCache, weaponJsonCache,
                projectileJsonCache, hullJsonCache, rulesCsvCache, ruleTokenCache,
                ruleCommandClassCache, resourceProbeCache, loadJsonMemo, null, null, null);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            RecordingMode recordingMode,
            boolean npotDirect,
            boolean unpadded,
            boolean singleChunkRecording,
            boolean campaignEntityIndex,
            boolean startupPhaseProbe,
            Path variantJsonCache,
            Path weaponJsonCache,
            Path projectileJsonCache,
            Path hullJsonCache,
            Path rulesCsvCache,
            boolean ruleTokenCache,
            Path ruleCommandClassCache,
            boolean resourceProbeCache,
            boolean loadJsonMemo,
            Path preparedAudioCache,
            String audioDecoderIdentity,
            Path mergedReadCache) {
        if (mergedReadCache != null && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("Merged read cache requires the enabled adapter");
        }
        if (preparedAudioCache != null && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("Prepared audio requires the enabled adapter");
        }
        if (loadJsonMemo && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("loadJSON memo requires the enabled adapter");
        }
        if (resourceProbeCache && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("Resource probe cache requires the enabled adapter");
        }
        if (ruleCommandClassCache != null && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("Rule command class cache requires the enabled adapter");
        }
        if (ruleTokenCache && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("Rule token cache requires the enabled adapter");
        }
        if (singleChunkRecording && !recordingMode.records()) {
            throw new IllegalArgumentException("Single-chunk recording requires recording to be enabled");
        }
        if (campaignEntityIndex && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("Campaign entity index requires the enabled adapter");
        }
        if (startupPhaseProbe && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("Startup phase probe requires the enabled adapter");
        }
        if (variantJsonCache != null && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("Variant JSON cache requires the enabled adapter");
        }
        if (weaponJsonCache != null && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("Weapon JSON cache requires the enabled adapter");
        }
        if (projectileJsonCache != null && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("Projectile JSON cache requires the enabled adapter");
        }
        if (hullJsonCache != null && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("Hull JSON cache requires the enabled adapter");
        }
        if (rulesCsvCache != null && adapterMode != AdapterMode.ENABLED) {
            throw new IllegalArgumentException("Rules CSV cache requires the enabled adapter");
        }
        String current = existing == null ? "" : existing.trim();
        String lower = current.toLowerCase(Locale.ROOT);
        if (lower.contains("-javaagent:") && lower.contains("preflight")) {
            throw new IllegalArgumentException("JAVA_TOOL_OPTIONS already contains a Preflight javaagent");
        }

        StringBuilder arguments = new StringBuilder("dest64=")
                .append(encodedPath(destination))
                .append(",adapter=")
                .append(adapterMode.optionValue())
                .append(",textureMode=")
                .append(textureAdapterMode.optionValue());
        appendPath(arguments, "adapterReport64", adapterReport);
        appendPath(arguments, "targets64", adapterTargets);
        appendPath(arguments, "textureCache64", textureCacheDirectory);
        appendPath(arguments, "textureManifest64", textureManifest);
        appendPath(arguments, "textureIndex64", textureIndex);
        appendPath(arguments, "variantJsonCache64", variantJsonCache);
        appendPath(arguments, "weaponJsonCache64", weaponJsonCache);
        appendPath(arguments, "projectileJsonCache64", projectileJsonCache);
        appendPath(arguments, "hullJsonCache64", hullJsonCache);
        appendPath(arguments, "rulesCsvCache64", rulesCsvCache);
        appendPath(arguments, "ruleCommandCache64", ruleCommandClassCache);
        appendPath(arguments, "mergedReadCache64", mergedReadCache);
        if (exhaustiveFileReads) {
            arguments.append(",fileReads=all");
        }
        if (recordingMode != RecordingMode.FULL) {
            arguments.append(",record=").append(recordingMode.optionValue());
        }
        if (singleChunkRecording) {
            // Recording.dump rotates the active chunk. A periodic sidecar is valuable for ordinary
            // sessions but defeats a mode whose entire purpose is one timestamp-coherent chunk.
            arguments.append(",flush=0");
        }
        if (startupPhaseProbe) {
            arguments.append(",startupPhases=on");
        }
        if (ruleTokenCache) {
            arguments.append(",ruleTokens=on");
        }
        if (resourceProbeCache) {
            arguments.append(",resourceProbes=on");
        }
        if (loadJsonMemo) {
            arguments.append(",loadJsonMemo=on");
        }
        // Both or neither: a cache with no decoder identity cannot be trusted to hold this
        // decoder's output, and an identity with no cache has nothing to check it against.
        if (preparedAudioCache != null && audioDecoderIdentity != null) {
            appendPath(arguments, "preparedAudioCache64", preparedAudioCache);
            arguments.append(",audioDecoder=").append(audioDecoderIdentity);
        }
        String option = "-javaagent:"
                + quoteJvmOptionValue(agentJar.toAbsolutePath().normalize().toString())
                + "="
                + arguments;
        // A system property rather than an agent option because the runtime reads it on every
        // texture through Boolean.getBoolean, which is a property read by construction.
        if (npotDirect) {
            option = option + " -D" + TexturePreparedPixelRuntime.COHERENT_DIRECT_PROPERTY + "=true";
        }
        if (unpadded) {
            option = option + " -D" + TexturePaddingRuntime.UNPADDED_PROPERTY + "=true";
        }
        if (singleChunkRecording) {
            // JFR's ordinary 12 MiB chunk rotation is the source of the cross-chunk timestamp
            // folding observed on the reviewed Zulu 17 runtime. maxchunksize cannot exceed the
            // recorder memory size, so these are one policy and must move together.
            option = option + " -XX:FlightRecorderOptions=memorysize=256m,maxchunksize=256m";
        }
        if (campaignEntityIndex) {
            option = option + " -D" + EntityLookupRuntime.ENABLED_PROPERTY + "=true";
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

    static String quoteJvmOptionValue(String value) {
        if (value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Unsupported character in javaagent path: " + value);
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            return '"' + value + '"';
        }
        return value;
    }
}

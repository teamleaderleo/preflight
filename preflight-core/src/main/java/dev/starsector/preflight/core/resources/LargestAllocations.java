package dev.starsector.preflight.core.resources;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top allocation candidates across textures, audio, and JAR bytecode.
 */
public record LargestAllocations(
        List<LargestTextureAllocation> textures,
        List<LargestAudioAllocation> audio,
        List<LargestJarAllocation> jars) {

    public LargestAllocations {
        textures = List.copyOf(textures);
        audio = List.copyOf(audio);
        jars = List.copyOf(jars);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("textures", textures.stream().map(LargestTextureAllocation::toMap).toList());
        map.put("audio", audio.stream().map(LargestAudioAllocation::toMap).toList());
        map.put("jars", jars.stream().map(LargestJarAllocation::toMap).toList());
        return map;
    }

    public record LargestTextureAllocation(
            String logicalPath,
            String modId,
            int width,
            int height,
            int channels,
            long diskBytes,
            long residentBytes,
            long paddingWasteBytes,
            String winnerModId) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("logicalPath", logicalPath);
            map.put("modId", modId);
            map.put("width", width);
            map.put("height", height);
            map.put("channels", channels);
            map.put("diskBytes", diskBytes);
            map.put("residentBytes", residentBytes);
            map.put("paddingWasteBytes", paddingWasteBytes);
            map.put("winnerModId", winnerModId);
            return map;
        }
    }

    public record LargestAudioAllocation(
            String logicalPath,
            String modId,
            String kind,
            int channels,
            int sampleRate,
            double durationSeconds,
            long diskBytes,
            long pcmBytes) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("logicalPath", logicalPath);
            map.put("modId", modId);
            map.put("kind", kind);
            map.put("channels", channels);
            map.put("sampleRate", sampleRate);
            map.put("durationSeconds", Math.round(durationSeconds * 1000.0) / 1000.0);
            map.put("diskBytes", diskBytes);
            map.put("pcmBytes", pcmBytes);
            return map;
        }
    }

    public record LargestJarAllocation(
            String modId,
            String relativePath,
            long diskBytes,
            long uncompressedBytecodeBytes,
            int classCount) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("modId", modId);
            map.put("relativePath", relativePath);
            map.put("diskBytes", diskBytes);
            map.put("uncompressedBytecodeBytes", uncompressedBytecodeBytes);
            map.put("classCount", classCount);
            return map;
        }
    }
}

package dev.starsector.preflight.cli;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable machine-readable identity for the prepared-data portion of one selected launch policy. */
final class LaunchConditionIdentity {
    private LaunchConditionIdentity() {
    }

    static Snapshot from(
            Platform platform,
            CommandLine options,
            boolean preparedTexturesEffective,
            boolean preparedAudioValidated,
            String preparedAudioFallbackState) {
        boolean texturesRequested = options.textureAuto();
        boolean audioRequested = options.preparedAudio();
        String intended = id(
                platform,
                options.optimizationPreset(),
                texturesRequested ? "prepared-textures" : "prepared-textures-disabled",
                audioRequested ? "prepared-audio-validated" : audioDisabledLabel(options));
        String effective = id(
                platform,
                options.optimizationPreset(),
                texturesRequested
                        ? (preparedTexturesEffective ? "prepared-textures" : "prepared-textures-fallback")
                        : "prepared-textures-disabled",
                audioRequested
                        ? (preparedAudioValidated
                                ? "prepared-audio-validated"
                                : preparedAudioFallbackState == null
                                        ? "prepared-audio-game-decode"
                                        : "prepared-audio-" + preparedAudioFallbackState)
                        : audioDisabledLabel(options));

        Map<String, Object> requestedPolicies = new LinkedHashMap<>();
        requestedPolicies.put("optimizationPreset", options.optimizationPreset().optionValue());
        requestedPolicies.put("disabledOptimizationDomains", options.disabledOptimizationDomains().stream()
                .map(OptimizationDomain::optionValue)
                .sorted()
                .toList());

        Map<String, Object> effectivePolicies = new LinkedHashMap<>();
        effectivePolicies.put("adapterMode", options.adapterMode().name().toLowerCase(java.util.Locale.ROOT));
        effectivePolicies.put("adapterPlanScope", options.adapterPlanScope().optionValue());
        effectivePolicies.put("preparedTexturesEnabled", texturesRequested);
        effectivePolicies.put("preparedTexturesCurrent", preparedTexturesEffective);
        effectivePolicies.put("preparedAudioEnabled", audioRequested);
        effectivePolicies.put("preparedAudioValidated", preparedAudioValidated);
        effectivePolicies.put("preparedAudioFallbackState", audioRequested
                ? preparedAudioFallbackState
                : "disabled");
        effectivePolicies.put("windowsRecommendedValidatedPreparedAudio",
                platform == Platform.WINDOWS
                        && options.optimizationPreset() == OptimizationPreset.RECOMMENDED
                        && preparedAudioValidated);

        return new Snapshot(
                intended,
                effective,
                intended.equals(effective),
                Collections.unmodifiableMap(new LinkedHashMap<>(requestedPolicies)),
                Collections.unmodifiableMap(new LinkedHashMap<>(effectivePolicies)));
    }

    private static String audioDisabledLabel(CommandLine options) {
        return options.disabledOptimizationDomains().contains(OptimizationDomain.PREPARED_AUDIO)
                ? "prepared-audio-disabled-explicit"
                : "prepared-audio-disabled-by-preset";
    }

    private static String id(
            Platform platform, OptimizationPreset preset, String texture, String audio) {
        return "preflight-launch-condition-v1/"
                + platform.name().toLowerCase(java.util.Locale.ROOT)
                + "/" + preset.optionValue()
                + "/" + texture
                + "+" + audio;
    }

    record Snapshot(
            String intended,
            String effective,
            boolean intendedEqualsEffective,
            Map<String, Object> requestedPolicies,
            Map<String, Object> effectivePolicies) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("format", "starsector-preflight-launch-condition-v1");
            values.put("intended", intended);
            values.put("effective", effective);
            values.put("intendedEqualsEffective", intendedEqualsEffective);
            values.put("requestedPolicies", requestedPolicies);
            values.put("effectivePolicies", effectivePolicies);
            return values;
        }
    }
}

package dev.starsector.preflight.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Graduates a successful script/CLI launch from bootstrap textures to learned Compact storage. */
final class AutomaticTextureGraduation {
    static final String DISABLE_ENVIRONMENT = "PREFLIGHT_DISABLE_AUTOMATIC_COMPACTION";

    private AutomaticTextureGraduation() {
    }

    static void afterRun(
            CommandLine options,
            LaunchTarget target,
            LaunchCacheContexts.Texture texture,
            int exitCode,
            Map<String, String> environment) {
        if (!eligible(options, texture, exitCode, environment)) {
            return;
        }
        CacheHealth.Report before = CacheHealth.inspect(
                PreflightHome.current(), texture.profileFingerprint());
        if (!needsGraduation(before, texture.profileFingerprint())) {
            return;
        }

        System.out.println("Preflight learned this profile's startup texture order."
                + " Building its smaller, ordered launch pack now…");
        try {
            int status = PrepareCommand.execute(arguments(target, texture), 0);
            CacheHealth.Report after = CacheHealth.inspect(
                    PreflightHome.current(), texture.profileFingerprint());
            if (status == 0 && isLearnedReady(after, texture.profileFingerprint())) {
                System.out.println("Preflight activated the learned Compact texture pack for the next launch.");
            } else {
                System.err.println("Preflight kept the existing prepared texture pack; automatic Compact"
                        + " preparation did not complete. Run `preflight prepare --texture-scope learned`"
                        + " to retry.");
            }
        } catch (Exception error) {
            String message = error.getMessage();
            System.err.println("Preflight kept the existing prepared texture pack; automatic Compact"
                    + " preparation failed: "
                    + (message == null || message.isBlank() ? error.getClass().getSimpleName() : message));
        }
    }

    static boolean eligible(
            CommandLine options,
            LaunchCacheContexts.Texture texture,
            int exitCode,
            Map<String, String> environment) {
        return exitCode == 0
                && options.optimizationPreset() == OptimizationPreset.RECOMMENDED
                && texture != null
                && texture.automatic()
                && texture.preparedTextures()
                && !disabled(environment.get(DISABLE_ENVIRONMENT))
                // The desktop already owns a delayed, observable background transition. Keeping
                // this path to standalone CLI/script launches avoids holding its run-state open.
                && !environment.containsKey(DesktopRunEvents.ENVIRONMENT_VARIABLE);
    }

    static boolean needsGraduation(CacheHealth.Report health, String profile) {
        return health != null
                && profile.equals(health.profileFingerprint())
                && "ready".equals(health.status())
                && Boolean.TRUE.equals(health.preparedTextures())
                && "balanced".equals(health.textureStorage())
                && "full".equals(health.textureScope())
                && health.compactAvailable();
    }

    static boolean isLearnedReady(CacheHealth.Report health, String profile) {
        return health != null
                && profile.equals(health.profileFingerprint())
                && "ready".equals(health.status())
                && Boolean.TRUE.equals(health.preparedTextures())
                && "balanced".equals(health.textureStorage())
                && "learned".equals(health.textureScope());
    }

    private static String[] arguments(
            LaunchTarget target, LaunchCacheContexts.Texture texture) {
        List<String> arguments = new ArrayList<>(List.of(
                "--game", target.installRoot().toString(),
                "--cache-dir", texture.cacheDirectory().toString(),
                "--texture-storage", "balanced",
                "--texture-scope", "learned",
                "--parallel-stages"));
        return arguments.toArray(String[]::new);
    }

    private static boolean disabled(String value) {
        return value != null
                && !value.isBlank()
                && !"0".equals(value)
                && !"false".equalsIgnoreCase(value);
    }
}

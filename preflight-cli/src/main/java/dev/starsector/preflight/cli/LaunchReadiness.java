package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ResourceIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Machine-readable admission check for a selected launch policy. */
final class LaunchReadiness {
    static final String FORMAT = "starsector-preflight-launch-readiness-v1";

    private LaunchReadiness() {
    }

    static Report inspect(
            Platform platform,
            CommandLine options,
            LaunchTarget target,
            Path requestedCache,
            RunIdentity engineIdentity) {
        Path cache;
        try {
            cache = CacheRootBoundary.canonical(requestedCache == null
                    ? PrepareCommand.defaultCacheDirectory()
                    : requestedCache);
        } catch (Exception error) {
            Component textures = component(
                    "prepared-textures", options.textureAuto(), "unsafe", false, false,
                    message(error), null, null);
            Component audio = component(
                    "prepared-audio", options.preparedAudio(), "unsafe", false, false,
                    message(error), null, "game-decode");
            return report(platform, options, target, null, null, engineIdentity, textures, audio, List.of());
        }

        ResourceIndex current;
        try {
            current = ResourceIndexBuilder.build(target.installRoot()).index();
        } catch (Exception error) {
            Component textures = component(
                    "prepared-textures", options.textureAuto(), "unknown", false, false,
                    message(error), null, null);
            Component audio = component(
                    "prepared-audio", options.preparedAudio(), "unknown", false, false,
                    message(error), null, "game-decode");
            return report(platform, options, target, cache, null, engineIdentity, textures, audio, List.of());
        }

        Component textures = inspectTextures(options, target, cache, current.profileFingerprint());
        Component audio = inspectAudio(options, target, cache, current);
        List<RefreshAction> refreshes = new ArrayList<>();
        if (textures.required() && !textures.usable() && textures.refreshAction() != null) {
            refreshes.add(textures.refreshAction());
        }
        if (audio.required() && !audio.usable() && audio.refreshAction() != null) {
            refreshes.add(audio.refreshAction());
        }
        return report(
                platform,
                options,
                target,
                cache,
                current.profileFingerprint(),
                engineIdentity,
                textures,
                audio,
                List.copyOf(refreshes));
    }

    private static Component inspectTextures(
            CommandLine options, LaunchTarget target, Path cache, String profile) {
        if (!options.textureAuto()) {
            boolean explicit = options.disabledOptimizationDomains()
                    .contains(OptimizationDomain.PREPARED_TEXTURES);
            return component(
                    "prepared-textures", false, "disabled", true, explicit,
                    explicit ? "Prepared textures are explicitly disabled for this condition."
                            : "The selected preset does not request prepared textures.",
                    null, null);
        }
        try {
            CurrentTextureCache.Resolution resolved = CurrentTextureCache.resolve(target.installRoot(), cache);
            if (resolved.minimal()) {
                return component(
                        "prepared-textures", true, "partial", false, true,
                        "Minimal preparation intentionally omits prepared textures.",
                        textureRefresh(target, cache), null);
            }
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("profileFingerprint", resolved.profileFingerprint());
            identity.put("manifest", resolved.manifest());
            identity.put("manifestSha256", resolved.manifestSha256());
            identity.put("index", resolved.index());
            identity.put("indexSha256", resolved.indexSha256());
            identity.put("checkedProviders", resolved.checkedProviders());
            return new Component(
                    "prepared-textures", true, "current", true, false,
                    null, Collections.unmodifiableMap(new LinkedHashMap<>(identity)), null, null);
        } catch (Exception error) {
            Path index = cache.resolve("resource-indexes").resolve(profile + ".spfi");
            Path legacyIndex = cache.resolve("indexes").resolve(profile + ".spfi");
            String state = Files.isRegularFile(index) || Files.isRegularFile(legacyIndex)
                    ? "stale"
                    : "missing";
            return component(
                    "prepared-textures", true, state, false, false,
                    message(error), textureRefresh(target, cache), null);
        }
    }

    private static Component inspectAudio(
            CommandLine options, LaunchTarget target, Path cache, ResourceIndex current) {
        if (!options.preparedAudio()) {
            boolean explicit = options.disabledOptimizationDomains()
                    .contains(OptimizationDomain.PREPARED_AUDIO);
            return component(
                    "prepared-audio", false, "disabled", true, explicit,
                    explicit ? "Prepared audio is explicitly disabled for this condition."
                            : "The selected preset does not request prepared audio.",
                    null, "disabled");
        }
        try (ProfileIdentityContext context = ProfileIdentityContext.of(target.installRoot(), current)) {
            PreparedAudioLaunchReadiness.Result selected =
                    PreparedAudioLaunchReadiness.inspect(context, cache);
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("decoderIdentitySha256", selected.decoderIdentity());
            identity.put("manifest", selected.manifest());
            identity.put("manifestIdentitySha256", selected.manifestIdentity());
            identity.put("pathEntries", selected.pathEntries());
            identity.put("sourceBytes", selected.sourceBytes());
            identity.put("validationMillis", selected.validationMillis());
            return new Component(
                    "prepared-audio",
                    true,
                    selected.state(),
                    selected.current(),
                    false,
                    selected.diagnostic(),
                    Collections.unmodifiableMap(new LinkedHashMap<>(identity)),
                    selected.current() ? null : audioRefresh(target, cache),
                    selected.fallbackState());
        } catch (Exception error) {
            return component(
                    "prepared-audio", true, "stale", false, false,
                    message(error), audioRefresh(target, cache), "game-decode");
        }
    }

    private static RefreshAction textureRefresh(LaunchTarget target, Path cache) {
        return new RefreshAction(
                "prepared-textures",
                List.of("prepare", "--game", target.installRoot().toString(),
                        "--cache-dir", cache.toString(), "--deep", "--verify-lookups"));
    }

    private static RefreshAction audioRefresh(LaunchTarget target, Path cache) {
        return new RefreshAction(
                "prepared-audio",
                List.of("audio", "prepare", "--game", target.installRoot().toString(),
                        "--cache", cache.toString()));
    }

    private static Component component(
            String id,
            boolean required,
            String state,
            boolean usable,
            boolean intentionalPartial,
            String diagnostic,
            RefreshAction refresh,
            String fallbackState) {
        return new Component(
                id, required, state, usable, intentionalPartial,
                diagnostic, Map.of(), refresh, fallbackState);
    }

    private static Report report(
            Platform platform,
            CommandLine options,
            LaunchTarget target,
            Path cache,
            String profile,
            RunIdentity engineIdentity,
            Component textures,
            Component audio,
            List<RefreshAction> refreshes) {
        boolean ready = (!textures.required() || textures.usable())
                && (!audio.required() || audio.usable());
        LaunchConditionIdentity.Snapshot condition = LaunchConditionIdentity.from(
                platform,
                options,
                textures.usable() && textures.required(),
                audio.usable() && audio.required(),
                audio.fallbackState());
        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("preflightJar", engineIdentity.preflightJar());
        engine.put("preflightJarSha256", engineIdentity.preflightJarSha256());
        engine.put("wrapperRuntime", engineIdentity.wrapperRuntime());
        Map<String, Object> game = new LinkedHashMap<>();
        game.put("installRoot", target.installRoot());
        game.put("launcher", target.launcher());
        game.put("launcherKind", target.kind());
        game.put("profileFingerprint", profile);
        if (profile != null) {
            try {
                game.put("starsectorBuildSha256", PrepareAudioCommand.starsectorBuildIdentity(
                        PrepareAudioCommand.jars(target.installRoot())));
            } catch (Exception ignored) {
                game.put("starsectorBuildSha256", null);
            }
        } else {
            game.put("starsectorBuildSha256", null);
        }
        return new Report(
                ready,
                cache,
                Collections.unmodifiableMap(new LinkedHashMap<>(engine)),
                Collections.unmodifiableMap(new LinkedHashMap<>(game)),
                condition,
                List.of(textures, audio),
                refreshes);
    }

    static Map<String, Object> json(Report report) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", FORMAT);
        values.put("readyForMeasurement", report.readyForMeasurement());
        values.put("cacheRoot", report.cacheRoot());
        values.put("engine", report.engine());
        values.put("game", report.game());
        values.put("launchCondition", report.launchCondition().toMap());
        values.put("components", report.components().stream().map(Component::toMap).toList());
        values.put("refreshActions", report.refreshActions().stream().map(RefreshAction::toMap).toList());
        return values;
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    record Report(
            boolean readyForMeasurement,
            Path cacheRoot,
            Map<String, Object> engine,
            Map<String, Object> game,
            LaunchConditionIdentity.Snapshot launchCondition,
            List<Component> components,
            List<RefreshAction> refreshActions) {
    }

    record Component(
            String id,
            boolean required,
            String state,
            boolean usable,
            boolean intentionalPartial,
            String diagnostic,
            Map<String, Object> identity,
            RefreshAction refreshAction,
            String fallbackState) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", id);
            values.put("required", required);
            values.put("state", state);
            values.put("usable", usable);
            values.put("intentionalPartial", intentionalPartial);
            values.put("diagnostic", diagnostic);
            values.put("identity", identity);
            values.put("refreshAction", refreshAction == null ? null : refreshAction.toMap());
            values.put("fallbackState", fallbackState);
            return values;
        }
    }

    record RefreshAction(String component, List<String> arguments) {
        Map<String, Object> toMap() {
            return Map.of("component", component, "arguments", arguments);
        }
    }
}

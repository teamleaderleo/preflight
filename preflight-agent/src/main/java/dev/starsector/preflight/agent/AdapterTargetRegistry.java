package dev.starsector.preflight.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Loads a small line-oriented allowlist of exact adapter targets. */
final class AdapterTargetRegistry {
    private static final long MAX_FILE_BYTES = 1L * 1024 * 1024;
    private static final int MAX_LINE_CHARS = 4_096;
    private static final int MAX_TARGETS = 256;
    private static final int MAX_METHODS_PER_TARGET = 128;
    private static final String LINUX_CORE_JAR_SHA256 =
            "3d41d31d4840158491426f0570f42d71c176d9bc9cc84605a284e4c76c8b91b0";
    private static final String WINDOWS_CORE_JAR_SHA256 =
            "5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8";

    private final List<AdapterTarget> targets;
    private final Map<String, List<AdapterTarget>> byClass;

    private AdapterTargetRegistry(List<AdapterTarget> targets) {
        this.targets = List.copyOf(targets);
        Map<String, List<AdapterTarget>> indexed = new LinkedHashMap<>();
        for (AdapterTarget target : targets) {
            indexed.computeIfAbsent(target.internalClassName(), ignored -> new ArrayList<>()).add(target);
        }
        Map<String, List<AdapterTarget>> frozen = new LinkedHashMap<>();
        indexed.forEach((name, values) -> frozen.put(name, List.copyOf(values)));
        this.byClass = Map.copyOf(frozen);
    }

    static AdapterTargetRegistry empty() {
        return new AdapterTargetRegistry(List.of());
    }

    private static AdapterTarget linuxCoreTarget(
            String id,
            String className,
            String classSha256,
            String planId,
            List<AdapterTarget.RequiredMethod> methods,
            String alternativeGroup) {
        return new AdapterTarget(
                id,
                className,
                classSha256,
                planId,
                methods,
                "STARSECTOR_CORE",
                "starfarer_obf.jar",
                LINUX_CORE_JAR_SHA256,
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup(alternativeGroup);
    }

    private static AdapterTarget windowsCoreTarget(
            String id,
            String className,
            String classSha256,
            String planId,
            List<AdapterTarget.RequiredMethod> methods,
            String alternativeGroup) {
        return new AdapterTarget(
                id,
                className,
                classSha256,
                planId,
                methods,
                "STARSECTOR_CORE",
                "starfarer_obf.jar",
                WINDOWS_CORE_JAR_SHA256,
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup(alternativeGroup);
    }

    static AdapterTarget textureCompatibilityTarget() {
        return textureTarget(
                "vanilla-texture-loader-0.98a-rc8-compatibility",
                TextureCompatibilityRuntime.PLAN_ID);
    }

    static AdapterTarget texturePreparedPixelTarget() {
        return textureTarget(
                "vanilla-texture-loader-0.98a-rc8-prepared-pixels",
                TexturePreparedPixelRuntime.PLAN_ID);
    }

    static AdapterTarget linuxTexturePreparedPixelTarget() {
        return linuxTextureTarget(
                "vanilla-texture-loader-linux-0.98a-rc8-prepared-pixels",
                TexturePreparedPixelRuntime.PLAN_ID);
    }

    static AdapterTarget windowsTexturePreparedPixelTarget() {
        return new AdapterTarget(
                "vanilla-texture-loader-windows-0.98a-rc8-prepared-pixels",
                TexturePreparedPixelPlan.TARGET_CLASS,
                "7d89b44c9401a122529450d17407dbfc8d52e13a9f7eb941dc93125eb5fc153b",
                TexturePreparedPixelRuntime.PLAN_ID,
                windowsTextureMethods(),
                "STARSECTOR_CORE",
                "fs.common_obf.jar",
                "5a26d047baefc6dcd763121a17d170e3b864bfb19a83d11f645ba8be49f1641b",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-texture-loader-0.98a-rc8");
    }

    /**
     * The game's own image prefetcher, pinned like every other target to an exact reviewed class.
     *
     * <p>Separate from the TextureLoader target because it is a different class in the same jar and
     * either rewrite must be able to install without the other.
     */
    static AdapterTarget texturePrefetchBypassTarget() {
        return new AdapterTarget(
                "vanilla-image-prefetcher-0.98a-rc8-bypass",
                TexturePrefetchBypassPlan.TARGET_CLASS,
                "229d05ef109d56913b2c04263839088aa2719d31bc5fd3d58af6bc2415b84cd2",
                TexturePrefetchBypassPlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        TexturePrefetchBypassPlan.CONSUMER_METHOD,
                        TexturePrefetchBypassPlan.CONSUMER_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/fs.common_obf.jar",
                "10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-image-prefetcher-0.98a-rc8");
    }

    static AdapterTarget linuxTexturePrefetchBypassTarget() {
        return new AdapterTarget(
                "vanilla-image-prefetcher-linux-0.98a-rc8-bypass",
                TexturePrefetchBypassPlan.TARGET_CLASS,
                "85cd54eb52dd70c30b11e8d964a726878516529d1d1fba8b348c03e83352da43",
                TexturePrefetchBypassPlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        TexturePrefetchBypassPlan.CONSUMER_METHOD,
                        TexturePrefetchBypassPlan.CONSUMER_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "fs.common_obf.jar",
                "83f4367bfb55416f25614f5a5ccf2199de35cb5c1599e630f6cd54538843cf9c",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-image-prefetcher-0.98a-rc8");
    }

    static AdapterTarget windowsTexturePrefetchBypassTarget() {
        return new AdapterTarget(
                "vanilla-image-prefetcher-windows-0.98a-rc8-bypass",
                TexturePrefetchBypassPlan.TARGET_CLASS,
                "9e339c5a0edadebdd81b088e0882f5a00b4696b9f5e862a9beec3ff03c439f3e",
                TexturePrefetchBypassPlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        TexturePrefetchBypassPlan.WINDOWS_CONSUMER_METHOD,
                        TexturePrefetchBypassPlan.CONSUMER_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "fs.common_obf.jar",
                "5a26d047baefc6dcd763121a17d170e3b864bfb19a83d11f645ba8be49f1641b",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-image-prefetcher-0.98a-rc8");
    }

    static AdapterTarget windowsTexturePreparedPrefetchTarget() {
        return new AdapterTarget(
                "vanilla-image-prefetcher-windows-0.98a-rc8-prepared-worker",
                TexturePreparedPrefetchPlan.TARGET_CLASS,
                "9e339c5a0edadebdd81b088e0882f5a00b4696b9f5e862a9beec3ff03c439f3e",
                TexturePreparedPrefetchPlan.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                TexturePrefetchBypassPlan.WINDOWS_CONSUMER_METHOD,
                                TexturePrefetchBypassPlan.CONSUMER_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                TexturePreparedPrefetchPlan.DECODE_METHOD,
                                TexturePreparedPrefetchPlan.DECODE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "fs.common_obf.jar",
                "5a26d047baefc6dcd763121a17d170e3b864bfb19a83d11f645ba8be49f1641b",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-image-prefetcher-0.98a-rc8");
    }

    /**
     * The resource resolver every load in the game goes through, game code and mod code alike.
     *
     * <p>Same jar as the image prefetcher above, and pinned the same way. This one is worth saying
     * out loud: it is the only target that is not on any particular loading path, because it is
     * underneath all of them.
     */
    static AdapterTarget resourceProbeCacheTarget() {
        return new AdapterTarget(
                "vanilla-resource-resolver-0.98a-rc8-probe-cache",
                ResourceProbePlan.TARGET_CLASS,
                "ee81369a75dfa518ddbbf1bfb83c96845effc2cf9189179fc08a17863837d0fd",
                ResourceProbeRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                ResourceProbePlan.RESOLVE_METHOD,
                                ResourceProbePlan.RESOLVE_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                ResourceProbePlan.OPEN_METHOD,
                                ResourceProbePlan.OPEN_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/fs.common_obf.jar",
                "10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** The resolver's one-shot mod-source hint, isolated per loading thread. */
    static AdapterTarget sourceHintIsolationTarget() {
        return new AdapterTarget(
                "vanilla-resource-resolver-0.98a-rc8-source-hint-isolation",
                SourceHintIsolationPlan.TARGET_CLASS,
                SourceHintIsolationPlan.ORIGINAL_SHA256,
                SourceHintIsolationRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                SourceHintIsolationPlan.SET_METHOD,
                                SourceHintIsolationPlan.SET_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                SourceHintIsolationPlan.RESOLVE_METHOD,
                                SourceHintIsolationPlan.RESOLVE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/fs.common_obf.jar",
                "10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /**
     * The game's Ogg Vorbis decode, in the sound jar.
     *
     * <p>Pure decode: the sound loader constructs one of these per file and does everything that
     * touches OpenAL after it returns. That is why this is the seam and the loader is not.
     */
    static AdapterTarget preparedAudioTarget() {
        return new AdapterTarget(
                "vanilla-ogg-decoder-0.98a-rc8-prepared-audio",
                PreparedAudioPlan.TARGET_CLASS,
                "d99e37bfedd0510418fa171ae1861918f8ef72d0c0c3084df669f1d195b18733",
                PreparedAudioRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        PreparedAudioPlan.DECODE_METHOD,
                        PreparedAudioPlan.DECODE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/fs.sound_obf.jar",
                "79e5bc71236333541674e2b9093642ac5a2d68d9e55cb8a71f299fd389ba1573",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Starsector 0.98a-RC8's streaming player checks a stale OpenAL error after source creation. */
    static AdapterTarget audioStreamSourceErrorTarget() {
        return new AdapterTarget(
                "vanilla-streaming-audio-source-error-order-0.98a-rc8",
                AudioStreamSourceErrorPlan.TARGET_CLASS,
                AudioStreamSourceErrorPlan.ORIGINAL_SHA256,
                AudioStreamSourceErrorRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                AudioStreamSourceErrorPlan.CONSTRUCTOR,
                                AudioStreamSourceErrorPlan.CONSTRUCTOR_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod("class", "(I)V"),
                        new AdapterTarget.RequiredMethod("Ô00000", "(I)V"),
                        new AdapterTarget.RequiredMethod("new", "(I)V"),
                        new AdapterTarget.RequiredMethod("Ó00000", "(I)V"),
                        new AdapterTarget.RequiredMethod("ö00000", "()V")),
                "STARSECTOR_CORE",
                "contents/resources/java/fs.sound_obf.jar",
                "79e5bc71236333541674e2b9093642ac5a2d68d9e55cb8a71f299fd389ba1573",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Starsector's sound store resolves caller paths relative to package sound before this fallback. */
    static AdapterTarget audioResourceFallbackTarget() {
        return new AdapterTarget(
                "vanilla-sound-classpath-root-resource-fallback-0.98a-rc8",
                AudioResourceFallbackPlan.TARGET_CLASS,
                AudioResourceFallbackPlan.ORIGINAL_SHA256,
                AudioResourceFallbackRuntime.PLAN_ID,
                AudioResourceFallbackPlan.METHODS.stream()
                        .map(name -> new AdapterTarget.RequiredMethod(
                                name, AudioResourceFallbackPlan.STRING_DESCRIPTOR))
                        .toList(),
                "STARSECTOR_CORE",
                "contents/resources/java/fs.sound_obf.jar",
                "79e5bc71236333541674e2b9093642ac5a2d68d9e55cb8a71f299fd389ba1573",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** AI Tweaks 2.2.10 grows two bounded temporary arc lists on every facing pass. */
    static AdapterTarget aiTweaksSplitArcsTarget() {
        return new AdapterTarget(
                "aitweaks-2.2.10-split-arcs-capacity",
                AiTweaksSplitArcsPlan.TARGET_CLASS,
                AiTweaksSplitArcsPlan.ORIGINAL_SHA256,
                AiTweaksSplitArcsPlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        AiTweaksSplitArcsPlan.METHOD, AiTweaksSplitArcsPlan.DESCRIPTOR)),
                "MOD",
                "aitweaks-core.jar",
                "9f6179bcd2df2e3ce8cea2da79051c9f1be3c9b71712c6c28d7568b777ecf5b2",
                "com/genir/aitweaks/launcher/loading/CoreLoader",
                "");
    }

    /** AI Tweaks 2.2.10 creates throwaway scaled vectors in seven affine expressions. */
    static List<AdapterTarget> aiTweaksAffineVectorTargets() {
        List<AdapterTarget> targets = new ArrayList<>();
        for (AiTweaksAffineVectorPlan.Target target : AiTweaksAffineVectorPlan.TARGETS) {
            targets.add(new AdapterTarget(
                    "aitweaks-2.2.10-affine-vector-" + target.method()
                            + "-" + targets.size(),
                    target.internalName(),
                    target.sha256(),
                    AiTweaksAffineVectorPlan.PLAN_ID,
                    AiTweaksAffineVectorPlan.methods(target).stream()
                            .map(method -> new AdapterTarget.RequiredMethod(
                                    method.name(), method.descriptor()))
                            .toList(),
                    "MOD",
                    AiTweaksAffineVectorPlan.SOURCE_FILE,
                    AiTweaksAffineVectorPlan.SOURCE_SHA256,
                    AiTweaksAffineVectorPlan.LOADER,
                    ""));
        }
        return List.copyOf(targets);
    }

    /** Vanilla range modifiers copy a live listener list into an ArrayList before every query. */
    static AdapterTarget combatListenerRangeSnapshotTarget() {
        return new AdapterTarget(
                "vanilla-combat-listener-range-snapshot-0.98a-rc8",
                CombatListenerRangeSnapshotPlan.TARGET_CLASS,
                CombatListenerRangeSnapshotPlan.ORIGINAL_SHA256,
                CombatListenerRangeSnapshotPlan.PLAN_ID,
                CombatListenerRangeSnapshotPlan.METHODS.stream()
                        .map(method -> new AdapterTarget.RequiredMethod(
                                method.name(), CombatListenerRangeSnapshotPlan.DESCRIPTOR))
                        .toList(),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer.api.jar",
                "6ac6c78c6116946d487376426340d019938f986ceae1391ae1fa599e890e3185",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** GraphicsLib 1.12.1's exact normal-map traversal implementation and owning mod archive. */
    static AdapterTarget graphicsLibCompactReplayTarget() {
        return new AdapterTarget(
                "graphicslib-1.12.1-texture-data-compact-replay",
                GraphicsLibCompactReplayPlan.TARGET_CLASS,
                GraphicsLibCompactReplayPlan.ORIGINAL_SHA256,
                GraphicsLibCompactReplayPlan.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod("autoGenMissingNormalMaps", "()V"),
                        new AdapterTarget.RequiredMethod("autoGenMissingNormalMapsInner", "(Z)V"),
                        new AdapterTarget.RequiredMethod(
                                "mapSpriteToMNSWithAutoGen",
                                "(Ljava/lang/String;Ljava/lang/String;"
                                        + "Lorg/dark/shaders/util/TextureData$ObjectType;IZZ)V")),
                "MOD",
                "graphics.jar",
                "832064013fe853731941e547842884ba121fb8b20eff08d24137f7a2c916903a",
                "java/net/URLClassLoader",
                "");
    }

    /** Exact AshLib repository loop, timed only by the opt-in startup phase probe. */
    static AdapterTarget startupAshRepoBreakdownTarget() {
        return new AdapterTarget(
                "ashlib-2.2.3-startup-repository-breakdown",
                "ashlib/data/plugins/repositories/ShipRenderInfoRepo",
                "5955d8f27dba81580e2648bbc0a7a16a9924bcd1734baf7937ab1d3417e6507f",
                StartupPhaseRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod("populateRenderInfoRepo", "()V"),
                        new AdapterTarget.RequiredMethod("populateShip",
                                "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)V")),
                "MOD",
                "ashlib.jar",
                "634a0542d2e934df3a212050633462475e3cc48faf4bb417dd5114dfc2fd1dfa",
                "java/net/URLClassLoader",
                "");
    }

    /** Exact AshLib render-info builder, timed only by the opt-in startup phase probe. */
    static AdapterTarget startupAshRenderInfoBreakdownTarget() {
        return new AdapterTarget(
                "ashlib-2.2.3-startup-render-info-breakdown",
                "ashlib/data/plugins/models/ShipRenderInfo",
                "bb8d74bfb775f63ba79aa802c7e67158b5eea80c2d3057f9fd40350fd99e1aed",
                StartupPhaseRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod("getModuleSlotsFromVariantFile",
                                "(Ljava/lang/String;)V"),
                        new AdapterTarget.RequiredMethod("populateSlotShipHullsMap", "()V"),
                        new AdapterTarget.RequiredMethod("populateBuiltInList",
                                "(Ljava/lang/String;Z)Ljava/util/ArrayList;"),
                        new AdapterTarget.RequiredMethod("populateModuleList",
                                "(Ljava/lang/String;)V")),
                "MOD",
                "ashlib.jar",
                "634a0542d2e934df3a212050633462475e3cc48faf4bb417dd5114dfc2fd1dfa",
                "java/net/URLClassLoader",
                "");
    }

    static AdapterTarget ashLibVariantRepositoryTarget() {
        return new AdapterTarget(
                "ashlib-2.2.3-callback-scoped-variant-index",
                AshLibVariantLookupPlan.REPOSITORY_CLASS,
                AshLibVariantLookupPlan.REPOSITORY_SHA256,
                AshLibVariantLookupRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        AshLibVariantLookupPlan.POPULATE,
                        AshLibVariantLookupPlan.POPULATE_DESCRIPTOR)),
                "MOD",
                "ashlib.jar",
                "634a0542d2e934df3a212050633462475e3cc48faf4bb417dd5114dfc2fd1dfa",
                "java/net/URLClassLoader",
                "");
    }

    static AdapterTarget ashLibVariantLookupTarget() {
        return new AdapterTarget(
                "ashlib-2.2.3-variant-lookup",
                AshLibVariantLookupPlan.LOOKUP_CLASS,
                AshLibVariantLookupPlan.LOOKUP_SHA256,
                AshLibVariantLookupRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        AshLibVariantLookupPlan.LOOKUP,
                        AshLibVariantLookupPlan.LOOKUP_DESCRIPTOR)),
                "MOD",
                "ashlib.jar",
                "634a0542d2e934df3a212050633462475e3cc48faf4bb417dd5114dfc2fd1dfa",
                "java/net/URLClassLoader",
                "");
    }

    static AdapterTarget ashLibShipJsonTarget() {
        return new AdapterTarget(
                "ashlib-2.2.3-callback-scoped-ship-json",
                AshLibVariantLookupPlan.SHIP_JSON_CLASS,
                AshLibVariantLookupPlan.SHIP_JSON_SHA256,
                AshLibVariantLookupRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        AshLibVariantLookupPlan.SHIP_JSON_METHOD,
                        AshLibVariantLookupPlan.SHIP_JSON_DESCRIPTOR)),
                "MOD",
                "ashlib.jar",
                "634a0542d2e934df3a212050633462475e3cc48faf4bb417dd5114dfc2fd1dfa",
                "java/net/URLClassLoader",
                "");
    }

    /** Exact GraphicsLib callback, timed only by the opt-in startup phase probe. */
    static AdapterTarget startupGraphicsBreakdownTarget() {
        return new AdapterTarget(
                "graphicslib-1.12.1-startup-callback-breakdown",
                "org/dark/shaders/ShaderModPlugin",
                "5863b38d7ea73ed65fb8d214e525daed0318f4563b92a15d22e0981cec275981",
                StartupPhaseRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod("onApplicationLoad", "()V")),
                "MOD",
                "graphics.jar",
                "832064013fe853731941e547842884ba121fb8b20eff08d24137f7a2c916903a",
                "java/net/URLClassLoader",
                "");
    }

    /** Exact MagicLib callback, timed only by the opt-in startup phase probe. */
    static AdapterTarget startupMagicLibBreakdownTarget() {
        return new AdapterTarget(
                "magiclib-1.5.6-startup-callback-breakdown",
                "org/magiclib/Magic_modPlugin",
                "0ef80d14aa00142bf5dd4d8eb8448cc5dbd342d47d4b48fbe9e57c6b22c00000",
                StartupPhaseRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod("onApplicationLoad", "()V")),
                "MOD",
                "MagicLib.jar",
                "af028fcd67dd537024eab0082d3e78cac8508355dbd5f8731b6c243c60dae0d5",
                "java/net/URLClassLoader",
                "");
    }

    /** Exact vanilla campaign bootstrap, timed only by the opt-in startup phase probe. */
    static AdapterTarget startupCampaignEngineBreakdownTarget() {
        return new AdapterTarget(
                "vanilla-campaign-engine-startup-bootstrap-breakdown",
                CampaignEngineTimePlan.TARGET_CLASS,
                CampaignEngineTimePlan.ORIGINAL_SHA256,
                StartupPhaseRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                "getInstance", "()Lcom/fs/starfarer/campaign/CampaignEngine;"),
                        new AdapterTarget.RequiredMethod(
                                "setInstance", "(Lcom/fs/starfarer/campaign/CampaignEngine;)V")),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Exact vanilla Codex constructor, timed only by the opt-in startup phase probe. */
    static AdapterTarget startupCodexBreakdownTarget() {
        return new AdapterTarget(
                "vanilla-codex-v2-startup-breakdown",
                "com/fs/starfarer/api/impl/codex/CodexDataV2",
                "0a2fbd188fa2937ec279d4ee41dea9ffa75f833ec7622a357857d70696f42896",
                StartupPhaseRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod("init", "()V"),
                        new AdapterTarget.RequiredMethod("linkRelatedEntries", "()V")),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer.api.jar",
                "6ac6c78c6116946d487376426340d019938f986ceae1391ae1fa599e890e3185",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Exact Nexerelin settings loader, timed only by the opt-in startup phase probe. */
    static AdapterTarget startupNexConfigBreakdownTarget() {
        return new AdapterTarget(
                "nexerelin-0.12.2b-startup-config-breakdown",
                "exerelin/utilities/NexConfig",
                "a59894d38876b8ed92d3d11726d776743b1203177ccacc8a6f8d79f7fd71ff7c",
                StartupPhaseRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod("loadSettings", "()V")),
                "MOD",
                "ExerelinCore.jar",
                "3d3bb30c44eec9060a7777317af519dd695a1aa31d75f478036fc338870b3b71",
                "java/net/URLClassLoader",
                "");
    }

    /** Exact Nexerelin faction-config constructor, timed only by the startup phase probe. */
    static AdapterTarget startupNexFactionConfigBreakdownTarget() {
        return new AdapterTarget(
                "nexerelin-0.12.2b-startup-faction-config-breakdown",
                "exerelin/utilities/NexFactionConfig",
                "f6aa5a769744f82498c05c0a878ddd36bfa4b32e15136bf08e2dd996447c9c6f",
                StartupPhaseRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod("<init>", "(Ljava/lang/String;)V"),
                        new AdapterTarget.RequiredMethod("loadFactionConfig", "()V")),
                "MOD",
                "ExerelinCore.jar",
                "3d3bb30c44eec9060a7777317af519dd695a1aa31d75f478036fc338870b3b71",
                "java/net/URLClassLoader",
                "");
    }

    /** Janino 2.7.8's exact complete-map compiler seam in Starsector 0.98a-RC8. */
    static AdapterTarget janinoBytecodeCacheTarget() {
        return new AdapterTarget(
                "vanilla-janino-2.7.8-complete-map-bytecode-cache",
                JaninoBytecodeCachePlan.TARGET_CLASS,
                "6b0eea7994ab4c314f1bc7cdefaa99b66897d500c2cad6fd2d97cd08b134c4b8",
                JaninoBytecodeCacheRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                JaninoBytecodeCachePlan.GENERATE_METHOD,
                                JaninoBytecodeCachePlan.GENERATE_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                "defineBytecode", "(Ljava/lang/String;[B)Ljava/lang/Class;"),
                        new AdapterTarget.RequiredMethod(
                                "findClass", "(Ljava/lang/String;)Ljava/lang/Class;")),
                "STARSECTOR_CORE",
                "contents/resources/java/janino.jar",
                "60f05562c22b6de06641a1f76148692ef336ad1f6712fe6a76f9e2611f766344",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-janino-2.7.8-complete-map-bytecode-cache");
    }

    /** GraphicsLib 1.12.1's exact per-frame insignia renderer and owning mod archive. */
    static AdapterTarget graphicsLibInsigniaManagerCacheTarget() {
        return new AdapterTarget(
                "graphicslib-1.12.1-insignia-manager-cache",
                GraphicsLibInsigniaManagerCachePlan.TARGET_CLASS,
                GraphicsLibInsigniaManagerCachePlan.ORIGINAL_SHA256,
                GraphicsLibInsigniaManagerCacheRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        GraphicsLibInsigniaManagerCachePlan.RENDER_METHOD,
                        GraphicsLibInsigniaManagerCachePlan.RENDER_DESCRIPTOR)),
                "MOD",
                "graphics.jar",
                "832064013fe853731941e547842884ba121fb8b20eff08d24137f7a2c916903a",
                "java/net/URLClassLoader",
                "");
    }

    /** GraphicsLib 1.12.1's repeated LunaLib lookups for per-render light constants. */
    static AdapterTarget graphicsLibHotSettingsTarget() {
        return new AdapterTarget(
                "graphicslib-1.12.1-hot-settings-cache",
                GraphicsLibHotSettingsPlan.TARGET_CLASS,
                GraphicsLibHotSettingsPlan.ORIGINAL_SHA256,
                GraphicsLibHotSettingsRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                GraphicsLibHotSettingsPlan.LOAD_METHOD,
                                GraphicsLibHotSettingsPlan.LOAD_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                GraphicsLibHotSettingsPlan.APPLY_METHOD,
                                GraphicsLibHotSettingsPlan.APPLY_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod("fighterBrightnessScale", "()F"),
                        new AdapterTarget.RequiredMethod("weaponFlashHeight", "()F"),
                        new AdapterTarget.RequiredMethod("weaponLightHeight", "()F")),
                "MOD",
                "graphics.jar",
                "832064013fe853731941e547842884ba121fb8b20eff08d24137f7a2c916903a",
                "java/net/URLClassLoader",
                "");
    }

    /** RAT 3.3.1's periodic abyss-faction scan with a false fallback for absent JSON flags. */
    static AdapterTarget ratAbyssFactionFlagTarget() {
        return new AdapterTarget(
                "rat-3.3.1-abyss-faction-optional-flag",
                RatAbyssFactionFlagPlan.TARGET_CLASS,
                RatAbyssFactionFlagPlan.ORIGINAL_SHA256,
                RatAbyssFactionFlagPlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        RatAbyssFactionFlagPlan.ADVANCE_METHOD,
                        RatAbyssFactionFlagPlan.ADVANCE_DESCRIPTOR)),
                "MOD",
                "randomassortmentofthings.jar",
                "d34c805f84c259d9edcec197183a49cef4f3e488b2bf37768bb55f39f6d694e7",
                "java/net/URLClassLoader",
                "");
    }

    /** MnemonicUtils 0.5.1's every-frame sensor entity discovery. */
    static AdapterTarget mnemonicSensorsEntityFilterTarget() {
        return new AdapterTarget(
                "mnemonicutils-0.5.1-sensors-entity-filter",
                MnemonicSensorsEntityFilterPlan.TARGET_CLASS,
                MnemonicSensorsEntityFilterPlan.ORIGINAL_SHA256,
                MnemonicSensorsEntityFilterPlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        MnemonicSensorsEntityFilterPlan.METHOD,
                        MnemonicSensorsEntityFilterPlan.DESCRIPTOR)),
                "MOD",
                "mnemonicutils.jar",
                "d85c2f52df2477b19cd31f0ab9273e758b50067b7151c4f99fb60cf96d10756e",
                "java/net/URLClassLoader",
                "");
    }

    /** LunaLib 2.0.5's version checker, which duplicates Nexerelin's remote reads. */
    static AdapterTarget lunaVersionCheckResponseDedupTarget() {
        return new AdapterTarget(
                "lunalib-2.0.5-version-check-response-dedup",
                VersionCheckResponseDedupPlan.LUNA_CLASS,
                VersionCheckResponseDedupPlan.LUNA_SHA256,
                VersionCheckResponseDedupRuntime.PLAN_ID,
                versionCheckMethods(),
                "MOD",
                "lunalib.jar",
                "d20304b9404f03392482703a55e655cadb0a1735d78c9b2da6b209e1217bbbfd",
                "java/net/URLClassLoader",
                "");
    }

    /** LunaLib 2.0.5's allocation-heavy campaign renderer snapshots. */
    static AdapterTarget lunaCampaignRendererSnapshotScriptTarget() {
        return new AdapterTarget(
                "lunalib-2.0.5-campaign-renderer-dead-snapshot",
                LunaCampaignRendererSnapshotPlan.SCRIPT_CLASS,
                LunaCampaignRendererSnapshotPlan.SCRIPT_SHA256,
                LunaCampaignRendererSnapshotRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                LunaCampaignRendererSnapshotPlan.ADVANCE,
                                LunaCampaignRendererSnapshotPlan.ADVANCE_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                LunaCampaignRendererSnapshotPlan.GET_RENDERERS,
                                LunaCampaignRendererSnapshotPlan.GET_RENDERERS_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                LunaCampaignRendererSnapshotPlan.GET_TRANSIENT,
                                LunaCampaignRendererSnapshotPlan.GET_RENDERERS_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                LunaCampaignRendererSnapshotPlan.GET_PERSISTENT,
                                LunaCampaignRendererSnapshotPlan.GET_RENDERERS_DESCRIPTOR)),
                "MOD",
                "lunalib.jar",
                "d20304b9404f03392482703a55e655cadb0a1735d78c9b2da6b209e1217bbbfd",
                "java/net/URLClassLoader",
                "");
    }

    /** LunaLib 2.0.5's render/advance call sites that consume private combined snapshots. */
    static AdapterTarget lunaCampaignRendererSnapshotEntityTarget() {
        return new AdapterTarget(
                "lunalib-2.0.5-campaign-renderer-entity-snapshot",
                LunaCampaignRendererSnapshotPlan.ENTITY_CLASS,
                LunaCampaignRendererSnapshotPlan.ENTITY_SHA256,
                LunaCampaignRendererSnapshotRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                LunaCampaignRendererSnapshotPlan.ADVANCE,
                                LunaCampaignRendererSnapshotPlan.ADVANCE_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                LunaCampaignRendererSnapshotPlan.RENDER,
                                LunaCampaignRendererSnapshotPlan.RENDER_DESCRIPTOR)),
                "MOD",
                "lunalib.jar",
                "d20304b9404f03392482703a55e655cadb0a1735d78c9b2da6b209e1217bbbfd",
                "java/net/URLClassLoader",
                "");
    }

    /** Starsector 0.98a-RC8's up to eight temporary vectors per rendered contrail point. */
    static AdapterTarget contrailRenderScratchTarget() {
        return new AdapterTarget(
                "vanilla-contrail-render-transient-vector-scratch-0.98a-rc8",
                ContrailRenderScratchPlan.TARGET_CLASS,
                ContrailRenderScratchPlan.ORIGINAL_SHA256,
                ContrailRenderScratchRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        ContrailRenderScratchPlan.RENDER_METHOD,
                        ContrailRenderScratchPlan.RENDER_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Starsector's exact font wrapper builds fixed tables and one-character strings per call. */
    static AdapterTarget fontWrapAllocationTarget() {
        return new AdapterTarget(
                "vanilla-font-wrap-character-allocation-0.98a-rc8",
                FontWrapAllocationPlan.TARGET_CLASS,
                FontWrapAllocationPlan.ORIGINAL_SHA256,
                FontWrapAllocationRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        FontWrapAllocationPlan.METHOD, FontWrapAllocationPlan.DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/fs.common_obf.jar",
                "10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Nexerelin 0.12.2b's older version-checker fork over the same mod URL set. */
    static AdapterTarget nexVersionCheckResponseDedupTarget() {
        return new AdapterTarget(
                "nexerelin-0.12.2b-version-check-response-dedup",
                VersionCheckResponseDedupPlan.NEX_CLASS,
                VersionCheckResponseDedupPlan.NEX_SHA256,
                VersionCheckResponseDedupRuntime.PLAN_ID,
                versionCheckMethods(),
                "MOD",
                "exerelincore.jar",
                "3d3bb30c44eec9060a7777317af519dd695a1aa31d75f478036fc338870b3b71",
                "java/net/URLClassLoader",
                "");
    }

    private static List<AdapterTarget.RequiredMethod> versionCheckMethods() {
        return List.of(
                new AdapterTarget.RequiredMethod(
                        VersionCheckResponseDedupPlan.REMOTE_METHOD,
                        VersionCheckResponseDedupPlan.REMOTE_DESCRIPTOR),
                new AdapterTarget.RequiredMethod(
                        VersionCheckResponseDedupPlan.LATEST_METHOD,
                        VersionCheckResponseDedupPlan.LATEST_DESCRIPTOR));
    }

    /** MagicLib 1.5.6's allocation-heavy unlocked-paintjob extension. */
    static AdapterTarget magicLibPaintjobTarget() {
        return new AdapterTarget(
                "magiclib-1.5.6-paintjob-unlocked-set",
                MagicLibPaintjobPlan.TARGET_CLASS,
                MagicLibPaintjobPlan.ORIGINAL_SHA256,
                MagicLibPaintjobRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        MagicLibPaintjobPlan.LOOKUP_METHOD,
                        MagicLibPaintjobPlan.LOOKUP_DESCRIPTOR)),
                "MOD",
                "MagicLib.jar",
                "af028fcd67dd537024eab0082d3e78cac8508355dbd5f8731b6c243c60dae0d5",
                "java/net/URLClassLoader",
                "");
    }

    /** MagicLib 1.5.6's per-frame scan of already-notified paintjob IDs. */
    static AdapterTarget magicLibPaintjobNotificationTarget() {
        return new AdapterTarget(
                "magiclib-1.5.6-paintjob-notification-set",
                MagicLibPaintjobNotificationPlan.TARGET_CLASS,
                MagicLibPaintjobNotificationPlan.ORIGINAL_SHA256,
                MagicLibPaintjobNotificationRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        MagicLibPaintjobNotificationPlan.ADVANCE_METHOD,
                        MagicLibPaintjobNotificationPlan.ADVANCE_DESCRIPTOR)),
                "MOD",
                "MagicLib.jar",
                "af028fcd67dd537024eab0082d3e78cac8508355dbd5f8731b6c243c60dae0d5",
                "java/net/URLClassLoader",
                "");
    }

    /** Stellar Networks 3.3.0's every-paused-frame remote market updater. */
    static AdapterTarget stelnetMarketUpdaterTarget() {
        return new AdapterTarget(
                "stelnet-3.3.0-paused-market-refresh-pass",
                StelnetMarketUpdaterPlan.TARGET_CLASS,
                StelnetMarketUpdaterPlan.ORIGINAL_SHA256,
                StelnetMarketUpdaterRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                StelnetMarketUpdaterPlan.ADVANCE_METHOD,
                                StelnetMarketUpdaterPlan.ADVANCE_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                StelnetMarketUpdaterPlan.PICK_METHOD,
                                "()Lcom/fs/starfarer/api/campaign/econ/MarketAPI;")),
                "MOD",
                "stelnet.jar",
                "3a0fcb88c9652de3f65e051d1eb0fb84020c566a2c18c0b03426c204e2003513",
                "java/net/URLClassLoader",
                "");
    }

    /** Logistics Notifications 1.7.1's uninitialized load-time fuel snapshot. */
    static AdapterTarget logisticsNotificationsFuelTarget() {
        return new AdapterTarget(
                "logistics-notifications-1.7.1-initial-fuel-snapshot",
                LogisticsNotificationsFuelPlan.TARGET_CLASS,
                LogisticsNotificationsFuelPlan.ORIGINAL_SHA256,
                LogisticsNotificationsFuelRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        LogisticsNotificationsFuelPlan.CONSTRUCTOR,
                        LogisticsNotificationsFuelPlan.CONSTRUCTOR_DESCRIPTOR)),
                "MOD",
                "LogNot.jar",
                "42ca235605cec137c66d50f46269b61c9569133f9f16e532db695e25ea71465e",
                "java/net/URLClassLoader",
                "");
    }

    /** Vanilla 0.98a-RC8's literal-free-memory warning, corrected for macOS memory pressure. */
    static AdapterTarget macMemoryWarningTarget() {
        return new AdapterTarget(
                "vanilla-macos-pressure-aware-memory-warning-0.98a-rc8",
                MacMemoryWarningPlan.TARGET_CLASS,
                MacMemoryWarningPlan.ORIGINAL_SHA256,
                MacMemoryWarningRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        MacMemoryWarningPlan.METHOD, MacMemoryWarningPlan.DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** LWJGL 2's display boundary, enabled only for explicit frame-time pilots. */
    static AdapterTarget frameTimeTarget() {
        return new AdapterTarget(
                "lwjgl-2-display-frame-time-and-presentation",
                FrameTimePlan.TARGET_CLASS,
                FrameTimePlan.ORIGINAL_SHA256,
                FrameTimeRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                FrameTimePlan.UPDATE_METHOD, FrameTimePlan.UPDATE_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                FrameTimePlan.ACTIVE_METHOD, FrameTimePlan.ACTIVE_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                FrameTimePlan.VSYNC_METHOD, FrameTimePlan.VSYNC_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                FrameTimePlan.SWAP_INTERVAL_METHOD,
                                FrameTimePlan.SWAP_INTERVAL_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                FrameTimePlan.DESTROY_METHOD,
                                FrameTimePlan.DESTROY_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/lwjgl.jar",
                "527d509f60132e5b2653c7fc0f8cf299d6f698f4a8013342bef47705dc57ed3f",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("lwjgl-display-frame-time-probe");
    }

    static AdapterTarget linuxFrameTimeTarget() {
        return new AdapterTarget(
                "lwjgl-2-display-linux-frame-time-probe",
                FrameTimePlan.TARGET_CLASS,
                FrameTimePlan.ORIGINAL_SHA256,
                FrameTimeRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                FrameTimePlan.UPDATE_METHOD, FrameTimePlan.UPDATE_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                FrameTimePlan.ACTIVE_METHOD, FrameTimePlan.ACTIVE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "lwjgl.jar",
                "527d509f60132e5b2653c7fc0f8cf299d6f698f4a8013342bef47705dc57ed3f",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("lwjgl-display-frame-time-probe");
    }

    /** Exact campaign main-loop limiter sleep, enabled only with frame-time telemetry. */
    static AdapterTarget frameLimiterTimeTarget() {
        return new AdapterTarget(
                "vanilla-campaign-frame-limiter-time-0.98a-rc8",
                FrameLimiterTimePlan.TARGET_CLASS,
                FrameLimiterTimePlan.ORIGINAL_SHA256,
                FrameLimiterTimePlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        FrameLimiterTimePlan.METHOD, FrameLimiterTimePlan.DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-campaign-frame-limiter-time-0.98a-rc8");
    }

    static AdapterTarget windowsFrameLimiterTimeTarget() {
        return windowsCoreTarget(
                "vanilla-campaign-frame-limiter-time-windows-0.98a-rc8",
                FrameLimiterTimePlan.TARGET_CLASS,
                FrameLimiterTimePlan.WINDOWS_ORIGINAL_SHA256,
                FrameLimiterTimePlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        FrameLimiterTimePlan.METHOD, FrameLimiterTimePlan.DESCRIPTOR)),
                "vanilla-campaign-frame-limiter-time-0.98a-rc8");
    }

    /** Minimal end-of-startup marker for frame-time pilots that deliberately avoid JFR. */
    static AdapterTarget frameTimeStartupCompletionTarget() {
        return new AdapterTarget(
                "vanilla-resource-loader-0.98a-rc8-frame-time-startup-completion",
                FrameTimeStartupCompletionPlan.TARGET_CLASS,
                FrameTimeStartupCompletionPlan.ORIGINAL_SHA256,
                FrameTimeStartupCompletionPlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        FrameTimeStartupCompletionPlan.INIT_METHOD,
                        FrameTimeStartupCompletionPlan.INIT_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-resource-loader-startup-completion-0.98a-rc8");
    }

    static AdapterTarget linuxFrameTimeStartupCompletionTarget() {
        return new AdapterTarget(
                "vanilla-resource-loader-linux-0.98a-rc8-frame-time-startup-completion",
                FrameTimeStartupCompletionPlan.TARGET_CLASS,
                FrameTimeStartupCompletionPlan.LINUX_ORIGINAL_SHA256,
                FrameTimeStartupCompletionPlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        FrameTimeStartupCompletionPlan.INIT_METHOD,
                        FrameTimeStartupCompletionPlan.INIT_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "starfarer_obf.jar",
                "3d41d31d4840158491426f0570f42d71c176d9bc9cc84605a284e4c76c8b91b0",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-resource-loader-startup-completion-0.98a-rc8");
    }

    static AdapterTarget mainMenuInteractiveTarget() {
        return new AdapterTarget(
                "vanilla-title-0.98a-rc8-main-menu-interactive-and-control",
                MainMenuInteractivePlan.TARGET_CLASS,
                MainMenuInteractivePlan.ORIGINAL_SHA256,
                MainMenuInteractivePlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        MainMenuInteractivePlan.ADVANCE_METHOD,
                        MainMenuInteractivePlan.ADVANCE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-main-menu-interactive-0.98a-rc8");
    }

    static AdapterTarget linuxMainMenuInteractiveTarget() {
        return new AdapterTarget(
                "vanilla-title-linux-0.98a-rc8-main-menu-interactive",
                MainMenuInteractivePlan.LINUX_TARGET_CLASS,
                MainMenuInteractivePlan.LINUX_ORIGINAL_SHA256,
                MainMenuInteractivePlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        MainMenuInteractivePlan.SHOW_METHOD,
                        MainMenuInteractivePlan.SHOW_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "starfarer_obf.jar",
                "3d41d31d4840158491426f0570f42d71c176d9bc9cc84605a284e4c76c8b91b0",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-main-menu-interactive-0.98a-rc8");
    }

    static AdapterTarget windowsMainMenuInteractiveTarget() {
        return windowsCoreTarget(
                "vanilla-title-windows-0.98a-rc8-main-menu-interactive-and-control",
                MainMenuInteractivePlan.WINDOWS_TARGET_CLASS,
                MainMenuInteractivePlan.WINDOWS_ORIGINAL_SHA256,
                MainMenuInteractivePlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        MainMenuInteractivePlan.ADVANCE_METHOD,
                        MainMenuInteractivePlan.ADVANCE_DESCRIPTOR)),
                "vanilla-main-menu-interactive-0.98a-rc8");
    }

    /** Starsector reprioritizes a large resource list with a quadratic ArrayList.removeAll. */
    static AdapterTarget resourcePriorityTarget() {
        return new AdapterTarget(
                "vanilla-resource-loader-0.98a-rc8-priority-index",
                ResourcePriorityPlan.TARGET_CLASS,
                ResourcePriorityPlan.ORIGINAL_SHA256,
                ResourcePriorityRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        ResourcePriorityPlan.INIT_METHOD, ResourcePriorityPlan.INIT_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-resource-priority-0.98a-rc8");
    }

    static AdapterTarget linuxResourcePriorityTarget() {
        return new AdapterTarget(
                "vanilla-resource-loader-linux-0.98a-rc8-priority-index",
                ResourcePriorityPlan.TARGET_CLASS,
                FrameTimeStartupCompletionPlan.LINUX_ORIGINAL_SHA256,
                ResourcePriorityRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        ResourcePriorityPlan.INIT_METHOD, ResourcePriorityPlan.INIT_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "starfarer_obf.jar",
                "3d41d31d4840158491426f0570f42d71c176d9bc9cc84605a284e4c76c8b91b0",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-resource-priority-0.98a-rc8");
    }

    static AdapterTarget windowsResourcePriorityTarget() {
        return windowsCoreTarget(
                "vanilla-resource-loader-windows-0.98a-rc8-priority-index",
                ResourcePriorityPlan.TARGET_CLASS,
                FrameTimeStartupCompletionPlan.WINDOWS_ORIGINAL_SHA256,
                ResourcePriorityRuntime.PLAN_ID,
                resourcePriorityTarget().requiredMethods(),
                "vanilla-resource-priority-0.98a-rc8");
    }

    /** Vanilla parses an entire save descriptor merely to enable the Continue button. */
    static AdapterTarget saveDescriptorCompatibilityTarget() {
        return new AdapterTarget(
                "vanilla-save-descriptor-compatibility-0.98a-rc8",
                SaveDescriptorCompatibilityPlan.TARGET_CLASS,
                SaveDescriptorCompatibilityPlan.ORIGINAL_SHA256,
                SaveDescriptorCompatibilityRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        SaveDescriptorCompatibilityPlan.METHOD,
                        SaveDescriptorCompatibilityPlan.DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Synthetic industry constructor behind SettingsAPI's demand/supply getters. */
    static AdapterTarget industryDemandSupplySettingsTarget() {
        return new AdapterTarget(
                "vanilla-settings-industry-demand-supply-0.98a-rc8",
                IndustryDemandSupplyMemoPlan.SETTINGS_CLASS,
                IndustryDemandSupplyMemoPlan.SETTINGS_SHA256,
                IndustryDemandSupplyMemoRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                "getIndustryDemand", IndustryDemandSupplyMemoPlan.QUERY_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                "getIndustrySupply", IndustryDemandSupplyMemoPlan.QUERY_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Exact Codex scope containing adjacent demand/supply queries for every industry. */
    static AdapterTarget industryDemandSupplyCodexTarget() {
        return new AdapterTarget(
                "vanilla-codex-industry-demand-supply-0.98a-rc8",
                IndustryDemandSupplyMemoPlan.CODEX_CLASS,
                IndustryDemandSupplyMemoPlan.CODEX_SHA256,
                IndustryDemandSupplyMemoRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        IndustryDemandSupplyMemoPlan.LINK_METHOD, "()V")),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer.api.jar",
                "6ac6c78c6116946d487376426340d019938f986ceae1391ae1fa599e890e3185",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Materializes a retained Codex variant on the first consumer access, then stores the member. */
    static AdapterTarget codexLazyFleetMemberEntryTarget() {
        return new AdapterTarget(
                "vanilla-codex-entry-lazy-fleet-members-0.98a-rc8",
                CodexLazyFleetMemberPlan.ENTRY_CLASS,
                CodexLazyFleetMemberPlan.ENTRY_SHA256,
                CodexLazyFleetMemberRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CodexLazyFleetMemberPlan.ACCESSOR_METHOD,
                        CodexLazyFleetMemberPlan.ACCESSOR_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer.api.jar",
                "6ac6c78c6116946d487376426340d019938f986ceae1391ae1fa599e890e3185",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    private static AdapterTarget indEvoSyntheticMarketTarget(
            String id, String className, String sha256, List<AdapterTarget.RequiredMethod> methods) {
        return new AdapterTarget(
                id,
                className,
                sha256,
                IndEvoSyntheticMarketRuntime.PLAN_ID,
                methods,
                "MOD",
                "indevo.jar",
                "1c319cd352619cd004b078db5bcf6e86095039fa3599d17ac4a9d609a6dcdfa0",
                "java/net/URLClassLoader",
                "");
    }

    static AdapterTarget indEvoRelaySyntheticMarketTarget() {
        return indEvoSyntheticMarketTarget(
                "indevo-4.1b-relay-synthetic-market-safety",
                IndEvoSyntheticMarketPlan.RELAY_CLASS,
                IndEvoSyntheticMarketPlan.RELAY_SHA256,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                "createCommRelayStation", "(Ljava/lang/String;)V"),
                        new AdapterTarget.RequiredMethod("removeCommRelayStation", "()V")));
    }

    static AdapterTarget indEvoArtillerySyntheticMarketTarget() {
        return indEvoSyntheticMarketTarget(
                "indevo-4.1b-artillery-synthetic-market-safety",
                IndEvoSyntheticMarketPlan.ARTILLERY_CLASS,
                IndEvoSyntheticMarketPlan.ARTILLERY_SHA256,
                List.of(
                        new AdapterTarget.RequiredMethod("apply", "()V"),
                        new AdapterTarget.RequiredMethod("unapply", "()V")));
    }

    static AdapterTarget indEvoWonderSyntheticMarketTarget() {
        return indEvoSyntheticMarketTarget(
                "indevo-4.1b-world-wonder-synthetic-market-safety",
                IndEvoSyntheticMarketPlan.WONDER_CLASS,
                IndEvoSyntheticMarketPlan.WONDER_SHA256,
                List.of(new AdapterTarget.RequiredMethod("apply", "()V")));
    }

    /** Vanilla campaign loop used only to segment opt-in frame-time recordings. */
    static AdapterTarget campaignFrameTimeStateTarget() {
        return frameTimeStateTarget(
                "vanilla-campaign-frame-time-segment-0.98a-rc8",
                FrameTimeStatePlan.CAMPAIGN_CLASS,
                FrameTimeStatePlan.CAMPAIGN_SHA256)
                .withAlternativeGroup("vanilla-campaign-frame-time-segment-0.98a-rc8");
    }

    static AdapterTarget linuxCampaignFrameTimeStateTarget() {
        return new AdapterTarget(
                "vanilla-campaign-linux-frame-time-segment-0.98a-rc8",
                FrameTimeStatePlan.CAMPAIGN_CLASS,
                FrameTimeStatePlan.LINUX_CAMPAIGN_SHA256,
                FrameTimeStatePlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        FrameTimeStatePlan.ADVANCE_METHOD,
                        FrameTimeStatePlan.ADVANCE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "starfarer_obf.jar",
                "3d41d31d4840158491426f0570f42d71c176d9bc9cc84605a284e4c76c8b91b0",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-campaign-frame-time-segment-0.98a-rc8");
    }

    static AdapterTarget windowsCampaignFrameTimeStateTarget() {
        return windowsCoreTarget(
                "vanilla-campaign-frame-time-windows-segment-0.98a-rc8",
                FrameTimeStatePlan.CAMPAIGN_CLASS,
                FrameTimeStatePlan.WINDOWS_CAMPAIGN_SHA256,
                FrameTimeStatePlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        FrameTimeStatePlan.ADVANCE_METHOD,
                        FrameTimeStatePlan.WINDOWS_ADVANCE_DESCRIPTOR)),
                "vanilla-campaign-frame-time-segment-0.98a-rc8");
    }

    /** Exact vanilla combat loop used for one-shot runtime integrity and opt-in frame segments. */
    static AdapterTarget combatRuntimeIntegrityTarget() {
        return new AdapterTarget(
                "vanilla-combat-runtime-integrity-0.98a-rc8",
                CombatRuntimeIntegrityPlan.TARGET_CLASS,
                CombatRuntimeIntegrityPlan.ORIGINAL_SHA256,
                CombatRuntimeIntegrityRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CombatRuntimeIntegrityPlan.ADVANCE_METHOD,
                        CombatRuntimeIntegrityPlan.ADVANCE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-combat-runtime-integrity-0.98a-rc8");
    }

    /** Exact combat input loop used only for closed desktop-smoke input actions. */
    static AdapterTarget combatStateInputTarget() {
        return new AdapterTarget(
                "vanilla-combat-state-input-0.98a-rc8",
                CombatRuntimeIntegrityPlan.COMBAT_STATE_CLASS,
                CombatRuntimeIntegrityPlan.COMBAT_STATE_SHA256,
                CombatRuntimeIntegrityRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CombatRuntimeIntegrityPlan.TRAVERSE_METHOD,
                        CombatRuntimeIntegrityPlan.TRAVERSE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-combat-state-input-0.98a-rc8");
    }

    static AdapterTarget windowsCombatRuntimeIntegrityTarget() {
        return windowsCoreTarget(
                "vanilla-combat-runtime-integrity-windows-0.98a-rc8",
                CombatRuntimeIntegrityPlan.TARGET_CLASS,
                CombatRuntimeIntegrityPlan.WINDOWS_ORIGINAL_SHA256,
                CombatRuntimeIntegrityRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CombatRuntimeIntegrityPlan.ADVANCE_METHOD,
                        CombatRuntimeIntegrityPlan.WINDOWS_ADVANCE_DESCRIPTOR)),
                "vanilla-combat-runtime-integrity-0.98a-rc8");
    }

    static AdapterTarget windowsCombatStateInputTarget() {
        return windowsCoreTarget(
                "vanilla-combat-state-input-windows-0.98a-rc8",
                CombatRuntimeIntegrityPlan.COMBAT_STATE_CLASS,
                CombatRuntimeIntegrityPlan.WINDOWS_COMBAT_STATE_SHA256,
                CombatRuntimeIntegrityRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CombatRuntimeIntegrityPlan.TRAVERSE_METHOD,
                        CombatRuntimeIntegrityPlan.TRAVERSE_DESCRIPTOR)),
                "vanilla-combat-state-input-0.98a-rc8");
    }

    /** Exact vanilla collision-grid query that builds a temporary insertion-ordered candidate set. */
    static AdapterTarget collisionQuerySetTarget() {
        return new AdapterTarget(
                "vanilla-collision-query-open-set-0.98a-rc8",
                CollisionQuerySetPlan.TARGET_CLASS,
                CollisionQuerySetPlan.ORIGINAL_SHA256,
                CollisionQuerySetPlan.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                CollisionQuerySetPlan.CONSTRUCTOR,
                                CollisionQuerySetPlan.CONSTRUCTOR_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                CollisionQuerySetPlan.COPY_METHOD,
                                CollisionQuerySetPlan.COPY_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    private static AdapterTarget frameTimeStateTarget(String id, String className, String classHash) {
        return new AdapterTarget(
                id,
                className,
                classHash,
                FrameTimeStatePlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        FrameTimeStatePlan.ADVANCE_METHOD,
                        FrameTimeStatePlan.ADVANCE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /**
     * The campaign's per-location entity lookup, in {@code starfarer_obf.jar} rather than the
     * graphics jar every other target lives in.
     *
     * <p>{@code BaseLocation} implements {@code com.fs.util.DoNotObfuscate}, so its name and members
     * survive a rebuild that renames everything around them. The class hash is still pinned like
     * every other target -- a stable name is not a stable body.
     */
    static AdapterTarget campaignEntityIndexTarget() {
        return new AdapterTarget(
                "vanilla-base-location-0.98a-rc8-entity-index",
                EntityLookupPlan.TARGET_CLASS,
                "ab16080b8c40d8f61d522089f3c3696fe3b7c8d8f8b287f9c12a47fa449bae24",
                EntityLookupRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                EntityLookupPlan.LOOKUP_METHOD, EntityLookupPlan.LOOKUP_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                EntityLookupPlan.ENTITIES_METHOD, EntityLookupPlan.ENTITIES_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                EntityLookupPlan.OBJECTS_METHOD, EntityLookupPlan.OBJECTS_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                CampaignEntityMaintenancePlan.PAUSED_LOCATION_METHOD,
                                CampaignEntityMaintenancePlan.LOCATION_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                CampaignEntityMaintenancePlan.ACTIVE_LOCATION_METHOD,
                                CampaignEntityMaintenancePlan.LOCATION_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** The repository list factory that supplies mutation generations to the campaign index. */
    static AdapterTarget campaignEntityRepositoryTarget() {
        return new AdapterTarget(
                "vanilla-object-repository-0.98a-rc8-entity-index",
                EntityRepositoryListPlan.TARGET_CLASS,
                EntityRepositoryListPlan.ORIGINAL_SHA256,
                EntityLookupRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                EntityRepositoryListPlan.GET_LIST_METHOD,
                                EntityRepositoryListPlan.GET_LIST_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                EntityRepositoryListPlan.ADD_METHOD,
                                EntityRepositoryListPlan.ADD_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/fs.common_obf.jar",
                "10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** The stable base setter through which ordinary campaign entities change ids. */
    static AdapterTarget campaignEntityIdMutationTarget() {
        return new AdapterTarget(
                "vanilla-base-campaign-entity-0.98a-rc8-entity-index",
                EntityIdMutationPlan.TARGET_CLASS,
                EntityIdMutationPlan.ORIGINAL_SHA256,
                EntityLookupRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        EntityIdMutationPlan.SET_ID_METHOD,
                        EntityIdMutationPlan.SET_ID_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** The exact vanilla deployment member grid observed in the gameplay recording. */
    static AdapterTarget deploymentIconCacheTarget() {
        return new AdapterTarget(
                "vanilla-deployment-member-icon-cache-v2-0.98a-rc8",
                DeploymentIconCachePlan.TARGET_CLASS,
                DeploymentIconCachePlan.ORIGINAL_SHA256,
                DeploymentIconCacheRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                DeploymentIconCachePlan.LOOKUP_METHOD,
                                DeploymentIconCachePlan.LOOKUP_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                DeploymentIconCachePlan.MEMBERS_METHOD,
                                DeploymentIconCachePlan.MEMBERS_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                DeploymentIconCachePlan.LIST_METHOD,
                                DeploymentIconCachePlan.LIST_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                DeploymentIconCachePlan.CLEAR_METHOD,
                                DeploymentIconCachePlan.CLEAR_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                DeploymentIconCachePlan.ADD_METHOD,
                                DeploymentIconCachePlan.ADD_MEMBER_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                DeploymentIconCachePlan.ADD_METHOD,
                                DeploymentIconCachePlan.ADD_COMBAT_ENTRY_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                DeploymentIconCachePlan.REMOVE_METHOD,
                                DeploymentIconCachePlan.REMOVE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** The exact vanilla campaign radar renderer observed in campaign frame samples. */
    static AdapterTarget radarRenderTarget() {
        return new AdapterTarget(
                "vanilla-campaign-radar-type-set-0.98a-rc8",
                RadarRenderPlan.TARGET_CLASS,
                RadarRenderPlan.ORIGINAL_SHA256,
                RadarRenderRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        RadarRenderPlan.RENDER_METHOD,
                        RadarRenderPlan.RENDER_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** The exact vanilla per-commodity event-mod rewrite observed in campaign frame samples. */
    static AdapterTarget commodityEventModMemoTarget() {
        return new AdapterTarget(
                "vanilla-commodity-event-mod-memo-0.98a-rc8",
                CommodityEventModMemoPlan.TARGET_CLASS,
                CommodityEventModMemoPlan.ORIGINAL_SHA256,
                CommodityEventModMemoRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                CommodityEventModMemoPlan.METHOD,
                                CommodityEventModMemoPlan.DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                CommodityEventModMemoPlan.QUANTITY_METHOD,
                                CommodityEventModMemoPlan.QUANTITY_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                CommodityEventModMemoPlan.MOD_VALUE_METHOD,
                                CommodityEventModMemoPlan.MOD_VALUE_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                CommodityEventModMemoPlan.AVAILABLE_METHOD,
                                CommodityEventModMemoPlan.AVAILABLE_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                CommodityEventModMemoPlan.COMMODITY_METHOD,
                                CommodityEventModMemoPlan.COMMODITY_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Read-only dirty-state seam used to make unchanged commodity memo hits constant work. */
    static AdapterTarget mutableStatDirtyAccessorTarget() {
        return new AdapterTarget(
                "vanilla-mutable-stat-dirty-accessor-0.98a-rc8",
                MutableStatDirtyAccessorPlan.TARGET_CLASS,
                MutableStatDirtyAccessorPlan.ORIGINAL_SHA256,
                CommodityEventModMemoRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        MutableStatDirtyAccessorPlan.VALUE_METHOD,
                        MutableStatDirtyAccessorPlan.VALUE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer.api.jar",
                "6ac6c78c6116946d487376426340d019938f986ceae1391ae1fa599e890e3185",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Starsector 0.98a-RC8 temporary-stat advancement in starfarer.api.jar. */
    static AdapterTarget mutableStatTempAdvanceTarget() {
        return new AdapterTarget(
                "vanilla-mutable-stat-temp-advance-0.98a-rc8",
                MutableStatTempAdvancePlan.TARGET_CLASS,
                MutableStatTempAdvancePlan.ORIGINAL_SHA256,
                MutableStatTempAdvancePlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        MutableStatTempAdvancePlan.METHOD,
                        MutableStatTempAdvancePlan.DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer.api.jar",
                "6ac6c78c6116946d487376426340d019938f986ceae1391ae1fa599e890e3185",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    static AdapterTarget campaignEntityScriptsTarget() {
        return new AdapterTarget(
                "vanilla-campaign-entity-empty-scripts-0.98a-rc8",
                CampaignEntityMaintenancePlan.ENTITY_CLASS,
                CampaignEntityMaintenancePlan.ENTITY_SHA256,
                CampaignEntityMaintenanceRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CampaignEntityMaintenancePlan.SCRIPT_METHOD,
                        CampaignEntityMaintenancePlan.SCRIPT_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    static AdapterTarget campaignFleetViewSnapshotTarget() {
        return new AdapterTarget(
                "vanilla-campaign-fleet-view-single-snapshot-0.98a-rc8",
                CampaignEntityMaintenancePlan.FLEET_VIEW_CLASS,
                CampaignEntityMaintenancePlan.FLEET_VIEW_SHA256,
                CampaignEntityMaintenanceRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CampaignEntityMaintenancePlan.ADVANCE_METHOD,
                        CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    static AdapterTarget campaignMarketSnapshotTarget() {
        return new AdapterTarget(
                "vanilla-campaign-market-compact-snapshots-0.98a-rc8",
                CampaignEntityMaintenancePlan.MARKET_CLASS,
                CampaignEntityMaintenancePlan.MARKET_SHA256,
                CampaignEntityMaintenanceRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CampaignEntityMaintenancePlan.ADVANCE_METHOD,
                        CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    static AdapterTarget campaignMemoryMaintenanceTarget() {
        return new AdapterTarget(
                "vanilla-campaign-memory-empty-maintenance-0.98a-rc8",
                CampaignEntityMaintenancePlan.MEMORY_CLASS,
                CampaignEntityMaintenancePlan.MEMORY_SHA256,
                CampaignEntityMaintenanceRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CampaignEntityMaintenancePlan.ADVANCE_METHOD,
                        CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    static AdapterTarget campaignPausedConditionSnapshotTarget() {
        return new AdapterTarget(
                "vanilla-campaign-paused-condition-compact-snapshot-0.98a-rc8",
                CampaignEntityMaintenancePlan.ECONOMY_CLASS,
                CampaignEntityMaintenancePlan.ECONOMY_SHA256,
                CampaignEntityMaintenanceRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CampaignEntityMaintenancePlan.PAUSED_CONDITIONS_METHOD,
                        CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    static AdapterTarget hyperspaceAutomatonNeighborTarget() {
        return new AdapterTarget(
                "vanilla-hyperspace-automaton-neighbor-count-0.98a-rc8",
                CampaignEntityMaintenancePlan.HYPERSPACE_AUTOMATON_CLASS,
                CampaignEntityMaintenancePlan.HYPERSPACE_AUTOMATON_SHA256,
                CampaignEntityMaintenanceRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CampaignEntityMaintenancePlan.LIVE_NEIGHBOR_METHOD,
                        CampaignEntityMaintenancePlan.LIVE_NEIGHBOR_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer.api.jar",
                "6ac6c78c6116946d487376426340d019938f986ceae1391ae1fa599e890e3185",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    static AdapterTarget fleetAiProfilerLabelTarget() {
        return new AdapterTarget(
                "vanilla-modular-fleet-ai-profiler-label-0.98a-rc8",
                FleetAiProfilerPlan.FLEET_AI_CLASS,
                FleetAiProfilerPlan.FLEET_AI_SHA256,
                FleetAiProfilerRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        FleetAiProfilerPlan.ADVANCE,
                        FleetAiProfilerPlan.ADVANCE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    static AdapterTarget profilerToggleTarget() {
        return new AdapterTarget(
                "vanilla-profiler-state-publish-0.98a-rc8",
                FleetAiProfilerPlan.PROFILER_CLASS,
                FleetAiProfilerPlan.PROFILER_SHA256,
                FleetAiProfilerRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        FleetAiProfilerPlan.PROFILER_TOGGLE,
                        FleetAiProfilerPlan.PROFILER_TOGGLE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/fs.common_obf.jar",
                "10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** The exact refit simulator method that consumes the merged simulation-opponent id list. */
    static AdapterTarget simOpponentSafetyTarget() {
        return new AdapterTarget(
                "vanilla-refit-simulator-opponent-safety-0.98a-rc8",
                SimOpponentSafetyPlan.TARGET_CLASS,
                SimOpponentSafetyPlan.ORIGINAL_SHA256,
                SimOpponentSafetyRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                SimOpponentSafetyPlan.SIMULATION_METHOD,
                                SimOpponentSafetyPlan.SIMULATION_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                SimOpponentSafetyPlan.LAUNCH_METHOD,
                                SimOpponentSafetyPlan.LAUNCH_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** The exact stock dialog that turns simulator reserves into the visible opponent grid. */
    static AdapterTarget simOpponentDialogProbeTarget() {
        return new AdapterTarget(
                "vanilla-simulator-opponent-dialog-probe-0.98a-rc8",
                SimOpponentDialogProbePlan.TARGET_CLASS,
                SimOpponentDialogProbePlan.ORIGINAL_SHA256,
                SimOpponentSafetyRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        SimOpponentDialogProbePlan.GRID_METHOD,
                        SimOpponentDialogProbePlan.GRID_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** The loading bar reaches 100% before this class waits for audio and runs final callbacks. */
    static AdapterTarget startupPhaseTarget() {
        return new AdapterTarget(
                "vanilla-resource-loader-0.98a-rc8-startup-phases",
                StartupPhasePlan.TARGET_CLASS,
                "a64927cec70db4e15d54b8611073b4008ba62878e0208f30ca338d66377214ab",
                StartupPhaseRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        StartupPhasePlan.INIT_METHOD, StartupPhasePlan.INIT_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** The exact coordinator for the data loaders hidden before the first progress update. */
    static AdapterTarget specStorePhaseTarget() {
        return new AdapterTarget(
                "vanilla-spec-store-0.98a-rc8-startup-phases",
                SpecStorePhasePlan.TARGET_CLASS,
                "1947fee1403e93b27ae89b4995fcfde5f65b8ffe1ef3f564b4daaed3a5e69821",
                StartupPhaseRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                SpecStorePhasePlan.INIT_METHOD, SpecStorePhasePlan.INIT_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                FactionLoaderPhasePlan.LOAD_METHOD,
                                FactionLoaderPhasePlan.LOAD_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Exact weapon-definition loader called during the initial zero-percent plateau. */
    static AdapterTarget weaponLoaderPhaseTarget() {
        return new AdapterTarget(
                "vanilla-weapon-loader-0.98a-rc8-startup-phases",
                WeaponLoaderPhasePlan.TARGET_CLASS,
                "c1e7a8a4c33d7ee7f714b05ac94dfa20745142d72ce868e954e8e6a04dc0544c",
                StartupPhaseRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                WeaponLoaderPhasePlan.LOAD_ALL_METHOD,
                                WeaponLoaderPhasePlan.LOAD_ALL_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                WeaponLoaderPhasePlan.LOAD_ONE_METHOD,
                                WeaponLoaderPhasePlan.LOAD_ONE_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                ProjectileLoaderPhasePlan.LOAD_ALL_METHOD,
                                ProjectileLoaderPhasePlan.LOAD_ALL_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                ProjectileLoaderPhasePlan.LOAD_ONE_METHOD,
                                ProjectileLoaderPhasePlan.LOAD_ONE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Exact ship-hull definition loader called during the initial zero-percent plateau. */
    static AdapterTarget shipHullLoaderPhaseTarget() {
        return new AdapterTarget(
                "vanilla-ship-hull-loader-0.98a-rc8-startup-phases",
                ShipHullLoaderPhasePlan.TARGET_CLASS,
                "88264bcb82e626aeab0ad8cc5a3d210a95b225d0ca5e3329e7982425a12b355c",
                StartupPhaseRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                ShipHullLoaderPhasePlan.LOAD_ALL_METHOD,
                                ShipHullLoaderPhasePlan.LOAD_ALL_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                ShipHullLoaderPhasePlan.LOAD_ONE_METHOD,
                                ShipHullLoaderPhasePlan.LOAD_ONE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Exact campaign-rules loader called near the end of the initial zero-percent plateau. */
    static AdapterTarget rulesLoaderPhaseTarget() {
        return new AdapterTarget(
                "vanilla-rules-loader-0.98a-rc8-startup-phases",
                RulesLoaderPhasePlan.TARGET_CLASS,
                "61f5432f35037ac48cb665930652f01e72e4ea94085ddf0676cd80b07b98d996",
                StartupPhaseRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        RulesLoaderPhasePlan.LOAD_METHOD,
                        RulesLoaderPhasePlan.LOAD_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Exact rule-expression constructor, the 62,340-call interior of the rules loader. */
    static AdapterTarget ruleExpressionPhaseTarget() {
        return new AdapterTarget(
                "vanilla-rule-expression-0.98a-rc8-startup-phases",
                RuleExpressionPhasePlan.TARGET_CLASS,
                "8f628d7fece777d0b100d1fe526e12873f9c0bb533c4367dc015b81184660a95",
                StartupPhaseRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        RuleExpressionPhasePlan.LOAD_METHOD,
                        RuleExpressionPhasePlan.LOAD_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /** Same expression constructor, used by the in-process tokenizer memo. */
    static AdapterTarget ruleTokenCacheTarget() {
        return new AdapterTarget(
                "vanilla-rule-expression-0.98a-rc8-token-cache",
                RuleTokenCachePlan.TARGET_CLASS,
                "8f628d7fece777d0b100d1fe526e12873f9c0bb533c4367dc015b81184660a95",
                RuleTokenCacheRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        RuleTokenCachePlan.LOAD_METHOD,
                        RuleTokenCachePlan.LOAD_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-rule-expression-0.98a-rc8-token-cache");
    }

    static AdapterTarget linuxRuleTokenCacheTarget() {
        return linuxCoreTarget(
                "vanilla-rule-expression-linux-0.98a-rc8-token-cache",
                RuleExpressionPhasePlan.LINUX_TARGET_CLASS,
                "894b652ad366387a6fb15dd066fca922c70411b502496a079cec2fd065a57760",
                RuleTokenCacheRuntime.PLAN_ID,
                ruleTokenCacheTarget().requiredMethods(),
                "vanilla-rule-expression-0.98a-rc8-token-cache");
    }

    static AdapterTarget windowsRuleTokenCacheTarget() {
        return windowsCoreTarget(
                "vanilla-rule-expression-windows-0.98a-rc8-token-cache",
                RuleExpressionPhasePlan.WINDOWS_TARGET_CLASS,
                "2161e729532ae56c5e3eb6738584f28742d95d272f7d87172fc4fffe5cbeeb13",
                RuleTokenCacheRuntime.PLAN_ID,
                ruleTokenCacheTarget().requiredMethods(),
                "vanilla-rule-expression-0.98a-rc8-token-cache");
    }

    /** Exact campaign-rules loader used by the trigger-local duplicate index. */
    static AdapterTarget rulesDuplicateIndexTarget() {
        return new AdapterTarget(
                "vanilla-rules-loader-0.98a-rc8-duplicate-index",
                RulesLoaderPhasePlan.TARGET_CLASS,
                "61f5432f35037ac48cb665930652f01e72e4ea94085ddf0676cd80b07b98d996",
                RulesDuplicateIndexRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        RulesLoaderPhasePlan.LOAD_METHOD,
                        RulesLoaderPhasePlan.LOAD_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-rules-loader-0.98a-rc8-duplicate-index");
    }

    static AdapterTarget linuxRulesDuplicateIndexTarget() {
        return linuxCoreTarget(
                "vanilla-rules-loader-linux-0.98a-rc8-duplicate-index",
                RulesLoaderPhasePlan.TARGET_CLASS,
                "7865fa80d98032c50346f800daecdd2d0dd6935a67e0ab58159410aa7c7c2842",
                RulesDuplicateIndexRuntime.PLAN_ID,
                rulesDuplicateIndexTarget().requiredMethods(),
                "vanilla-rules-loader-0.98a-rc8-duplicate-index");
    }

    static AdapterTarget windowsRulesDuplicateIndexTarget() {
        return windowsCoreTarget(
                "vanilla-rules-loader-windows-0.98a-rc8-duplicate-index",
                RulesLoaderPhasePlan.TARGET_CLASS,
                "72f0925d83ff48bfa2c4b8d2f691b10935d4567dc6ab1e12392a2ee388539df9",
                RulesDuplicateIndexRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        RulesLoaderPhasePlan.WINDOWS_LOAD_METHOD,
                        RulesLoaderPhasePlan.LOAD_DESCRIPTOR)),
                "vanilla-rules-loader-0.98a-rc8-duplicate-index");
    }

    /** Exact campaign-rules loader used by the strict-profile merged-CSV cache. */
    static AdapterTarget rulesCsvCacheTarget() {
        return new AdapterTarget(
                "vanilla-rules-loader-0.98a-rc8-csv-cache",
                RulesLoaderPhasePlan.TARGET_CLASS,
                "61f5432f35037ac48cb665930652f01e72e4ea94085ddf0676cd80b07b98d996",
                RulesCsvCacheRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        RulesLoaderPhasePlan.LOAD_METHOD,
                        RulesLoaderPhasePlan.LOAD_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-rules-loader-0.98a-rc8-csv-cache");
    }

    static AdapterTarget linuxRulesCsvCacheTarget() {
        return linuxCoreTarget(
                "vanilla-rules-loader-linux-0.98a-rc8-csv-cache",
                RulesLoaderPhasePlan.TARGET_CLASS,
                "7865fa80d98032c50346f800daecdd2d0dd6935a67e0ab58159410aa7c7c2842",
                RulesCsvCacheRuntime.PLAN_ID,
                rulesCsvCacheTarget().requiredMethods(),
                "vanilla-rules-loader-0.98a-rc8-csv-cache");
    }

    static AdapterTarget windowsRulesCsvCacheTarget() {
        return windowsCoreTarget(
                "vanilla-rules-loader-windows-0.98a-rc8-csv-cache",
                RulesLoaderPhasePlan.TARGET_CLASS,
                "72f0925d83ff48bfa2c4b8d2f691b10935d4567dc6ab1e12392a2ee388539df9",
                RulesCsvCacheRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        RulesLoaderPhasePlan.WINDOWS_LOAD_METHOD,
                        RulesLoaderPhasePlan.LOAD_DESCRIPTOR)),
                "vanilla-rules-loader-0.98a-rc8-csv-cache");
    }

    /** Exact campaign-rules loader used by the fixed-pattern regex cache. */
    static AdapterTarget rulesRegexCacheTarget() {
        return new AdapterTarget(
                "vanilla-rules-loader-0.98a-rc8-regex-cache",
                RulesRegexCachePlan.TARGET_CLASS,
                "61f5432f35037ac48cb665930652f01e72e4ea94085ddf0676cd80b07b98d996",
                RulesRegexCacheRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        RulesRegexCachePlan.LOAD_METHOD,
                        RulesRegexCachePlan.LOAD_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-rules-loader-0.98a-rc8-regex-cache");
    }

    static AdapterTarget linuxRulesRegexCacheTarget() {
        return linuxCoreTarget(
                "vanilla-rules-loader-linux-0.98a-rc8-regex-cache",
                RulesRegexCachePlan.TARGET_CLASS,
                "7865fa80d98032c50346f800daecdd2d0dd6935a67e0ab58159410aa7c7c2842",
                RulesRegexCacheRuntime.PLAN_ID,
                rulesRegexCacheTarget().requiredMethods(),
                "vanilla-rules-loader-0.98a-rc8-regex-cache");
    }

    static AdapterTarget windowsRulesRegexCacheTarget() {
        return windowsCoreTarget(
                "vanilla-rules-loader-windows-0.98a-rc8-regex-cache",
                RulesRegexCachePlan.TARGET_CLASS,
                "72f0925d83ff48bfa2c4b8d2f691b10935d4567dc6ab1e12392a2ee388539df9",
                RulesRegexCacheRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        RulesLoaderPhasePlan.WINDOWS_LOAD_METHOD,
                        RulesRegexCachePlan.LOAD_DESCRIPTOR)),
                "vanilla-rules-loader-0.98a-rc8-regex-cache");
    }

    /** The expression class again, this time for its static command-name resolver. */
    static AdapterTarget ruleCommandClassLookupTarget() {
        return new AdapterTarget(
                "vanilla-rule-expression-0.98a-rc8-command-class-cache",
                RuleCommandClassCachePlan.TARGET_CLASS,
                "8f628d7fece777d0b100d1fe526e12873f9c0bb533c4367dc015b81184660a95",
                RuleCommandClassCacheRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        RuleCommandClassCachePlan.LOOKUP_METHOD,
                        RuleCommandClassCachePlan.LOOKUP_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-rule-expression-0.98a-rc8-command-class-cache");
    }

    static AdapterTarget linuxRuleCommandClassLookupTarget() {
        return linuxCoreTarget(
                "vanilla-rule-expression-linux-0.98a-rc8-command-class-cache",
                RuleExpressionPhasePlan.LINUX_TARGET_CLASS,
                "894b652ad366387a6fb15dd066fca922c70411b502496a079cec2fd065a57760",
                RuleCommandClassCacheRuntime.PLAN_ID,
                ruleCommandClassLookupTarget().requiredMethods(),
                "vanilla-rule-expression-0.98a-rc8-command-class-cache");
    }

    static AdapterTarget windowsRuleCommandClassLookupTarget() {
        return windowsCoreTarget(
                "vanilla-rule-expression-windows-0.98a-rc8-command-class-cache",
                RuleExpressionPhasePlan.WINDOWS_TARGET_CLASS,
                "2161e729532ae56c5e3eb6738584f28742d95d272f7d87172fc4fffe5cbeeb13",
                RuleCommandClassCacheRuntime.PLAN_ID,
                ruleCommandClassLookupTarget().requiredMethods(),
                "vanilla-rule-expression-0.98a-rc8-command-class-cache");
    }

    /** The rules loader, where the learning run publishes what it observed. */
    static AdapterTarget ruleCommandClassPublishTarget() {
        return new AdapterTarget(
                "vanilla-rules-loader-0.98a-rc8-command-class-publish",
                RulesLoaderPhasePlan.TARGET_CLASS,
                "61f5432f35037ac48cb665930652f01e72e4ea94085ddf0676cd80b07b98d996",
                RuleCommandClassCacheRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        RulesLoaderPhasePlan.LOAD_METHOD,
                        RulesLoaderPhasePlan.LOAD_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-rules-loader-0.98a-rc8-command-class-publish");
    }

    static AdapterTarget linuxRuleCommandClassPublishTarget() {
        return linuxCoreTarget(
                "vanilla-rules-loader-linux-0.98a-rc8-command-class-publish",
                RulesLoaderPhasePlan.TARGET_CLASS,
                "7865fa80d98032c50346f800daecdd2d0dd6935a67e0ab58159410aa7c7c2842",
                RuleCommandClassCacheRuntime.PLAN_ID,
                ruleCommandClassPublishTarget().requiredMethods(),
                "vanilla-rules-loader-0.98a-rc8-command-class-publish");
    }

    static AdapterTarget windowsRuleCommandClassPublishTarget() {
        return windowsCoreTarget(
                "vanilla-rules-loader-windows-0.98a-rc8-command-class-publish",
                RulesLoaderPhasePlan.TARGET_CLASS,
                "72f0925d83ff48bfa2c4b8d2f691b10935d4567dc6ab1e12392a2ee388539df9",
                RuleCommandClassCacheRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        RulesLoaderPhasePlan.WINDOWS_LOAD_METHOD,
                        RulesLoaderPhasePlan.LOAD_DESCRIPTOR)),
                "vanilla-rules-loader-0.98a-rc8-command-class-publish");
    }

    /** Exact reviewed variant loader used by the strict-profile merged-JSON cache. */
    static AdapterTarget variantJsonCacheTarget() {
        return new AdapterTarget(
                "vanilla-spec-store-0.98a-rc8-variant-json-cache",
                SpecStorePhasePlan.TARGET_CLASS,
                "1947fee1403e93b27ae89b4995fcfde5f65b8ffe1ef3f564b4daaed3a5e69821",
                VariantJsonCacheRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        VariantLoaderPhasePlan.METHOD, VariantLoaderPhasePlan.DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-spec-store-0.98a-rc8-variant-json-cache");
    }

    static AdapterTarget linuxVariantJsonCacheTarget() {
        return linuxCoreTarget(
                "vanilla-spec-store-linux-0.98a-rc8-variant-json-cache",
                SpecStorePhasePlan.TARGET_CLASS,
                "c24e0891883158c29767bd1d94cb41f4ce281418669d80b39472745626e23172",
                VariantJsonCacheRuntime.PLAN_ID,
                variantJsonCacheTarget().requiredMethods(),
                "vanilla-spec-store-0.98a-rc8-variant-json-cache");
    }

    static AdapterTarget windowsVariantJsonCacheTarget() {
        return windowsCoreTarget(
                "vanilla-spec-store-windows-0.98a-rc8-variant-json-cache",
                SpecStorePhasePlan.TARGET_CLASS,
                "011125fae8e21c0c1618d50258e9cf4b2292f0179093b3659ddc4f9a2555a5d8",
                VariantJsonCacheRuntime.PLAN_ID,
                variantJsonCacheTarget().requiredMethods(),
                "vanilla-spec-store-0.98a-rc8-variant-json-cache");
    }

    /** Exact SpecStore smart-quote cleanup used when no prepared variant cache is available. */
    static AdapterTarget specStoreQuoteNormalizationTarget() {
        return new AdapterTarget(
                "vanilla-spec-store-0.98a-rc8-quote-normalization",
                SpecStoreQuoteNormalizationPlan.TARGET_CLASS,
                "1947fee1403e93b27ae89b4995fcfde5f65b8ffe1ef3f564b4daaed3a5e69821",
                SpecStoreQuoteNormalizationPlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        SpecStoreQuoteNormalizationPlan.METHOD,
                        SpecStoreQuoteNormalizationPlan.DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-spec-store-0.98a-rc8-quote-normalization");
    }

    static AdapterTarget linuxSpecStoreQuoteNormalizationTarget() {
        return linuxCoreTarget(
                "vanilla-spec-store-linux-0.98a-rc8-quote-normalization",
                SpecStoreQuoteNormalizationPlan.TARGET_CLASS,
                "c24e0891883158c29767bd1d94cb41f4ce281418669d80b39472745626e23172",
                SpecStoreQuoteNormalizationPlan.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        SpecStoreQuoteNormalizationPlan.LINUX_METHOD,
                        SpecStoreQuoteNormalizationPlan.DESCRIPTOR)),
                "vanilla-spec-store-0.98a-rc8-quote-normalization");
    }

    static AdapterTarget windowsSpecStoreQuoteNormalizationTarget() {
        return windowsCoreTarget(
                "vanilla-spec-store-windows-0.98a-rc8-quote-normalization",
                SpecStoreQuoteNormalizationPlan.TARGET_CLASS,
                "011125fae8e21c0c1618d50258e9cf4b2292f0179093b3659ddc4f9a2555a5d8",
                SpecStoreQuoteNormalizationPlan.PLAN_ID,
                specStoreQuoteNormalizationTarget().requiredMethods(),
                "vanilla-spec-store-0.98a-rc8-quote-normalization");
    }

    /** Exact reviewed weapon loader used by the strict-profile merged-JSON cache. */
    static AdapterTarget weaponJsonCacheTarget() {
        return new AdapterTarget(
                "vanilla-weapon-loader-0.98a-rc8-json-cache",
                WeaponLoaderPhasePlan.TARGET_CLASS,
                "c1e7a8a4c33d7ee7f714b05ac94dfa20745142d72ce868e954e8e6a04dc0544c",
                WeaponJsonCacheRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                WeaponLoaderPhasePlan.LOAD_ALL_METHOD,
                                WeaponLoaderPhasePlan.LOAD_ALL_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                WeaponLoaderPhasePlan.LOAD_ONE_METHOD,
                                WeaponLoaderPhasePlan.LOAD_ONE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-weapon-loader-0.98a-rc8-json-cache");
    }

    static AdapterTarget linuxWeaponJsonCacheTarget() {
        return linuxCoreTarget(
                "vanilla-weapon-loader-linux-0.98a-rc8-json-cache",
                WeaponLoaderPhasePlan.TARGET_CLASS,
                "d551ae2441d94c338cc4000bff809a5bd0f8d0783dfe2d9147831d289f91644e",
                WeaponJsonCacheRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                WeaponLoaderPhasePlan.LINUX_LOAD_ALL_METHOD,
                                WeaponLoaderPhasePlan.LOAD_ALL_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                WeaponLoaderPhasePlan.LINUX_LOAD_ONE_METHOD,
                                WeaponLoaderPhasePlan.LOAD_ONE_DESCRIPTOR)),
                "vanilla-weapon-loader-0.98a-rc8-json-cache");
    }

    static AdapterTarget windowsWeaponJsonCacheTarget() {
        return windowsCoreTarget(
                "vanilla-weapon-loader-windows-0.98a-rc8-json-cache",
                WeaponLoaderPhasePlan.TARGET_CLASS,
                "fb7a0efe7ecd7e9b56b31832d89288ac8909da68fc49bbea7b721a4bca2e05bd",
                WeaponJsonCacheRuntime.PLAN_ID,
                linuxWeaponJsonCacheTarget().requiredMethods(),
                "vanilla-weapon-loader-0.98a-rc8-json-cache");
    }

    /** Exact reviewed projectile loader used by the strict-profile merged-JSON cache. */
    static AdapterTarget projectileJsonCacheTarget() {
        return new AdapterTarget(
                "vanilla-projectile-loader-0.98a-rc8-json-cache",
                WeaponLoaderPhasePlan.TARGET_CLASS,
                "c1e7a8a4c33d7ee7f714b05ac94dfa20745142d72ce868e954e8e6a04dc0544c",
                ProjectileJsonCacheRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                ProjectileLoaderPhasePlan.LOAD_ALL_METHOD,
                                ProjectileLoaderPhasePlan.LOAD_ALL_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                ProjectileLoaderPhasePlan.LOAD_ONE_METHOD,
                                ProjectileLoaderPhasePlan.LOAD_ONE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-projectile-loader-0.98a-rc8-json-cache");
    }

    static AdapterTarget linuxProjectileJsonCacheTarget() {
        return linuxCoreTarget(
                "vanilla-projectile-loader-linux-0.98a-rc8-json-cache",
                WeaponLoaderPhasePlan.TARGET_CLASS,
                "d551ae2441d94c338cc4000bff809a5bd0f8d0783dfe2d9147831d289f91644e",
                ProjectileJsonCacheRuntime.PLAN_ID,
                projectileJsonCacheTarget().requiredMethods(),
                "vanilla-projectile-loader-0.98a-rc8-json-cache");
    }

    static AdapterTarget windowsProjectileJsonCacheTarget() {
        return windowsCoreTarget(
                "vanilla-projectile-loader-windows-0.98a-rc8-json-cache",
                WeaponLoaderPhasePlan.TARGET_CLASS,
                "fb7a0efe7ecd7e9b56b31832d89288ac8909da68fc49bbea7b721a4bca2e05bd",
                ProjectileJsonCacheRuntime.PLAN_ID,
                projectileJsonCacheTarget().requiredMethods(),
                "vanilla-projectile-loader-0.98a-rc8-json-cache");
    }

    /** Exact reviewed ship-hull loader used by the strict-profile merged-JSON cache. */
    static AdapterTarget hullJsonCacheTarget() {
        return new AdapterTarget(
                "vanilla-ship-hull-loader-0.98a-rc8-json-cache",
                ShipHullLoaderPhasePlan.TARGET_CLASS,
                "88264bcb82e626aeab0ad8cc5a3d210a95b225d0ca5e3329e7982425a12b355c",
                HullJsonCacheRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                ShipHullLoaderPhasePlan.LOAD_ALL_METHOD,
                                ShipHullLoaderPhasePlan.LOAD_ALL_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                ShipHullLoaderPhasePlan.LOAD_ONE_METHOD,
                                ShipHullLoaderPhasePlan.LOAD_ONE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-ship-hull-loader-0.98a-rc8-json-cache");
    }

    static AdapterTarget linuxHullJsonCacheTarget() {
        return linuxCoreTarget(
                "vanilla-ship-hull-loader-linux-0.98a-rc8-json-cache",
                ShipHullLoaderPhasePlan.TARGET_CLASS,
                "1132ea9ddf52b2d6293f9ac8379fbb7dee3181ca5652a87bcf6f64a655fc5c00",
                HullJsonCacheRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                ShipHullLoaderPhasePlan.LOAD_ALL_METHOD,
                                ShipHullLoaderPhasePlan.LOAD_ALL_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                ShipHullLoaderPhasePlan.LINUX_LOAD_ONE_METHOD,
                                ShipHullLoaderPhasePlan.LOAD_ONE_DESCRIPTOR)),
                "vanilla-ship-hull-loader-0.98a-rc8-json-cache");
    }

    static AdapterTarget windowsHullJsonCacheTarget() {
        return windowsCoreTarget(
                "vanilla-ship-hull-loader-windows-0.98a-rc8-json-cache",
                ShipHullLoaderPhasePlan.TARGET_CLASS,
                "93a78a8b95c8f9abf0cbcc5523efb706efe0c5f02cf6f3956a3a7dae78f91f43",
                HullJsonCacheRuntime.PLAN_ID,
                hullJsonCacheTarget().requiredMethods(),
                "vanilla-ship-hull-loader-0.98a-rc8-json-cache");
    }

    private static AdapterTarget textureTarget(String id, String planId) {
        return new AdapterTarget(
                id,
                "com/fs/graphics/TextureLoader",
                "d8fcb4cb90d457fc3075e711b6293940774dcf990ea66a7584c231bd96898b50",
                planId,
                textureMethods(),
                "STARSECTOR_CORE",
                "contents/resources/java/fs.common_obf.jar",
                "10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-texture-loader-0.98a-rc8");
    }

    private static AdapterTarget linuxTextureTarget(String id, String planId) {
        return new AdapterTarget(
                id,
                "com/fs/graphics/TextureLoader",
                "9679ffab9f56e12183bce93dd6a459b6f6d26dfd7ec2230a67476d8cc20c0680",
                planId,
                linuxTextureMethods(),
                "STARSECTOR_CORE",
                "fs.common_obf.jar",
                "83f4367bfb55416f25614f5a5ccf2199de35cb5c1599e630f6cd54538843cf9c",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-texture-loader-0.98a-rc8");
    }

    private static List<AdapterTarget.RequiredMethod> textureMethods() {
        return List.of(
                new AdapterTarget.RequiredMethod(
                        "o00000",
                        "(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/Object;)Ljava/nio/ByteBuffer;"),
                new AdapterTarget.RequiredMethod(
                        "o00000", "(Ljava/lang/String;Ljava/awt/image/BufferedImage;)V"),
                new AdapterTarget.RequiredMethod(
                        "Ò00000",
                        "(Ljava/lang/String;Ljava/awt/image/BufferedImage;)Lcom/fs/graphics/Object;"),
                new AdapterTarget.RequiredMethod(
                        "Ô00000", "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;"),
                new AdapterTarget.RequiredMethod(
                        "o00000", "(Ljava/awt/image/BufferedImage;IIII)Lcom/fs/graphics/Object;"),
                new AdapterTarget.RequiredMethod(
                        "o00000", "(Ljava/nio/ByteBuffer;Ljava/lang/String;)V"),
                new AdapterTarget.RequiredMethod(
                        "Ò00000", "(Ljava/lang/String;)Ljava/nio/ByteBuffer;"),
                new AdapterTarget.RequiredMethod(
                        "o00000",
                        "(Lcom/fs/graphics/Object;Ljava/lang/String;IIIIZ)Lcom/fs/graphics/Object;"),
                new AdapterTarget.RequiredMethod(
                        "o00000", "(Ljava/lang/String;)Lcom/fs/graphics/Object;"));
    }

    private static List<AdapterTarget.RequiredMethod> linuxTextureMethods() {
        return List.of(
                new AdapterTarget.RequiredMethod(
                        "super",
                        "(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/Object;)Ljava/nio/ByteBuffer;"),
                new AdapterTarget.RequiredMethod(
                        "super", "(Ljava/lang/String;Ljava/awt/image/BufferedImage;)V"),
                new AdapterTarget.RequiredMethod(
                        "Ò00000",
                        "(Ljava/lang/String;Ljava/awt/image/BufferedImage;)Lcom/fs/graphics/Object;"),
                new AdapterTarget.RequiredMethod(
                        "String", "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;"),
                new AdapterTarget.RequiredMethod(
                        "super", "(Ljava/awt/image/BufferedImage;IIII)Lcom/fs/graphics/Object;"),
                new AdapterTarget.RequiredMethod(
                        "super", "(Ljava/nio/ByteBuffer;Ljava/lang/String;)V"),
                new AdapterTarget.RequiredMethod(
                        "Ò00000", "(Ljava/lang/String;)Ljava/nio/ByteBuffer;"),
                new AdapterTarget.RequiredMethod(
                        "super",
                        "(Lcom/fs/graphics/Object;Ljava/lang/String;IIIIZ)Lcom/fs/graphics/Object;"),
                new AdapterTarget.RequiredMethod(
                        "super", "(Ljava/lang/String;)Lcom/fs/graphics/Object;"));
    }

    private static List<AdapterTarget.RequiredMethod> windowsTextureMethods() {
        return List.of(
                new AdapterTarget.RequiredMethod(
                        "o00000",
                        "(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/Object;)Ljava/nio/ByteBuffer;"),
                new AdapterTarget.RequiredMethod(
                        "o00000", "(Ljava/lang/String;Ljava/awt/image/BufferedImage;)V"),
                new AdapterTarget.RequiredMethod(
                        "new",
                        "(Ljava/lang/String;Ljava/awt/image/BufferedImage;)Lcom/fs/graphics/Object;"),
                new AdapterTarget.RequiredMethod(
                        "Ô00000", "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;"),
                new AdapterTarget.RequiredMethod(
                        "o00000", "(Ljava/awt/image/BufferedImage;IIII)Lcom/fs/graphics/Object;"),
                new AdapterTarget.RequiredMethod(
                        "o00000", "(Ljava/nio/ByteBuffer;Ljava/lang/String;)V"),
                new AdapterTarget.RequiredMethod(
                        "new", "(Ljava/lang/String;)Ljava/nio/ByteBuffer;"),
                new AdapterTarget.RequiredMethod(
                        "o00000",
                        "(Lcom/fs/graphics/Object;Ljava/lang/String;IIIIZ)Lcom/fs/graphics/Object;"),
                new AdapterTarget.RequiredMethod(
                        "o00000", "(Ljava/lang/String;)Lcom/fs/graphics/Object;"));
    }

    AdapterTargetRegistry withTextureCompatibilityTarget() {
        return withTarget(textureCompatibilityTarget());
    }

    /**
     * Registered unconditionally with the texture targets, because the rewrite it installs is inert
     * until {@code preflight.campaign.entityIndex} is set. The gate requires all three reviewed
     * location, repository-list, and id-mutation seams; a partial installation remains inert.
     */
    AdapterTargetRegistry withCampaignEntityIndexTarget() {
        return withTarget(campaignEntityIndexTarget())
                .withTarget(campaignEntityRepositoryTarget())
                .withTarget(campaignEntityIdMutationTarget());
    }

    AdapterTargetRegistry withDeploymentIconCacheTarget() {
        return withTarget(deploymentIconCacheTarget());
    }

    AdapterTargetRegistry withCommodityEventModMemoTarget() {
        return withTarget(commodityEventModMemoTarget())
                .withTarget(mutableStatDirtyAccessorTarget());
    }

    AdapterTargetRegistry withCampaignEntityMaintenanceTargets() {
        return withTarget(campaignEntityScriptsTarget())
                .withTarget(campaignFleetViewSnapshotTarget())
                .withTarget(campaignMarketSnapshotTarget())
                .withTarget(campaignMemoryMaintenanceTarget())
                .withTarget(campaignPausedConditionSnapshotTarget())
                .withTarget(hyperspaceAutomatonNeighborTarget());
    }

    AdapterTargetRegistry withFleetAiProfilerTargets() {
        return withTarget(fleetAiProfilerLabelTarget()).withTarget(profilerToggleTarget());
    }

    AdapterTargetRegistry withSimOpponentSafetyTarget() {
        return withTarget(simOpponentSafetyTarget()).withTarget(simOpponentDialogProbeTarget());
    }

    AdapterTargetRegistry withStartupPhaseTarget() {
        AdapterTargetRegistry registry = withTarget(startupPhaseTarget())
                .withTarget(specStorePhaseTarget())
                .withTarget(weaponLoaderPhaseTarget())
                .withTarget(shipHullLoaderPhaseTarget())
                .withTarget(rulesLoaderPhaseTarget())
                .withTarget(ruleExpressionPhaseTarget());
        // The production AshLib target composes this diagnostic rewrite itself. Registering a
        // second exact target for the same class would leave one apparently unavailable because a
        // transformer returns after the first successful rewrite.
        if (forClass(AshLibVariantLookupPlan.REPOSITORY_CLASS).isEmpty()) {
            registry = registry.withTarget(startupAshRepoBreakdownTarget());
        }
        if (forClass(AshLibVariantLookupPlan.SHIP_JSON_CLASS).isEmpty()) {
            registry = registry.withTarget(startupAshRenderInfoBreakdownTarget());
        }
        return registry
                .withTarget(startupCodexBreakdownTarget())
                .withTarget(startupCampaignEngineBreakdownTarget())
                .withTarget(startupGraphicsBreakdownTarget())
                .withTarget(startupMagicLibBreakdownTarget())
                .withTarget(startupNexConfigBreakdownTarget())
                .withTarget(startupNexFactionConfigBreakdownTarget())
                .withTarget(mergedReadProbeTarget());
    }

    AdapterTargetRegistry withVariantJsonCacheTarget() {
        return withTarget(variantJsonCacheTarget())
                .withTarget(linuxVariantJsonCacheTarget())
                .withTarget(windowsVariantJsonCacheTarget());
    }

    AdapterTargetRegistry withSpecStoreQuoteNormalizationTarget() {
        return withTarget(specStoreQuoteNormalizationTarget())
                .withTarget(linuxSpecStoreQuoteNormalizationTarget())
                .withTarget(windowsSpecStoreQuoteNormalizationTarget());
    }

    AdapterTargetRegistry withWeaponJsonCacheTarget() {
        return withTarget(weaponJsonCacheTarget())
                .withTarget(linuxWeaponJsonCacheTarget())
                .withTarget(windowsWeaponJsonCacheTarget());
    }

    AdapterTargetRegistry withProjectileJsonCacheTarget() {
        return withTarget(projectileJsonCacheTarget())
                .withTarget(linuxProjectileJsonCacheTarget())
                .withTarget(windowsProjectileJsonCacheTarget());
    }

    AdapterTargetRegistry withHullJsonCacheTarget() {
        return withTarget(hullJsonCacheTarget())
                .withTarget(linuxHullJsonCacheTarget())
                .withTarget(windowsHullJsonCacheTarget());
    }

    AdapterTargetRegistry withRulesDuplicateIndexTarget() {
        return withTarget(rulesDuplicateIndexTarget())
                .withTarget(linuxRulesDuplicateIndexTarget())
                .withTarget(windowsRulesDuplicateIndexTarget());
    }

    AdapterTargetRegistry withRulesCsvCacheTarget() {
        return withTarget(rulesCsvCacheTarget())
                .withTarget(linuxRulesCsvCacheTarget())
                .withTarget(windowsRulesCsvCacheTarget());
    }

    AdapterTargetRegistry withRulesRegexCacheTarget() {
        return withTarget(rulesRegexCacheTarget())
                .withTarget(linuxRulesRegexCacheTarget())
                .withTarget(windowsRulesRegexCacheTarget());
    }

    AdapterTargetRegistry withRuleTokenCacheTarget() {
        return withTarget(ruleTokenCacheTarget())
                .withTarget(linuxRuleTokenCacheTarget())
                .withTarget(windowsRuleTokenCacheTarget());
    }

    AdapterTargetRegistry withRuleCommandClassCacheTarget() {
        return withTarget(ruleCommandClassLookupTarget())
                .withTarget(linuxRuleCommandClassLookupTarget())
                .withTarget(windowsRuleCommandClassLookupTarget())
                .withTarget(ruleCommandClassPublishTarget())
                .withTarget(linuxRuleCommandClassPublishTarget())
                .withTarget(windowsRuleCommandClassPublishTarget());
    }

    AdapterTargetRegistry withMergedReadCacheTarget() {
        return withTarget(mergedReadCacheTarget())
                .withTarget(linuxMergedReadCacheTarget())
                .withTarget(windowsMergedReadCacheTarget());
    }

    /**
     * The same class, pinned for the merged-read timing rather than the single-file memo.
     *
     * <p>Two targets on one class is deliberate. They gate on different things -- this one on the
     * startup phase probe, the memo on its own flag -- and only one of them can transform, because
     * the transformer returns on the first plan that produces bytes. Both plan branches therefore
     * chain the other's rewrite, so the pair composes whichever target the loop reaches first.
     */
    static AdapterTarget mergedReadProbeTarget() {
        return new AdapterTarget(
                "vanilla-loading-utils-0.98a-rc8-merged-reads",
                MergedReadProbePlan.TARGET_CLASS,
                "aa9f88ee76576894432503103de2979f297c01b399e528c096d1905f5a59f89d",
                StartupPhaseRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                MergedReadProbePlan.MERGED_METHOD, MergedReadProbePlan.CSV_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                MergedReadProbePlan.MERGED_METHOD, MergedReadProbePlan.JSON_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
    }

    /**
     * The same class again, pinned for the general merged-read cache.
     *
     * <p>Three targets now name {@code LoadingUtils}: the merged-read timing, the single-file memo,
     * and this. They gate on different things and only one of them transforms, because the
     * transformer returns on the first plan that produces bytes; every plan branch chains the
     * others, so the set composes whichever target the loop reaches first.
     */
    static AdapterTarget mergedReadCacheTarget() {
        return new AdapterTarget(
                "vanilla-loading-utils-0.98a-rc8-merged-read-cache",
                MergedReadCachePlan.TARGET_CLASS,
                "aa9f88ee76576894432503103de2979f297c01b399e528c096d1905f5a59f89d",
                MergedReadCacheRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                MergedReadCachePlan.MERGED_METHOD, MergedReadCachePlan.CSV_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                MergedReadCachePlan.MERGED_METHOD, MergedReadCachePlan.JSON_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-loading-utils-0.98a-rc8-merged-read-cache");
    }

    static AdapterTarget linuxMergedReadCacheTarget() {
        return linuxCoreTarget(
                "vanilla-loading-utils-linux-0.98a-rc8-merged-read-cache",
                MergedReadCachePlan.TARGET_CLASS,
                "b1737290343c69e71dfa3d3a28ddd7757f3bdc5a230f877043312f510ba85e2e",
                MergedReadCacheRuntime.PLAN_ID,
                mergedReadCacheTarget().requiredMethods(),
                "vanilla-loading-utils-0.98a-rc8-merged-read-cache");
    }

    static AdapterTarget windowsMergedReadCacheTarget() {
        return windowsCoreTarget(
                "vanilla-loading-utils-windows-0.98a-rc8-merged-read-cache",
                MergedReadCachePlan.TARGET_CLASS,
                "35581e89dabe9befac66ca1d3602db234033e38baef1036d2a196c8703e30b37",
                MergedReadCacheRuntime.PLAN_ID,
                mergedReadCacheTarget().requiredMethods(),
                "vanilla-loading-utils-0.98a-rc8-merged-read-cache");
    }

    /**
     * The single-file JSON loader behind {@code SettingsAPI.loadJSON}, in {@code starfarer_obf.jar}.
     *
     * <p>Every mod that reads game data arrives here, which is the whole point of pinning it: one
     * memo serves all of them rather than one mod each.
     */
    static AdapterTarget loadJsonMemoTarget() {
        return new AdapterTarget(
                "vanilla-loading-utils-0.98a-rc8-loadjson-memo",
                LoadJsonMemoPlan.TARGET_CLASS,
                "aa9f88ee76576894432503103de2979f297c01b399e528c096d1905f5a59f89d",
                LoadJsonMemoRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        LoadJsonMemoPlan.LOAD_METHOD, LoadJsonMemoPlan.LOAD_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app").withAlternativeGroup("vanilla-loading-utils-0.98a-rc8-loadjson-memo");
    }

    static AdapterTarget linuxLoadJsonMemoTarget() {
        return linuxCoreTarget(
                "vanilla-loading-utils-linux-0.98a-rc8-loadjson-memo",
                LoadJsonMemoPlan.TARGET_CLASS,
                "b1737290343c69e71dfa3d3a28ddd7757f3bdc5a230f877043312f510ba85e2e",
                LoadJsonMemoRuntime.PLAN_ID,
                loadJsonMemoTarget().requiredMethods(),
                "vanilla-loading-utils-0.98a-rc8-loadjson-memo");
    }

    static AdapterTarget windowsLoadJsonMemoTarget() {
        return windowsCoreTarget(
                "vanilla-loading-utils-windows-0.98a-rc8-loadjson-memo",
                LoadJsonMemoPlan.TARGET_CLASS,
                "35581e89dabe9befac66ca1d3602db234033e38baef1036d2a196c8703e30b37",
                LoadJsonMemoRuntime.PLAN_ID,
                loadJsonMemoTarget().requiredMethods(),
                "vanilla-loading-utils-0.98a-rc8-loadjson-memo");
    }

    AdapterTargetRegistry withLoadJsonMemoTarget() {
        return withTarget(loadJsonMemoTarget())
                .withTarget(linuxLoadJsonMemoTarget())
                .withTarget(windowsLoadJsonMemoTarget());
    }

    AdapterTargetRegistry withResourceProbeCacheTarget() {
        return withTarget(resourceProbeCacheTarget());
    }

    AdapterTargetRegistry withPreparedAudioTarget() {
        return withTarget(preparedAudioTarget());
    }

    AdapterTargetRegistry withGraphicsLibCompactReplayTarget() {
        return withTarget(graphicsLibCompactReplayTarget());
    }

    AdapterTargetRegistry withJaninoBytecodeCacheTarget() {
        // Linux has the same Janino class bytes but not the same runtime semantics: replaying a
        // complete cached map skips source-loader state that Starsector later needs for mission
        // resource discovery.  Exact warm launches then reject mission_text.txt files that are
        // physically present.  Keep the reviewed macOS target and fail closed on Linux.
        return withTarget(janinoBytecodeCacheTarget());
    }

    AdapterTargetRegistry withGraphicsLibInsigniaManagerCacheTarget() {
        return withTarget(graphicsLibInsigniaManagerCacheTarget());
    }

    AdapterTargetRegistry withFrameTimeTarget() {
        return withTarget(frameTimeTarget())
                .withTarget(linuxFrameTimeTarget())
                .withTarget(frameLimiterTimeTarget())
                .withTarget(windowsFrameLimiterTimeTarget())
                .withTarget(campaignFrameTimeStateTarget())
                .withTarget(linuxCampaignFrameTimeStateTarget())
                .withTarget(windowsCampaignFrameTimeStateTarget());
    }

    AdapterTargetRegistry withDynamicParticleGroupProbeTarget() {
        return withTarget(new AdapterTarget(
                "vanilla-dynamic-particle-group-render-probe-0.98a-rc8",
                DynamicParticleGroupRenderProbePlan.TARGET_CLASS,
                DynamicParticleGroupRenderProbePlan.ORIGINAL_SHA256,
                FrameTimeRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        DynamicParticleGroupRenderProbePlan.RENDER_METHOD,
                        DynamicParticleGroupRenderProbePlan.RENDER_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/fs.common_obf.jar",
                "10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app"));
    }

    AdapterTargetRegistry withGraphicsLibTessellateArrayTarget() {
        return withTarget(new AdapterTarget(
                "graphicslib-1.12.1-tessellate-array-replay",
                GraphicsLibTessellateArrayPlan.TARGET_CLASS,
                GraphicsLibTessellateArrayPlan.ORIGINAL_SHA256,
                FrameTimeRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        GraphicsLibTessellateArrayPlan.RENDER_METHOD,
                        GraphicsLibTessellateArrayPlan.RENDER_DESCRIPTOR)),
                "MOD",
                "graphics.jar",
                GraphicsLibTessellateArrayPlan.SOURCE_SHA256,
                "java/net/URLClassLoader",
                ""));
    }

    AdapterTargetRegistry withGlCommandCountTargets() {
        AdapterTargetRegistry registry = this;
        for (GlCommandCountPlan.Target target : GlCommandCountPlan.targets()) {
            registry = registry.withTarget(new AdapterTarget(
                    "lwjgl-2-opengl-command-count-" + target.idSuffix(),
                    target.internalName(),
                    target.sha256(),
                    GlCommandCountRuntime.PLAN_ID,
                    List.of(new AdapterTarget.RequiredMethod(
                            target.requiredMethod(), target.requiredDescriptor())),
                    "STARSECTOR_CORE",
                    GlCommandCountPlan.SOURCE_FILE,
                    GlCommandCountPlan.SOURCE_SHA256,
                    GlCommandCountPlan.LOADER,
                    GlCommandCountPlan.LOADER_NAME));
        }
        return registry;
    }

    AdapterTargetRegistry withCampaignCallTimeTargets() {
        AdapterTargetRegistry registry = this;
        for (CampaignCallTimePlan.Probe probe : CampaignCallTimePlan.probes()) {
            boolean nex = probe.className().startsWith("exerelin/");
            registry = registry.withTarget(new AdapterTarget(
                    "campaign-call-time-" + probe.id(),
                    probe.className(),
                    probe.sha256(),
                    CampaignCallTimeRuntime.PLAN_ID,
                    List.of(new AdapterTarget.RequiredMethod(
                            probe.method(), probe.descriptor())),
                    nex ? "MOD" : "STARSECTOR_CORE",
                    nex ? "exerelincore.jar" : "contents/resources/java/starfarer.api.jar",
                    nex
                            ? "3d3bb30c44eec9060a7777317af519dd695a1aa31d75f478036fc338870b3b71"
                            : "6ac6c78c6116946d487376426340d019938f986ceae1391ae1fa599e890e3185",
                    nex ? "java/net/URLClassLoader" : "jdk/internal/loader/ClassLoaders$AppClassLoader",
                    nex ? "" : "app"));
        }
        return registry;
    }

    AdapterTargetRegistry withCampaignEngineTimeTarget() {
        return withTarget(new AdapterTarget(
                "campaign-engine-call-time-0.98a-rc8",
                CampaignEngineTimePlan.TARGET_CLASS,
                CampaignEngineTimePlan.ORIGINAL_SHA256,
                CampaignEngineTimeRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CampaignEngineTimePlan.METHOD, CampaignEngineTimePlan.DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app"));
    }

    AdapterTargetRegistry withCampaignLocationEconomyTimeTargets() {
        AdapterTargetRegistry registry = this;
        registry = registry.withTarget(new AdapterTarget(
                "campaign-location-call-time-0.98a-rc8",
                CampaignLocationEconomyTimePlan.LOCATION_CLASS,
                CampaignLocationEconomyTimePlan.LOCATION_SHA256,
                CampaignLocationEconomyTimeRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                CampaignLocationEconomyTimePlan.LOCATION_ADVANCE,
                                CampaignLocationEconomyTimePlan.LOCATION_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                CampaignLocationEconomyTimePlan.LOCATION_PAUSED,
                                CampaignLocationEconomyTimePlan.LOCATION_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app"));
        return registry.withTarget(new AdapterTarget(
                "campaign-economy-call-time-0.98a-rc8",
                CampaignLocationEconomyTimePlan.ECONOMY_CLASS,
                CampaignLocationEconomyTimePlan.ECONOMY_SHA256,
                CampaignLocationEconomyTimeRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CampaignLocationEconomyTimePlan.ECONOMY_ADVANCE,
                        CampaignLocationEconomyTimePlan.ECONOMY_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app"));
    }

    AdapterTargetRegistry withCampaignMarketFleetTimeTargets() {
        AdapterTargetRegistry registry = withTarget(new AdapterTarget(
                "campaign-market-call-time-0.98a-rc8",
                CampaignMarketFleetTimePlan.MARKET_CLASS,
                CampaignMarketFleetTimePlan.MARKET_SHA256,
                CampaignMarketFleetTimeRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CampaignMarketFleetTimePlan.ADVANCE,
                        CampaignMarketFleetTimePlan.DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app"));
        return registry.withTarget(new AdapterTarget(
                "campaign-fleet-call-time-0.98a-rc8",
                CampaignMarketFleetTimePlan.FLEET_CLASS,
                CampaignMarketFleetTimePlan.FLEET_SHA256,
                CampaignMarketFleetTimeRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CampaignMarketFleetTimePlan.ADVANCE,
                        CampaignMarketFleetTimePlan.DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app"));
    }

    AdapterTargetRegistry withTacticalFleetAiTimeTarget() {
        return withTarget(new AdapterTarget(
                "vanilla-tactical-fleet-ai-time-0.98a-rc8",
                TacticalFleetAiTimePlan.TARGET_CLASS,
                TacticalFleetAiTimePlan.ORIGINAL_SHA256,
                TacticalFleetAiTimeRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        TacticalFleetAiTimePlan.METHOD,
                        TacticalFleetAiTimePlan.DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app"));
    }

    AdapterTargetRegistry withFleetInflationTimeTarget() {
        return withTarget(new AdapterTarget(
                "vanilla-default-fleet-inflater-time-0.98a-rc8",
                FleetInflationTimePlan.TARGET_CLASS,
                FleetInflationTimePlan.ORIGINAL_SHA256,
                FleetInflationTimeRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        FleetInflationTimePlan.METHOD,
                        FleetInflationTimePlan.DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer.api.jar",
                "6ac6c78c6116946d487376426340d019938f986ceae1391ae1fa599e890e3185",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app"));
    }

    AdapterTargetRegistry withCoreAutofitTimeTarget() {
        return withTarget(new AdapterTarget(
                "vanilla-core-autofit-time-0.98a-rc8",
                CoreAutofitTimePlan.TARGET_CLASS,
                CoreAutofitTimePlan.ORIGINAL_SHA256,
                CoreAutofitTimeRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        CoreAutofitTimePlan.METHOD,
                        CoreAutofitTimePlan.DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer.api.jar",
                "6ac6c78c6116946d487376426340d019938f986ceae1391ae1fa599e890e3185",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app"));
    }

    AdapterTargetRegistry withNexEconomyInfoTimeTarget() {
        return withTarget(new AdapterTarget(
                "nexerelin-0.12.2b-economy-info-time",
                NexEconomyInfoTimePlan.TARGET_CLASS,
                NexEconomyInfoTimePlan.ORIGINAL_SHA256,
                NexEconomyInfoTimeRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        NexEconomyInfoTimePlan.METHOD,
                        NexEconomyInfoTimePlan.DESCRIPTOR)),
                "MOD",
                "ExerelinCore.jar",
                NexEconomyInfoTimePlan.SOURCE_SHA256,
                "java/net/URLClassLoader",
                ""));
    }

    AdapterTargetRegistry withNexMarketListScopeTargets() {
        AdapterTarget nex = new AdapterTarget(
                "nexerelin-0.12.2b-economy-market-list-scope",
                NexMarketListScopePlan.NEX_CLASS,
                NexMarketListScopePlan.NEX_SHA256,
                NexMarketListScopeRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        NexMarketListScopePlan.NEX_METHOD,
                        NexMarketListScopePlan.NEX_DESCRIPTOR)),
                "MOD",
                "ExerelinCore.jar",
                NexMarketListScopePlan.NEX_SOURCE_SHA256,
                "java/net/URLClassLoader",
                "");
        AdapterTarget core = new AdapterTarget(
                "vanilla-commodity-market-data-nexerelin-scope-0.98a-rc8",
                NexMarketListScopePlan.CORE_CLASS,
                NexMarketListScopePlan.CORE_SHA256,
                NexMarketListScopeRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        NexMarketListScopePlan.CORE_METHOD,
                        NexMarketListScopePlan.CORE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/starfarer_obf.jar",
                NexMarketListScopePlan.CORE_SOURCE_SHA256,
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
        return withTarget(nex).withTarget(core);
    }

    AdapterTargetRegistry withFrameTimeStartupCompletionTarget() {
        return withTarget(frameTimeStartupCompletionTarget())
                .withTarget(linuxFrameTimeStartupCompletionTarget());
    }

    AdapterTargetRegistry withMainMenuInteractiveTarget() {
        return withTarget(mainMenuInteractiveTarget())
                .withTarget(linuxMainMenuInteractiveTarget())
                .withTarget(windowsMainMenuInteractiveTarget());
    }

    AdapterTargetRegistry withTextureTarget(TextureAdapterMode mode) {
        return withTextureTarget(
                mode,
                Boolean.getBoolean(TexturePrefetchBypassPlan.WINDOWS_PROBE_PROPERTY),
                Boolean.getBoolean(TexturePreparedPrefetchPlan.WINDOWS_PROBE_PROPERTY));
    }

    AdapterTargetRegistry withTextureTarget(
            TextureAdapterMode mode, boolean includeWindowsPrefetchBypassProbe) {
        return withTextureTarget(mode, includeWindowsPrefetchBypassProbe, false);
    }

    AdapterTargetRegistry withTextureTarget(
            TextureAdapterMode mode,
            boolean includeWindowsPrefetchBypassProbe,
            boolean includeWindowsPreparedPrefetchProbe) {
        // Both cache-backed modes read through the same manifest, so both want the prefetcher to
        // stop queueing what that manifest can serve.
        AdapterTargetRegistry registry = withTarget(mode == TextureAdapterMode.PREPARED_PIXELS
                ? texturePreparedPixelTarget()
                : textureCompatibilityTarget());
        if (mode == TextureAdapterMode.PREPARED_PIXELS) {
            registry = registry.withTarget(linuxTexturePreparedPixelTarget())
                    .withTarget(windowsTexturePreparedPixelTarget());
        }
        registry = registry
                .withTarget(texturePrefetchBypassTarget())
                .withTarget(linuxTexturePrefetchBypassTarget());
        // The exact Windows shape is reviewed, tested, and diagnosed. It remains absent by default;
        // this explicit discovery gate exists only to compare upload attribution against the safe
        // stock queue without turning a rejected llvmpipe experiment into product behavior.
        if (includeWindowsPreparedPrefetchProbe) {
            registry = registry.withTarget(windowsTexturePreparedPrefetchTarget());
        } else if (includeWindowsPrefetchBypassProbe) {
            registry = registry.withTarget(windowsTexturePrefetchBypassTarget());
        }
        registry = registry
                .withTarget(campaignEntityIndexTarget())
                .withTarget(campaignEntityRepositoryTarget())
                .withTarget(campaignEntityIdMutationTarget())
                .withTarget(radarRenderTarget())
                .withTarget(deploymentIconCacheTarget())
                .withTarget(commodityEventModMemoTarget())
                .withTarget(mutableStatDirtyAccessorTarget())
                .withTarget(campaignEntityScriptsTarget())
                .withTarget(campaignFleetViewSnapshotTarget())
                .withTarget(campaignMarketSnapshotTarget())
                .withTarget(campaignMemoryMaintenanceTarget())
                .withTarget(campaignPausedConditionSnapshotTarget())
                .withTarget(hyperspaceAutomatonNeighborTarget())
                .withTarget(fleetAiProfilerLabelTarget())
                .withTarget(profilerToggleTarget())
                .withTarget(simOpponentSafetyTarget())
                .withTarget(simOpponentDialogProbeTarget())
                .withTarget(resourcePriorityTarget())
                .withTarget(linuxResourcePriorityTarget())
                .withTarget(windowsResourcePriorityTarget())
                .withTarget(saveDescriptorCompatibilityTarget())
                .withTarget(industryDemandSupplySettingsTarget())
                .withTarget(industryDemandSupplyCodexTarget())
                .withTarget(codexLazyFleetMemberEntryTarget())
                .withTarget(indEvoRelaySyntheticMarketTarget())
                .withTarget(indEvoArtillerySyntheticMarketTarget())
                .withTarget(indEvoWonderSyntheticMarketTarget())
                .withTarget(sourceHintIsolationTarget())
                .withTarget(audioResourceFallbackTarget())
                .withTarget(ashLibVariantRepositoryTarget())
                .withTarget(ashLibVariantLookupTarget())
                .withTarget(ashLibShipJsonTarget())
                .withTarget(magicLibPaintjobTarget())
                .withTarget(magicLibPaintjobNotificationTarget())
                .withTarget(graphicsLibHotSettingsTarget())
                .withTarget(ratAbyssFactionFlagTarget())
                .withTarget(mnemonicSensorsEntityFilterTarget())
                .withTarget(mutableStatTempAdvanceTarget())
                .withTarget(contrailRenderScratchTarget())
                .withTarget(fontWrapAllocationTarget())
                .withTarget(lunaCampaignRendererSnapshotScriptTarget())
                .withTarget(lunaCampaignRendererSnapshotEntityTarget())
                .withTarget(lunaVersionCheckResponseDedupTarget())
                .withTarget(nexVersionCheckResponseDedupTarget())
                .withTarget(stelnetMarketUpdaterTarget())
                .withTarget(logisticsNotificationsFuelTarget())
                .withTarget(macMemoryWarningTarget())
                .withTarget(combatRuntimeIntegrityTarget())
                .withTarget(combatStateInputTarget())
                .withTarget(windowsCombatRuntimeIntegrityTarget())
                .withTarget(windowsCombatStateInputTarget())
                .withTarget(collisionQuerySetTarget());
        if (!AudioStreamSourceErrorRuntime.disabled()) {
            registry = registry.withTarget(audioStreamSourceErrorTarget());
        }
        if (AiTweaksSplitArcsPlan.enabled()) {
            registry = registry.withTarget(aiTweaksSplitArcsTarget());
        }
        if (AiTweaksAffineVectorPlan.enabled()) {
            for (AdapterTarget target : aiTweaksAffineVectorTargets()) {
                registry = registry.withTarget(target);
            }
        }
        if (CombatListenerRangeSnapshotPlan.enabled()) {
            registry = registry.withTarget(combatListenerRangeSnapshotTarget());
        }
        return registry;
    }

    private AdapterTargetRegistry withTarget(AdapterTarget builtIn) {
        for (AdapterTarget target : targets) {
            if (target.id().equals(builtIn.id())) {
                throw new IllegalArgumentException("Duplicate target ID: " + builtIn.id());
            }
        }
        List<AdapterTarget> combined = new ArrayList<>(targets.size() + 1);
        combined.addAll(targets);
        combined.add(builtIn);
        return new AdapterTargetRegistry(combined);
    }

    static AdapterTargetRegistry load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path absolute = path.toAbsolutePath().normalize();
        long bytes = Files.size(absolute);
        if (bytes > MAX_FILE_BYTES) {
            throw new IOException("Adapter target registry exceeds " + MAX_FILE_BYTES + " bytes: " + absolute);
        }

        List<String> lines = Files.readAllLines(absolute, StandardCharsets.UTF_8);
        List<AdapterTarget> targets = new ArrayList<>();
        Set<String> targetIds = new HashSet<>();
        Builder builder = null;
        for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
            String sourceLine = lines.get(lineNumber - 1);
            if (sourceLine.length() > MAX_LINE_CHARS) {
                throw syntax(absolute, lineNumber, "Line exceeds " + MAX_LINE_CHARS + " characters");
            }
            String raw = sourceLine.trim();
            if (raw.isEmpty() || raw.startsWith("#")) {
                continue;
            }
            int separator = raw.indexOf(' ');
            String key = separator < 0 ? raw : raw.substring(0, separator).trim();
            String value = separator < 0 ? "" : raw.substring(separator + 1).trim();
            switch (key) {
                case "target" -> {
                    if (builder != null) {
                        throw syntax(absolute, lineNumber, "Nested target block");
                    }
                    builder = new Builder(value);
                }
                case "class" -> requireBuilder(absolute, lineNumber, builder).className = value;
                case "sha256" -> requireBuilder(absolute, lineNumber, builder).sha256 = value;
                case "plan" -> requireBuilder(absolute, lineNumber, builder).planId = value;
                case "source-kind" -> requireBuilder(absolute, lineNumber, builder).sourceKind = value;
                case "source-suffix" -> requireBuilder(absolute, lineNumber, builder).sourceSuffix = value;
                case "source-sha256" -> requireBuilder(absolute, lineNumber, builder).sourceSha256 = value;
                case "loader-class" -> requireBuilder(absolute, lineNumber, builder).loaderClass = value;
                case "loader-name" -> requireBuilder(absolute, lineNumber, builder).loaderName = value;
                case "alternative-group" ->
                        requireBuilder(absolute, lineNumber, builder).alternativeGroup = value;
                case "method" -> {
                    Builder active = requireBuilder(absolute, lineNumber, builder);
                    if (active.methods.size() >= MAX_METHODS_PER_TARGET) {
                        throw syntax(absolute, lineNumber,
                                "Target exceeds " + MAX_METHODS_PER_TARGET + " required methods");
                    }
                    int split = value.indexOf(' ');
                    if (split <= 0 || split == value.length() - 1) {
                        throw syntax(absolute, lineNumber, "Expected: method <name> <descriptor>");
                    }
                    active.methods.add(new AdapterTarget.RequiredMethod(
                            value.substring(0, split).trim(),
                            value.substring(split + 1).trim()));
                }
                case "end" -> {
                    if (!value.isEmpty()) {
                        throw syntax(absolute, lineNumber, "end does not accept a value");
                    }
                    Builder active = requireBuilder(absolute, lineNumber, builder);
                    AdapterTarget target = active.build(absolute, lineNumber);
                    if (!targetIds.add(target.id())) {
                        throw syntax(absolute, lineNumber, "Duplicate target ID: " + target.id());
                    }
                    if (targets.size() >= MAX_TARGETS) {
                        throw syntax(absolute, lineNumber, "Registry exceeds " + MAX_TARGETS + " targets");
                    }
                    targets.add(target);
                    builder = null;
                }
                default -> throw syntax(absolute, lineNumber, "Unknown directive: " + key);
            }
        }
        if (builder != null) {
            throw new IOException("Unterminated target block in " + absolute);
        }
        return new AdapterTargetRegistry(targets);
    }

    List<AdapterTarget> targets() {
        return targets;
    }

    AdapterTargetRegistry withoutPlans(Set<String> planIds) {
        if (planIds.isEmpty()) return this;
        return new AdapterTargetRegistry(targets.stream()
                .filter(target -> !planIds.contains(target.planId()))
                .toList());
    }

    AdapterTargetRegistry forScope(AdapterPlanScope scope) {
        Objects.requireNonNull(scope, "scope");
        if (scope == AdapterPlanScope.FULL) return this;
        return new AdapterTargetRegistry(targets.stream()
                .filter(target -> scope.allows(target.planId()))
                .toList());
    }

    List<AdapterTarget> forClass(String internalName) {
        return byClass.getOrDefault(internalName, List.of());
    }

    private static Builder requireBuilder(Path path, int lineNumber, Builder builder) throws IOException {
        if (builder == null) {
            throw syntax(path, lineNumber, "Directive must appear inside a target block");
        }
        return builder;
    }

    private static IOException syntax(Path path, int lineNumber, String detail) {
        return new IOException(path.toAbsolutePath().normalize() + ":" + lineNumber + ": " + detail);
    }

    private static final class Builder {
        private final String id;
        private String className;
        private String sha256;
        private String planId = "none";
        private String sourceKind = "";
        private String sourceSuffix = "";
        private String sourceSha256 = "";
        private String loaderClass = "";
        private String loaderName = "";
        private String alternativeGroup = "";
        private final List<AdapterTarget.RequiredMethod> methods = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        private AdapterTarget build(Path path, int lineNumber) throws IOException {
            try {
                return new AdapterTarget(
                        id,
                        className,
                        sha256,
                        planId,
                        methods,
                        sourceKind,
                        sourceSuffix,
                        sourceSha256,
                        loaderClass,
                        loaderName,
                        alternativeGroup);
            } catch (RuntimeException error) {
                throw syntax(path, lineNumber, error.getMessage());
            }
        }
    }
}

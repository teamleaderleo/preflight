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
                "app");
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

    /** AI Tweaks 2.2.10 recomputes and repeatedly boxes fixed ranges during target selection. */
    static AdapterTarget aiTweaksEngagementRangeTarget() {
        return new AdapterTarget(
                "aitweaks-2.2.10-select-target-range-snapshot",
                AiTweaksEngagementRangePlan.TARGET_CLASS,
                AiTweaksEngagementRangePlan.ORIGINAL_SHA256,
                AiTweaksEngagementRangeRuntime.PLAN_ID,
                List.of(new AdapterTarget.RequiredMethod(
                        AiTweaksEngagementRangePlan.CONSTRUCTOR,
                        AiTweaksEngagementRangePlan.CONSTRUCTOR_DESCRIPTOR)),
                "MOD",
                "aitweaks-core.jar",
                "9f6179bcd2df2e3ce8cea2da79051c9f1be3c9b71712c6c28d7568b777ecf5b2",
                "com/genir/aitweaks/launcher/loading/CoreLoader",
                "");
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
                "app");
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
                "lwjgl-2-display-frame-time-probe",
                FrameTimePlan.TARGET_CLASS,
                FrameTimePlan.ORIGINAL_SHA256,
                FrameTimeRuntime.PLAN_ID,
                List.of(
                        new AdapterTarget.RequiredMethod(
                                FrameTimePlan.UPDATE_METHOD, FrameTimePlan.UPDATE_DESCRIPTOR),
                        new AdapterTarget.RequiredMethod(
                                FrameTimePlan.ACTIVE_METHOD, FrameTimePlan.ACTIVE_DESCRIPTOR)),
                "STARSECTOR_CORE",
                "contents/resources/java/lwjgl.jar",
                "527d509f60132e5b2653c7fc0f8cf299d6f698f4a8013342bef47705dc57ed3f",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
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
                "app");
    }

    /** Vanilla campaign loop used only to segment opt-in frame-time recordings. */
    static AdapterTarget campaignFrameTimeStateTarget() {
        return frameTimeStateTarget(
                "vanilla-campaign-frame-time-segment-0.98a-rc8",
                FrameTimeStatePlan.CAMPAIGN_CLASS,
                FrameTimeStatePlan.CAMPAIGN_SHA256);
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
                                EntityLookupPlan.OBJECTS_METHOD, EntityLookupPlan.OBJECTS_DESCRIPTOR)),
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
                List.of(new AdapterTarget.RequiredMethod(
                        SpecStorePhasePlan.INIT_METHOD, SpecStorePhasePlan.INIT_DESCRIPTOR)),
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
                "app");
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
                "app");
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
                "app");
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
                "app");
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
                "app");
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
                "app");
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
                "app");
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
                "app");
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
                "app");
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
                "app");
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
                .withTarget(campaignMemoryMaintenanceTarget());
    }

    AdapterTargetRegistry withFleetAiProfilerTargets() {
        return withTarget(fleetAiProfilerLabelTarget()).withTarget(profilerToggleTarget());
    }

    AdapterTargetRegistry withSimOpponentSafetyTarget() {
        return withTarget(simOpponentSafetyTarget()).withTarget(simOpponentDialogProbeTarget());
    }

    AdapterTargetRegistry withStartupPhaseTarget() {
        return withTarget(startupPhaseTarget())
                .withTarget(specStorePhaseTarget())
                .withTarget(weaponLoaderPhaseTarget())
                .withTarget(shipHullLoaderPhaseTarget())
                .withTarget(rulesLoaderPhaseTarget())
                .withTarget(ruleExpressionPhaseTarget())
                .withTarget(mergedReadProbeTarget());
    }

    AdapterTargetRegistry withVariantJsonCacheTarget() {
        return withTarget(variantJsonCacheTarget());
    }

    AdapterTargetRegistry withWeaponJsonCacheTarget() {
        return withTarget(weaponJsonCacheTarget());
    }

    AdapterTargetRegistry withProjectileJsonCacheTarget() {
        return withTarget(projectileJsonCacheTarget());
    }

    AdapterTargetRegistry withHullJsonCacheTarget() {
        return withTarget(hullJsonCacheTarget());
    }

    AdapterTargetRegistry withRulesDuplicateIndexTarget() {
        return withTarget(rulesDuplicateIndexTarget());
    }

    AdapterTargetRegistry withRulesCsvCacheTarget() {
        return withTarget(rulesCsvCacheTarget());
    }

    AdapterTargetRegistry withRuleTokenCacheTarget() {
        return withTarget(ruleTokenCacheTarget());
    }

    AdapterTargetRegistry withRuleCommandClassCacheTarget() {
        return withTarget(ruleCommandClassLookupTarget())
                .withTarget(ruleCommandClassPublishTarget());
    }

    AdapterTargetRegistry withMergedReadCacheTarget() {
        return withTarget(mergedReadCacheTarget());
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
                "app");
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
                "app");
    }

    AdapterTargetRegistry withLoadJsonMemoTarget() {
        return withTarget(loadJsonMemoTarget());
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
        return withTarget(janinoBytecodeCacheTarget());
    }

    AdapterTargetRegistry withGraphicsLibInsigniaManagerCacheTarget() {
        return withTarget(graphicsLibInsigniaManagerCacheTarget());
    }

    AdapterTargetRegistry withFrameTimeTarget() {
        return withTarget(frameTimeTarget())
                .withTarget(campaignFrameTimeStateTarget());
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

    AdapterTargetRegistry withFrameTimeStartupCompletionTarget() {
        return withTarget(frameTimeStartupCompletionTarget());
    }

    AdapterTargetRegistry withTextureTarget(TextureAdapterMode mode) {
        // Both cache-backed modes read through the same manifest, so both want the prefetcher to
        // stop queueing what that manifest can serve.
        AdapterTargetRegistry registry = withTarget(mode == TextureAdapterMode.PREPARED_PIXELS
                ? texturePreparedPixelTarget()
                : textureCompatibilityTarget())
                .withTarget(texturePrefetchBypassTarget())
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
                .withTarget(fleetAiProfilerLabelTarget())
                .withTarget(profilerToggleTarget())
                .withTarget(simOpponentSafetyTarget())
                .withTarget(simOpponentDialogProbeTarget())
                .withTarget(sourceHintIsolationTarget())
                .withTarget(audioResourceFallbackTarget())
                .withTarget(aiTweaksEngagementRangeTarget())
                .withTarget(magicLibPaintjobTarget())
                .withTarget(magicLibPaintjobNotificationTarget())
                .withTarget(graphicsLibHotSettingsTarget())
                .withTarget(stelnetMarketUpdaterTarget())
                .withTarget(macMemoryWarningTarget())
                .withTarget(combatRuntimeIntegrityTarget());
        if (!AudioStreamSourceErrorRuntime.disabled()) {
            registry = registry.withTarget(audioStreamSourceErrorTarget());
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
                        loaderName);
            } catch (RuntimeException error) {
                throw syntax(path, lineNumber, error.getMessage());
            }
        }
    }
}

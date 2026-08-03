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
     * until {@code preflight.campaign.entityIndex} is set. Weaving it costs one wrapper on one
     * method; not weaving it means the property can never take effect.
     */
    AdapterTargetRegistry withCampaignEntityIndexTarget() {
        return withTarget(campaignEntityIndexTarget());
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

    AdapterTargetRegistry withTextureTarget(TextureAdapterMode mode) {
        // Both cache-backed modes read through the same manifest, so both want the prefetcher to
        // stop queueing what that manifest can serve.
        return withTarget(mode == TextureAdapterMode.PREPARED_PIXELS
                ? texturePreparedPixelTarget()
                : textureCompatibilityTarget())
                .withTarget(texturePrefetchBypassTarget())
                .withTarget(campaignEntityIndexTarget());
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

package dev.starsector.preflight.agent;

/** Registry for manually reviewed target-specific bytecode rewrites. */
final class AdapterTransformationRegistry {
    private AdapterTransformationRegistry() {
    }

    static byte[] transform(AdapterTarget target, ClassSignature signature, byte[] originalBytes) {
        if (TextureCompatibilityRuntime.PLAN_ID.equals(target.planId())) {
            return TextureCompatibilityRuntime.ready()
                    ? TextureCompatibilityPlan.transform(signature, originalBytes)
                    : null;
        }
        if (TexturePreparedPixelRuntime.PLAN_ID.equals(target.planId())) {
            if (!TexturePreparedPixelRuntime.ready()) {
                return null;
            }
            return withFoldBypass(TexturePreparedPixelPlan.transform(signature, originalBytes));
        }
        // Gated on the compatibility runtime because the predicate it installs reads that manifest.
        // Without a ready cache the bypass would drop prefetches it cannot replace.
        if (TexturePrefetchBypassPlan.PLAN_ID.equals(target.planId())) {
            return TextureCompatibilityRuntime.ready()
                    ? TexturePrefetchBypassPlan.transform(signature, originalBytes)
                    : null;
        }
        // No built-in target declares this plan yet. Adding one means pinning a reviewed class and
        // jar digest for a specific game build, which is the step every other target went through,
        // and it is deliberately not done from a reading of one installation.
        if (TexturePaddingRuntime.PLAN_ID.equals(target.planId())) {
            return TexturePaddingRuntime.ready()
                    ? TexturePaddingPlan.transform(signature, originalBytes)
                    : null;
        }
        // Ungated: the wrapper it installs delegates to the original until
        // preflight.campaign.entityIndex says otherwise, so there is nothing to be ready for.
        if (EntityLookupRuntime.PLAN_ID.equals(target.planId())) {
            byte[] location = EntityLookupPlan.transform(signature, originalBytes);
            if (location != null) {
                return location;
            }
            byte[] repository = EntityRepositoryListPlan.transform(signature, originalBytes);
            return repository != null
                    ? repository : EntityIdMutationPlan.transform(signature, originalBytes);
        }
        // Like the campaign index, this wrapper is inert until its system property is enabled.
        if (DeploymentIconCacheRuntime.PLAN_ID.equals(target.planId())) {
            return DeploymentIconCachePlan.transform(signature, originalBytes);
        }
        if (RadarRenderRuntime.PLAN_ID.equals(target.planId())) {
            return RadarRenderPlan.transform(signature, originalBytes);
        }
        // Inert until --campaign-entity-index/--fast enables its runtime property. The exact
        // wrapper retains and delegates to the reviewed vanilla method on every cache miss.
        if (CommodityEventModMemoRuntime.PLAN_ID.equals(target.planId())) {
            byte[] dirtyAccessor = MutableStatDirtyAccessorPlan.transform(signature, originalBytes);
            return dirtyAccessor != null
                    ? dirtyAccessor
                    : CommodityEventModMemoPlan.transform(signature, originalBytes);
        }
        // Always-on inside adapter mode. The exact refit UI target returns the shipped list unless
        // Starsector's own variant registry proves that one of its merged CSV ids is invalid.
        if (SimOpponentSafetyRuntime.PLAN_ID.equals(target.planId())) {
            byte[] safety = SimOpponentSafetyPlan.transform(signature, originalBytes);
            return safety != null
                    ? safety : SimOpponentDialogProbePlan.transform(signature, originalBytes);
        }
        if (StartupPhaseRuntime.PLAN_ID.equals(target.planId())) {
            // LoadingUtils is reached by this plan, by the merged-read cache's and by the loadJSON
            // memo's, and only one target per class ever transforms, so every branch composes all
            // three.
            byte[] loadingUtils = loadingUtilsPlans(signature, originalBytes);
            if (loadingUtils != null) {
                return loadingUtils;
            }
            byte[] startupPhases = StartupPhasePlan.transform(signature, originalBytes);
            if (startupPhases != null) {
                return startupPhases;
            }
            byte[] specStore = SpecStorePhasePlan.transform(signature, originalBytes);
            if (specStore == null) {
                byte[] weaponPhases = WeaponLoaderPhasePlan.transform(signature, originalBytes);
                if (weaponPhases == null) {
                    byte[] hullPhases = ShipHullLoaderPhasePlan.transform(signature, originalBytes);
                    if (hullPhases == null) {
                        byte[] rulesPhases = RulesLoaderPhasePlan.transform(signature, originalBytes);
                        if (rulesPhases == null) {
                            // A different class in the same plan: the expression constructor the
                            // rules loader calls 62,340 times. The token memo is chained after the
                            // attribution so the probe still sees a tokenize call to wrap.
                            byte[] expressionPhases =
                                    RuleExpressionPhasePlan.transform(signature, originalBytes);
                            byte[] memoised = ruleTokenCache(
                                    expressionPhases == null ? originalBytes : expressionPhases,
                                    expressionPhases);
                            byte[] shortcut = ruleCommandClassLookup(
                                    memoised == null ? originalBytes : memoised);
                            return shortcut == null ? memoised : shortcut;
                        }
                        byte[] optimized = rulesOptimizations(rulesPhases);
                        byte[] rules = optimized == null ? rulesPhases : optimized;
                        byte[] published = ruleCommandClassPublish(rules);
                        return published == null ? rules : published;
                    }
                    if (!HullJsonCacheRuntime.ready()) {
                        return hullPhases;
                    }
                    try {
                        byte[] cached = HullJsonCachePlan.transform(
                                ClassSignature.parse(hullPhases), hullPhases);
                        return cached == null ? hullPhases : cached;
                    } catch (java.io.IOException ignored) {
                        return hullPhases;
                    }
                }
                try {
                    byte[] projectilePhases = ProjectileLoaderPhasePlan.transform(
                            ClassSignature.parse(weaponPhases), weaponPhases);
                    byte[] attributed = projectilePhases == null ? weaponPhases : projectilePhases;
                    byte[] cached = weaponJsonCaches(attributed);
                    return cached == null ? attributed : cached;
                } catch (java.io.IOException ignored) {
                    return weaponPhases;
                }
            }
            try {
                byte[] variantPhases = VariantLoaderPhasePlan.transform(
                        ClassSignature.parse(specStore), specStore);
                byte[] attributed = variantPhases == null ? specStore : variantPhases;
                if (!VariantJsonCacheRuntime.ready()) {
                    return attributed;
                }
                byte[] cached = VariantJsonCachePlan.transform(
                        ClassSignature.parse(attributed), attributed);
                return cached == null ? attributed : cached;
            } catch (java.io.IOException ignored) {
                return specStore;
            }
        }
        if (VariantJsonCacheRuntime.PLAN_ID.equals(target.planId())) {
            return VariantJsonCacheRuntime.ready()
                    ? VariantJsonCachePlan.transform(signature, originalBytes)
                    : null;
        }
        if (WeaponJsonCacheRuntime.PLAN_ID.equals(target.planId())
                || ProjectileJsonCacheRuntime.PLAN_ID.equals(target.planId())) {
            return weaponJsonCaches(originalBytes);
        }
        if (HullJsonCacheRuntime.PLAN_ID.equals(target.planId())) {
            return HullJsonCacheRuntime.ready()
                    ? HullJsonCachePlan.transform(signature, originalBytes)
                    : null;
        }
        if (RulesDuplicateIndexRuntime.PLAN_ID.equals(target.planId())
                || RulesCsvCacheRuntime.PLAN_ID.equals(target.planId())) {
            return rulesOptimizations(originalBytes);
        }
        if (LoadJsonMemoRuntime.PLAN_ID.equals(target.planId())
                || MergedReadCacheRuntime.PLAN_ID.equals(target.planId())) {
            return loadingUtilsPlans(signature, originalBytes);
        }
        if (SourceHintIsolationRuntime.PLAN_ID.equals(target.planId())
                || ResourceProbeRuntime.PLAN_ID.equals(target.planId())) {
            return resourceResolverPlans(signature, originalBytes);
        }
        if (PreparedAudioRuntime.PLAN_ID.equals(target.planId())) {
            return PreparedAudioRuntime.ready()
                    ? PreparedAudioPlan.transform(signature, originalBytes)
                    : null;
        }
        if (AudioStreamSourceErrorRuntime.PLAN_ID.equals(target.planId())) {
            return AudioStreamSourceErrorPlan.transform(signature, originalBytes);
        }
        if (AiTweaksEngagementRangeRuntime.PLAN_ID.equals(target.planId())) {
            return AiTweaksEngagementRangePlan.transform(signature, originalBytes);
        }
        if (GraphicsLibCompactReplayPlan.PLAN_ID.equals(target.planId())) {
            return GraphicsLibCompactReplayPlan.ready()
                    ? GraphicsLibCompactReplayPlan.transform(signature)
                    : null;
        }
        if (JaninoBytecodeCacheRuntime.PLAN_ID.equals(target.planId())) {
            return JaninoBytecodeCacheRuntime.ready()
                    ? JaninoBytecodeCachePlan.transform(signature, originalBytes)
                    : null;
        }
        if (GraphicsLibInsigniaManagerCacheRuntime.PLAN_ID.equals(target.planId())) {
            return GraphicsLibInsigniaManagerCacheRuntime.ready()
                    ? GraphicsLibInsigniaManagerCachePlan.transform(signature, originalBytes)
                    : null;
        }
        if (GraphicsLibHotSettingsRuntime.PLAN_ID.equals(target.planId())) {
            return GraphicsLibHotSettingsPlan.transform(signature, originalBytes);
        }
        if (MagicLibPaintjobRuntime.PLAN_ID.equals(target.planId())) {
            return MagicLibPaintjobPlan.transform(signature, originalBytes);
        }
        if (MagicLibPaintjobNotificationRuntime.PLAN_ID.equals(target.planId())) {
            return MagicLibPaintjobNotificationPlan.transform(signature, originalBytes);
        }
        if (StelnetMarketUpdaterRuntime.PLAN_ID.equals(target.planId())) {
            return StelnetMarketUpdaterPlan.transform(signature, originalBytes);
        }
        if (MacMemoryWarningRuntime.PLAN_ID.equals(target.planId())) {
            return MacMemoryWarningPlan.transform(signature, originalBytes);
        }
        if (FrameTimeRuntime.PLAN_ID.equals(target.planId())) {
            return FrameTimePlan.transform(signature, originalBytes);
        }
        if (CampaignCallTimeRuntime.PLAN_ID.equals(target.planId())) {
            return CampaignCallTimePlan.transform(signature, originalBytes);
        }
        if (FrameTimeStatePlan.PLAN_ID.equals(target.planId())) {
            return FrameTimeStatePlan.transform(signature, originalBytes);
        }
        if (FrameTimeStartupCompletionPlan.PLAN_ID.equals(target.planId())) {
            return FrameTimeStartupCompletionPlan.transform(signature, originalBytes);
        }
        if (CombatRuntimeIntegrityRuntime.PLAN_ID.equals(target.planId())) {
            return CombatRuntimeIntegrityPlan.transform(signature, originalBytes);
        }
        // The memo can install on its own, without the attribution probe in front of it.
        if (RuleTokenCacheRuntime.PLAN_ID.equals(target.planId())) {
            return ruleTokenCache(originalBytes, null);
        }
        // Two classes share this plan: the expression class carries the shortcut, the rules loader
        // carries the publish. Only one of them can match any given signature.
        if (RuleCommandClassCacheRuntime.PLAN_ID.equals(target.planId())) {
            if (!RuleCommandClassCacheRuntime.ready()) {
                return null;
            }
            byte[] shortcut = RuleCommandClassCachePlan.transform(signature, originalBytes);
            return shortcut != null
                    ? shortcut
                    : RuleCommandClassCachePlan.transformLoader(signature, originalBytes);
        }
        return null;
    }

    /**
     * Composes the three independent rewrites that share {@code LoadingUtils}.
     *
     * <p>The cache goes first because it and the probe weave the same pair of merged readers and
     * only one of them can: each declines a class already carrying the other's renamed methods.
     * Serving a launch is worth more than timing one, and the cache reports the same per-path timing
     * the probe does, so going first costs the measurement nothing. The single-file memo touches a
     * different method and composes with whichever of the two installed.
     *
     * <p>Returning null means no rewrite applied, which is how the caller tells "this class is not
     * mine" from "nothing left to add".
     */
    private static byte[] loadingUtilsPlans(ClassSignature signature, byte[] originalBytes) {
        byte[] current = originalBytes;
        ClassSignature currentSignature = signature;
        boolean changed = false;
        try {
            if (MergedReadCacheRuntime.ready()) {
                byte[] cached = MergedReadCachePlan.transform(currentSignature, current);
                if (cached != null) {
                    current = cached;
                    currentSignature = ClassSignature.parse(current);
                    changed = true;
                }
            }
            if (StartupPhaseRuntime.mergedReadProbeEnabled()) {
                byte[] probed = MergedReadProbePlan.transform(currentSignature, current);
                if (probed != null) {
                    current = probed;
                    currentSignature = ClassSignature.parse(current);
                    changed = true;
                }
            }
            if (LoadJsonMemoRuntime.ready()) {
                byte[] memoised = LoadJsonMemoPlan.transform(currentSignature, current);
                if (memoised != null) {
                    current = memoised;
                    changed = true;
                }
            }
            return changed ? current : null;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Whatever already applied is valid bytecode; losing the rest is the safe direction.
            return changed ? current : null;
        }
    }

    /** Composes the two independent method-pair rewrites that share WeaponSpecLoader. */
    private static byte[] weaponJsonCaches(byte[] originalBytes) {
        byte[] current = originalBytes;
        boolean changed = false;
        try {
            if (WeaponJsonCacheRuntime.ready()) {
                byte[] weapon = WeaponJsonCachePlan.transform(ClassSignature.parse(current), current);
                if (weapon != null) {
                    current = weapon;
                    changed = true;
                }
            }
            if (ProjectileJsonCacheRuntime.ready()) {
                byte[] projectile = ProjectileJsonCachePlan.transform(ClassSignature.parse(current), current);
                if (projectile != null) {
                    current = projectile;
                    changed = true;
                }
            }
            return changed ? current : null;
        } catch (java.io.IOException ignored) {
            return changed ? current : null;
        }
    }

    /** Composes the merged-CSV cache and duplicate index that share the rules loader. */
    private static byte[] rulesOptimizations(byte[] originalBytes) {
        byte[] current = originalBytes;
        boolean changed = false;
        try {
            if (RulesCsvCacheRuntime.ready()) {
                byte[] cached = RulesCsvCachePlan.transform(ClassSignature.parse(current), current);
                if (cached != null) {
                    current = cached;
                    changed = true;
                }
            }
            if (RulesDuplicateIndexRuntime.ready()) {
                byte[] indexed = RulesDuplicateIndexPlan.transform(ClassSignature.parse(current), current);
                if (indexed != null) {
                    current = indexed;
                    changed = true;
                }
            }
            return changed ? current : null;
        } catch (java.io.IOException ignored) {
            return changed ? current : null;
        }
    }

    /**
     * Chains the tokenizer memo onto the expression constructor.
     *
     * @param current the bytes to rewrite, already carrying the attribution probe when there is one
     * @param attributed the attributed bytes, or null when only the memo applies -- returning null
     *     for that case keeps "no plan matched this class" distinguishable from "nothing to add"
     */
    private static byte[] ruleTokenCache(byte[] current, byte[] attributed) {
        if (!RuleTokenCacheRuntime.ready()) {
            return attributed;
        }
        try {
            byte[] cached = RuleTokenCachePlan.transform(ClassSignature.parse(current), current);
            return cached == null ? attributed : cached;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // The attribution rewrite is already valid; losing the memo is the safe direction.
            return attributed;
        }
    }

    /** Chains the command-name shortcut onto an expression class that may already be rewritten. */
    private static byte[] ruleCommandClassLookup(byte[] current) {
        if (!RuleCommandClassCacheRuntime.ready()) {
            return null;
        }
        try {
            return RuleCommandClassCachePlan.transform(ClassSignature.parse(current), current);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Losing the shortcut leaves vanilla's walk, which is the safe direction.
            return null;
        }
    }

    /** Chains the learning run's publish onto a rules loader that may already be rewritten. */
    private static byte[] ruleCommandClassPublish(byte[] current) {
        if (!RuleCommandClassCacheRuntime.ready()) {
            return null;
        }
        try {
            return RuleCommandClassCachePlan.transformLoader(ClassSignature.parse(current), current);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Without the publish the run simply learns nothing durable.
            return null;
        }
    }

    /**
     * Weaves the padded-dimension fold bypass into a loader that has already been rewritten.
     *
     * <p>Both plans rewrite {@code com/fs/graphics/TextureLoader} and the dispatch above chooses one
     * plan per class, which is why the fold bypass had no way to reach an installed loader and why
     * {@code --prepared-unpadded} behaved exactly like {@code --prepared-npot}
     * ([evidence](../../../../../../../docs/evidence/2026-07-31-half-an-invariant-kills-the-launcher.md)).
     * Chaining is safe because the two touch different overloads: the fold is {@code o00000(I)I},
     * while the prepared-pixel plan rewrites {@code Ô00000(String)BufferedImage} and the {@code o00000}
     * overloads that convert and clean up. {@code TexturePaddingPlan} matches on name <em>and</em>
     * descriptor and refuses anything but a unique match, so it cannot pick up one of the others.
     *
     * <p>This only makes the bypass <em>reachable</em>. It does not turn it on:
     * {@link TexturePaddingRuntime#enabled()} still requires {@code preflight.padding.unpadded},
     * which is off unless asked for. Failing to weave leaves the original bytes' allocation padded,
     * which is the safe direction, so a null from the padding plan keeps the primary rewrite rather
     * than discarding it.
     */
    static byte[] withFoldBypass(byte[] rewritten) {
        if (rewritten == null) {
            return null;
        }
        try {
            ClassSignature rewrittenSignature = ClassSignature.parse(rewritten);
            byte[] folded = TexturePaddingPlan.transform(rewrittenSignature, rewritten);
            return folded == null ? rewritten : folded;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            // The primary rewrite is already valid and the gate stays shut, so the run continues
            // with padded allocations rather than losing the prepared-pixel path over this.
            return rewritten;
        }
    }

    static boolean hasPlan(String planId) {
        if (TextureCompatibilityRuntime.PLAN_ID.equals(planId)) {
            return TextureCompatibilityRuntime.ready();
        }
        if (TexturePreparedPixelRuntime.PLAN_ID.equals(planId)) {
            return TexturePreparedPixelRuntime.ready();
        }
        if (TexturePrefetchBypassPlan.PLAN_ID.equals(planId)) {
            return TextureCompatibilityRuntime.ready();
        }
        if (TexturePaddingRuntime.PLAN_ID.equals(planId)) {
            return TexturePaddingRuntime.ready();
        }
        if (EntityLookupRuntime.PLAN_ID.equals(planId)) {
            return EntityLookupRuntime.ready();
        }
        if (StartupPhaseRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (VariantJsonCacheRuntime.PLAN_ID.equals(planId)) {
            return VariantJsonCacheRuntime.ready();
        }
        if (WeaponJsonCacheRuntime.PLAN_ID.equals(planId)) {
            return WeaponJsonCacheRuntime.ready();
        }
        if (ProjectileJsonCacheRuntime.PLAN_ID.equals(planId)) {
            return ProjectileJsonCacheRuntime.ready();
        }
        if (HullJsonCacheRuntime.PLAN_ID.equals(planId)) {
            return HullJsonCacheRuntime.ready();
        }
        if (RulesDuplicateIndexRuntime.PLAN_ID.equals(planId)) {
            return RulesDuplicateIndexRuntime.ready();
        }
        if (RulesCsvCacheRuntime.PLAN_ID.equals(planId)) {
            return RulesCsvCacheRuntime.ready();
        }
        if (RuleTokenCacheRuntime.PLAN_ID.equals(planId)) {
            return RuleTokenCacheRuntime.ready();
        }
        if (RuleCommandClassCacheRuntime.PLAN_ID.equals(planId)) {
            return RuleCommandClassCacheRuntime.ready();
        }
        if (SourceHintIsolationRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (ResourceProbeRuntime.PLAN_ID.equals(planId)) {
            return ResourceProbeRuntime.ready();
        }
        if (PreparedAudioRuntime.PLAN_ID.equals(planId)) {
            return PreparedAudioRuntime.ready();
        }
        if (AudioStreamSourceErrorRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (AiTweaksEngagementRangeRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (LoadJsonMemoRuntime.PLAN_ID.equals(planId)) {
            return LoadJsonMemoRuntime.ready();
        }
        if (MergedReadCacheRuntime.PLAN_ID.equals(planId)) {
            return MergedReadCacheRuntime.ready();
        }
        if (GraphicsLibCompactReplayPlan.PLAN_ID.equals(planId)) {
            return GraphicsLibCompactReplayPlan.ready();
        }
        if (JaninoBytecodeCacheRuntime.PLAN_ID.equals(planId)) {
            return JaninoBytecodeCacheRuntime.ready();
        }
        if (GraphicsLibInsigniaManagerCacheRuntime.PLAN_ID.equals(planId)) {
            return GraphicsLibInsigniaManagerCacheRuntime.ready();
        }
        if (GraphicsLibHotSettingsRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (MagicLibPaintjobRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (MagicLibPaintjobNotificationRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (StelnetMarketUpdaterRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (MacMemoryWarningRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (FrameTimeRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (CampaignCallTimeRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (FrameTimeStatePlan.PLAN_ID.equals(planId)) {
            return true;
        }
        if (FrameTimeStartupCompletionPlan.PLAN_ID.equals(planId)) {
            return true;
        }
        if (CombatRuntimeIntegrityRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (DeploymentIconCacheRuntime.PLAN_ID.equals(planId)) {
            return DeploymentIconCacheRuntime.ready();
        }
        if (RadarRenderRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (CommodityEventModMemoRuntime.PLAN_ID.equals(planId)) {
            return CommodityEventModMemoRuntime.ready();
        }
        if (SimOpponentSafetyRuntime.PLAN_ID.equals(planId)) {
            return SimOpponentSafetyRuntime.ready();
        }
        return false;
    }

    /** Composes the always-on race fix with the optional probe cache on their shared resolver. */
    private static byte[] resourceResolverPlans(ClassSignature signature, byte[] originalBytes) {
        byte[] current = SourceHintIsolationPlan.transform(signature, originalBytes);
        boolean changed = current != null;
        if (!changed) {
            current = originalBytes;
        }
        if (ResourceProbeRuntime.ready()) {
            try {
                byte[] probed = ResourceProbePlan.transform(ClassSignature.parse(current), current);
                if (probed != null) {
                    current = probed;
                    changed = true;
                }
            } catch (java.io.IOException ignored) {
                // The source-hint rewrite, if installed, remains valid on its own.
            }
        }
        return changed ? current : null;
    }

    static boolean anyPlanCompiled() {
        return true;
    }
}

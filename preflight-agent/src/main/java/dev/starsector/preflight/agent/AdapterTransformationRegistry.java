package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Registry for manually reviewed target-specific bytecode rewrites. */
final class AdapterTransformationRegistry {
    private AdapterTransformationRegistry() {
    }

    static byte[] transform(AdapterTarget target, ClassSignature signature, byte[] originalBytes) {
        if (!AdapterPlanControl.allows(target.planId())) {
            return null;
        }
        if (FastRenderingPreparedTextureRuntime.PLAN_ID.equals(target.planId())) {
            return FastRenderingPreparedTextureRuntime.ready()
                    ? FastRenderingPreparedTexturePlan.transform(signature, originalBytes)
                    : null;
        }
        if (TextureCompatibilityRuntime.PLAN_ID.equals(target.planId())) {
            return TextureCompatibilityRuntime.ready()
                    ? TextureCompatibilityPlan.transform(signature, originalBytes)
                    : null;
        }
        if (TexturePreparedPixelRuntime.PLAN_ID.equals(target.planId())) {
            if (!TexturePreparedPixelRuntime.ready()) {
                return null;
            }
            byte[] prepared = withFoldBypass(
                    TexturePreparedPixelPlan.transform(signature, originalBytes));
            if (prepared != null && TexturePreparedResourceRuntime.requested()) {
                byte[] resources = TexturePreparedResourceLoaderPlan.transform(signature, prepared);
                if (resources != null) prepared = resources;
            }
            if (prepared == null || !DisplayThreadSpecStoreProbeRuntime.candidateRequested()) {
                return prepared;
            }
            byte[] overlap = TextureSpecStoreOverlapPlan.transform(signature, prepared);
            return overlap == null ? prepared : overlap;
        }
        if (TexturePreparedPrefetchPlan.PLAN_ID.equals(target.planId())) {
            if (TexturePreparedResourcePlan.WORKER.equals(signature.internalName())) {
                return TexturePreparedPixelRuntime.ready()
                        ? TexturePreparedResourcePlan.transformWorker(signature, originalBytes) : null;
            }
            return TexturePreparedPixelRuntime.ready()
                    ? TexturePreparedPrefetchPlan.transform(signature, originalBytes)
                    : null;
        }
        if (AssetProgressLogRuntime.PLAN_ID.equals(target.planId())) {
            return AssetProgressLogRuntime.suppress()
                    ? AssetProgressLogPlan.transform(signature, originalBytes) : null;
        }
        if (TexturePreparedStagingRuntime.PLAN_ID.equals(target.planId())) {
            return TexturePreparedPixelRuntime.ready()
                    ? resourceLoaderPlans(signature, originalBytes)
                    : null;
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
                // BaseLocation also carries paused snapshot maintenance and deeper opt-in
                // attribution. Compose the disjoint rewrites while the original exact source
                // identity is still known.
                byte[] maintained = AdapterPlanControl.allows(CampaignEntityMaintenanceRuntime.PLAN_ID)
                        ? CampaignEntityMaintenancePlan.transform(signature, location)
                        : null;
                byte[] base = maintained == null ? location : maintained;
                byte[] timed = AdapterPlanControl.allows(CampaignLocationEconomyTimeRuntime.PLAN_ID)
                        ? CampaignLocationEconomyTimePlan.transform(signature, base)
                        : null;
                return timed == null ? base : timed;
            }
            byte[] repository = EntityRepositoryListPlan.transform(signature, originalBytes);
            if (repository != null) {
                return repository;
            }
            byte[] entity = EntityIdMutationPlan.transform(signature, originalBytes);
            if (entity == null) {
                return null;
            }
            // BaseCampaignEntity also carries the empty-script maintenance shortcut. The
            // transformer returns after this entity-index target succeeds, so compose the
            // disjoint runScripts rewrite while retaining the original exact source identity.
            byte[] maintained = AdapterPlanControl.allows(CampaignEntityMaintenanceRuntime.PLAN_ID)
                    ? CampaignEntityMaintenancePlan.transform(signature, entity)
                    : null;
            return maintained == null ? entity : maintained;
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
            // memo's and by the shared reader rewrite. Only one target per class ever transforms,
            // so every branch composes all four.
            byte[] loadingUtils = loadingUtilsPlans(signature, originalBytes);
            if (loadingUtils != null) {
                return loadingUtils;
            }
            byte[] callbackBreakdown = StartupCallBreakdownPlan.transform(signature, originalBytes);
            if (callbackBreakdown != null) {
                return callbackBreakdown;
            }
            byte[] startupPhases = StartupPhasePlan.transform(signature, originalBytes);
            if (startupPhases != null) {
                return startupPhases;
            }
            byte[] composedSpecStore = specStorePlans(signature, originalBytes);
            if (composedSpecStore != null) {
                return composedSpecStore;
            }
            byte[] specStoreBase = SpecStorePhasePlan.transform(signature, originalBytes);
            if (specStoreBase == null) {
                byte[] composedWeaponLoader = weaponLoaderPlans(signature, originalBytes);
                if (composedWeaponLoader != null) {
                    return composedWeaponLoader;
                }
                byte[] weaponPhases = WeaponLoaderPhasePlan.transform(signature, originalBytes);
                if (weaponPhases == null) {
                    byte[] composedHullLoader = shipHullLoaderPlans(signature, originalBytes);
                    if (composedHullLoader != null) {
                        return composedHullLoader;
                    }
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
                        byte[] optimized = rulesLoaderPlans(signature, rulesPhases);
                        return optimized == null ? rulesPhases : optimized;
                    }
                    if (!AdapterPlanControl.allows(HullJsonCacheRuntime.PLAN_ID)
                            || !HullJsonCacheRuntime.ready()) {
                        byte[] concise = assetProgressLogs(hullPhases);
                        return concise == null ? hullPhases : concise;
                    }
                    try {
                        byte[] cached = HullJsonCachePlan.transform(
                                ClassSignature.parse(hullPhases), hullPhases);
                        byte[] current = cached == null ? hullPhases : cached;
                        byte[] concise = assetProgressLogs(current);
                        return concise == null ? current : concise;
                    } catch (java.io.IOException ignored) {
                        byte[] concise = assetProgressLogs(hullPhases);
                        return concise == null ? hullPhases : concise;
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
            byte[] factionPhases = FactionLoaderPhasePlan.transform(signature, specStoreBase);
            byte[] specStore = factionPhases == null ? specStoreBase : factionPhases;
            try {
                byte[] variantPhases = VariantLoaderPhasePlan.transform(
                        ClassSignature.parse(specStore), specStore);
                byte[] attributed = variantPhases == null ? specStore : variantPhases;
                byte[] optimized = specStoreOptimizations(
                        ClassSignature.parse(attributed), attributed);
                return optimized == null ? attributed : optimized;
            } catch (java.io.IOException ignored) {
                return specStore;
            }
        }
        if (ResourcePriorityRuntime.PLAN_ID.equals(target.planId())) {
            return resourceLoaderPlans(signature, originalBytes);
        }
        if (SaveDescriptorCompatibilityRuntime.PLAN_ID.equals(target.planId())) {
            return SaveDescriptorCompatibilityPlan.transform(signature, originalBytes);
        }
        if (IndustryDemandSupplyMemoRuntime.PLAN_ID.equals(target.planId())) {
            byte[] memo = IndustryDemandSupplyMemoPlan.transform(signature, originalBytes);
            if (memo == null) return null;
            byte[] lazy = AdapterPlanControl.allows(CodexLazyFleetMemberRuntime.PLAN_ID)
                    ? CodexLazyFleetMemberPlan.transform(signature, memo)
                    : null;
            byte[] optimized = lazy == null ? memo : lazy;
            if (!StartupPhaseRuntime.phaseProbeEnabled()
                    || !IndustryDemandSupplyMemoPlan.CODEX_CLASS.equals(signature.internalName())) {
                return optimized;
            }
            byte[] timed = AdapterPlanControl.allows(StartupPhaseRuntime.PLAN_ID)
                    ? StartupCallBreakdownPlan.transform(signature, optimized)
                    : null;
            return timed == null ? optimized : timed;
        }
        if (CodexLazyFleetMemberRuntime.PLAN_ID.equals(target.planId())) {
            return CodexLazyFleetMemberPlan.transform(signature, originalBytes);
        }
        if (IndEvoSyntheticMarketRuntime.PLAN_ID.equals(target.planId())) {
            return IndEvoSyntheticMarketPlan.transform(signature, originalBytes);
        }
        if (VariantJsonCacheRuntime.PLAN_ID.equals(target.planId())
                || FactionPriorityCacheRuntime.PLAN_ID.equals(target.planId())
                || SpecStoreQuoteNormalizationPlan.PLAN_ID.equals(target.planId())) {
            return specStoreOptimizations(signature, originalBytes);
        }
        if (WeaponJsonCacheRuntime.PLAN_ID.equals(target.planId())
                || ProjectileJsonCacheRuntime.PLAN_ID.equals(target.planId())) {
            return weaponJsonCaches(originalBytes);
        }
        if (HullJsonCacheRuntime.PLAN_ID.equals(target.planId())) {
            if (!HullJsonCacheRuntime.ready()) {
                return null;
            }
            byte[] cached = HullJsonCachePlan.transform(signature, originalBytes);
            byte[] current = cached == null ? originalBytes : cached;
            byte[] concise = assetProgressLogs(current);
            return concise == null ? cached : concise;
        }
        if (RulesDuplicateIndexRuntime.PLAN_ID.equals(target.planId())
                || RulesCsvCacheRuntime.PLAN_ID.equals(target.planId())
                || RulesRegexCacheRuntime.PLAN_ID.equals(target.planId())) {
            return rulesLoaderPlans(signature, originalBytes);
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
                    ? (WindowsPcmCopyPlan.TARGET.equals(signature.internalName())
                            ? PreparedAudioPlan.transformWindows(signature, originalBytes)
                            : PreparedAudioPlan.transform(signature, originalBytes))
                    : null;
        }
        if (JaninoUnitMemoRuntime.PLAN_ID.equals(target.planId())) {
            return JaninoUnitMemoRuntime.enabled() ? JaninoUnitMemoPlan.transform(signature, originalBytes) : null;
        }
        if (WindowsPcmCopyRuntime.PLAN_ID.equals(target.planId())) {
            return WindowsPcmCopyRuntime.enabled() ? WindowsPcmCopyPlan.transform(signature, originalBytes) : null;
        }
        if (AudioStreamSourceErrorRuntime.PLAN_ID.equals(target.planId())) {
            return AudioStreamSourceErrorPlan.transform(signature, originalBytes);
        }
        if (AudioResourceFallbackRuntime.PLAN_ID.equals(target.planId())) {
            byte[] repaired = AudioResourceFallbackPlan.transform(signature, originalBytes);
            if (repaired == null || !PreparedAudioRuntime.pathLookupReady()) {
                return repaired;
            }
            byte[] pathIndexed = AdapterPlanControl.allows(PreparedAudioRuntime.PLAN_ID)
                    ? PreparedAudioPathPlan.transform(repaired)
                    : null;
            return pathIndexed == null ? repaired : pathIndexed;
        }
        if (AiTweaksSplitArcsPlan.PLAN_ID.equals(target.planId())) {
            return AiTweaksSplitArcsPlan.transform(signature, originalBytes);
        }
        if (AiTweaksAffineVectorPlan.PLAN_ID.equals(target.planId())) {
            return AiTweaksAffineVectorPlan.transform(signature, originalBytes);
        }
        if (CombatListenerRangeSnapshotPlan.PLAN_ID.equals(target.planId())) {
            return CombatListenerRangeSnapshotPlan.transform(signature, originalBytes);
        }
        if (AshLibVariantLookupRuntime.PLAN_ID.equals(target.planId())) {
            byte[] optimized = AshLibVariantLookupPlan.transform(signature, originalBytes);
            if (optimized == null || !StartupPhaseRuntime.phaseProbeEnabled()
                    || (!AshLibVariantLookupPlan.REPOSITORY_CLASS.equals(signature.internalName())
                    && !AshLibVariantLookupPlan.SHIP_JSON_CLASS.equals(signature.internalName()))) {
                return optimized;
            }
            byte[] timed = AdapterPlanControl.allows(StartupPhaseRuntime.PLAN_ID)
                    ? StartupCallBreakdownPlan.transform(signature, optimized)
                    : null;
            return timed == null ? optimized : timed;
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
        if (RatAbyssFactionFlagPlan.PLAN_ID.equals(target.planId())) {
            return RatAbyssFactionFlagPlan.transform(signature, originalBytes);
        }
        if (MnemonicSensorsEntityFilterPlan.PLAN_ID.equals(target.planId())) {
            return MnemonicSensorsEntityFilterPlan.transform(signature, originalBytes);
        }
        if (MutableStatTempAdvancePlan.PLAN_ID.equals(target.planId())) {
            return MutableStatTempAdvancePlan.transform(signature, originalBytes);
        }
        if (ContrailRenderScratchRuntime.PLAN_ID.equals(target.planId())) {
            return ContrailRenderScratchPlan.transform(signature, originalBytes);
        }
        if (FontWrapAllocationRuntime.PLAN_ID.equals(target.planId())) {
            return FontWrapAllocationPlan.transform(signature, originalBytes);
        }
        if (LunaCampaignRendererSnapshotRuntime.PLAN_ID.equals(target.planId())) {
            return LunaCampaignRendererSnapshotPlan.transform(signature, originalBytes);
        }
        if (VersionCheckResponseDedupRuntime.PLAN_ID.equals(target.planId())) {
            return VersionCheckResponseDedupPlan.transform(signature, originalBytes);
        }
        if (MagicLibPaintjobRuntime.PLAN_ID.equals(target.planId())) {
            return MagicLibPaintjobPlan.transform(signature, originalBytes);
        }
        if (MagicLibPaintjobNotificationRuntime.PLAN_ID.equals(target.planId())) {
            byte[] notification =
                    MagicLibPaintjobNotificationPlan.transform(signature, originalBytes);
            byte[] current = notification == null ? originalBytes : notification;
            byte[] frameSnapshot = AdapterPlanControl.allows(MagicLibPaintjobSnapshotRuntime.PLAN_ID)
                    ? MagicLibPaintjobSnapshotPlan.transform(signature, current)
                    : null;
            current = frameSnapshot == null ? current : frameSnapshot;
            byte[] optionalJson = AdapterPlanControl.allows(MagicLibPaintjobLoadRuntime.PLAN_ID)
                    ? MagicLibPaintjobLoadPlan.transform(signature, current)
                    : null;
            boolean changed = notification != null || frameSnapshot != null || optionalJson != null;
            current = optionalJson == null ? current : optionalJson;
            byte[] catalog = MagicLibPaintjobCacheRuntime.ready()
                            && AdapterPlanControl.allows(MagicLibPaintjobCacheRuntime.PLAN_ID)
                    ? MagicLibPaintjobCachePlan.transform(signature, current)
                    : null;
            changed |= catalog != null;
            current = catalog == null ? current : catalog;
            if (changed && AdapterPlanControl.allows(StartupPhaseRuntime.PLAN_ID)
                    && StartupPhaseRuntime.phaseProbeEnabled()) {
                byte[] timed = StartupCallBreakdownPlan.transform(signature, current);
                current = timed == null ? current : timed;
            }
            return changed ? current : null;
        }
        if (StelnetMarketUpdaterRuntime.PLAN_ID.equals(target.planId())) {
            return StelnetMarketUpdaterPlan.transform(signature, originalBytes);
        }
        if (LogisticsNotificationsFuelRuntime.PLAN_ID.equals(target.planId())) {
            return LogisticsNotificationsFuelPlan.transform(signature, originalBytes);
        }
        if (MacMemoryWarningRuntime.PLAN_ID.equals(target.planId())) {
            return MacMemoryWarningPlan.transform(signature, originalBytes);
        }
        if (FrameTimeRuntime.PLAN_ID.equals(target.planId())) {
            return FrameTimePlan.transform(signature, originalBytes);
        }
        if (GlCommandCountRuntime.PLAN_ID.equals(target.planId())) {
            return GlCommandCountPlan.transform(signature, originalBytes);
        }
        if (FrameLimiterTimePlan.PLAN_ID.equals(target.planId())) {
            return FrameLimiterTimePlan.transform(signature, originalBytes);
        }
        if (CampaignCallTimeRuntime.PLAN_ID.equals(target.planId())) {
            return CampaignCallTimePlan.transform(signature, originalBytes);
        }
        if (CampaignEngineTimeRuntime.PLAN_ID.equals(target.planId())) {
            return CampaignEngineTimePlan.transform(signature, originalBytes);
        }
        if (CampaignLocationEconomyTimeRuntime.PLAN_ID.equals(target.planId())) {
            return CampaignLocationEconomyTimePlan.transform(signature, originalBytes);
        }
        if (CampaignMarketFleetTimeRuntime.PLAN_ID.equals(target.planId())) {
            return CampaignMarketFleetTimePlan.transform(signature, originalBytes);
        }
        if (CampaignEntityMaintenanceRuntime.PLAN_ID.equals(target.planId())) {
            byte[] maintained = CampaignEntityMaintenancePlan.transform(signature, originalBytes);
            if (maintained == null) return null;
            // Market.advance is also an opt-in attribution target. Maintenance is registered
            // first in production, so compose the probe here while the original exact source
            // identity is still available.
            byte[] timed = AdapterPlanControl.allows(CampaignMarketFleetTimeRuntime.PLAN_ID)
                    ? CampaignMarketFleetTimePlan.transform(signature, maintained)
                    : null;
            if (timed != null) return timed;
            timed = AdapterPlanControl.allows(CampaignLocationEconomyTimeRuntime.PLAN_ID)
                    ? CampaignLocationEconomyTimePlan.transform(signature, maintained)
                    : null;
            return timed == null ? maintained : timed;
        }
        if (FleetAiProfilerRuntime.PLAN_ID.equals(target.planId())) {
            byte[] profiler = FleetAiProfilerPlan.transform(signature, originalBytes);
            byte[] current = profiler == null ? originalBytes : profiler;
            byte[] timed = AdapterPlanControl.allows(FleetAiModuleTimeRuntime.PLAN_ID)
                    ? FleetAiModuleTimePlan.transform(signature, current)
                    : null;
            return timed == null ? profiler : timed;
        }
        if (TacticalFleetAiTimeRuntime.PLAN_ID.equals(target.planId())) {
            return TacticalFleetAiTimePlan.transform(signature, originalBytes);
        }
        if (FleetInflationTimeRuntime.PLAN_ID.equals(target.planId())) {
            return FleetInflationTimePlan.transform(signature, originalBytes);
        }
        if (CoreAutofitTimeRuntime.PLAN_ID.equals(target.planId())) {
            return CoreAutofitTimePlan.transform(signature, originalBytes);
        }
        if (NexEconomyInfoTimeRuntime.PLAN_ID.equals(target.planId())) {
            byte[] timed = NexEconomyInfoTimePlan.transform(signature, originalBytes);
            byte[] current = timed == null ? originalBytes : timed;
            byte[] scoped = AdapterPlanControl.allows(NexMarketListScopeRuntime.PLAN_ID)
                    && NexMarketListScopeRuntime.configured()
                    ? NexMarketListScopePlan.transform(signature, current)
                    : null;
            return scoped == null ? timed : scoped;
        }
        if (NexMarketListScopeRuntime.PLAN_ID.equals(target.planId())) {
            byte[] current = originalBytes;
            byte[] timed = null;
            if (NexMarketListScopePlan.NEX_CLASS.equals(signature.internalName())
                    && AdapterPlanControl.allows(NexEconomyInfoTimeRuntime.PLAN_ID)
                    && NexEconomyInfoTimeRuntime.enabled()) {
                timed = NexEconomyInfoTimePlan.transform(signature, originalBytes);
                if (timed != null) current = timed;
            }
            byte[] scoped = NexMarketListScopePlan.transform(signature, current);
            return scoped == null ? timed : scoped;
        }
        if (FrameTimeStatePlan.PLAN_ID.equals(target.planId())) {
            return FrameTimeStatePlan.transform(signature, originalBytes);
        }
        if (FrameTimeStartupCompletionPlan.PLAN_ID.equals(target.planId())) {
            return FrameTimeStartupCompletionPlan.transform(signature, originalBytes);
        }
        if (MainMenuInteractivePlan.PLAN_ID.equals(target.planId())) {
            return MainMenuInteractivePlan.transform(signature, originalBytes);
        }
        if (CombatRuntimeIntegrityRuntime.PLAN_ID.equals(target.planId())) {
            return CombatRuntimeIntegrityPlan.transform(signature, originalBytes);
        }
        if (CollisionQuerySetPlan.PLAN_ID.equals(target.planId())) {
            return CollisionQuerySetPlan.transform(signature, originalBytes);
        }
        // The token target normally wins selection for this shared class. Compose the command-name
        // shortcut here too; otherwise --fast can configure both caches while silently installing
        // only the tokenizer memo. Either optimization may still install on its own.
        if (RuleTokenCacheRuntime.PLAN_ID.equals(target.planId())) {
            byte[] memoised = ruleTokenCache(originalBytes, null);
            byte[] current = memoised == null ? originalBytes : memoised;
            byte[] shortcut = ruleCommandClassLookup(current);
            return shortcut == null ? memoised : shortcut;
        }
        // Two classes share this plan: the expression class carries the shortcut, the rules loader
        // carries the publish. Only one of them can match any given signature.
        if (RuleCommandClassCacheRuntime.PLAN_ID.equals(target.planId())) {
            if (!RuleCommandClassCacheRuntime.ready()) {
                return null;
            }
            byte[] shortcut = RuleCommandClassCachePlan.transform(signature, originalBytes);
            return shortcut != null ? shortcut : rulesLoaderPlans(signature, originalBytes);
        }
        return null;
    }

    /** Applies every independent WeaponSpecLoader rewrite to one tree and computes frames once. */
    private static byte[] weaponLoaderPlans(ClassSignature signature, byte[] originalBytes) {
        if (!WeaponLoaderPhasePlan.TARGET_CLASS.equals(signature.internalName())) {
            return null;
        }
        try {
            ClassNode owner = new ClassNode(Opcodes.ASM9);
            new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
            if (!WeaponLoaderPhasePlan.apply(signature, owner)) {
                return null;
            }
            ProjectileLoaderPhasePlan.apply(signature, owner);
            if (AdapterPlanControl.allows(WeaponJsonCacheRuntime.PLAN_ID)
                    && WeaponJsonCacheRuntime.ready()) {
                WeaponJsonCachePlan.apply(signature, owner);
            }
            if (AdapterPlanControl.allows(ProjectileJsonCacheRuntime.PLAN_ID)
                    && ProjectileJsonCacheRuntime.ready()) {
                ProjectileJsonCachePlan.apply(signature, owner);
            }
            if (AssetProgressLogRuntime.suppress()) {
                AssetProgressLogPlan.apply(signature, owner);
            }
            if (StartupPhaseRuntime.phaseProbeEnabled()) {
                WeaponHydrationBreakdownPlan.apply(signature, owner);
            }
            return WeaponJsonCachePlan.write(owner);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // The caller retains the old independently fail-open pipeline as a fallback.
            return null;
        }
    }

    /** Applies every independent ShipHullSpecLoader rewrite to one tree and frame pass. */
    private static byte[] shipHullLoaderPlans(ClassSignature signature, byte[] originalBytes) {
        if (!ShipHullLoaderPhasePlan.TARGET_CLASS.equals(signature.internalName())) {
            return null;
        }
        try {
            ClassNode owner = new ClassNode(Opcodes.ASM9);
            new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
            if (!ShipHullLoaderPhasePlan.apply(signature, owner)) {
                return null;
            }
            if (AdapterPlanControl.allows(HullJsonCacheRuntime.PLAN_ID)
                    && HullJsonCacheRuntime.ready()) {
                HullJsonCachePlan.apply(signature, owner);
            }
            if (AssetProgressLogRuntime.suppress()) {
                AssetProgressLogPlan.apply(signature, owner);
            }
            return ShipHullLoaderPhasePlan.write(owner);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // The caller retains the old independently fail-open pipeline as a fallback.
            return null;
        }
    }

    /** Applies every independent SpecStore rewrite to one tree and computes its frames once. */
    private static byte[] specStorePlans(ClassSignature signature, byte[] originalBytes) {
        if (!SpecStorePhasePlan.TARGET_CLASS.equals(signature.internalName())) {
            return null;
        }
        try {
            ClassNode owner = new ClassNode(Opcodes.ASM9);
            new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
            if (!SpecStorePhasePlan.apply(signature, owner)) {
                return null;
            }
            FactionLoaderPhasePlan.apply(signature, owner);
            VariantLoaderPhasePlan.apply(signature, owner);
            if (AdapterPlanControl.allows(VariantJsonCacheRuntime.PLAN_ID)
                    && VariantJsonCacheRuntime.ready()) {
                VariantJsonCachePlan.apply(signature, owner);
            }
            if (AdapterPlanControl.allows(SpecStoreQuoteNormalizationPlan.PLAN_ID)) {
                SpecStoreQuoteNormalizationPlan.apply(signature, owner);
            }
            if (AdapterPlanControl.allows(FactionPriorityCacheRuntime.PLAN_ID)
                    && FactionPriorityCacheRuntime.ready()) {
                FactionPriorityCachePlan.apply(signature, owner);
            }
            if (StartupPhaseRuntime.phaseProbeEnabled()) {
                ShipSystemHydrationBreakdownPlan.apply(signature, owner);
            }
            if (AssetProgressLogRuntime.suppress()) {
                AssetProgressLogPlan.apply(signature, owner);
            }
            byte[] transformed = VariantJsonCachePlan.write(owner);
            StartupPhaseRuntime.installed();
            return transformed;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // The caller retains the old independently fail-open pipeline as a fallback.
            return null;
        }
    }

    /**
     * Composes the four independent rewrites that share {@code LoadingUtils}.
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
        if (!LoadingUtilsReaderPlan.TARGET_CLASS.equals(signature.internalName())) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        boolean changed = false;
        try {
            new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
            if (AdapterPlanControl.allows(MergedReadCacheRuntime.PLAN_ID)
                    && MergedReadCacheRuntime.ready()) {
                changed |= MergedReadCachePlan.apply(signature, owner);
            }
            if (AdapterPlanControl.allows(StartupPhaseRuntime.PLAN_ID)
                    && StartupPhaseRuntime.mergedReadProbeEnabled()) {
                changed |= MergedReadProbePlan.apply(signature, owner);
            }
            if (AdapterPlanControl.allows(LoadJsonMemoRuntime.PLAN_ID)
                    && LoadJsonMemoRuntime.ready()) {
                changed |= LoadJsonMemoPlan.apply(signature, owner);
            }
            if (AdapterPlanControl.allows(LoadingUtilsReaderPlan.PLAN_ID)) {
                changed |= LoadingUtilsReaderPlan.apply(signature, owner);
            }
            return changed ? LoadingUtilsReaderPlan.write(owner) : null;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // A partial in-memory rewrite is never published. The original class remains active.
            return null;
        }
    }


    /** Composes the two independent method-pair rewrites that share WeaponSpecLoader. */
    private static byte[] weaponJsonCaches(byte[] originalBytes) {
        byte[] current = originalBytes;
        boolean changed = false;
        try {
            if (AdapterPlanControl.allows(WeaponJsonCacheRuntime.PLAN_ID)
                    && WeaponJsonCacheRuntime.ready()) {
                byte[] weapon = WeaponJsonCachePlan.transform(ClassSignature.parse(current), current);
                if (weapon != null) {
                    current = weapon;
                    changed = true;
                }
            }
            if (AdapterPlanControl.allows(ProjectileJsonCacheRuntime.PLAN_ID)
                    && ProjectileJsonCacheRuntime.ready()) {
                byte[] projectile = ProjectileJsonCachePlan.transform(ClassSignature.parse(current), current);
                if (projectile != null) {
                    current = projectile;
                    changed = true;
                }
            }
            byte[] concise = assetProgressLogs(current);
            if (concise != null) {
                current = concise;
                changed = true;
            }
            return changed ? current : null;
        } catch (java.io.IOException ignored) {
            return changed ? current : null;
        }
    }

    /** Composes the prepared variant restore and the disjoint fixed quote normalization. */
    private static byte[] specStoreOptimizations(
            ClassSignature signature, byte[] originalBytes) {
        byte[] current = originalBytes;
        boolean changed = false;
        if (AdapterPlanControl.allows(VariantJsonCacheRuntime.PLAN_ID)
                && VariantJsonCacheRuntime.ready()) {
            byte[] cached = VariantJsonCachePlan.transform(signature, current);
            if (cached != null) {
                current = cached;
                changed = true;
            }
        }
        try {
            ClassSignature currentSignature = changed ? ClassSignature.parse(current) : signature;
            byte[] priority = AdapterPlanControl.allows(FactionPriorityCacheRuntime.PLAN_ID)
                    && FactionPriorityCacheRuntime.ready()
                    ? FactionPriorityCachePlan.transform(currentSignature, current)
                    : null;
            if (priority != null) {
                current = priority;
                changed = true;
            }
        } catch (java.io.IOException ignored) {
            // Any existing disjoint rewrite remains valid; the priority walk stays original.
        }
        try {
            ClassSignature currentSignature = changed ? ClassSignature.parse(current) : signature;
            byte[] normalized = AdapterPlanControl.allows(SpecStoreQuoteNormalizationPlan.PLAN_ID)
                    ? SpecStoreQuoteNormalizationPlan.transform(currentSignature, current)
                    : null;
            if (normalized != null) {
                current = normalized;
                changed = true;
            }
        } catch (java.io.IOException ignored) {
            // A valid prepared-variant transform remains useful if the disjoint rewrite cannot
            // inspect its output. Every original String.replaceAll call remains untouched.
        }
        byte[] concise = assetProgressLogs(current);
        if (concise != null) {
            current = concise;
            changed = true;
        }
        return changed ? current : null;
    }

    private static byte[] assetProgressLogs(byte[] originalBytes) {
        if (!AssetProgressLogRuntime.suppress()) {
            return null;
        }
        try {
            return AssetProgressLogPlan.transform(ClassSignature.parse(originalBytes), originalBytes);
        } catch (java.io.IOException ignored) {
            return null;
        }
    }

    private static byte[] rulesOptimizations(byte[] originalBytes) {
        byte[] current = originalBytes;
        boolean changed = false;
        try {
            if (AdapterPlanControl.allows(RulesCsvCacheRuntime.PLAN_ID)
                    && RulesCsvCacheRuntime.ready()) {
                byte[] cached = RulesCsvCachePlan.transform(ClassSignature.parse(current), current);
                if (cached != null) {
                    current = cached;
                    changed = true;
                }
            }
            if (AdapterPlanControl.allows(RulesDuplicateIndexRuntime.PLAN_ID)
                    && RulesDuplicateIndexRuntime.ready()) {
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

    /** Composes every independent rewrite on the exact campaign-rules loader. */
    private static byte[] rulesLoaderPlans(ClassSignature signature, byte[] originalBytes) {
        byte[] current = originalBytes;
        boolean changed = false;
        if (AssetProgressLogRuntime.suppress()) {
            byte[] quiet = AssetProgressLogPlan.windowsRules(signature, current);
            if (quiet != null) { current = quiet; changed = true; }
        }

        byte[] optimized = rulesOptimizations(current);
        if (optimized != null) {
            current = optimized;
            changed = true;
        }
        byte[] regex = AdapterPlanControl.allows(RulesRegexCacheRuntime.PLAN_ID)
                ? RulesRegexCachePlan.transform(signature, current)
                : null;
        if (regex != null) {
            current = regex;
            changed = true;
        }
        byte[] published = ruleCommandClassPublish(current);
        if (published != null) {
            current = published;
            changed = true;
        }
        return changed ? current : null;
    }

    /**
     * Chains the tokenizer memo onto the expression constructor.
     *
     * @param current the bytes to rewrite, already carrying the attribution probe when there is one
     * @param attributed the attributed bytes, or null when only the memo applies -- returning null
     *     for that case keeps "no plan matched this class" distinguishable from "nothing to add"
     */
    private static byte[] ruleTokenCache(byte[] current, byte[] attributed) {
        if (!AdapterPlanControl.allows(RuleTokenCacheRuntime.PLAN_ID)
                || !RuleTokenCacheRuntime.ready()) {
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
        if (!AdapterPlanControl.allows(RuleCommandClassCacheRuntime.PLAN_ID)
                || !RuleCommandClassCacheRuntime.ready()) {
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
        if (!AdapterPlanControl.allows(RuleCommandClassCacheRuntime.PLAN_ID)
                || !RuleCommandClassCacheRuntime.ready()) {
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
        if (!AdapterPlanControl.allows(TexturePaddingRuntime.PLAN_ID)) {
            return rewritten;
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
        if (!AdapterPlanControl.allows(planId)) {
            return false;
        }
        if (AssetProgressLogRuntime.PLAN_ID.equals(planId)) {
            return AssetProgressLogRuntime.suppress();
        }
        if (FastRenderingPreparedTextureRuntime.PLAN_ID.equals(planId)) {
            return FastRenderingPreparedTextureRuntime.ready();
        }
        if (TextureCompatibilityRuntime.PLAN_ID.equals(planId)) {
            return TextureCompatibilityRuntime.ready();
        }
        if (TexturePreparedPixelRuntime.PLAN_ID.equals(planId)) {
            return TexturePreparedPixelRuntime.ready();
        }
        if (TexturePrefetchBypassPlan.PLAN_ID.equals(planId)) {
            return TextureCompatibilityRuntime.ready();
        }
        if (TexturePreparedPrefetchPlan.PLAN_ID.equals(planId)) {
            return TexturePreparedPixelRuntime.ready();
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
        if (RulesRegexCacheRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (SpecStoreQuoteNormalizationPlan.PLAN_ID.equals(planId)) {
            return true;
        }
        if (FactionPriorityCacheRuntime.PLAN_ID.equals(planId)) {
            return FactionPriorityCacheRuntime.ready();
        }
        if (ResourcePriorityRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (SaveDescriptorCompatibilityRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (IndustryDemandSupplyMemoRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (CodexLazyFleetMemberRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (IndEvoSyntheticMarketRuntime.PLAN_ID.equals(planId)) {
            return true;
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
        if (JaninoUnitMemoRuntime.PLAN_ID.equals(planId)) {
            return JaninoUnitMemoRuntime.enabled();
        }
        if (WindowsPcmCopyRuntime.PLAN_ID.equals(planId)) {
            return WindowsPcmCopyRuntime.enabled();
        }
        if (AudioStreamSourceErrorRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (AudioMusicTransitionRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (AudioResourceFallbackRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (AiTweaksSplitArcsPlan.PLAN_ID.equals(planId)) {
            return true;
        }
        if (AiTweaksAffineVectorPlan.PLAN_ID.equals(planId)) {
            return true;
        }
        if (CombatListenerRangeSnapshotPlan.PLAN_ID.equals(planId)) {
            return true;
        }
        if (AshLibVariantLookupRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (LoadJsonMemoRuntime.PLAN_ID.equals(planId)) {
            return LoadJsonMemoRuntime.ready();
        }
        if (MergedReadCacheRuntime.PLAN_ID.equals(planId)) {
            return MergedReadCacheRuntime.ready();
        }
        if (LoadingUtilsReaderPlan.PLAN_ID.equals(planId)) {
            return true;
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
        if (RatAbyssFactionFlagPlan.PLAN_ID.equals(planId)) {
            return true;
        }
        if (MnemonicSensorsEntityFilterPlan.PLAN_ID.equals(planId)) {
            return true;
        }
        if (MutableStatTempAdvancePlan.PLAN_ID.equals(planId)) {
            return true;
        }
        if (ContrailRenderScratchRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (FontWrapAllocationRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (VersionCheckResponseDedupRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (MagicLibPaintjobRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (MagicLibPaintjobNotificationRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (LunaCampaignRendererSnapshotRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (StelnetMarketUpdaterRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (LogisticsNotificationsFuelRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (MacMemoryWarningRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (FrameTimeRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (GlCommandCountRuntime.PLAN_ID.equals(planId)) {
            return GlCommandCountRuntime.planEnabled();
        }
        if (FrameLimiterTimePlan.PLAN_ID.equals(planId)) {
            return FrameTimeRuntime.enabled();
        }
        if (CampaignCallTimeRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (CampaignEngineTimeRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (CampaignLocationEconomyTimeRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (CampaignMarketFleetTimeRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (CampaignEntityMaintenanceRuntime.PLAN_ID.equals(planId)) {
            return CampaignEntityMaintenanceRuntime.enabled();
        }
        if (FleetAiProfilerRuntime.PLAN_ID.equals(planId)) {
            return FleetAiProfilerRuntime.enabled();
        }
        if (TacticalFleetAiTimeRuntime.PLAN_ID.equals(planId)) {
            return TacticalFleetAiTimeRuntime.enabled();
        }
        if (FleetInflationTimeRuntime.PLAN_ID.equals(planId)) {
            return FleetInflationTimeRuntime.enabled();
        }
        if (CoreAutofitTimeRuntime.PLAN_ID.equals(planId)) {
            return CoreAutofitTimeRuntime.enabled();
        }
        if (NexEconomyInfoTimeRuntime.PLAN_ID.equals(planId)) {
            return NexEconomyInfoTimeRuntime.enabled();
        }
        if (NexMarketListScopeRuntime.PLAN_ID.equals(planId)) {
            return NexMarketListScopeRuntime.configured();
        }
        if (FrameTimeStatePlan.PLAN_ID.equals(planId)) {
            return true;
        }
        if (FrameTimeStartupCompletionPlan.PLAN_ID.equals(planId)) {
            return true;
        }
        if (MainMenuInteractivePlan.PLAN_ID.equals(planId)) {
            return true;
        }
        if (CombatRuntimeIntegrityRuntime.PLAN_ID.equals(planId)) {
            return true;
        }
        if (CollisionQuerySetPlan.PLAN_ID.equals(planId)) {
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
        if (!SourceHintIsolationPlan.TARGET_CLASS.equals(signature.internalName())) {
            return null;
        }
        try {
            ClassNode owner = new ClassNode(Opcodes.ASM9);
            new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
            boolean isolated = AdapterPlanControl.allows(SourceHintIsolationRuntime.PLAN_ID)
                    && SourceHintIsolationPlan.apply(signature, owner);
            boolean probed = AdapterPlanControl.allows(ResourceProbeRuntime.PLAN_ID)
                    && ResourceProbeRuntime.ready()
                    && ResourceProbePlan.apply(signature, owner);
            if (!isolated && !probed) return null;
            byte[] transformed = SourceHintIsolationPlan.write(owner);
            if (isolated) SourceHintIsolationRuntime.installed();
            return transformed;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Retry whichever standalone plan remains enabled. Losing either optimization is the
            // safe direction; a disabled plan must never return through this fallback.
            if (AdapterPlanControl.allows(SourceHintIsolationRuntime.PLAN_ID)) {
                return SourceHintIsolationPlan.transform(signature, originalBytes);
            }
            return AdapterPlanControl.allows(ResourceProbeRuntime.PLAN_ID)
                    && ResourceProbeRuntime.ready()
                    ? ResourceProbePlan.transform(signature, originalBytes)
                    : null;
        }
    }

    static boolean anyPlanCompiled() {
        return true;
    }

    /** Composes the always-on priority index with either optional ResourceLoaderState marker. */
    private static byte[] resourceLoaderPlans(ClassSignature signature, byte[] originalBytes) {
        if (!ResourcePriorityPlan.TARGET_CLASS.equals(signature.internalName())) {
            return null;
        }
        try {
            ClassNode owner = new ClassNode(Opcodes.ASM9);
            new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
            boolean phaseMarked = false;
            if (AdapterPlanControl.allows(StartupPhaseRuntime.PLAN_ID)
                    && StartupPhaseRuntime.phaseProbeEnabled()) {
                phaseMarked = StartupPhasePlan.apply(signature, owner);
            }
            boolean marked = phaseMarked;
            if (!marked && AdapterPlanControl.allows(FrameTimeStartupCompletionPlan.PLAN_ID)) {
                marked = FrameTimeStartupCompletionPlan.apply(signature, owner);
            }
            boolean staged = AdapterPlanControl.allows(TexturePreparedStagingRuntime.PLAN_ID)
                    && TexturePreparedStagingPlan.apply(signature, owner);
            boolean preparedOrdered = AdapterPlanControl.allows(TexturePreparedPrefetchPlan.PLAN_ID)
                    && TexturePreparedPriorityPlan.apply(signature, owner);
            boolean preparedResources = AdapterPlanControl.allows(TexturePreparedPrefetchPlan.PLAN_ID)
                    && TexturePreparedResourcePlan.apply(signature, owner);
            boolean indexed = ResourcePriorityPlan.apply(signature, owner);
            boolean rateLimited = ResourceProgressRateLimitPlan.apply(signature, owner);
            if (!marked && !staged && !preparedOrdered && !preparedResources && !indexed && !rateLimited) return null;
            byte[] transformed = preparedResources ? TexturePreparedResourcePlan.write(owner)
                    : ResourcePriorityPlan.write(owner);
            if (AdapterPlanControl.allows(FrameTimeRuntime.PLAN_ID)
                    && DisplayThreadTextureProbeRuntime.requested()) {
                byte[] ownershipProof = DisplayUpdateCallerPlan.transform(signature, transformed);
                if (ownershipProof != null) transformed = ownershipProof;
            }
            if (DisplayThreadSpecStoreProbeRuntime.requested()) {
                byte[] ownershipOverlap = DisplayThreadSpecStoreProbePlan.transform(
                        signature, transformed);
                if (ownershipOverlap != null) transformed = ownershipOverlap;
            }
            if (phaseMarked) {
                StartupPhaseRuntime.installed();
            }
            return transformed;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // A partial tree is never published. Retry the old independent fail-open pipeline.
            return resourceLoaderPlansIndependently(signature, originalBytes);
        }
    }

    private static byte[] resourceLoaderPlansIndependently(
            ClassSignature signature, byte[] originalBytes) {
        byte[] current = originalBytes;
        boolean changed = false;
        try {
            byte[] marked = null;
            if (AdapterPlanControl.allows(StartupPhaseRuntime.PLAN_ID)
                    && StartupPhaseRuntime.phaseProbeEnabled()) {
                marked = StartupPhasePlan.transform(signature, current);
            }
            if (marked == null
                    && AdapterPlanControl.allows(FrameTimeStartupCompletionPlan.PLAN_ID)) {
                marked = FrameTimeStartupCompletionPlan.transform(signature, current);
            }
            if (marked != null) {
                current = marked;
                changed = true;
            }
            if (AdapterPlanControl.allows(TexturePreparedStagingRuntime.PLAN_ID)) {
                byte[] staged = TexturePreparedStagingPlan.transform(
                        ClassSignature.parse(current), current);
                if (staged != null) {
                    current = staged;
                    changed = true;
                }
            }
            if (AdapterPlanControl.allows(TexturePreparedPrefetchPlan.PLAN_ID)) {
                byte[] ordered = TexturePreparedPriorityPlan.transform(signature, current);
                if (ordered != null) {
                    current = ordered;
                    changed = true;
                }
                byte[] resources = TexturePreparedResourcePlan.transform(signature, current);
                if (resources != null) {
                    current = resources;
                    changed = true;
                }
            }
            byte[] indexed = ResourcePriorityPlan.transform(ClassSignature.parse(current), current);
            if (indexed != null) {
                current = indexed;
                changed = true;
            }
            byte[] rateLimited = ResourceProgressRateLimitPlan.transform(signature, current);
            if (rateLimited != null) {
                current = rateLimited;
                changed = true;
            }
            if (AdapterPlanControl.allows(FrameTimeRuntime.PLAN_ID)
                    && DisplayThreadTextureProbeRuntime.requested()) {
                byte[] ownershipProof = DisplayUpdateCallerPlan.transform(signature, current);
                if (ownershipProof != null) {
                    current = ownershipProof;
                    changed = true;
                }
            }
            if (DisplayThreadSpecStoreProbeRuntime.requested()) {
                byte[] ownershipOverlap = DisplayThreadSpecStoreProbePlan.transform(
                        signature, current);
                if (ownershipOverlap != null) {
                    current = ownershipOverlap;
                    changed = true;
                }
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Return whichever exact standalone transformation completed, if any.
        }
        return changed ? current : null;
    }
}

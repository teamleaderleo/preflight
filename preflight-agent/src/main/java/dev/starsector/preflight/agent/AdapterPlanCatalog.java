package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Bounded inventory of every runtime plan and the exact target plan that hosts it. */
final class AdapterPlanCatalog {
    private static final Set<String> DIRECT_PLAN_IDS = Set.of(
            AiTweaksEngagementRangeRuntime.PLAN_ID,
            AshLibVariantLookupRuntime.PLAN_ID,
            AudioResourceFallbackRuntime.PLAN_ID,
            AudioStreamSourceErrorRuntime.PLAN_ID,
            CampaignCallTimeRuntime.PLAN_ID,
            CampaignEngineTimeRuntime.PLAN_ID,
            CampaignEntityMaintenanceRuntime.PLAN_ID,
            CampaignLocationEconomyTimeRuntime.PLAN_ID,
            CampaignMarketFleetTimeRuntime.PLAN_ID,
            CodexLazyFleetMemberRuntime.PLAN_ID,
            CombatRuntimeIntegrityRuntime.PLAN_ID,
            ContrailRenderScratchRuntime.PLAN_ID,
            CommodityEventModMemoRuntime.PLAN_ID,
            DeploymentIconCacheRuntime.PLAN_ID,
            EntityLookupRuntime.PLAN_ID,
            FleetAiProfilerRuntime.PLAN_ID,
            FontWrapAllocationRuntime.PLAN_ID,
            FrameTimeRuntime.PLAN_ID,
            FrameTimeStartupCompletionPlan.PLAN_ID,
            FrameTimeStatePlan.PLAN_ID,
            GraphicsLibCompactReplayPlan.PLAN_ID,
            GraphicsLibHotSettingsRuntime.PLAN_ID,
            GraphicsLibInsigniaManagerCacheRuntime.PLAN_ID,
            HullJsonCacheRuntime.PLAN_ID,
            IndEvoSyntheticMarketRuntime.PLAN_ID,
            IndustryDemandSupplyMemoRuntime.PLAN_ID,
            JaninoBytecodeCacheRuntime.PLAN_ID,
            LoadJsonMemoRuntime.PLAN_ID,
            LogisticsNotificationsFuelRuntime.PLAN_ID,
            LunaCampaignRendererSnapshotRuntime.PLAN_ID,
            MainMenuInteractivePlan.PLAN_ID,
            MacMemoryWarningRuntime.PLAN_ID,
            MagicLibPaintjobNotificationRuntime.PLAN_ID,
            MagicLibPaintjobRuntime.PLAN_ID,
            MergedReadCacheRuntime.PLAN_ID,
            MnemonicSensorsEntityFilterPlan.PLAN_ID,
            MutableStatTempAdvancePlan.PLAN_ID,
            PreparedAudioRuntime.PLAN_ID,
            ProjectileJsonCacheRuntime.PLAN_ID,
            RatAbyssFactionFlagPlan.PLAN_ID,
            RadarRenderRuntime.PLAN_ID,
            ResourcePriorityRuntime.PLAN_ID,
            ResourceProbeRuntime.PLAN_ID,
            RuleCommandClassCacheRuntime.PLAN_ID,
            RuleTokenCacheRuntime.PLAN_ID,
            RulesCsvCacheRuntime.PLAN_ID,
            RulesDuplicateIndexRuntime.PLAN_ID,
            RulesRegexCacheRuntime.PLAN_ID,
            SaveDescriptorCompatibilityRuntime.PLAN_ID,
            SimOpponentSafetyRuntime.PLAN_ID,
            SourceHintIsolationRuntime.PLAN_ID,
            SpecStoreQuoteNormalizationPlan.PLAN_ID,
            StartupPhaseRuntime.PLAN_ID,
            StelnetMarketUpdaterRuntime.PLAN_ID,
            TextureCompatibilityRuntime.PLAN_ID,
            TexturePrefetchBypassPlan.PLAN_ID,
            TexturePreparedPixelRuntime.PLAN_ID,
            VariantJsonCacheRuntime.PLAN_ID,
            VersionCheckResponseDedupRuntime.PLAN_ID,
            WeaponJsonCacheRuntime.PLAN_ID);

    private static final List<Descriptor> DESCRIPTORS = buildDescriptors();

    private AdapterPlanCatalog() {
    }

    static List<Descriptor> descriptors() {
        return DESCRIPTORS;
    }

    private static List<Descriptor> buildDescriptors() {
        List<Descriptor> values = new ArrayList<>();
        for (String planId : DIRECT_PLAN_IDS) {
            values.add(new Descriptor(planId, Set.of(planId)));
        }
        values.add(new Descriptor(
                AssetProgressLogRuntime.PLAN_ID,
                Set.of(
                        HullJsonCacheRuntime.PLAN_ID,
                        ProjectileJsonCacheRuntime.PLAN_ID,
                        SpecStoreQuoteNormalizationPlan.PLAN_ID,
                        StartupPhaseRuntime.PLAN_ID,
                        VariantJsonCacheRuntime.PLAN_ID,
                        WeaponJsonCacheRuntime.PLAN_ID)));
        values.add(new Descriptor(
                AudioMusicTransitionRuntime.PLAN_ID,
                Set.of(AudioStreamSourceErrorRuntime.PLAN_ID)));
        values.add(new Descriptor(
                LoadingUtilsReaderPlan.PLAN_ID,
                Set.of(
                        LoadJsonMemoRuntime.PLAN_ID,
                        MergedReadCacheRuntime.PLAN_ID,
                        StartupPhaseRuntime.PLAN_ID)));
        values.add(new Descriptor(
                MagicLibPaintjobLoadRuntime.PLAN_ID,
                Set.of(MagicLibPaintjobNotificationRuntime.PLAN_ID)));
        values.add(new Descriptor(
                MagicLibPaintjobSnapshotRuntime.PLAN_ID,
                Set.of(MagicLibPaintjobNotificationRuntime.PLAN_ID)));
        values.add(new Descriptor(
                TexturePaddingRuntime.PLAN_ID,
                Set.of(TextureCompatibilityRuntime.PLAN_ID, TexturePreparedPixelRuntime.PLAN_ID)));
        values.sort(Comparator.comparing(Descriptor::planId));

        Set<String> unique = new LinkedHashSet<>();
        for (Descriptor descriptor : values) {
            if (!unique.add(descriptor.planId())) {
                throw new IllegalStateException("Duplicate adapter plan catalog entry: "
                        + descriptor.planId());
            }
        }
        return List.copyOf(values);
    }

    static Map<String, Object> inventory(Map<String, Integer> registeredPlanTargets) {
        AdapterPlanScope scope = AdapterPlanControl.scope();
        Set<String> disabled = AdapterPlanControl.disabledPlans();
        Map<String, Object> plans = new LinkedHashMap<>();
        for (Descriptor descriptor : DESCRIPTORS) {
            int directTargets = nonnegative(registeredPlanTargets.get(descriptor.planId()));
            int boundaryTargets = descriptor.boundaryPlanIds().stream()
                    .mapToInt(planId -> nonnegative(registeredPlanTargets.get(planId)))
                    .sum();
            boolean scopeAllowed = scope.allows(descriptor.planId());
            boolean explicitlyDisabled = disabled.contains(descriptor.planId());

            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("scopeAllowed", scopeAllowed);
            plan.put("explicitlyDisabled", explicitlyDisabled);
            plan.put("registeredTargets", directTargets);
            plan.put("boundaryPlanIds", List.copyOf(new TreeSet<>(descriptor.boundaryPlanIds())));
            plan.put("registeredBoundaryTargets", boundaryTargets);
            plan.put("integration", descriptor.direct() ? "DIRECT" : "COMPOSED");
            plan.put("state", state(
                    scopeAllowed, explicitlyDisabled, directTargets, boundaryTargets));
            plan.put("fallback", "ORIGINAL_BYTECODE");
            plans.put(descriptor.planId(), Collections.unmodifiableMap(plan));
        }
        return Collections.unmodifiableMap(plans);
    }

    private static String state(
            boolean scopeAllowed,
            boolean explicitlyDisabled,
            int directTargets,
            int boundaryTargets) {
        if (!scopeAllowed) return "OUT_OF_SCOPE";
        if (explicitlyDisabled) return "DISABLED";
        if (directTargets > 0) return "REGISTERED";
        if (boundaryTargets > 0) return "COMPOSED";
        return "INACTIVE";
    }

    private static int nonnegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    record Descriptor(String planId, Set<String> boundaryPlanIds) {
        Descriptor {
            if (planId == null || planId.isBlank()) {
                throw new IllegalArgumentException("Plan id is required");
            }
            boundaryPlanIds = Set.copyOf(boundaryPlanIds);
            if (boundaryPlanIds.isEmpty()) {
                throw new IllegalArgumentException("At least one boundary plan is required: " + planId);
            }
        }

        boolean direct() {
            return boundaryPlanIds.size() == 1 && boundaryPlanIds.contains(planId);
        }
    }
}

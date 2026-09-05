package dev.starsector.preflight.agent;

import java.util.Locale;
import java.util.Set;

/** Engine-owned groups of exact adapter plans; callers select a scope, never individual ids. */
public enum AdapterPlanScope {
    FULL("full"),
    PORTABLE_STARTUP("portable-startup"),
    MEASUREMENT_ONLY("measurement-only");

    private static final Set<String> MEASUREMENT_ONLY_PLANS = Set.of(
            FrameTimeRuntime.PLAN_ID,
            GlCommandCountRuntime.PLAN_ID,
            GlMatrixOperationRuntime.PLAN_ID,
            GlStateReissueRuntime.PLAN_ID,
            FrameLimiterTimePlan.PLAN_ID,
            FrameTimeStatePlan.PLAN_ID,
            FrameTimeStartupCompletionPlan.PLAN_ID,
            MainMenuInteractivePlan.PLAN_ID);

    private static final Set<String> PORTABLE_STARTUP_PLANS = Set.of(
            FrameTimeRuntime.PLAN_ID,
            TextureCompatibilityRuntime.PLAN_ID,
            TexturePreparedPixelRuntime.PLAN_ID,
            TexturePrefetchBypassPlan.PLAN_ID,
            TexturePreparedPrefetchPlan.PLAN_ID,
            TexturePreparedStagingRuntime.PLAN_ID,
            TexturePaddingRuntime.PLAN_ID,
            StartupPhaseRuntime.PLAN_ID,
            FactionPriorityCacheRuntime.PLAN_ID,
            ResourcePriorityRuntime.PLAN_ID,
            VariantJsonCacheRuntime.PLAN_ID,
            SpecStoreQuoteNormalizationPlan.PLAN_ID,
            WeaponJsonCacheRuntime.PLAN_ID,
            ProjectileJsonCacheRuntime.PLAN_ID,
            HullJsonCacheRuntime.PLAN_ID,
            RulesDuplicateIndexRuntime.PLAN_ID,
            RulesCsvCacheRuntime.PLAN_ID,
            RulesRegexCacheRuntime.PLAN_ID,
            LoadJsonMemoRuntime.PLAN_ID,
            MergedReadCacheRuntime.PLAN_ID,
            LoadingUtilsReaderPlan.PLAN_ID,
            SourceHintIsolationRuntime.PLAN_ID,
            PreparedAudioRuntime.PLAN_ID,
            WindowsPcmCopyRuntime.PLAN_ID,
            AudioStreamSourceErrorRuntime.PLAN_ID,
            AudioMusicTransitionRuntime.PLAN_ID,
            AudioResourceFallbackRuntime.PLAN_ID,
            AssetProgressLogRuntime.PLAN_ID,
            RuleTokenCacheRuntime.PLAN_ID,
            RuleCommandClassCacheRuntime.PLAN_ID,
            JaninoBytecodeCacheRuntime.PLAN_ID);

    private final String optionValue;

    AdapterPlanScope(String optionValue) {
        this.optionValue = optionValue;
    }

    public String optionValue() {
        return optionValue;
    }

    boolean allows(String planId) {
        return switch (this) {
            case FULL -> true;
            case PORTABLE_STARTUP -> PORTABLE_STARTUP_PLANS.contains(planId);
            case MEASUREMENT_ONLY -> MEASUREMENT_ONLY_PLANS.contains(planId);
        };
    }

    static AdapterPlanScope parse(String raw) {
        if (raw == null || raw.isBlank()) return FULL;
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (AdapterPlanScope scope : values()) {
            if (scope.optionValue.equals(normalized)) return scope;
        }
        throw new IllegalArgumentException("Unknown adapter plan scope: " + raw);
    }
}

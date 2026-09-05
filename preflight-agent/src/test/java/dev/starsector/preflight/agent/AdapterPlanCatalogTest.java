package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AdapterPlanCatalogTest {
    @AfterEach
    void resetPlanControl() {
        AdapterPlanControl.configure(Set.of());
    }

    @Test
    void catalogCoversEveryRegisteredPlanAndEveryExactHostBoundary() {
        Set<String> registeredPlans = exhaustiveTargets().stream()
                .map(AdapterTarget::planId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<AdapterPlanCatalog.Descriptor> descriptors = AdapterPlanCatalog.descriptors();
        Set<String> catalogPlans = descriptors.stream()
                .map(AdapterPlanCatalog.Descriptor::planId)
                .collect(Collectors.toSet());

        assertEquals(82, catalogPlans.size(), "adapter plan inventory changed");
        assertTrue(catalogPlans.containsAll(registeredPlans),
                () -> "uncatalogued registered plans: " + difference(registeredPlans, catalogPlans));
        for (AdapterPlanCatalog.Descriptor descriptor : descriptors) {
            assertTrue(registeredPlans.containsAll(descriptor.boundaryPlanIds()),
                    () -> descriptor.planId() + " lacks exact host boundaries: "
                            + difference(descriptor.boundaryPlanIds(), registeredPlans));
        }
    }

    @Test
    void inventoryShowsComposedBoundariesAndEveryPlanHasAnIndependentKillSwitch() {
        AdapterPlanControl.configure(AdapterPlanScope.FULL, Set.of(AudioMusicTransitionRuntime.PLAN_ID));
        Map<String, Object> inventory = AdapterPlanCatalog.inventory(Map.of(
                AudioStreamSourceErrorRuntime.PLAN_ID, 1,
                TexturePreparedPixelRuntime.PLAN_ID, 1));

        assertEquals("DISABLED", field(inventory, AudioMusicTransitionRuntime.PLAN_ID, "state"));
        assertEquals("COMPOSED", field(inventory, TexturePaddingRuntime.PLAN_ID, "state"));
        assertEquals("INACTIVE", field(inventory, GlStateReissueRuntime.PLAN_ID, "state"));
        assertEquals("COMPOSED", field(inventory, GlStateReissueRuntime.PLAN_ID, "integration"));
        assertEquals("INACTIVE", field(inventory, GlMatrixOperationRuntime.PLAN_ID, "state"));
        assertEquals("COMPOSED", field(inventory, GlMatrixOperationRuntime.PLAN_ID, "integration"));
        assertEquals("REGISTERED", field(inventory, TexturePreparedPixelRuntime.PLAN_ID, "state"));
        assertEquals("INACTIVE", field(inventory, SimOpponentSafetyRuntime.PLAN_ID, "state"));
        assertEquals("DIRECT", field(inventory, SimOpponentSafetyRuntime.PLAN_ID, "integration"));
        assertEquals("COMPOSED", field(inventory, TexturePaddingRuntime.PLAN_ID, "integration"));
        assertEquals("ORIGINAL_BYTECODE",
                field(inventory, TexturePaddingRuntime.PLAN_ID, "fallback"));

        for (AdapterPlanCatalog.Descriptor descriptor : AdapterPlanCatalog.descriptors()) {
            AdapterPlanControl.configure(AdapterPlanScope.FULL, Set.of(descriptor.planId()));
            assertFalse(AdapterPlanControl.allows(descriptor.planId()), descriptor.planId());
        }
    }

    private static List<AdapterTarget> exhaustiveTargets() {
        AdapterTargetRegistry prepared = AdapterTargetRegistry.empty()
                .withTextureTarget(TextureAdapterMode.PREPARED_PIXELS)
                .withFastRenderingPreparedTextureTarget()
                .withStartupPhaseTarget()
                .withVariantJsonCacheTarget()
                .withSpecStoreQuoteNormalizationTarget()
                .withWeaponJsonCacheTarget()
                .withProjectileJsonCacheTarget()
                .withHullJsonCacheTarget()
                .withRulesDuplicateIndexTarget()
                .withRulesCsvCacheTarget()
                .withRulesRegexCacheTarget()
                .withRuleTokenCacheTarget()
                .withRuleCommandClassCacheTarget()
                .withMergedReadCacheTarget()
                .withLoadJsonMemoTarget()
                .withResourceProbeCacheTarget()
                .withPreparedAudioTarget()
                .withGraphicsLibCompactReplayTarget()
                .withJaninoBytecodeCacheTarget()
                .withGraphicsLibInsigniaManagerCacheTarget()
                .withCampaignCallTimeTargets()
                .withCampaignEngineTimeTarget()
                .withCampaignLocationEconomyTimeTargets()
                .withCampaignMarketFleetTimeTargets()
                .withTacticalFleetAiTimeTarget()
                .withFleetInflationTimeTarget()
                .withCoreAutofitTimeTarget()
                .withNexEconomyInfoTimeTarget()
                .withNexMarketListScopeTargets()
                .withFrameTimeStartupCompletionTarget()
                .withMainMenuInteractiveTarget();
        List<AdapterTarget> targets = new java.util.ArrayList<>(prepared.targets());
        targets.add(AdapterTargetRegistry.combatListenerRangeSnapshotTarget());
        targets.add(AdapterTargetRegistry.windowsTexturePreparedPrefetchTarget());
        targets.add(AdapterTargetRegistry.windowsTexturePreparedStagingTarget());
        targets.add(AdapterTargetRegistry.windowsPcmCopyTarget());
        targets.addAll(AdapterTargetRegistry.empty()
                .withTextureTarget(TextureAdapterMode.COMPATIBILITY).targets());
        targets.addAll(AdapterTargetRegistry.empty().withFrameTimeTarget().targets());
        targets.addAll(AdapterTargetRegistry.empty().withGlCommandCountTargets().targets());
        return List.copyOf(targets);
    }

    @SuppressWarnings("unchecked")
    private static Object field(Map<String, Object> inventory, String planId, String field) {
        return ((Map<String, Object>) inventory.get(planId)).get(field);
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }
}

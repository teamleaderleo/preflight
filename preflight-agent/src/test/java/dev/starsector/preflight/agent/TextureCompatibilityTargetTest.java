package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextureCompatibilityTargetTest {
    @Test
    void exactReviewedIdentityMatchesAndEverySingleIdentityChangeRejects() {
        AdapterTarget target = AdapterTargetRegistry.textureCompatibilityTarget();
        assertExactIdentity(target, TextureCompatibilityRuntime.PLAN_ID);
    }

    @Test
    void preparedPixelPlanUsesTheSameReviewedIdentityWithSeparatePlanId() {
        AdapterTarget compatibility = AdapterTargetRegistry.textureCompatibilityTarget();
        AdapterTarget prepared = AdapterTargetRegistry.texturePreparedPixelTarget();

        assertExactIdentity(prepared, TexturePreparedPixelRuntime.PLAN_ID);
        assertEquals(compatibility.internalClassName(), prepared.internalClassName());
        assertEquals(compatibility.sha256(), prepared.sha256());
        assertEquals(compatibility.requiredMethods(), prepared.requiredMethods());
        assertEquals(compatibility.sourceSha256(), prepared.sourceSha256());
        assertFalse(compatibility.id().equals(prepared.id()));
        assertFalse(compatibility.planId().equals(prepared.planId()));
    }

    @Test
    void linuxPreparedPixelTargetPinsTheFlatDistribution() {
        AdapterTarget target = AdapterTargetRegistry.linuxTexturePreparedPixelTarget();
        ClassSignature exactClass = signature(target, target.sha256(), target.requiredMethods());
        AdapterSourceIdentity exactSource = source(
                target.sourceKind(),
                "/home/player/Games/starsector/fs.common_obf.jar",
                target.sourceSha256(),
                target.loaderClass(),
                target.loaderName());

        assertEquals(TexturePreparedPixelRuntime.PLAN_ID, target.planId());
        assertEquals(9, target.requiredMethods().size());
        assertTrue(target.match(exactClass, exactSource).exact());
        assertFalse(target.match(exactClass, source(
                target.sourceKind(),
                "/home/player/Games/starsector/other.jar",
                target.sourceSha256(),
                target.loaderClass(),
                target.loaderName())).exact());
    }

    @Test
    void windowsPreparedPixelTargetPinsTheReviewedNativeDistribution() {
        AdapterTarget mac = AdapterTargetRegistry.texturePreparedPixelTarget();
        AdapterTarget target = AdapterTargetRegistry.windowsTexturePreparedPixelTarget();
        ClassSignature exactClass = signature(target, target.sha256(), target.requiredMethods());
        AdapterSourceIdentity exactSource = source(
                target.sourceKind(),
                "C:/Games/Starsector/starsector-core/fs.common_obf.jar",
                target.sourceSha256(),
                target.loaderClass(),
                target.loaderName());

        assertEquals(TexturePreparedPixelRuntime.PLAN_ID, target.planId());
        assertEquals(mac.alternativeGroup(), target.alternativeGroup());
        assertEquals(9, target.requiredMethods().size());
        assertTrue(target.match(exactClass, exactSource).exact());
        assertFalse(mac.match(exactClass, exactSource).exact());
    }

    @Test
    void linuxPrefetchTargetPinsTheFlatDistributionAsAMacAlternative() {
        AdapterTarget mac = AdapterTargetRegistry.texturePrefetchBypassTarget();
        AdapterTarget linux = AdapterTargetRegistry.linuxTexturePrefetchBypassTarget();
        ClassSignature exactClass = signature(linux, linux.sha256(), linux.requiredMethods());
        AdapterSourceIdentity exactSource = source(
                linux.sourceKind(),
                "/home/player/Games/starsector/fs.common_obf.jar",
                linux.sourceSha256(),
                linux.loaderClass(),
                linux.loaderName());

        assertEquals(TexturePrefetchBypassPlan.PLAN_ID, linux.planId());
        assertEquals(mac.alternativeGroup(), linux.alternativeGroup());
        assertFalse(linux.alternativeGroup().isBlank());
        assertEquals(1, linux.requiredMethods().size());
        assertTrue(linux.match(exactClass, exactSource).exact());
        assertFalse(mac.match(exactClass, exactSource).exact());
    }

    @Test
    void windowsPrefetchTargetPinsTheReviewedNativeDistribution() {
        AdapterTarget mac = AdapterTargetRegistry.texturePrefetchBypassTarget();
        AdapterTarget target = AdapterTargetRegistry.windowsTexturePrefetchBypassTarget();
        ClassSignature exactClass = signature(target, target.sha256(), target.requiredMethods());
        AdapterSourceIdentity exactSource = source(
                target.sourceKind(),
                "C:/Games/Starsector/starsector-core/fs.common_obf.jar",
                target.sourceSha256(),
                target.loaderClass(),
                target.loaderName());

        assertEquals(TexturePrefetchBypassPlan.PLAN_ID, target.planId());
        assertEquals(mac.alternativeGroup(), target.alternativeGroup());
        assertEquals(TexturePrefetchBypassPlan.WINDOWS_CONSUMER_METHOD,
                target.requiredMethods().get(0).name());
        assertTrue(target.match(exactClass, exactSource).exact());
        assertFalse(mac.match(exactClass, exactSource).exact());
    }

    private static void assertExactIdentity(AdapterTarget target, String planId) {
        ClassSignature exactClass = signature(target, target.sha256(), target.requiredMethods());
        AdapterSourceIdentity exactSource = source(
                target.sourceKind(),
                "/Applications/Starsector.app/Contents/Resources/Java/fs.common_obf.jar",
                target.sourceSha256(),
                target.loaderClass(),
                target.loaderName());

        assertEquals(planId, target.planId());
        assertEquals(9, target.requiredMethods().size());
        assertTrue(target.match(exactClass, exactSource).exact());

        assertFalse(target.match(signature(target, "0".repeat(64), target.requiredMethods()), exactSource).exact());
        assertFalse(target.match(signature(
                target,
                target.sha256(),
                target.requiredMethods().subList(0, target.requiredMethods().size() - 1)), exactSource).exact());
        assertFalse(target.match(exactClass, source(
                "MOD",
                exactSource.normalizedSource(),
                exactSource.sourceSha256(),
                exactSource.loaderClass(),
                exactSource.loaderName())).exact());
        assertFalse(target.match(exactClass, source(
                exactSource.sourceKind(),
                "/tmp/fs.common_obf.jar",
                exactSource.sourceSha256(),
                exactSource.loaderClass(),
                exactSource.loaderName())).exact());
        assertFalse(target.match(exactClass, source(
                exactSource.sourceKind(),
                exactSource.normalizedSource(),
                "f".repeat(64),
                exactSource.loaderClass(),
                exactSource.loaderName())).exact());
        assertFalse(target.match(exactClass, source(
                exactSource.sourceKind(),
                exactSource.normalizedSource(),
                exactSource.sourceSha256(),
                "example/Loader",
                exactSource.loaderName())).exact());
        assertFalse(target.match(exactClass, source(
                exactSource.sourceKind(),
                exactSource.normalizedSource(),
                exactSource.sourceSha256(),
                exactSource.loaderClass(),
                "other")).exact());
    }

    private static ClassSignature signature(
            AdapterTarget target,
            String hash,
            List<AdapterTarget.RequiredMethod> methods) {
        return new ClassSignature(
                target.internalClassName(),
                hash,
                61,
                0,
                methods.stream()
                        .map(method -> new ClassSignature.Method(method.name(), method.descriptor(), 0))
                        .toList());
    }

    private static AdapterSourceIdentity source(
            String kind,
            String normalized,
            String hash,
            String loaderClass,
            String loaderName) {
        return new AdapterSourceIdentity(
                "file:" + normalized,
                normalized,
                kind,
                hash,
                "",
                loaderClass,
                loaderName);
    }
}

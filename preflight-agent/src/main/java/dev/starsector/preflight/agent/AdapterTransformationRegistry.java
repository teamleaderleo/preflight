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
            return EntityLookupPlan.transform(signature, originalBytes);
        }
        if (StartupPhaseRuntime.PLAN_ID.equals(target.planId())) {
            byte[] startupPhases = StartupPhasePlan.transform(signature, originalBytes);
            if (startupPhases != null) {
                return startupPhases;
            }
            byte[] specStore = SpecStorePhasePlan.transform(signature, originalBytes);
            if (specStore == null) {
                byte[] weaponPhases = WeaponLoaderPhasePlan.transform(signature, originalBytes);
                if (weaponPhases == null) {
                    byte[] hullPhases = ShipHullLoaderPhasePlan.transform(signature, originalBytes);
                    if (hullPhases == null || !HullJsonCacheRuntime.ready()) {
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
        return null;
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
        return false;
    }

    static boolean anyPlanCompiled() {
        return true;
    }
}
